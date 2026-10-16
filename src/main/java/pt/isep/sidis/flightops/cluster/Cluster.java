package pt.isep.sidis.flightops.cluster;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The instances of this service and which one owns which aircraft (P1 p.15, sharding by registration).
 *
 * <p>Two ways to know the instances:
 * <ul>
 *   <li>{@code flightops.cluster = instance1=url,instance2=url}: the same fixed list on every instance (hardcoded peer
 *       list, PL3 p.12). Used by docker-compose and scripts/run-local.sh.</li>
 *   <li>{@code flightops.discovery.url-pattern = https://flightops-{i}.flightops-peers:8083}: the instances find each
 *       other through DNS (service discovery, PL3 p.25) - used on Kubernetes, where the auto-scaler adds and removes
 *       instances while the system runs. See {@link DnsDiscovery}.</li>
 * </ul>
 * This instance is the member named {@code flightops.instance-name}.
 *
 * <p>Rendezvous hashing ranks the instances for each aircraft by SHA-256(name + "|" + registration). All instances
 * agree on the ranking without talking to each other, and adding an instance only moves the aircraft the new one wins.
 * The first {@code flightops.replication.factor} instances of the ranking hold the aircraft's flights (its replicas);
 * the first of them is the owner, where bookings go.
 * Without a cluster list (standalone, or the old {@code flightops.peers}) this instance holds everything.
 */
@Component
public class Cluster {

    private static final Logger log = LoggerFactory.getLogger(Cluster.class);

    public record Member(String name, String url) {
    }

    /** Instances that joined or left (discovery only). */
    public record Change(List<Member> joined, List<Member> left) {
    }

    /** A change as shown on the dashboard: when this instance noticed it. */
    public record MembershipEvent(Instant at, List<String> joined, List<String> left) {
    }

    private static final int EVENTS_KEPT = 20;

    /** One consistent view of the membership; replaced as a whole when it changes. */
    private record View(List<Member> members, List<Member> peers) {
    }

    private final String self;
    private final int configuredFactor;
    private final DnsDiscovery discovery;   // null = fixed list
    private final List<Consumer<Change>> listeners = new CopyOnWriteArrayList<>();
    private final Deque<MembershipEvent> events = new ConcurrentLinkedDeque<>();   // newest first
    private final ScheduledExecutorService discoveryThread;
    private volatile View view;

    /** Fixed membership (cluster list or legacy peers). */
    public Cluster(String self, List<String> entries, List<String> legacyPeers, int replicationFactor) {
        this(self, entries, legacyPeers, replicationFactor, null, 0);
    }

    @Autowired
    public Cluster(@Value("${flightops.instance-name}") String self,
                   @Value("${flightops.cluster:}") List<String> entries,
                   @Value("${flightops.peers:}") List<String> legacyPeers,
                   @Value("${flightops.replication.factor:2}") int replicationFactor,
                   @Value("${flightops.discovery.url-pattern:}") String discoveryPattern,
                   @Value("${flightops.discovery.max-instances:10}") int maxInstances,
                   @Value("${flightops.discovery.interval-ms:5000}") long discoveryIntervalMs) {
        this(self, entries, legacyPeers, replicationFactor,
                discoveryPattern == null || discoveryPattern.isBlank() ? null
                        : new DnsDiscovery(discoveryPattern, maxInstances, DnsDiscovery::resolves),
                discoveryIntervalMs);
    }

    /** With a discovery: the members are looked up now, then every {@code discoveryIntervalMs} (0 = only now). */
    public Cluster(String self, List<String> entries, List<String> legacyPeers, int replicationFactor,
            DnsDiscovery discovery, long discoveryIntervalMs) {
        if (replicationFactor < 1) {
            throw new IllegalArgumentException("flightops.replication.factor must be at least 1");
        }
        this.self = self;
        this.configuredFactor = replicationFactor;
        this.discovery = discovery;

        if (discovery != null) {
            if (discovery.memberNamed(self).isEmpty()) {
                throw new IllegalArgumentException("flightops.discovery.url-pattern does not produce this instance ("
                        + self + ")");
            }
            this.view = viewOf(discoverWithSelf(), List.of());
            log.info("Discovery: members {}", names(view.members()));
            if (discoveryIntervalMs > 0) {
                discoveryThread = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "cluster-discovery");
                    t.setDaemon(true);
                    return t;
                });
                discoveryThread.scheduleWithFixedDelay(this::refreshSafely, discoveryIntervalMs, discoveryIntervalMs,
                        TimeUnit.MILLISECONDS);
            } else {
                discoveryThread = null;
            }
            return;
        }
        this.discoveryThread = null;

        List<Member> parsed = new ArrayList<>();
        for (String entry : entries) {
            if (entry.isBlank()) continue;
            int eq = entry.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("flightops.cluster entries must be name=url, got: " + entry);
            }
            parsed.add(new Member(entry.substring(0, eq).trim(), entry.substring(eq + 1).trim()));
        }
        if (!parsed.isEmpty() && parsed.stream().noneMatch(m -> m.name().equals(self))) {
            throw new IllegalArgumentException("flightops.cluster does not contain this instance (" + self + ")");
        }
        List<Member> legacy = legacyPeers.stream().filter(u -> !u.isBlank()).map(u -> new Member(u, u)).toList();
        this.view = viewOf(parsed, legacy);
    }

    private View viewOf(List<Member> members, List<Member> legacyPeers) {
        List<Member> sorted = new ArrayList<>(members);
        sorted.sort(Comparator.comparing(Member::name));
        List<Member> peers = sorted.isEmpty() ? legacyPeers
                : sorted.stream().filter(m -> !m.name().equals(self)).toList();
        return new View(List.copyOf(sorted), List.copyOf(peers));
    }

    /** Looks the members up again; tells the listeners if some joined or left. */
    public void refresh() {
        if (discovery == null) {
            return;
        }
        View before = view;
        View after = viewOf(discoverWithSelf(), List.of());
        if (after.members().equals(before.members())) {
            return;
        }
        view = after;
        List<Member> joined = after.members().stream().filter(m -> !before.members().contains(m)).toList();
        List<Member> left = before.members().stream().filter(m -> !after.members().contains(m)).toList();
        log.info("Discovery: {} joined, {} left - members now {}", names(joined), names(left), names(after.members()));
        events.addFirst(new MembershipEvent(Instant.now(), names(joined), names(left)));
        while (events.size() > EVENTS_KEPT) {
            events.pollLast();
        }
        Change change = new Change(joined, left);
        for (Consumer<Change> listener : listeners) {
            listener.accept(change);
        }
    }

    /** This instance is always a member, even before its own DNS name can be looked up. */
    private List<Member> discoverWithSelf() {
        List<Member> found = new ArrayList<>(discovery.discover());
        if (found.stream().noneMatch(m -> m.name().equals(self))) {
            found.add(discovery.memberNamed(self).orElseThrow());
        }
        return found;
    }

    private void refreshSafely() {
        try {
            refresh();
        } catch (RuntimeException e) {
            log.warn("Discovery failed, keeping the current members: {}", e.getMessage());
        }
    }

    /** The last instances that joined or left, newest first (only with discovery). */
    public List<MembershipEvent> recentChanges() {
        return List.copyOf(events);
    }

    /** Called after instances joined or left (only with discovery). */
    public void onChange(Consumer<Change> listener) {
        listeners.add(listener);
    }

    public String self() {
        return self;
    }

    /** True if aircraft are sharded over a set of instances (fixed list or discovery). */
    public boolean isClustered() {
        return discovery != null || !view.members().isEmpty();
    }

    /** True if instances can join and leave while running (Kubernetes). */
    public boolean isDynamic() {
        return discovery != null;
    }

    public List<Member> peers() {
        return view.peers();
    }

    public List<Member> members() {
        return view.members();
    }

    public Optional<Member> member(String name) {
        return view.members().stream().filter(m -> m.name().equals(name)).findFirst();
    }

    /** How many instances hold each flight (1 = no copies). Never more than the number of instances. */
    public int replicationFactor() {
        return factorFor(view.members().size());
    }

    /** The replication factor asked for in the configuration, whatever the number of instances right now. */
    public int configuredReplicationFactor() {
        return configuredFactor;
    }

    private int factorFor(int memberCount) {
        return memberCount == 0 ? 1 : Math.min(configuredFactor, memberCount);
    }

    /** The instances that hold the flights of this aircraft, best-ranked first; the first one is the owner. */
    public List<String> replicasOf(String registration) {
        List<String> names = view.members().stream().map(Member::name).toList();
        return names.isEmpty() ? List.of(self) : rank(names, registration);
    }

    /**
     * Like {@link #replicasOf}, counting {@code instance} as a member even if this instance has not discovered it
     * yet: a new instance asks for its copies while it starts, possibly before the others have seen it.
     */
    public List<String> replicasOf(String registration, String instance) {
        List<String> names = new ArrayList<>(view.members().stream().map(Member::name).toList());
        if (names.isEmpty()) {
            return replicasOf(registration);
        }
        if (!names.contains(instance)) {
            names.add(instance);
        }
        return rank(names, registration);
    }

    private List<String> rank(List<String> names, String registration) {
        String reg = registration.trim().toUpperCase();
        return names.stream()
                .sorted((a, b) -> Long.compareUnsigned(score(b, reg), score(a, reg)))
                .limit(factorFor(names.size()))
                .toList();
    }

    /** The instance where bookings of this aircraft are made. */
    public String ownerOf(String registration) {
        return replicasOf(registration).get(0);
    }

    public boolean ownsLocally(String registration) {
        return self.equals(ownerOf(registration));
    }

    /** True if this instance keeps a copy of this aircraft's flights (as owner or as backup). */
    public boolean holdsLocally(String registration) {
        return replicasOf(registration).contains(self);
    }

    @PreDestroy
    void shutdown() {
        if (discoveryThread != null) {
            discoveryThread.shutdownNow();
        }
    }

    private static List<String> names(List<Member> members) {
        return members.stream().map(Member::name).toList();
    }

    private static long score(String instanceName, String registration) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((instanceName + "|" + registration).getBytes(StandardCharsets.UTF_8));
            long value = 0;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | (hash[i] & 0xff);
            }
            return value;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
