package pt.isep.sidis.flightops.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pt.isep.sidis.flightops.api.dto.UnicornDTO;
import pt.isep.sidis.flightops.services.UnicornService;

import java.util.List;

/**
 * The mock endpoint of Assignment 2 (P2): the mood of the airplanes, in JSON. A query of CQRS, answered from the
 * read model; see {@link UnicornService}.
 */
@RestController
@RequestMapping("/api/v2/unicorns")
@Tag(name = "Unicorns", description = "Mood of the airplanes (Assignment 2 mock endpoint, CQRS query)")
public class UnicornsController {

    private final UnicornService unicorns;

    public UnicornsController(UnicornService unicorns) {
        this.unicorns = unicorns;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ATCC', 'BACKOFFICE_OPERATOR', 'ADMIN')")
    @Operation(summary = "Mood of every airplane with flights",
            description = "FLYING (in the air now), EXCITED (leaves within 24 h), TIRED (10 h or more in the last "
                    + "7 days), BORED (nothing scheduled) or HAPPY (a trip is planned). Read from the CQRS read "
                    + "model (X-Read-Side).")
    public List<UnicornDTO> all() {
        return unicorns.all();
    }

    @GetMapping("/{registration}")
    @PreAuthorize("hasAnyRole('ATCC', 'BACKOFFICE_OPERATOR', 'ADMIN')")
    @Operation(summary = "Mood of one airplane")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Its mood (BORED if it has no flights)"),
            @ApiResponse(responseCode = "404", description = "Aircraft unknown to Aircraft & Maintenance")
    })
    public UnicornDTO one(@PathVariable String registration) {
        return unicorns.of(registration);
    }
}
