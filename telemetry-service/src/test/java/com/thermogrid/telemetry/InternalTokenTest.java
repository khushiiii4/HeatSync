package com.thermogrid.telemetry;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** /internal/telemetry/** needs X-Internal-Token; the public telemetry and alert APIs are untouched. */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"thermogrid.security.enabled=true", "thermogrid.internal-token=s3cret"})
class InternalTokenTest extends IntegrationTestBase {

    @Autowired MockMvc mvc;

    @Test
    void internalEndpointRejectsMissingAndWrongTokens() throws Exception {
        mvc.perform(get("/internal/telemetry/racks/latest")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/telemetry/racks/latest").header("X-Internal-Token", "wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void internalEndpointAcceptsTheToken() throws Exception {
        mvc.perform(get("/internal/telemetry/racks/latest").header("X-Internal-Token", "s3cret"))
                .andExpect(status().isOk());
    }

    @Test
    void publicEndpointsAreNotBlocked() throws Exception {
        mvc.perform(get("/api/telemetry/racks/latest")).andExpect(status().isOk());
        mvc.perform(get("/api/alerts")).andExpect(status().isOk());
    }
}
