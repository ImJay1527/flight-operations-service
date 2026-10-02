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
 * The instances of this service, and which one owns which aircraft (P1 p.15 "Data-Based Sharding: partition by
 * operational identifiers, e.g. aircraft registration numbers").
 *
 * <p>Configured with {@code flightops.cluster = instance1=https://host1:8083,instance2=https://host2:8083} - the same
 * list on every instance (hardcoded peer list, PL3 p.12); this instance is the entry named
 * {@code flightops.instance-name}, the others are its peers.
 *
 * <p>Owner of an aircraft = rendezvous (highest random weight) hashing: every instance computes
 * SHA-256(instance name + "|" + registration) for every instance and the highest value wins. All instances agree
 * without talking to each other, and adding an instance only moves the aircraft that the new instance wins.
 *
 * <p>Without {@code flightops.cluster} (a standalone instance, or the old {@code flightops.peers} setting) this
 * instance owns everything.
 */
@Component
public class Cluster {

    public record Member(String name, String url) {
    }

    private final String self;
    private final List<Member> members;   // all instances, including this one; empty = not clustered
    private final List<Member> peers;

    public Cluster(@Value("${flightops.instance-name}") String self,
                   @Value("${flightops.cluster:}") List<String> entries,
                   @Value("${flightops.peers:}") List<String> legacyPeers) {
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
    }

    public String self() {
        return self;
    }

    public boolean isClustered() {
        return !members.isEmpty();
    }

    /** The other instances of this service. */
    public List<Member> peers() {
        return peers;
    }

    public List<Member> members() {
        return members;
    }

    public Optional<Member> member(String name) {
        return members.stream().filter(m -> m.name().equals(name)).findFirst();
    }

    /** Name of the instance that owns (stores the flights of) this aircraft. */
    public String ownerOf(String registration) {
        if (members.isEmpty()) {
            return self;
        }
        String reg = registration.trim().toUpperCase();
        Member best = null;
        long bestScore = 0;
        for (Member m : members) {
            long score = score(m.name(), reg);
            if (best == null || Long.compareUnsigned(score, bestScore) > 0) {
                best = m;
                bestScore = score;
            }
        }
        return best.name();
    }

    public boolean ownsLocally(String registration) {
        return self.equals(ownerOf(registration));
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
