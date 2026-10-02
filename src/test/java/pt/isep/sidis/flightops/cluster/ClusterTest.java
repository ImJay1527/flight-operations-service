package pt.isep.sidis.flightops.cluster;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
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
}
