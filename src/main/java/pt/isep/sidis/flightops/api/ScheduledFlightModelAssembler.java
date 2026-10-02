package pt.isep.sidis.flightops.api;

import org.springframework.hateoas.server.mvc.RepresentationModelAssemblerSupport;
import org.springframework.stereotype.Component;
import pt.isep.sidis.flightops.api.dto.FlightView;
import pt.isep.sidis.flightops.api.dto.ScheduledFlightResponseDTO;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

@Component
public class ScheduledFlightModelAssembler
        extends RepresentationModelAssemblerSupport<FlightView, ScheduledFlightResponseDTO> {

    public ScheduledFlightModelAssembler() {
        super(ScheduledFlightController.class, ScheduledFlightResponseDTO.class);
    }

    @Override
    public ScheduledFlightResponseDTO toModel(FlightView flight) {
        ScheduledFlightResponseDTO dto = new ScheduledFlightResponseDTO(
                flight.flightNumber(),
                flight.routeId(),
                flight.aircraftRegistration(),
                flight.scheduledDeparture(),
                flight.scheduledArrival(),
                flight.status()
        );

        ScheduledFlightController ctrl = methodOn(ScheduledFlightController.class);

        dto.add(linkTo(ctrl.getFlightById(flight.flightNumber())).withSelfRel());
        dto.add(linkTo(ctrl.getFlightsByAircraft(flight.aircraftRegistration())).withRel("all-aircraft-flights"));

        return dto;
    }
}
