package com.thermogrid.internalsecurity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import java.util.Collection;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class InternalSecurityTest {

    private final InternalTokenFilter filter = new InternalTokenFilter("s3cret");

    private MockHttpServletResponse call(String path, String token) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        if (token != null) req.addHeader("X-Internal-Token", token);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res;
    }

    @Test
    void internalPathWithoutTokenIs401() throws Exception {
        assertThat(call("/internal/topology", null).getStatus()).isEqualTo(401);
    }

    @Test
    void internalPathWithWrongTokenIs401AndDoesNotEchoSecrets() throws Exception {
        MockHttpServletResponse res = call("/internal/topology", "nope");
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).doesNotContain("s3cret").doesNotContain("nope");
    }

    @Test
    void internalPathWithRightTokenPasses() throws Exception {
        assertThat(call("/internal/topology", "s3cret").getStatus()).isEqualTo(200);
    }

    @Test
    void publicPathsAreNotBlocked() throws Exception {
        assertThat(call("/api/topology", null).getStatus()).isEqualTo(200);
        assertThat(call("/internalish", null).getStatus()).isEqualTo(200);
    }

    @Test
    void blankTokenFailsFast() {
        assertThatThrownBy(() -> new InternalTokenFilter(" ")).isInstanceOf(IllegalStateException.class);
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(InternalSecurityAutoConfiguration.class));

    @Test
    void feignInterceptorAddsTheTokenToOutgoingCalls() {
        runner.withPropertyValues("thermogrid.internal-token=s3cret").run(ctx -> {
            RequestTemplate t = new RequestTemplate();
            ctx.getBean(RequestInterceptor.class).apply(t);
            Collection<String> v = t.headers().get("X-Internal-Token");
            assertThat(v).containsExactly("s3cret");
        });
    }

    @Test
    void enabledWithoutTokenRefusesToStart() {
        runner.withPropertyValues("thermogrid.internal-token=").run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void disabledFlagRegistersNothing() {
        runner.withPropertyValues("thermogrid.security.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(RequestInterceptor.class);
            assertThat(ctx).doesNotHaveBean(InternalTokenFilter.class);
        });
    }
}
