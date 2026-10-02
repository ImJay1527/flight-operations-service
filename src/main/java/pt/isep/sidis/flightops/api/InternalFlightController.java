package pt.isep.sidis.flightops.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pt.isep.sidis.flightops.api.dto.FlightView;
import pt.isep.sidis.flightops.services.FlightQueryService;
import pt.isep.sidis.flightops.services.ScheduledFlightService;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Peer-to-peer endpoints between replicas of this service. They answer ONLY from the local shard and never forward,
 * which is what prevents query loops. Protected by SecurityConfig: requires role SERVICE.
 */
@RestController
@RequestMapping("/internal/flights")
@RequiredArgsConstructor
@Tag(name = "Internal (replica-to-replica)", description = "Local-shard queries used by peer replicas. Role SERVICE only.")
public class InternalFlightController {

    private final FlightQueryService flightQueryService;
    private final ScheduledFlightService scheduledFlightService;

    @Operation(summary = "Local flight by number (404 if not on this replica)")
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

    @Operation(summary = "Cancel a flight stored on this replica (404 if not here)")
    @PatchMapping("/{flightNumber}/cancel")
    public ResponseEntity<FlightView> cancel(@PathVariable String flightNumber) {
        FlightView flight = scheduledFlightService.cancelLocal(flightNumber);
        return flight == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(flight);
    }
}
