package com.bhagyashrit.aigateway.config;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gateway configuration, bound from {@code application.yml} under {@code gateway.*}.
 *
 * <p>Tenants and their quotas are declared here (a config file, not a database)
 * because the gateway is a single-instance reference implementation; the
 * trade-offs section of the README explains what changes for multi-instance
 * production use.
 */
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    /** Response cache TTL in seconds. */
    private long cacheTtlSeconds = 300;

    /** When true, requests whose prompt trips the injection heuristics are rejected with 400. */
    private boolean blockOnInjection = true;

    /** Consecutive upstream failures before a provider's circuit opens. */
    private int circuitBreakerFailureThreshold = 5;

    /** Seconds a provider's circuit stays open before a trial request is allowed. */
    private long circuitBreakerCooldownSeconds = 60;

    private List<TenantConfig> tenants = new ArrayList<>();
    private List<ProviderConfig> providers = new ArrayList<>();

    public long getCacheTtlSeconds() { return cacheTtlSeconds; }
    public void setCacheTtlSeconds(long v) { this.cacheTtlSeconds = v; }
    public boolean isBlockOnInjection() { return blockOnInjection; }
    public void setBlockOnInjection(boolean v) { this.blockOnInjection = v; }
    public int getCircuitBreakerFailureThreshold() { return circuitBreakerFailureThreshold; }
    public void setCircuitBreakerFailureThreshold(int v) { this.circuitBreakerFailureThreshold = v; }
    public long getCircuitBreakerCooldownSeconds() { return circuitBreakerCooldownSeconds; }
    public void setCircuitBreakerCooldownSeconds(long v) { this.circuitBreakerCooldownSeconds = v; }
    public List<TenantConfig> getTenants() { return tenants; }
    public void setTenants(List<TenantConfig> v) { this.tenants = v; }
    public List<ProviderConfig> getProviders() { return providers; }
    public void setProviders(List<ProviderConfig> v) { this.providers = v; }

    public static class TenantConfig {
        private String name;
        private String apiKey;
        /** Requests per minute (token bucket). */
        private int requestsPerMinute = 60;
        /** Estimated tokens per UTC day. */
        private long tokensPerDay = 100_000;

        public String getName() { return name; }
        public void setName(String v) { this.name = v; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String v) { this.apiKey = v; }
        public int getRequestsPerMinute() { return requestsPerMinute; }
        public void setRequestsPerMinute(int v) { this.requestsPerMinute = v; }
        public long getTokensPerDay() { return tokensPerDay; }
        public void setTokensPerDay(long v) { this.tokensPerDay = v; }
    }

    public static class ProviderConfig {
        /** "stub" or "openai" (any OpenAI-compatible HTTP API). */
        private String type = "stub";
        private String name;
        private String baseUrl;
        /** May reference an env var, e.g. ${UPSTREAM_API_KEY}. Never commit a real key. */
        private String apiKey;
        private String model;
        private BigDecimal costPer1kPromptTokens = BigDecimal.ZERO;
        private BigDecimal costPer1kCompletionTokens = BigDecimal.ZERO;

        public String getType() { return type; }
        public void setType(String v) { this.type = v; }
        public String getName() { return name; }
        public void setName(String v) { this.name = v; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String v) { this.baseUrl = v; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String v) { this.apiKey = v; }
        public String getModel() { return model; }
        public void setModel(String v) { this.model = v; }
        public BigDecimal getCostPer1kPromptTokens() { return costPer1kPromptTokens; }
        public void setCostPer1kPromptTokens(BigDecimal v) { this.costPer1kPromptTokens = v; }
        public BigDecimal getCostPer1kCompletionTokens() { return costPer1kCompletionTokens; }
        public void setCostPer1kCompletionTokens(BigDecimal v) { this.costPer1kCompletionTokens = v; }
    }
}
