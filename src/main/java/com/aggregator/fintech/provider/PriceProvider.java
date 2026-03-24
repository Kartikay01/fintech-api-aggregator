package com.aggregator.fintech.provider;

import com.aggregator.fintech.model.PriceRequest;
import com.aggregator.fintech.model.PriceResponse;

public interface PriceProvider {

    /**
     * Fetch price for the given request.
     * Implementations must throw RuntimeException on any failure —
     * the AggregatorService uses this contract to trigger fallback.
     */
    PriceResponse getPrice(PriceRequest request);

    /**
     * Human-readable provider name, used in logs and response payload.
     */
    String getName();

    /**
     * Which asset types this provider supports.
     * e.g. CoinGecko supports "crypto", AlphaVantage supports "stock"
     */
    boolean supports(String type);
}