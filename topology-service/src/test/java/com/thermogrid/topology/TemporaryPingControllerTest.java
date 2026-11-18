package com.thermogrid.topology;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TemporaryPingController.class)
class TemporaryPingControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void pingIdentifiesTopologyService() throws Exception {
        mvc.perform(get("/api/topology/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("TOPOLOGY-SERVICE"))
                .andExpect(jsonPath("$.status").value("UP"));
    }
}