package pt.isep.sidis.flightops.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import pt.isep.sidis.flightops.common.security.JwtUtils;
import pt.isep.sidis.flightops.services.AircraftUtilizationService;
import pt.isep.sidis.flightops.services.FuelEfficiencyService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Report controllers tested over HTTP with the real security filters (PSOFT disabled them). Both report services are
 * mocked here, so the two test classes share one Spring context.
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class ReportControllerTestBase {

    @Autowired MockMvc mockMvc;
    @Autowired JwtUtils jwtUtils;

    @MockBean FuelEfficiencyService fuelEfficiencyService;
    @MockBean AircraftUtilizationService utilizationService;

    MockHttpServletRequestBuilder getAs(String role, String url) {
        return get(url).header("Authorization", "Bearer " + jwtUtils.generateToken("test-" + role.toLowerCase(), role));
    }

    MockHttpServletRequestBuilder getAsAtcc(String url) {
        return getAs("ATCC", url);
    }
}
