package com.aggregator.fintech.aggregator;

import com.aggregator.fintech.cache.CacheService;
import com.aggregator.fintech.circuitbreaker.CircuitBreakerRegistry;
import com.aggregator.fintech.circuitbreaker.CircuitBreakerState;
import com.aggregator.fintech.model.PriceRequest;
import com.aggregator.fintech.model.PriceResponse;
import com.aggregator.fintech.provider.PriceProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class AggregatorService {

    private final List<PriceProvider> providers;
    private final CacheService cacheService;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public AggregatorService(List<PriceProvider> providers,
                             CacheService cacheService,
                             CircuitBreakerRegistry circuitBreakerRegistry) {
        this.providers = providers;
        this.cacheService = cacheService;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        log.info("[Aggregator] Initialized with {} provider(s): {}",
                providers.size(),
                providers.stream().map(PriceProvider::getName).toList());
    }

    public PriceResponse getPrice(PriceRequest request) {
        log.info("[Aggregator] Request received: symbol={}, type={}",
                request.getSymbol(), request.getType());
        log.debug("[DEBUG] CircuitBreakerRegistry instance: {}", System.identityHashCode(circuitBreakerRegistry));
        // 1. Cache check — before touching any provider or circuit breaker
        String cacheKey = cacheService.buildKey(request.getType(), request.getSymbol());
        PriceResponse cached = cacheService.get(cacheKey);
        if (cached != null) {
            log.info("[Aggregator] Cache HIT for key: {}", cacheKey);
            return PriceResponse.builder()
                    .symbol(cached.getSymbol())
                    .price(cached.getPrice())
                    .currency(cached.getCurrency())
                    .type(cached.getType())
                    .source(cached.getSource())
                    .fallbackUsed(cached.isFallbackUsed())
                    .cachedResponse(true)
                    .timestamp(cached.getTimestamp())
                    .build();
        }

        // 2. Filter eligible providers by asset type, preserving @Order priority
        List<PriceProvider> eligible = providers.stream()
                .filter(p -> p.supports(request.getType()))
                .toList();

        if (eligible.isEmpty()) {
            throw new IllegalArgumentException(
                    "No provider available for type: " + request.getType());
        }

        // 3. Fallback loop with circuit breaker guard on each provider
        List<String> attemptedProviders = new ArrayList<>();
        List<String> skippedByBreaker = new ArrayList<>();
        boolean fallbackUsed = false;

        for (PriceProvider provider : eligible) {
            CircuitBreakerState breaker = circuitBreakerRegistry.get(provider.getName());

            // Circuit breaker check — OPEN breakers are skipped entirely
            if (!breaker.allowRequest()) {
                log.warn("[Aggregator] Circuit breaker OPEN for provider: {} — skipping",
                        provider.getName());
                skippedByBreaker.add(provider.getName());
                continue;
            }

            attemptedProviders.add(provider.getName());
            log.debug("[Aggregator] Trying provider: {}", provider.getName());

            try {
                PriceResponse response = provider.getPrice(request);
                validateResponse(response, provider.getName());

                // Success — close the breaker if it was in HALF_OPEN
                breaker.recordSuccess();

                if (!attemptedProviders.isEmpty() && attemptedProviders.size() > 1
                        || !skippedByBreaker.isEmpty()) {
                    fallbackUsed = true;
                    log.warn("[Aggregator] Fallback used. Skipped by breaker: {}. Failed: {}. Succeeded: {}",
                            skippedByBreaker,
                            attemptedProviders.subList(0, attemptedProviders.size() - 1),
                            provider.getName());
                }

                PriceResponse finalResponse = PriceResponse.builder()
                        .symbol(response.getSymbol())
                        .price(response.getPrice())
                        .currency(response.getCurrency())
                        .type(response.getType())
                        .source(response.getSource())
                        .fallbackUsed(fallbackUsed || response.isFallbackUsed())
                        .cachedResponse(false)
                        .timestamp(response.getTimestamp())
                        .build();

                // Write to cache — only for clean (non-degraded) responses
                cacheService.set(cacheKey, finalResponse);

                log.info("[Aggregator] Success: symbol={}, price={}, source={}, fallbackUsed={}",
                        finalResponse.getSymbol(), finalResponse.getPrice(),
                        finalResponse.getSource(), finalResponse.isFallbackUsed());

                return finalResponse;

            } catch (Exception e) {
                // Failure — record against the breaker; may trip it to OPEN
                breaker.recordFailure();
                log.warn("[Aggregator] Provider {} failed: {}. Breaker state now: {}.",
                        provider.getName(), e.getMessage(), breaker.getState());
            }
        }

        throw new RuntimeException(
                "All providers failed or blocked for symbol=" + request.getSymbol()
                + ", type=" + request.getType()
                + ". Attempted: " + attemptedProviders
                + ", Skipped by breaker: " + skippedByBreaker);
    }

    private void validateResponse(PriceResponse response, String providerName) {
        if (response == null) {
            throw new RuntimeException("Provider " + providerName + " returned null response");
        }
        if (response.getPrice() == null || response.getPrice() <= 0) {
            throw new RuntimeException("Provider " + providerName
                    + " returned invalid price: " + response.getPrice());
        }
    }
}