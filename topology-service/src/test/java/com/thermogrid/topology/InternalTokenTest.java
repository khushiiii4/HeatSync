package com.thermogrid.topology;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** /internal/topology needs X-Internal-Token; the public API is untouched. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"thermogrid.security.enabled=true", "thermogrid.internal-token=s3cret"})
class InternalTokenTest {

    @Autowired MockMvc mvc;

    @Test
    void internalTopologyWithoutTokenIs401() throws Exception {
        mvc.perform(get("/internal/topology")).andExpect(status().isUnauthorized());
    }

    @Test
    void internalTopologyWithWrongTokenIs401() throws Exception {
        mvc.perform(get("/internal/topology").header("X-Internal-Token", "wrong")).andExpect(status().isUnauthorized());
    }

    @Test
    void internalTopologyWithTheTokenIs200() throws Exception {
        mvc.perform(get("/internal/topology").header("X-Internal-Token", "s3cret")).andExpect(status().isOk());
    }

    @Test
    void publicTopologyIsNotBlocked() throws Exception {
        mvc.perform(get("/api/topology")).andExpect(status().isOk());
    }
}
