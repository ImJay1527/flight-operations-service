package pt.isep.sidis.flightops.readmodel;

import pt.isep.sidis.flightops.api.dto.AircraftFuelEfficiencyDTO;
import pt.isep.sidis.flightops.api.dto.AircraftUtilizationDTO;
import pt.isep.sidis.flightops.api.dto.AircraftUtilizationPeriodDTO;
import pt.isep.sidis.flightops.api.dto.FlightView;
import pt.isep.sidis.flightops.api.dto.RouteFuelEfficiencyDTO;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The reports (utilization US223, fuel efficiency) computed from a list of non-cancelled flights. Used by the read
 * model, which computes them when a flight changes and stores the result, and by the fallback path, which computes
 * them on every request. Pure functions: the same flights always give the same result.
 */
public final class FlightStatistics {

    private FlightStatistics() {
    }

    /** Monthly flights and hours, one entry per aircraft, in the order the aircraft first appear. */
    public static List<AircraftUtilizationDTO> utilization(List<FlightView> flights) {
        Map<String, UtilizationAccumulator> byAircraft = new LinkedHashMap<>();
        for (FlightView f : flights) {
            byAircraft.computeIfAbsent(f.aircraftRegistration(),
                            k -> new UtilizationAccumulator(f.aircraftRegistration(), f.aircraftModel()))
                    .add(f.scheduledDeparture().getYear(), f.scheduledDeparture().getMonthValue(), hours(f));
        }
        return byAircraft.values().stream().map(UtilizationAccumulator::toDTO).toList();
    }

    /** Distance, fuel and km per litre, one entry per aircraft. */
    public static List<AircraftFuelEfficiencyDTO> aircraftFuel(List<FlightView> flights) {
        Map<String, AircraftFuelAccumulator> byAircraft = new LinkedHashMap<>();
        for (FlightView f : flights) {
            byAircraft.computeIfAbsent(f.aircraftRegistration(), k -> new AircraftFuelAccumulator(
                            f.aircraftRegistration(), f.aircraftModel(), f.fuelBurnRate()))
                    .add(f.distanceKm());
        }
        return byAircraft.values().stream().map(AircraftFuelAccumulator::toDTO).toList();
    }

    /** Fuel per flight and km per litre (averaged over the aircraft that flew it), one entry per route. */
    public static List<RouteFuelEfficiencyDTO> routeFuel(List<FlightView> flights) {
        Map<String, RouteFuelAccumulator> byRoute = new LinkedHashMap<>();
        for (FlightView f : flights) {
            byRoute.computeIfAbsent(f.routeId(), k -> new RouteFuelAccumulator(
                            f.routeId(), f.originIata(), f.destinationIata(), f.distanceKm()))
                    .add(f.fuelBurnRate());
        }
        return byRoute.values().stream().map(RouteFuelAccumulator::toDTO).toList();
    }

    /** An aircraft with no (non-cancelled) flights: nothing flown yet. */
    public static AircraftFuelEfficiencyDTO zeroAircraftFuel(String registration, String modelName, double burnRate) {
        return new AircraftFuelEfficiencyDTO(registration, modelName, burnRate, 0, 0, burnRate > 0 ? 1.0 / burnRate : 0, 0);
    }

    static double hours(FlightView f) {
        return ChronoUnit.MINUTES.between(f.scheduledDeparture(), f.scheduledArrival()) / 60.0;
    }

    private static final class UtilizationAccumulator {
        private final String registration;
        private final String model;
        private final Map<String, long[]> flightsByPeriod = new LinkedHashMap<>();
        private final Map<String, double[]> hoursByPeriod = new LinkedHashMap<>();

        UtilizationAccumulator(String registration, String model) {
            this.registration = registration;
            this.model = model;
        }

        void add(int year, int month, double hours) {
            String key = year + "-" + month;
            flightsByPeriod.computeIfAbsent(key, k -> new long[1])[0]++;
            hoursByPeriod.computeIfAbsent(key, k -> new double[1])[0] += hours;
        }

        AircraftUtilizationDTO toDTO() {
            List<AircraftUtilizationPeriodDTO> periods = new ArrayList<>();
            flightsByPeriod.forEach((key, count) -> {
                String[] ym = key.split("-");
                periods.add(new AircraftUtilizationPeriodDTO(Integer.parseInt(ym[0]), Integer.parseInt(ym[1]),
                        count[0], hoursByPeriod.get(key)[0]));
            });
            return new AircraftUtilizationDTO(registration, model, periods);
        }
    }

    private static final class AircraftFuelAccumulator {
        private final String registration;
        private final String model;
        private final double burnRate;
        private double totalDistance;
        private int flightCount;

        AircraftFuelAccumulator(String registration, String model, double burnRate) {
            this.registration = registration;
            this.model = model;
            this.burnRate = burnRate;
        }

        void add(double distanceKm) {
            totalDistance += distanceKm;
            flightCount++;
        }

        AircraftFuelEfficiencyDTO toDTO() {
            return new AircraftFuelEfficiencyDTO(registration, model, burnRate, totalDistance, burnRate * totalDistance,
                    burnRate > 0 ? 1.0 / burnRate : 0, flightCount);
        }
    }

    private static final class RouteFuelAccumulator {
        private final String routeId;
        private final String origin;
        private final String destination;
        private final double distanceKm;
        private double totalBurnRate;
        private int flightCount;

        RouteFuelAccumulator(String routeId, String origin, String destination, double distanceKm) {
            this.routeId = routeId;
            this.origin = origin;
            this.destination = destination;
            this.distanceKm = distanceKm;
        }

        void add(double burnRate) {
            totalBurnRate += burnRate;
            flightCount++;
        }

        RouteFuelEfficiencyDTO toDTO() {
            double avgBurnRate = totalBurnRate / flightCount;
            return new RouteFuelEfficiencyDTO(routeId, origin, destination, distanceKm, avgBurnRate * distanceKm,
                    avgBurnRate > 0 ? 1.0 / avgBurnRate : 0, flightCount);
        }
    }
}
