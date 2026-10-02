package pt.isep.sidis.flightops.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pt.isep.sidis.flightops.api.dto.FlightView;
import pt.isep.sidis.flightops.services.FlightQueryService;
import pt.isep.sidis.flightops.services.InternalBookingRequest;
import pt.isep.sidis.flightops.services.LocalBookingService;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Endpoints for the other instances of this service (role SERVICE). They use local data only and never forward,
 * which is what prevents query loops.
 */
@RestController
@RequestMapping("/internal/flights")
@RequiredArgsConstructor
@Tag(name = "Internal (instance-to-instance)", description = "Local-data endpoints used by the other instances. Role SERVICE only.")
public class InternalFlightController {

    private final FlightQueryService flightQueryService;
    private final LocalBookingService localBookings;

    @Operation(summary = "Book a flight on THIS instance (a booking forwarded by the peer that received it, "
            + "because this instance holds the aircraft). Never forwarded again.")
    @PostMapping
    public ResponseEntity<FlightView> book(@RequestBody InternalBookingRequest request) {
        FlightView flight = localBookings.book(request.routeId(), request.aircraftRegistration(),
                request.departureTime(), request.arrivalTime());
        return ResponseEntity.status(HttpStatus.CREATED).body(flight);
    }

    @Operation(summary = "Local flight by number (404 if not on this instance)")
    @GetMapping("/{flightNumber}")
    public ResponseEntity<FlightView> byId(@PathVariable String flightNumber) {
        FlightView flight = flightQueryService.localById(flightNumber);
        return flight == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(flight);
    }

    @Operation(summary = "Local flights of an aircraft")
    @GetMapping
    public List<FlightView> byAircraft(@RequestParam String aircraft) {
        return flightQueryService.localByAircraft(aircraft);
    }

    @Operation(summary = "Local non-cancelled flights, optionally of one aircraft")
    @GetMapping("/active")
    public List<FlightView> active(@RequestParam(required = false) String aircraft) {
        return flightQueryService.localActive(aircraft);
    }

    @Operation(summary = "Local upcoming departures from an airport")
    @GetMapping("/departures/{iata}")
    public List<FlightView> departures(@PathVariable String iata, @RequestParam int hours) {
        LocalDateTime now = LocalDateTime.now();
        return flightQueryService.localDepartures(iata.toUpperCase(), now, now.plusHours(hours));
    }

    @Operation(summary = "Cancel this instance's copy of a flight (404 if not here); the other copies are updated from here")
    @PatchMapping("/{flightNumber}/cancel")
    public ResponseEntity<FlightView> cancel(@PathVariable String flightNumber) {
        FlightView flight = localBookings.cancel(flightNumber);
        return flight == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(flight);
    }
}
