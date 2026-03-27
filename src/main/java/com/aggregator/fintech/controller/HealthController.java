package com.aggregator.fintech.controller;

import com.aggregator.fintech.circuitbreaker.CircuitBreakerRegistry;
import com.aggregator.fintech.circuitbreaker.CircuitBreakerState;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class HealthController {

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    /**
     * GET /health
     *
     * Returns the circuit breaker state for every provider that has
     * received at least one request. Clean providers won't appear here
     * until they've been called at least once.
     *
     * Example response:
     * {
     *   "status": "UP",
     *   "circuitBreakers": [
     *     {
     *       "provider": "CoinGecko",
     *       "state": "CLOSED",
     *       "consecutiveFailures": 0,
     *       "openedAt": null
     *     },
     *     {
     *       "provider": "MockProvider",
     *       "state": "OPEN",
     *       "consecutiveFailures": 3,
     *       "openedAt": "2024-03-01T10:00:00Z"
     *     }
     *   ]
     * }
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        List<Map<String, Object>> breakerStatus = circuitBreakerRegistry.all().stream()
                .map(this::summarize)
                .toList();

        // Overall system status: DOWN if any breaker is OPEN
        boolean anyOpen = circuitBreakerRegistry.all().stream()
                .anyMatch(b -> b.getState() == CircuitBreakerState.State.OPEN);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", anyOpen ? "DEGRADED" : "UP");
        response.put("timestamp", Instant.now().toString());
        response.put("circuitBreakers", breakerStatus);

        return ResponseEntity.ok(response);
    }

    private Map<String, Object> summarize(CircuitBreakerState breaker) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("provider", breaker.getProviderName());
        map.put("state", breaker.getState().name());
        map.put("consecutiveFailures", breaker.getConsecutiveFailures());
        map.put("openedAt", breaker.getOpenedAt() != null
                ? breaker.getOpenedAt().toString() : null);
        return map;
    }
}