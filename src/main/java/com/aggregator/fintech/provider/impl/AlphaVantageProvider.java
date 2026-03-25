package com.aggregator.fintech.provider.impl;

import com.aggregator.fintech.model.PriceRequest;
import com.aggregator.fintech.model.PriceResponse;
import com.aggregator.fintech.provider.PriceProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Map;

@Order(1)
@Slf4j
@Component
public class AlphaVantageProvider implements PriceProvider {

    private static final String NAME = "AlphaVantage";

    // Hardcoded fallback prices for when we've burned the 25 free calls/day
    private static final Map<String, Double> FALLBACK_PRICES = Map.of(
            "AAPL",  189.50,
            "MSFT",  415.00,
            "GOOGL", 175.00,
            "AMZN",  185.00,
            "TSLA",  172.00,
            "NVDA",  875.00,
            "META",  510.00
    );

    @Value("${providers.alphavantage.base-url}")
    private String baseUrl;

    @Value("${providers.alphavantage.api-key}")
    private String apiKey;

    @Value("${providers.alphavantage.fallback-enabled}")
    private boolean fallbackEnabled;

    private final RestTemplate restTemplate;

    public AlphaVantageProvider(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public PriceResponse getPrice(PriceRequest request) {
        String symbol = request.getSymbol().toUpperCase();

        try {
            // GLOBAL_QUOTE returns the latest price for a stock symbol
            String url = baseUrl
                    + "?function=GLOBAL_QUOTE"
                    + "&symbol=" + symbol
                    + "&apikey=" + apiKey;

            log.debug("[AlphaVantage] Calling URL: {}", url.replace(apiKey, "***"));

            Map response = restTemplate.getForObject(url, Map.class);

            if (response == null) {
                throw new RuntimeException("[AlphaVantage] Null response for " + symbol);
            }

            // AlphaVantage wraps the result in a "Global Quote" object
            Map<String, Object> quote = (Map<String, Object>) response.get("Global Quote");

            if (quote == null || quote.isEmpty()) {
                // This happens when the free tier limit is hit — the response body
                // contains a "Note" key instead of data
                String note = (String) response.get("Note");
                String info = (String) response.get("Information");
                throw new RuntimeException(
                        "[AlphaVantage] No quote data for " + symbol
                        + (note != null ? " — API note: " + note : "")
                        + (info != null ? " — API info: " + info : "")
                );
            }

            String priceStr = (String) quote.get("05. price");
            if (priceStr == null || priceStr.isBlank()) {
                throw new RuntimeException("[AlphaVantage] Price field missing for " + symbol);
            }

            Double price = Double.parseDouble(priceStr);
            log.debug("[AlphaVantage] Fetched {} = ${}", symbol, price);

            return PriceResponse.builder()
                    .symbol(symbol)
                    .price(price)
                    .currency("USD")
                    .type("stock")
                    .source(NAME)
                    .fallbackUsed(false)
                    .cachedResponse(false)
                    .timestamp(Instant.now())
                    .build();

        } catch (Exception e) {
            log.warn("[AlphaVantage] Real API call failed for {}: {}. Trying hardcoded fallback.",
                    symbol, e.getMessage());

            if (fallbackEnabled && FALLBACK_PRICES.containsKey(symbol)) {
                log.info("[AlphaVantage] Serving hardcoded fallback price for {}", symbol);
                return PriceResponse.builder()
                        .symbol(symbol)
                        .price(FALLBACK_PRICES.get(symbol))
                        .currency("USD")
                        .type("stock")
                        .source(NAME + " (hardcoded-fallback)")
                        .fallbackUsed(true)
                        .cachedResponse(false)
                        .timestamp(Instant.now())
                        .build();
            }

            throw new RuntimeException(
                    "[AlphaVantage] Failed to fetch price for " + symbol + ": " + e.getMessage(), e);
        }
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean supports(String type) {
        return "stock".equalsIgnoreCase(type);
    }
}