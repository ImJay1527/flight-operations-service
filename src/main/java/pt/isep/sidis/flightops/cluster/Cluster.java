package pt.isep.sidis.flightops.cluster;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The instances of this service and which one owns which aircraft (P1 p.15, sharding by registration).
 *
 * <p>{@code flightops.cluster = instance1=url,instance2=url} is the same list on every instance (hardcoded peer list,
 * PL3 p.12); this instance is the entry named {@code flightops.instance-name}.
 *
 * <p>Rendezvous hashing ranks the instances for each aircraft by SHA-256(name + "|" + registration). All instances
 * agree on the ranking without talking to each other, and adding an instance only moves the aircraft the new one wins.
 * The first {@code flightops.replication.factor} instances of the ranking hold the aircraft's flights (its replicas);
 * the first of them is the owner, where bookings go.
 * Without a cluster list (standalone, or the old {@code flightops.peers}) this instance holds everything.
 */
@Component
public class Cluster {

    public record Member(String name, String url) {
    }

    private final String self;
    private final List<Member> members;   // all instances, including this one; empty = not clustered
    private final List<Member> peers;
    private final int replicationFactor;

    public Cluster(@Value("${flightops.instance-name}") String self,
                   @Value("${flightops.cluster:}") List<String> entries,
                   @Value("${flightops.peers:}") List<String> legacyPeers,
                   @Value("${flightops.replication.factor:2}") int replicationFactor) {
        if (replicationFactor < 1) {
            throw new IllegalArgumentException("flightops.replication.factor must be at least 1");
        }
        this.self = self;
        List<Member> parsed = new ArrayList<>();
        for (String entry : entries) {
            if (entry.isBlank()) continue;
            int eq = entry.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("flightops.cluster entries must be name=url, got: " + entry);
            }
            parsed.add(new Member(entry.substring(0, eq).trim(), entry.substring(eq + 1).trim()));
        }
        parsed.sort(Comparator.comparing(Member::name));
        this.members = List.copyOf(parsed);

        if (members.isEmpty()) {
            this.peers = legacyPeers.stream().filter(u -> !u.isBlank()).map(u -> new Member(u, u)).toList();
        } else {
            if (members.stream().noneMatch(m -> m.name().equals(self))) {
                throw new IllegalArgumentException("flightops.cluster does not contain this instance (" + self + ")");
            }
            this.peers = members.stream().filter(m -> !m.name().equals(self)).toList();
        }
        this.replicationFactor = members.isEmpty() ? 1 : Math.min(replicationFactor, members.size());
    }

    public String self() {
        return self;
    }

    public boolean isClustered() {
        return !members.isEmpty();
    }

    public List<Member> peers() {
        return peers;
    }

    public List<Member> members() {
        return members;
    }

    public Optional<Member> member(String name) {
        return members.stream().filter(m -> m.name().equals(name)).findFirst();
    }

    /** How many instances hold each flight (1 = no copies). Never more than the number of instances. */
    public int replicationFactor() {
        return replicationFactor;
    }

    /** The instances that hold the flights of this aircraft, best-ranked first; the first one is the owner. */
    public List<String> replicasOf(String registration) {
        if (members.isEmpty()) {
            return List.of(self);
        }
        String reg = registration.trim().toUpperCase();
        return members.stream()
                .sorted((a, b) -> Long.compareUnsigned(score(b.name(), reg), score(a.name(), reg)))
                .limit(replicationFactor)
                .map(Member::name)
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
