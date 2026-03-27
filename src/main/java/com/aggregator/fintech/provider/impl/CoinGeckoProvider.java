package com.aggregator.fintech.provider.impl;

import com.aggregator.fintech.model.PriceRequest;
import com.aggregator.fintech.model.PriceResponse;
import com.aggregator.fintech.provider.PriceProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Map;

@Order(1)
@Slf4j
@Component
public class CoinGeckoProvider implements PriceProvider {

    private static final String NAME = "CoinGecko";

    // Mapping from common symbols to CoinGecko coin IDs
    private static final Map<String, String> SYMBOL_TO_ID = Map.of(
            "BTC", "bitcoin",
            "ETH", "ethereum",
            "SOL", "solana",
            "BNB", "binancecoin",
            "USDT", "tether"
    );

    // Hardcoded fallback prices used when the real API is unreachable (Option B)
    private static final Map<String, Double> FALLBACK_PRICES = Map.of(
            "BTC", 65000.00,
            "ETH", 3200.00,
            "SOL", 140.00,
            "BNB", 570.00,
            "USDT", 1.00
    );

    @Value("${providers.coingecko.base-url}")
    private String baseUrl;

    @Value("${providers.coingecko.fallback-enabled}")
    private boolean fallbackEnabled;

    private final RestTemplate restTemplate;

    public CoinGeckoProvider(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public PriceResponse getPrice(PriceRequest request) {
        String symbol = request.getSymbol().toUpperCase();
        String coinId = SYMBOL_TO_ID.get(symbol);

        if (coinId == null) {
            throw new IllegalArgumentException("Unsupported crypto symbol: " + symbol);
        }

        // try {
            String url = baseUrl + "/simple/price?ids=" + coinId + "&vs_currencies=usd";
            log.debug("[CoinGecko] Calling URL: {}", url);

            Map response = restTemplate.getForObject(url, Map.class);

            if (response == null || !response.containsKey(coinId)) {
                throw new RuntimeException("[CoinGecko] Empty or unexpected response for " + symbol);
            }

            Map<String, Object> coinData = (Map<String, Object>) response.get(coinId);
            Double price = ((Number) coinData.get("usd")).doubleValue();

            log.debug("[CoinGecko] Fetched {} = ${}", symbol, price);

            return PriceResponse.builder()
                    .symbol(symbol)
                    .price(price)
                    .currency("USD")
                    .type("crypto")
                    .source(NAME)
                    .fallbackUsed(false)
                    .cachedResponse(false)
                    .timestamp(Instant.now())
                    .build();

        // } catch (Exception e) {
        //     log.warn("[CoinGecko] Real API call failed for {}: {}. Trying hardcoded fallback.", symbol, e.getMessage());

        //     if (fallbackEnabled && FALLBACK_PRICES.containsKey(symbol)) {
        //         log.info("[CoinGecko] Serving hardcoded fallback price for {}", symbol);
        //         return PriceResponse.builder()
        //                 .symbol(symbol)
        //                 .price(FALLBACK_PRICES.get(symbol))
        //                 .currency("USD")
        //                 .type("crypto")
        //                 .source(NAME + " (hardcoded-fallback)")
        //                 .fallbackUsed(true)
        //                 .cachedResponse(false)
        //                 .timestamp(Instant.now())
        //                 .build();
        //     }

        //     // No fallback available — let AggregatorService handle it
        //     throw new RuntimeException("[CoinGecko] Failed to fetch price for " + symbol + ": " + e.getMessage(), e);
        // }
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean supports(String type) {
        return "crypto".equalsIgnoreCase(type);
    }
}