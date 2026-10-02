package pt.isep.sidis.flightops;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import pt.isep.sidis.flightops.common.security.JwtUtils;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Boots a standalone replica (no peers, bootstrap shard 0) and checks the access rules. */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JwtUtils jwtUtils;

    @Test
    void loginReturnsToken() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"atcc\",\"password\":\"atcc123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void loginWithWrongPasswordIs401() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"atcc\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publicApiWithoutTokenIs401() throws Exception {
        mvc.perform(get("/api/aircraft-utilization")).andExpect(status().isUnauthorized());
    }

    @Test
    void userTokenCannotCallInternalEndpoints() throws Exception {
        String userToken = jwtUtils.generateToken("atcc", "ATCC");
        mvc.perform(get("/internal/flights/active").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void serviceTokenCanCallInternalEndpoints() throws Exception {
        String serviceToken = jwtUtils.generateServiceToken("flight-operations-service");
        mvc.perform(get("/internal/flights/active").header("Authorization", "Bearer " + serviceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(10));
    }

    @Test
    void serviceTokenCannotCallUserEndpoints() throws Exception {
        String serviceToken = jwtUtils.generateServiceToken("flight-operations-service");
        mvc.perform(get("/api/aircraft-utilization").header("Authorization", "Bearer " + serviceToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void utilizationWorksStandaloneForAtcc() throws Exception {
        String userToken = jwtUtils.generateToken("atcc", "ATCC");
        mvc.perform(get("/api/aircraft-utilization").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());
    }
}
