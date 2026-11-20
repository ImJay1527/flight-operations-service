package pt.isep.sidis.flightops.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pt.isep.sidis.flightops.api.dto.FlightView;
import pt.isep.sidis.flightops.clients.AircraftDirectory;
import pt.isep.sidis.flightops.clients.RouteDirectory;
import pt.isep.sidis.flightops.common.metrics.FlightOpsMetrics;
import pt.isep.sidis.flightops.readmodel.ReadModel;
import pt.isep.sidis.flightops.readmodel.ReadModelHeaders;
import pt.isep.sidis.flightops.readmodel.ReadModelUnavailableException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The query side of CQRS. Answers from this instance's read model (its own read database, holding every flight of
 * the cluster), so a query is local and never touches the write database. While the read model can't be trusted
 * (not configured, still building, STALE) the query takes the fallback path: the write side, asking the peers as in
 * Assignment 1 ({@link FlightQueryService}) - slower, but fresh. A client that needs to see every change already made
 * can ask for that path with the request header {@code X-Read-Side: write-side}. Which side answered is in the
 * X-Read-Side response header.
 */
@Service
@RequiredArgsConstructor
public class FlightQueries {

    private final ReadModel readModel;
    private final FlightQueryService writeSide;
    private final AircraftDirectory aircraftClient;
    private final RouteDirectory airportsRoutesClient;
    private final FlightOpsMetrics metrics;

    /**
     * One flight. Not in the read model yet (booked a moment ago on another instance, before the change reached this
     * one): the write side is asked, so it is still found - and its 404/503 rules apply.
     */
    public FlightLookup flight(String flightNumber) {
        if (useReadModel()) {
            long start = System.nanoTime();
            try {
                Optional<FlightView> found = readModel.flight(flightNumber);
                if (found.isPresent()) {
                    metrics.recordLookup(FlightOpsMetrics.READ_MODEL, start);
                    ReadModelHeaders.record(true, readModel.state());
                    return new FlightLookup(found.get(), ReadModelHeaders.READ_MODEL);
                }
            } catch (ReadModelUnavailableException e) {
                // the write side answers
            }
        }
        ReadModelHeaders.record(false, readModel.state());
        return writeSide.findById(flightNumber);
    }

    /** All flights of an aircraft (US213); 404 if Aircraft & Maintenance doesn't know it. */
    public List<FlightView> flightsOfAircraft(String registration) {
        aircraftClient.getAircraft(registration);
        return answer(() -> readModel.flightsOfAircraft(registration), () -> writeSide.findByAircraft(registration));
    }

    /** Non-cancelled flights leaving an airport in the next {@code hours}; 404 if Airports & Routes doesn't know it. */
    public List<FlightView> departures(String originIata, int hours) {
        if (hours <= 0) {
            throw new IllegalArgumentException("The time window must be greater than zero hours.");
        }
        String iata = originIata.toUpperCase();
        airportsRoutesClient.getAirport(iata);
        LocalDateTime now = LocalDateTime.now();
        return answer(() -> readModel.departures(iata, now, now.plusHours(hours)),
                () -> writeSide.findUpcomingDepartures(iata, hours));
    }

    /** The read model's answer while it is READY and its database works, otherwise the write side's. */
    public <T> T answer(Supplier<T> fromReadModel, Supplier<T> fromWriteSide) {
        if (useReadModel()) {
            try {
                T result = fromReadModel.get();
                ReadModelHeaders.record(true, readModel.state());
                return result;
            } catch (ReadModelUnavailableException e) {
                // the write side answers
            }
        }
        ReadModelHeaders.record(false, readModel.state());
        return fromWriteSide.get();
    }

    /** READY, and the client did not ask for the write side (X-Read-Side: write-side). */
    private boolean useReadModel() {
        return readModel.isReady() && !ReadModelHeaders.writeSideRequested();
    }
}
