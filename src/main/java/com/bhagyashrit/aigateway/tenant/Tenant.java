package com.bhagyashrit.aigateway.tenant;

/** A configured tenant: who owns the API key and what quota it gets. */
public record Tenant(String name, String apiKey, int requestsPerMinute, long tokensPerDay) {}
