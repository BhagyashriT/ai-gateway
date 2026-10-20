package com.bhagyashrit.aigateway.tenant;

import com.bhagyashrit.aigateway.config.GatewayProperties;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Looks up tenants by API key. Keys live in config (see README for the production story). */
@Component
public class TenantRegistry {

    private final Map<String, Tenant> byKey = new ConcurrentHashMap<>();

    public TenantRegistry(GatewayProperties properties) {
        for (GatewayProperties.TenantConfig t : properties.getTenants()) {
            byKey.put(t.getApiKey(), new Tenant(t.getName(), t.getApiKey(),
                    t.getRequestsPerMinute(), t.getTokensPerDay()));
        }
    }

    public Optional<Tenant> findByKey(String apiKey) {
        return Optional.ofNullable(byKey.get(apiKey));
    }
}
