package com.aggregator.fintech.aggregator;

import com.aggregator.fintech.model.PriceResponse;
import lombok.Value;

/**
 * Carries the result of a single provider call out of a CompletableFuture.
 * Includes providerName so the selection logic knows who answered,
 * and durationMs so we can prefer faster providers when scores tie.
 */
@Value
public class ProviderResult {
    String providerName;
    PriceResponse response;
    long durationMs;
    boolean success;
    String errorMessage;

    public static ProviderResult success(String providerName, PriceResponse response, long durationMs) {
        return new ProviderResult(providerName, response, durationMs, true, null);
    }

    public static ProviderResult failure(String providerName, String errorMessage, long durationMs) {
        return new ProviderResult(providerName, null, durationMs, false, errorMessage);
    }
}