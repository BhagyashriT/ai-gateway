package com.bhagyashrit.aigateway.web;

import com.bhagyashrit.aigateway.metrics.MetricsRegistry;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ops endpoints. {@code /health} is public (load-balancer friendly);
 * {@code /metrics} requires a tenant key.
 */
@RestController
public class OpsController {

    private final MetricsRegistry metrics;

    public OpsController(MetricsRegistry metrics) {
        this.metrics = metrics;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "service", "ai-gateway");
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return metrics.snapshot();
    }
}
