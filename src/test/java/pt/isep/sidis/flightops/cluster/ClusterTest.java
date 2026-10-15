package pt.isep.sidis.flightops.cluster;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClusterTest {

    private static final List<String> TWO = List.of("instance1=https://a:8083", "instance2=https://b:8083");
    private static final List<String> THREE = List.of("instance1=https://a:8083", "instance2=https://b:8083",
            "instance3=https://c:8083");

    @Test
    void everyInstanceAgreesOnTheOwner() {
        Cluster seenBy1 = new Cluster("instance1", TWO, List.of(), 2);
        Cluster seenBy2 = new Cluster("instance2", List.of(TWO.get(1), TWO.get(0)), List.of(), 2);   // order doesn't matter
        for (String reg : List.of("CS-TPA", "CS-TPB", "CS-TPC", "CS-TPD", "cs-tpe")) {
            assertThat(seenBy1.ownerOf(reg)).isEqualTo(seenBy2.ownerOf(reg));
        }
        assertThat(seenBy1.ownerOf("CS-TPA")).isEqualTo(seenBy1.ownerOf("cs-tpa"));
    }

    @Test
    void peersAreTheOtherInstances() {
        Cluster cluster = new Cluster("instance2", THREE, List.of(), 2);
        assertThat(cluster.peers()).extracting(Cluster.Member::name).containsExactly("instance1", "instance3");
        assertThat(cluster.isClustered()).isTrue();
    }

    @Test
    void aircraftAreSpreadOverAllInstances() {
        Cluster cluster = new Cluster("instance1", THREE, List.of(), 2);
        Map<String, Long> perInstance = IntStream.range(0, 3000).mapToObj(i -> "CS-" + i)
                .collect(Collectors.groupingBy(cluster::ownerOf, Collectors.counting()));
        assertThat(perInstance).hasSize(3);
        perInstance.values().forEach(n -> assertThat(n).isBetween(850L, 1150L));   // ~1000 each
    }

    @Test
    void addingAnInstanceOnlyMovesAircraftToTheNewInstance() {
        // rendezvous hashing: scaling 2 -> 3 never moves an aircraft between the two old instances
        Cluster before = new Cluster("instance1", TWO, List.of(), 2);
        Cluster after = new Cluster("instance1", THREE, List.of(), 2);
        long moved = 0;
        for (int i = 0; i < 3000; i++) {
            String reg = "CS-" + i;
            if (!before.ownerOf(reg).equals(after.ownerOf(reg))) {
                assertThat(after.ownerOf(reg)).isEqualTo("instance3");
                moved++;
            }
        }
        assertThat(moved).isBetween(850L, 1150L);   // ~1/3 of the aircraft move, all to the new instance
    }

    @Test
    void withoutAClusterListThisInstanceOwnsEverything() {
        Cluster standalone = new Cluster("standalone", List.of(), List.of("http://localhost:8093"), 2);
        assertThat(standalone.isClustered()).isFalse();
        assertThat(standalone.ownerOf("CS-TPA")).isEqualTo("standalone");
        assertThat(standalone.peers()).extracting(Cluster.Member::url).containsExactly("http://localhost:8093");
    }

    @Test
    void eachAircraftIsHeldByTwoDifferentInstancesOwnerFirst() {
        Cluster cluster = new Cluster("instance1", THREE, List.of(), 2);
        for (int i = 0; i < 300; i++) {
            String reg = "CS-" + i;
            List<String> replicas = cluster.replicasOf(reg);
            assertThat(replicas).hasSize(2).doesNotHaveDuplicates();
            assertThat(replicas.get(0)).isEqualTo(cluster.ownerOf(reg));
        }
    }

    @Test
    void everyInstanceAgreesOnTheReplicas() {
        Cluster seenBy1 = new Cluster("instance1", THREE, List.of(), 2);
        Cluster seenBy3 = new Cluster("instance3", THREE, List.of(), 2);
        for (String reg : List.of("CS-TPA", "CS-TPB", "CS-TPC", "CS-TPD", "CS-TPE")) {
            assertThat(seenBy1.replicasOf(reg)).isEqualTo(seenBy3.replicasOf(reg));
        }
    }

    @Test
    void withThreeInstancesEachHoldsAboutTwoThirdsOfTheAircraft() {
        Cluster cluster = new Cluster("instance1", THREE, List.of(), 2);
        Map<String, Long> perInstance = IntStream.range(0, 3000).mapToObj(i -> "CS-" + i)
                .flatMap(reg -> cluster.replicasOf(reg).stream())
                .collect(Collectors.groupingBy(name -> name, Collectors.counting()));
        perInstance.values().forEach(n -> assertThat(n).isBetween(1850L, 2150L));   // ~2000 of 3000 each
    }

    @Test
    void replicationFactorIsCappedByTheNumberOfInstances() {
        assertThat(new Cluster("instance1", TWO, List.of(), 3).replicationFactor()).isEqualTo(2);
        assertThat(new Cluster("instance1", TWO, List.of(), 1).replicasOf("CS-TPA")).hasSize(1);
        assertThat(new Cluster("standalone", List.of(), List.of(), 2).replicasOf("CS-TPA")).containsExactly("standalone");
    }

    @Test
    void thisInstanceMustBeInTheList() {
        assertThatThrownBy(() -> new Cluster("instance9", TWO, List.of(), 2)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------- discovery (Kubernetes) ----------------

    private static final String PATTERN = "https://flightops-{i}.flightops-peers:8083";

    /** A cluster that "sees" the pods in {@code running} (their DNS names resolve). */
    private static Cluster discovering(String self, Set<String> running) {
        DnsDiscovery dns = new DnsDiscovery(PATTERN, 10, host -> running.contains(host.substring(0, host.indexOf('.'))));
        return new Cluster(self, List.of(), List.of(), 2, dns, 0);
    }

    @Test
    void discoveryFindsThePodsWhoseNamesResolve() {
        Cluster cluster = discovering("flightops-1", Set.of("flightops-0", "flightops-1", "flightops-2"));
        assertThat(cluster.isClustered()).isTrue();
        assertThat(cluster.isDynamic()).isTrue();
        assertThat(cluster.members()).extracting(Cluster.Member::name)
                .containsExactly("flightops-0", "flightops-1", "flightops-2");
        assertThat(cluster.peers()).extracting(Cluster.Member::url)
                .containsExactly("https://flightops-0.flightops-peers:8083", "https://flightops-2.flightops-peers:8083");
    }

    @Test
    void anInstanceIsAMemberBeforeItsOwnNameResolves() {
        Cluster cluster = discovering("flightops-0", Set.of());
        assertThat(cluster.members()).extracting(Cluster.Member::name).containsExactly("flightops-0");
        assertThat(cluster.replicationFactor()).isEqualTo(1);   // alone for now
        assertThat(cluster.configuredReplicationFactor()).isEqualTo(2);
    }

    @Test
    void refreshReportsInstancesThatJoinedOrLeft() {
        Set<String> running = new HashSet<>(Set.of("flightops-0", "flightops-1", "flightops-2"));
        Cluster cluster = discovering("flightops-0", running);
        List<Cluster.Change> changes = new ArrayList<>();
        cluster.onChange(changes::add);

        cluster.refresh();
        assertThat(changes).isEmpty();   // nothing changed

        running.add("flightops-3");      // the auto-scaler added a pod
        cluster.refresh();
        running.remove("flightops-2");   // ... and later removed one
        cluster.refresh();

        assertThat(changes).hasSize(2);
        assertThat(changes.get(0).joined()).extracting(Cluster.Member::name).containsExactly("flightops-3");
        assertThat(changes.get(1).left()).extracting(Cluster.Member::name).containsExactly("flightops-2");
        assertThat(cluster.members()).extracting(Cluster.Member::name)
                .containsExactly("flightops-0", "flightops-1", "flightops-3");
    }

    @Test
    void discoveredInstancesAgreeWithAFixedListOfTheSameNames() {
        Cluster discovered = discovering("flightops-0", Set.of("flightops-0", "flightops-1", "flightops-2"));
        Cluster fixed = new Cluster("flightops-0", List.of("flightops-0=https://x", "flightops-1=https://y",
                "flightops-2=https://z"), List.of(), 2);
        for (int i = 0; i < 100; i++) {
            assertThat(discovered.replicasOf("CS-" + i)).isEqualTo(fixed.replicasOf("CS-" + i));
        }
    }

    @Test
    void anInstanceNotDiscoveredYetIsCountedWhenItAsksForItsCopies() {
        // flightops-3 is starting and asks flightops-0 for its copies before flightops-0 has discovered it
        Cluster seenBy0 = discovering("flightops-0", Set.of("flightops-0", "flightops-1", "flightops-2"));
        Cluster withNewPod = discovering("flightops-0", Set.of("flightops-0", "flightops-1", "flightops-2", "flightops-3"));
        for (int i = 0; i < 100; i++) {
            assertThat(seenBy0.replicasOf("CS-" + i, "flightops-3")).isEqualTo(withNewPod.replicasOf("CS-" + i));
        }
    }

    @Test
    void thePatternMustProduceThisInstance() {
        assertThatThrownBy(() -> discovering("instance1", Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DnsDiscovery("https://flightops.peers:8083", 3, h -> true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
