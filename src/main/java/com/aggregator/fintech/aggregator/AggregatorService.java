package com.aggregator.fintech.aggregator;

import com.aggregator.fintech.cache.CacheService;
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

    public AggregatorService(List<PriceProvider> providers, CacheService cacheService) {
        this.providers = providers;
        this.cacheService = cacheService;
        log.info("[Aggregator] Initialized with {} provider(s): {}",
                providers.size(),
                providers.stream().map(PriceProvider::getName).toList());
    }

    public PriceResponse getPrice(PriceRequest request) {
        log.info("[Aggregator] Request received: symbol={}, type={}",
                request.getSymbol(), request.getType());

        String cacheKey = cacheService.buildKey(request.getType(), request.getSymbol());

        // 1. Cache check — before touching any provider
        PriceResponse cached = cacheService.get(cacheKey);
        if (cached != null) {
            log.info("[Aggregator] Cache HIT for key: {} returning cached price={}", cacheKey, cached.getPrice());
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

        // 3. Fallback loop — try each provider in priority order
        List<String> attemptedProviders = new ArrayList<>();
        boolean fallbackUsed = false;

        for (PriceProvider provider : eligible) {
            attemptedProviders.add(provider.getName());
            log.debug("[Aggregator] Trying provider: {} (attempt {}/{})",
                    provider.getName(), attemptedProviders.size(), eligible.size());

            try {
                PriceResponse response = provider.getPrice(request);
                validateResponse(response, provider.getName());

                if (attemptedProviders.size() > 1) {
                    fallbackUsed = true;
                    log.warn("[Aggregator] Fallback triggered. Failed: {} Succeeded: {}",
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

                // 4. Write to cache — only if response is not degraded
                cacheService.set(cacheKey, finalResponse);

                log.info("[Aggregator] Success: symbol={}, price={}, source={}, fallbackUsed={}",
                        finalResponse.getSymbol(),
                        finalResponse.getPrice(),
                        finalResponse.getSource(),
                        finalResponse.isFallbackUsed());

                return finalResponse;

            } catch (Exception e) {
                log.warn("[Aggregator] Provider {} failed: {}. Moving to next provider.",
                        provider.getName(), e.getMessage());
            }
        }

        throw new RuntimeException(
                "All providers failed for symbol=" + request.getSymbol()
                + ", type=" + request.getType()
                + ". Attempted: " + attemptedProviders);
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