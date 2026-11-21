package pt.isep.sidis.flightops.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * This instance's copy of a route of the Airports &amp; Routes service: status, distance, what an aircraft needs to
 * fly it, and its airports (their own copies, {@link AirportCopy}). Events: route.created, route.updated,
 * route.deactivated. See {@link ReferenceCopy}.
 */
@Entity
@Table(name = "route_copy")
public class RouteCopy extends ReferenceCopy {

    @Id
    private String routeId;
    private String status;
    private double distanceKm;
    private double minRangeRequired;
    private int minCapacityRequired;
    private String originIata;
    private String destinationIata;

    protected RouteCopy() {
    }

    public RouteCopy(String routeId) {
        this.routeId = routeId;
    }

    /** The whole route (route.created / route.updated, or the REST API); false if it is older than the copy. */
    public boolean replace(String status, double distanceKm, double minRangeRequired, int minCapacityRequired,
                           String originIata, String destinationIata, Instant at, String from) {
        if (!accept(at, from)) {
            return false;
        }
        this.status = status;
        this.distanceKm = distanceKm;
        this.minRangeRequired = minRangeRequired;
        this.minCapacityRequired = minCapacityRequired;
        this.originIata = originIata.toUpperCase();
        this.destinationIata = destinationIata.toUpperCase();
        return true;
    }

    public boolean changeStatus(String newStatus, Instant at) {
        if (!accept(at, "event")) {
            return false;
        }
        this.status = newStatus;
        return true;
    }

    public String getRouteId() {
        return routeId;
    }

    public String getStatus() {
        return status;
    }

    public double getDistanceKm() {
        return distanceKm;
    }

    public double getMinRangeRequired() {
        return minRangeRequired;
    }

    public int getMinCapacityRequired() {
        return minCapacityRequired;
    }

    public String getOriginIata() {
        return originIata;
    }

    public String getDestinationIata() {
        return destinationIata;
    }
}
