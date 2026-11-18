package com.thermogrid.cooling;

import static org.assertj.core.api.Assertions.assertThat;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** With security on, the topology Feign call carries X-Internal-Token. */
@TestPropertySource(properties = {"thermogrid.security.enabled=true", "thermogrid.internal-token=s3cret"})
class FeignTokenPropagationTest extends CoolingTestBase {

    @Autowired List<RequestInterceptor> interceptors;

    @Test
    void outgoingFeignCallsCarryTheInternalToken() {
        RequestTemplate template = new RequestTemplate();
        interceptors.forEach(i -> i.apply(template));
        assertThat(template.headers().get("X-Internal-Token")).containsExactly("s3cret");
    }
}
