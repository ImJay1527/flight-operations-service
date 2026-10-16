package pt.isep.sidis.flightops.cluster;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Finds the instances of this service through DNS (service discovery, PL3 p.25 "Kubernetes DNS").
 *
 * <p>On Kubernetes the instances are the pods of a StatefulSet, so their names are known in advance: flightops-0,
 * flightops-1, ... and a headless Service gives each one a DNS name (flightops-0.flightops-peers). The pattern
 * {@code https://flightops-{i}.flightops-peers:8083} is tried for i = 0 .. maxInstances - 1; the names that resolve
 * are the instances that exist right now. When the auto-scaler adds or removes a pod, its name appears in or disappears
 * from DNS, and the next lookup sees it. The member name is the first part of the host name (= the pod name).
 */
public class DnsDiscovery {

    private final String urlPattern;
    private final int maxInstances;
    private final Predicate<String> resolves;

    public DnsDiscovery(String urlPattern, int maxInstances, Predicate<String> resolves) {
        if (!urlPattern.contains("{i}")) {
            throw new IllegalArgumentException("flightops.discovery.url-pattern must contain {i}, got: " + urlPattern);
        }
        if (maxInstances < 1) {
            throw new IllegalArgumentException("flightops.discovery.max-instances must be at least 1");
        }
        this.urlPattern = urlPattern;
        this.maxInstances = maxInstances;
        this.resolves = resolves;
    }

    /** The instances whose DNS name resolves now. */
    public List<Cluster.Member> discover() {
        List<Cluster.Member> found = new ArrayList<>();
        for (Cluster.Member candidate : candidates()) {
            if (resolves.test(host(candidate.url()))) {
                found.add(candidate);
            }
        }
        return found;
    }

    /** The member with this name, if the pattern can produce it (used for this instance itself). */
    public Optional<Cluster.Member> memberNamed(String name) {
        return candidates().stream().filter(m -> m.name().equals(name)).findFirst();
    }

    private List<Cluster.Member> candidates() {
        List<Cluster.Member> all = new ArrayList<>(maxInstances);
        for (int i = 0; i < maxInstances; i++) {
            String url = urlPattern.replace("{i}", Integer.toString(i));
            String host = host(url);
            int dot = host.indexOf('.');
            all.add(new Cluster.Member(dot < 0 ? host : host.substring(0, dot), url));
        }
        return all;
    }

    private static String host(String url) {
        String host = URI.create(url).getHost();
        if (host == null) {
            throw new IllegalArgumentException("No host in discovery URL " + url);
        }
        return host;
    }

    static boolean resolves(String host) {
        try {
            InetAddress.getByName(host);
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
