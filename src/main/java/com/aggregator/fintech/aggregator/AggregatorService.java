package com.aggregator.fintech.aggregator;

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

    // Spring injects providers sorted by @Order value.
    // Priority: CoinGecko(1) → AlphaVantage(1) → MockProvider(99)
    // Within same @Order value, type filtering ensures the right one is called.
    public AggregatorService(List<PriceProvider> providers) {
        this.providers = providers;
        log.info("[Aggregator] Initialized with {} provider(s): {}",
                providers.size(),
                providers.stream().map(PriceProvider::getName).toList());
    }

    public PriceResponse getPrice(PriceRequest request) {
        log.info("[Aggregator] Request received: symbol={}, type={}",
                request.getSymbol(), request.getType());

        // Filter to providers that support the requested asset type,
        // preserving @Order priority.
        List<PriceProvider> eligible = providers.stream()
                .filter(p -> p.supports(request.getType()))
                .toList();

        if (eligible.isEmpty()) {
            throw new IllegalArgumentException(
                    "No provider available for type: " + request.getType());
        }

        List<String> attemptedProviders = new ArrayList<>();
        boolean fallbackUsed = false;

        for (PriceProvider provider : eligible) {
            attemptedProviders.add(provider.getName());
            log.debug("[Aggregator] Trying provider: {} (attempt {}/{})",
                    provider.getName(), attemptedProviders.size(), eligible.size());

            try {
                PriceResponse response = provider.getPrice(request);

                // Validate before accepting — bad data triggers fallback too
                validateResponse(response, provider.getName());

                // If we got past the first provider, flag that fallback was used
                if (attemptedProviders.size() > 1) {
                    fallbackUsed = true;
                    log.warn("[Aggregator] Fallback triggered. Failed providers: {}. Succeeded with: {}",
                            attemptedProviders.subList(0, attemptedProviders.size() - 1),
                            provider.getName());
                }

                // Stamp the final fallbackUsed flag on the response
                PriceResponse finalResponse = PriceResponse.builder()
                        .symbol(response.getSymbol())
                        .price(response.getPrice())
                        .currency(response.getCurrency())
                        .type(response.getType())
                        .source(response.getSource())
                        .fallbackUsed(fallbackUsed || response.isFallbackUsed())
                        .cachedResponse(response.isCachedResponse())
                        .timestamp(response.getTimestamp())
                        .build();

                log.info("[Aggregator] Success: symbol={}, price={}, source={}, fallbackUsed={}",
                        finalResponse.getSymbol(),
                        finalResponse.getPrice(),
                        finalResponse.getSource(),
                        finalResponse.isFallbackUsed());

                return finalResponse;

            } catch (Exception e) {
                log.warn("[Aggregator] Provider {} failed: {}. Moving to next provider.",
                        provider.getName(), e.getMessage());
                // Continue to the next provider in the loop
            }
        }

        // All providers exhausted
        throw new RuntimeException(
                "All providers failed for symbol=" + request.getSymbol()
                + ", type=" + request.getType()
                + ". Attempted: " + attemptedProviders);
    }

    /**
     * Validates that the response contains usable data.
     * A null or non-positive price is treated as a provider failure,
     * which triggers fallback to the next provider in the chain.
     */
    private void validateResponse(PriceResponse response, String providerName) {
        if (response == null) {
            throw new RuntimeException(
                    "Provider " + providerName + " returned null response");
        }
        if (response.getPrice() == null || response.getPrice() <= 0) {
            throw new RuntimeException(
                    "Provider " + providerName
                    + " returned invalid price: " + response.getPrice());
        }
    }
}