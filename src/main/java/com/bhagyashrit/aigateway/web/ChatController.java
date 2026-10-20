package com.bhagyashrit.aigateway.web;

import com.bhagyashrit.aigateway.cache.ResponseCache;
import com.bhagyashrit.aigateway.config.GatewayProperties;
import com.bhagyashrit.aigateway.failover.ProviderRouter;
import com.bhagyashrit.aigateway.guardrail.Guardrails;
import com.bhagyashrit.aigateway.metrics.MetricsRegistry;
import com.bhagyashrit.aigateway.model.ChatDtos.ChatCompletionRequest;
import com.bhagyashrit.aigateway.model.ChatDtos.ChatCompletionResponse;
import com.bhagyashrit.aigateway.model.ChatDtos.ChatMessage;
import com.bhagyashrit.aigateway.model.ChatDtos.ErrorBody;
import com.bhagyashrit.aigateway.model.ChatDtos.TokenUsage;
import com.bhagyashrit.aigateway.provider.ProviderException;
import com.bhagyashrit.aigateway.ratelimit.QuotaExceededException;
import com.bhagyashrit.aigateway.ratelimit.QuotaManager;
import com.bhagyashrit.aigateway.tenant.Tenant;
import com.bhagyashrit.aigateway.usage.UsageLedger;
import com.bhagyashrit.aigateway.util.TokenEstimator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The OpenAI-compatible chat endpoint.
 *
 * <p>Request pipeline: auth (filter) → guardrail scan → request quota →
 * cache lookup → token quota (estimate) → provider failover → PII redaction →
 * cache store → usage ledger → response.
 */
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final GatewayProperties properties;
    private final ProviderRouter router;
    private final QuotaManager quotas;
    private final ResponseCache cache;
    private final Guardrails guardrails;
    private final UsageLedger ledger;
    private final MetricsRegistry metrics;
    private final ObjectMapper mapper;

    public ChatController(GatewayProperties properties, ProviderRouter router, QuotaManager quotas,
                          ResponseCache cache, Guardrails guardrails, UsageLedger ledger,
                          MetricsRegistry metrics, ObjectMapper mapper) {
        this.properties = properties;
        this.router = router;
        this.quotas = quotas;
        this.cache = cache;
        this.guardrails = guardrails;
        this.ledger = ledger;
        this.metrics = metrics;
        this.mapper = mapper;
    }

    @PostMapping("/v1/chat/completions")
    public ResponseEntity<?> chat(
            @RequestAttribute(TenantAuthFilter.TENANT_ATTRIBUTE) Tenant tenant,
            @RequestBody ChatCompletionRequest request) {

        metrics.request();
        long startNanos = System.nanoTime();

        if (request == null || request.messages() == null || request.messages().isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "messages must be a non-empty array",
                    "invalid_request_error", "missing_messages");
        }
        if (Boolean.TRUE.equals(request.stream())) {
            return error(HttpStatus.BAD_REQUEST, "streaming is not supported by this gateway",
                    "invalid_request_error", "streaming_unsupported");
        }
        String model = request.model() != null ? request.model() : "default";

        // 1. Inbound guardrail: prompt-injection heuristics.
        String promptText = concatUserText(request.messages());
        List<String> findings = guardrails.scanPrompt(promptText);
        if (!findings.isEmpty()) {
            metrics.guardrailBlock();
            log.warn("guardrail_block tenant={} findings={}", tenant.name(), findings);
            if (properties.isBlockOnInjection()) {
                return error(HttpStatus.BAD_REQUEST,
                        "prompt rejected by input guardrail (" + String.join(",", findings) + ")",
                        "guardrail_error", "prompt_blocked");
            }
        }

        // 2. Request quota (token bucket).
        try {
            quotas.checkRequestQuota(tenant);
        } catch (QuotaExceededException e) {
            return quotaError(e);
        }

        // 3. Cache lookup (exact prompt).
        String canonical = canonicalMessages(model, request.messages());
        String cacheKey = cache.keyFor(model, canonical);
        var cached = cache.get(cacheKey);
        if (cached.isPresent()) {
            metrics.cacheHit();
            log.info("chat_cache_hit tenant={} model={}", tenant.name(), model);
            return ResponseEntity.ok()
                    .header("X-Cache", "HIT")
                    .header("X-Provider", "cache")
                    .body(cached.get());
        }

        // 4. Token quota against the estimated prompt size.
        int estimatedPromptTokens = TokenEstimator.estimateMessages(request.messages());
        try {
            quotas.checkTokenQuota(tenant, estimatedPromptTokens);
        } catch (QuotaExceededException e) {
            return quotaError(e);
        }

        // 5. Provider failover.
        ProviderRouter.RoutedResult routed;
        long upstreamStart = System.nanoTime();
        try {
            routed = router.route(request);
        } catch (ProviderRouter.AllProvidersFailedException e) {
            metrics.upstreamError("all");
            return error(HttpStatus.BAD_GATEWAY, "all upstream providers failed: " + e.getLastError(),
                    "provider_error", "all_providers_failed");
        } catch (ProviderException e) {
            metrics.upstreamError("all");
            return error(HttpStatus.BAD_GATEWAY, "upstream rejected the request: " + e.getMessage(),
                    "provider_error", "upstream_rejected");
        }
        long upstreamMs = (System.nanoTime() - upstreamStart) / 1_000_000;
        metrics.providerCall(routed.providerName(), upstreamMs);

        // 6. Outbound guardrail: redact PII from the model output.
        String redacted = guardrails.redactPii(routed.result().content());

        // 7. Reconcile quota with real usage and record cost.
        int totalTokens = routed.result().promptTokens() + routed.result().completionTokens();
        quotas.reconcile(tenant, estimatedPromptTokens, totalTokens);
        BigDecimal[] rates = ratesFor(routed.providerName());
        ledger.record(tenant.name(), routed.result().modelUsed(),
                routed.result().promptTokens(), routed.result().completionTokens(),
                rates[0], rates[1]);

        // 8. Build the OpenAI-shaped response and cache it.
        var body = new ChatCompletionResponse(
                "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24),
                "chat.completion",
                Instant.now().getEpochSecond(),
                routed.result().modelUsed(),
                List.of(new ChatCompletionResponse.Choice(0,
                        new ChatMessage("assistant", redacted), "stop")),
                new TokenUsage(routed.result().promptTokens(), routed.result().completionTokens(), totalTokens));
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "failed to serialize response",
                    "server_error", "serialization_failed");
        }
        cache.put(cacheKey, json, properties.getCacheTtlSeconds());

        long totalMs = (System.nanoTime() - startNanos) / 1_000_000;
        log.info("chat_completion tenant={} model={} provider={} promptTokens={} completionTokens={} upstreamMs={} totalMs={} attempts={}",
                tenant.name(), routed.result().modelUsed(), routed.providerName(),
                routed.result().promptTokens(), routed.result().completionTokens(),
                upstreamMs, totalMs, routed.attempts());

        return ResponseEntity.ok()
                .header("X-Cache", "MISS")
                .header("X-Provider", routed.providerName())
                .body(json);
    }

    private BigDecimal[] ratesFor(String providerName) {
        for (GatewayProperties.ProviderConfig c : properties.getProviders()) {
            String name = c.getName() != null ? c.getName() : c.getType();
            if (name.equals(providerName)) {
                return new BigDecimal[]{c.getCostPer1kPromptTokens(), c.getCostPer1kCompletionTokens()};
            }
        }
        return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO};
    }

    private ResponseEntity<ErrorBody> quotaError(QuotaExceededException e) {
        metrics.rateLimited();
        log.warn("quota_exceeded error={} retryAfter={}", e.getMessage(), e.getRetryAfterSeconds());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(e.getRetryAfterSeconds()))
                .body(new ErrorBody(new ErrorBody.ErrorDetail(e.getMessage(), "rate_limit_error", "quota_exceeded")));
    }

    private static ResponseEntity<ErrorBody> error(HttpStatus status, String message, String type, String code) {
        return ResponseEntity.status(status)
                .body(new ErrorBody(new ErrorBody.ErrorDetail(message, type, code)));
    }

    private static String concatUserText(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : messages) {
            if ("user".equalsIgnoreCase(m.role()) && m.content() != null) {
                sb.append(m.content()).append('\n');
            }
        }
        return sb.toString();
    }

    private String canonicalMessages(String model, List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder(model).append('\n');
        for (ChatMessage m : messages) {
            sb.append(m.role()).append(':').append(m.content()).append('\n');
        }
        return sb.toString();
    }

    /** Tenant-scoped usage ledger. */
    @RestController
    public static class UsageController {
        private final UsageLedger ledger;

        public UsageController(UsageLedger ledger) {
            this.ledger = ledger;
        }

        @org.springframework.web.bind.annotation.GetMapping("/v1/usage")
        public Map<String, UsageLedger.ModelUsage> usage(
                @RequestAttribute(TenantAuthFilter.TENANT_ATTRIBUTE) Tenant tenant) {
            return ledger.forTenant(tenant.name());
        }
    }
}
