package com.aggregator.fintech.circuitbreaker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry — one CircuitBreakerState per provider name.
 * Breakers are created lazily on first access so we never need
 * to enumerate providers at startup.
 *
 * Thresholds are shared across all breakers for simplicity.
 * A production system would allow per-provider overrides in config.
 */
@Component
public class CircuitBreakerRegistry {

    @Value("${circuit-breaker.failure-threshold:3}")
    private int failureThreshold;

    @Value("${circuit-breaker.recovery-timeout-seconds:10}")
    private long recoveryTimeoutSeconds;

    private final Map<String, CircuitBreakerState> breakers = new ConcurrentHashMap<>();

    /**
     * Returns the CircuitBreakerState for the given provider,
     * creating one if it does not yet exist.
     */
    public CircuitBreakerState get(String providerName) {
        return breakers.computeIfAbsent(providerName, name ->
                new CircuitBreakerState(name, failureThreshold, recoveryTimeoutSeconds));
    }

    /**
     * Returns all registered breakers — used by the /health endpoint.
     */
    public Collection<CircuitBreakerState> all() {
        return breakers.values();
    }
}