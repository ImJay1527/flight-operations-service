package pt.isep.sidis.flightops.monitoring;

import pt.isep.sidis.flightops.cluster.Cluster;
import pt.isep.sidis.flightops.resilience.EndpointHealth;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One instance's view of itself, for the dashboard (GET /api/cluster/overview gathers it from every instance).
 *
 * @param members           the instances this one knows (fixed list or discovered)
 * @param flightsAsOwner    flights of aircraft this instance owns (bookings are made here)
 * @param flightsAsBackup   flights of aircraft this instance keeps a copy of
 * @param flightsToHandOff  flights it no longer holds after a membership change, waiting to be handed off
 * @param aircraft          every aircraft with flights here, and this instance's role for it
 * @param requestsServed    client requests answered since start (not counting the dashboard and health checks)
 * @param cpuPercent        CPU used by this process (100 = one full core), null if not measured yet
 */
public record InstanceStatus(String instance, boolean reachable, Instant startedAt, List<String> members,
                             int replicationFactor, long flightsAsOwner, long flightsAsBackup, long flightsToHandOff,
                             List<AircraftRole> aircraft, long requestsServed, Double cpuPercent, long heapUsedMb,
                             long heapMaxMb, List<EndpointHealth.Snapshot> remoteInstances,
                             Map<String, Long> replicationBacklog, List<Cluster.MembershipEvent> membershipChanges) {

    public enum Role { OWNER, BACKUP, HAND_OFF }

    public record AircraftRole(String registration, Role role, long flights) {
    }

    /** A peer that did not answer. */
    public static InstanceStatus unreachable(String instance) {
        return new InstanceStatus(instance, false, null, List.of(), 0, 0, 0, 0, List.of(), 0, null, 0, 0, List.of(),
                Map.of(), List.of());
    }
}
