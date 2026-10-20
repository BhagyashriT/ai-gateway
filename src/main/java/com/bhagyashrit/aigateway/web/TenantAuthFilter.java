package com.bhagyashrit.aigateway.web;

import com.bhagyashrit.aigateway.tenant.Tenant;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates requests via {@code Authorization: Bearer <tenant-api-key>}.
 *
 * <p>On success the tenant is stashed as a request attribute and in the MDC
 * (so structured logs carry tenant + request id). {@code /health} is public;
 * everything else requires a key.
 */
@Component
public class TenantAuthFilter extends OncePerRequestFilter {

    public static final String TENANT_ATTRIBUTE = "gateway.tenant";
    public static final String REQUEST_ID_ATTRIBUTE = "gateway.requestId";

    private final com.bhagyashrit.aigateway.tenant.TenantRegistry registry;
    private final com.bhagyashrit.aigateway.metrics.MetricsRegistry metrics;

    public TenantAuthFilter(com.bhagyashrit.aigateway.tenant.TenantRegistry registry,
                            com.bhagyashrit.aigateway.metrics.MetricsRegistry metrics) {
        this.registry = registry;
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        MDC.put("requestId", requestId);
        try {
            if (request.getRequestURI().equals("/health")) {
                chain.doFilter(request, response);
                return;
            }
            String key = bearerKey(request.getHeader(HttpHeaders.AUTHORIZATION));
            var tenant = key == null ? java.util.Optional.<Tenant>empty() : registry.findByKey(key);
            if (tenant.isEmpty()) {
                metrics.authFailure();
                reject(response, "invalid or missing API key; use 'Authorization: Bearer <key>'");
                return;
            }
            request.setAttribute(TENANT_ATTRIBUTE, tenant.get());
            MDC.put("tenant", tenant.get().name());
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }

    private static String bearerKey(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        String key = header.substring(7).trim();
        return key.isEmpty() ? null : key;
    }

    private static void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"error\":{\"message\":\"" + message + "\",\"type\":\"auth_error\",\"code\":\"invalid_api_key\"}}");
    }
}
