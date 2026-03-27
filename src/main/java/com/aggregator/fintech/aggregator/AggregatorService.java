package com.aggregator.fintech.aggregator;

import com.aggregator.fintech.cache.CacheService;
import com.aggregator.fintech.circuitbreaker.CircuitBreakerRegistry;
import com.aggregator.fintech.circuitbreaker.CircuitBreakerState;
import com.aggregator.fintech.deduplication.DeduplicationService;
import com.aggregator.fintech.model.PriceRequest;
import com.aggregator.fintech.model.PriceResponse;
import com.aggregator.fintech.provider.PriceProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class AggregatorService {

    private final List<PriceProvider> providers;
    private final CacheService cacheService;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final DeduplicationService deduplicationService;
    private final ResponseSelector responseSelector;
    private final ExecutorService providerExecutor;

    @Value("${async.provider-pool.timeout-seconds:5}")
    private int timeoutSeconds;

    public AggregatorService(
            List<PriceProvider> providers,
            CacheService cacheService,
            CircuitBreakerRegistry circuitBreakerRegistry,
            DeduplicationService deduplicationService,
            ResponseSelector responseSelector,
            @Qualifier("providerExecutor") ExecutorService providerExecutor) {
        this.providers = providers;
        this.cacheService = cacheService;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.deduplicationService = deduplicationService;
        this.responseSelector = responseSelector;
        this.providerExecutor = providerExecutor;
        log.info("[Aggregator] Initialized with {} provider(s): {}",
                providers.size(),
                providers.stream().map(PriceProvider::getName).toList());
    }

    public PriceResponse getPrice(PriceRequest request) {
        log.info("[Aggregator] Request: symbol={}, type={}",
                request.getSymbol(), request.getType());

        // 1. Cache check — free hit, no provider calls needed
        String cacheKey = cacheService.buildKey(request.getType(), request.getSymbol());
        PriceResponse cached = cacheService.get(cacheKey);
        if (cached != null) {
            log.info("[Aggregator] Cache HIT: {}", cacheKey);
            return stamped(cached, true);
        }

        // 2. Deduplication — coalesce concurrent requests for the same key
        CompletableFuture<PriceResponse> myFuture = new CompletableFuture<>();
        CompletableFuture<PriceResponse> canonical =
                deduplicationService.getOrRegister(cacheKey, myFuture);

        if (canonical != myFuture) {
            // Another request is already doing the work — wait for its result
            log.info("[Aggregator] Dedup HIT: waiting on in-flight future for {}", cacheKey);
            try {
                return canonical.get(timeoutSeconds + 1L, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(
                        "Deduplicated request failed for key: " + cacheKey, e);
            }
        }

        // 3. This request owns the future — do the fan-out
        try {
            PriceResponse result = fanOutAndSelect(request, cacheKey);
            myFuture.complete(result);
            return result;
        } catch (Exception e) {
            myFuture.completeExceptionally(e);
            throw e;
        } finally {
            deduplicationService.complete(cacheKey);
        }
    }

    /**
     * Core aggregator logic:
     *   - Filter eligible providers (support type + circuit breaker allows)
     *   - Fan out to ALL eligible providers in parallel via CompletableFuture
     *   - Collect all results (successes and failures)
     *   - Delegate to ResponseSelector to pick the best response
     *   - Record circuit breaker outcomes
     *   - Cache the winner if it is a clean response
     */
    private PriceResponse fanOutAndSelect(PriceRequest request, String cacheKey) {
        List<PriceProvider> eligible = providers.stream()
                .filter(p -> p.supports(request.getType()))
                .filter(p -> {
                    CircuitBreakerState breaker =
                            circuitBreakerRegistry.get(p.getName());
                    boolean allowed = breaker.allowRequest();
                    if (!allowed) {
                        log.warn("[Aggregator] Circuit breaker OPEN for {} — skipping",
                                p.getName());
                    }
                    return allowed;
                })
                .toList();

        if (eligible.isEmpty()) {
            throw new RuntimeException(
                    "All providers are circuit-broken for type=" + request.getType());
        }

        log.debug("[Aggregator] Fanning out to {} provider(s): {}",
                eligible.size(),
                eligible.stream().map(PriceProvider::getName).toList());

        // Submit all providers in parallel
        List<CompletableFuture<ProviderResult>> futures = eligible.stream()
                .map(provider -> CompletableFuture.supplyAsync(
                        () -> callProvider(provider, request), providerExecutor))
                .toList();

        // Wait for ALL futures up to timeout, then collect results
        CompletableFuture<Void> allDone = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0]));

        try {
            allDone.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Timeout — some futures may still be running.
            // We proceed with whatever completed so far.
            log.warn("[Aggregator] Fan-out timed out after {}s — using completed results",
                    timeoutSeconds);
        }

        // Collect results from completed futures only
        List<ProviderResult> results = futures.stream()
                .filter(CompletableFuture::isDone)
                .filter(f -> !f.isCompletedExceptionally())
                .map(f -> {
                    try { return f.get(); } catch (Exception e) { return null; }
                })
                .filter(r -> r != null)
                .toList();

        // Record circuit breaker outcomes for every result
        results.forEach(result -> {
            CircuitBreakerState breaker =
                    circuitBreakerRegistry.get(result.getProviderName());
            if (result.isSuccess()) {
                breaker.recordSuccess();
            } else {
                breaker.recordFailure();
                log.warn("[Aggregator] Provider {} failed: {} — breaker now: {}",
                        result.getProviderName(),
                        result.getErrorMessage(),
                        breaker.getState());
            }
        });

        // Select the best response across all provider results
        Optional<ProviderResult> best = responseSelector.selectBest(results);

        if (best.isEmpty()) {
            throw new RuntimeException(
                    "All providers failed for symbol=" + request.getSymbol()
                    + ", type=" + request.getType());
        }

        PriceResponse winner = best.get().getResponse();

        // Determine if any fallback was involved
        boolean anyFailed = results.stream().anyMatch(r -> !r.isSuccess());
        boolean finalFallbackUsed = winner.isFallbackUsed() || anyFailed;

        PriceResponse finalResponse = PriceResponse.builder()
                .symbol(winner.getSymbol())
                .price(winner.getPrice())
                .currency(winner.getCurrency())
                .type(winner.getType())
                .source(winner.getSource())
                .fallbackUsed(finalFallbackUsed)
                .cachedResponse(false)
                .timestamp(winner.getTimestamp())
                .build();

        // Cache only clean responses
        cacheService.set(cacheKey, finalResponse);

        log.info("[Aggregator] Done: symbol={}, price={}, source={}, fallbackUsed={}, providers={}",
                finalResponse.getSymbol(),
                finalResponse.getPrice(),
                finalResponse.getSource(),
                finalResponse.isFallbackUsed(),
                results.size());

        return finalResponse;
    }

    /**
     * Calls a single provider and wraps the result in a ProviderResult.
     * Never throws — failures are captured as ProviderResult.failure().
     * This is critical: CompletableFuture.supplyAsync() must not throw
     * or the future completes exceptionally and is excluded from results.
     */
    private ProviderResult callProvider(PriceProvider provider, PriceRequest request) {
        long start = System.currentTimeMillis();
        try {
            log.debug("[Aggregator] Calling provider: {} on thread: {}",
                    provider.getName(), Thread.currentThread().getName());
            PriceResponse response = provider.getPrice(request);
            long duration = System.currentTimeMillis() - start;

            if (response == null || response.getPrice() == null || response.getPrice() <= 0) {
                return ProviderResult.failure(provider.getName(),
                        "Invalid price: " + (response != null ? response.getPrice() : "null"),
                        duration);
            }

            log.debug("[Aggregator] Provider {} returned price={} in {}ms",
                    provider.getName(), response.getPrice(), duration);
            return ProviderResult.success(provider.getName(), response, duration);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.warn("[Aggregator] Provider {} threw after {}ms: {}",
                    provider.getName(), duration, e.getMessage());
            return ProviderResult.failure(provider.getName(), e.getMessage(), duration);
        }
    }

    private PriceResponse stamped(PriceResponse source, boolean cached) {
        return PriceResponse.builder()
                .symbol(source.getSymbol())
                .price(source.getPrice())
                .currency(source.getCurrency())
                .type(source.getType())
                .source(source.getSource())
                .fallbackUsed(source.isFallbackUsed())
                .cachedResponse(cached)
                .timestamp(source.getTimestamp())
                .build();
    }
}