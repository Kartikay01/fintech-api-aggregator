package com.aggregator.fintech.aggregator;

import com.aggregator.fintech.model.PriceRequest;
import com.aggregator.fintech.model.PriceResponse;
import com.aggregator.fintech.provider.PriceProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class AggregatorService {

    private final List<PriceProvider> providers;

    // Spring auto-injects all PriceProvider beans in declaration order.
    // Day 2: we'll wire priority lists per type.
    // Day 1: just use the first provider that supports the requested type.
    public AggregatorService(List<PriceProvider> providers) {
        this.providers = providers;
        log.info("[Aggregator] Initialized with {} provider(s): {}",
                providers.size(),
                providers.stream().map(PriceProvider::getName).toList());
    }

    public PriceResponse getPrice(PriceRequest request) {
        log.info("[Aggregator] Received request: symbol={}, type={}", request.getSymbol(), request.getType());

        PriceProvider provider = providers.stream()
                .filter(p -> p.supports(request.getType()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No provider available for type: " + request.getType()));

        log.debug("[Aggregator] Delegating to provider: {}", provider.getName());

        // Day 1: no fallback, no cache, no rate limiter — raw provider call.
        // This will be layered in Day 2 and 3.
        PriceResponse response = provider.getPrice(request);

        validateResponse(response, provider.getName());

        log.info("[Aggregator] Response: symbol={}, price={}, source={}",
                response.getSymbol(), response.getPrice(), response.getSource());

        return response;
    }

    /**
     * Validates that the response contains usable data.
     * On Day 2, a failed validation will trigger fallback to the next provider.
     */
    private void validateResponse(PriceResponse response, String providerName) {
        if (response == null) {
            throw new RuntimeException("[Aggregator] Provider " + providerName + " returned null response");
        }
        if (response.getPrice() == null || response.getPrice() <= 0) {
            throw new RuntimeException("[Aggregator] Provider " + providerName
                    + " returned invalid price: " + response.getPrice());
        }
    }
}