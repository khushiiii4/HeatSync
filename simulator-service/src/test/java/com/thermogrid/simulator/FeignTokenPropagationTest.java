package com.thermogrid.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import com.thermogrid.simulator.topology.TopologyClient;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** With security on, the simulator's Feign calls (topology, workloads) carry X-Internal-Token exactly once. */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {"thermogrid.security.enabled=true", "thermogrid.internal-token=s3cret"})
@Import(FeignTokenPropagationTest.StubTopology.class)
class FeignTokenPropagationTest {

    @TestConfiguration
    static class StubTopology {
        @Bean
        @Primary
        TopologyClient stubTopologyClient() {
            return TestTopology::payload;
        }
    }

    @Autowired List<RequestInterceptor> interceptors;

    @Test
    void outgoingFeignCallsCarryTheInternalTokenOnce() {
        RequestTemplate template = new RequestTemplate();
        interceptors.forEach(i -> i.apply(template));
        assertThat(template.headers().get("X-Internal-Token")).containsExactly("s3cret");
    }
}
