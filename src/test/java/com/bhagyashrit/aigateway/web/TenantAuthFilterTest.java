package com.bhagyashrit.aigateway.web;

import static org.junit.jupiter.api.Assertions.*;
import com.bhagyashrit.aigateway.config.GatewayProperties;
import com.bhagyashrit.aigateway.metrics.MetricsRegistry;
import com.bhagyashrit.aigateway.tenant.Tenant;
import com.bhagyashrit.aigateway.tenant.TenantRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Auth filter behavior with mock servlet objects — no container. */
class TenantAuthFilterTest {

    private TenantAuthFilter filter() {
        GatewayProperties props = new GatewayProperties();
        GatewayProperties.TenantConfig t = new GatewayProperties.TenantConfig();
        t.setName("demo");
        t.setApiKey("secret-key");
        t.setRequestsPerMinute(60);
        t.setTokensPerDay(1000);
        props.setTenants(java.util.List.of(t));
        return new TenantAuthFilter(new TenantRegistry(props), new MetricsRegistry());
    }

    @Test
    void healthIsPublic() throws Exception {
        var req = new MockHttpServletRequest("GET", "/health");
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter().doFilter(req, res, chain);
        assertEquals(200, res.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    void missingKeyIs401() throws Exception {
        var req = new MockHttpServletRequest("POST", "/v1/chat/completions");
        var res = new MockHttpServletResponse();
        filter().doFilter(req, res, new MockFilterChain());
        assertEquals(401, res.getStatus());
        assertTrue(res.getContentAsString().contains("invalid_api_key"));
    }

    @Test
    void unknownKeyIs401() throws Exception {
        var req = new MockHttpServletRequest("POST", "/v1/chat/completions");
        req.addHeader("Authorization", "Bearer nope");
        var res = new MockHttpServletResponse();
        filter().doFilter(req, res, new MockFilterChain());
        assertEquals(401, res.getStatus());
    }

    @Test
    void validKeySetsTenantAttribute() throws Exception {
        var req = new MockHttpServletRequest("POST", "/v1/chat/completions");
        req.addHeader("Authorization", "Bearer secret-key");
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter().doFilter(req, res, chain);
        assertEquals(200, res.getStatus());
        Tenant tenant = (Tenant) req.getAttribute(TenantAuthFilter.TENANT_ATTRIBUTE);
        assertNotNull(tenant);
        assertEquals("demo", tenant.name());
    }
}
