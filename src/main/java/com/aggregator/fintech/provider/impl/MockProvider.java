package com.aggregator.fintech.provider.impl;

import com.aggregator.fintech.model.PriceRequest;
import com.aggregator.fintech.model.PriceResponse;
import com.aggregator.fintech.provider.PriceProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Random;

@Slf4j
@Component
public class MockProvider implements PriceProvider {

    private static final String NAME = "MockProvider";

    // Realistic mock prices for demo purposes
    private static final Map<String, Double> MOCK_PRICES = Map.of(
            "BTC", 64500.00,
            "ETH", 3150.00,
            "SOL", 138.00,
            "BNB", 565.00,
            "USDT", 1.00,
            "AAPL", 189.50,
            "MSFT", 415.00,
            "GOOGL", 175.00,
            "AMZN", 185.00,
            "TSLA", 172.00
    );

    public enum MockMode {
        FAIL,       // Throws a RuntimeException immediately
        SLOW,       // Sleeps 3-5 seconds then returns valid data
        BAD_DATA,   // Returns null price (invalid data)
        RANDOM      // Randomly picks one of the above on each call
    }

    @Value("${providers.mock.mode}")
    private String configuredMode;

    private final Random random = new Random();

    @Override
    public PriceResponse getPrice(PriceRequest request) {
        String symbol = request.getSymbol().toUpperCase();
        MockMode mode = resolveMode();

        log.debug("[MockProvider] Handling request for {} with mode: {}", symbol, mode);

        switch (mode) {
            case FAIL -> {
                log.warn("[MockProvider] Simulating instant failure for {}", symbol);
                throw new RuntimeException("[MockProvider] Simulated provider failure for " + symbol);
            }
            case SLOW -> {
                // In RANDOM mode, 40% of rolls land here as a "normal" fast response.
                // Only actually sleep if we were explicitly set to SLOW or randomly hit 20% path.
                // We differentiate by checking configuredMode directly.
                boolean isIntentionallySlow = "SLOW".equalsIgnoreCase(configuredMode)
                        || (random.nextInt(10) < 2); // re-roll: ~20% of SLOW paths actually delay
                if (isIntentionallySlow) {
                    int delayMs = 3000 + random.nextInt(2000); // 3000–5000ms
                    log.warn("[MockProvider] Simulating slow response for {} ({}ms delay)", symbol, delayMs);
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("[MockProvider] Interrupted during slow simulation");
                    }
                }
                // Falls through to return valid data
            }
            case BAD_DATA -> {
                log.warn("[MockProvider] Simulating bad data for {}", symbol);
                return PriceResponse.builder()
                        .symbol(symbol)
                        .price(null)       // Intentionally null — triggers validation failure
                        .currency("USD")
                        .type(request.getType())
                        .source(NAME)
                        .fallbackUsed(false)
                        .cachedResponse(false)
                        .timestamp(Instant.now())
                        .build();
            }
        }

        // SLOW mode lands here after sleeping, RANDOM may also pick a "normal" path
        Double price = MOCK_PRICES.getOrDefault(symbol, 100.00);

        return PriceResponse.builder()
                .symbol(symbol)
                .price(price)
                .currency("USD")
                .type(request.getType())
                .source(NAME)
                .fallbackUsed(false)
                .cachedResponse(false)
                .timestamp(Instant.now())
                .build();
    }

    /**
     * RANDOM mode: 40% chance of a failure mode (FAIL/SLOW/BAD_DATA evenly split),
     * 60% chance of a clean response. This keeps the demo interesting without
     * being so flaky that you can never get a clean result.
     *
     * For a guaranteed failure during demos, set mock.mode: FAIL in application.yml.
     */
    private MockMode resolveMode() {
        if ("RANDOM".equalsIgnoreCase(configuredMode)) {
            int roll = random.nextInt(10); // 0–9
            if (roll < 2) return MockMode.FAIL;      // 20%
            if (roll < 4) return MockMode.SLOW;      // 20%
            if (roll < 6) return MockMode.BAD_DATA;  // 20%
            return MockMode.SLOW;                    // 40% — normal path (SLOW with 0ms override below)
        }
        return MockMode.valueOf(configuredMode.toUpperCase());
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean supports(String type) {
        return true; // MockProvider supports everything — it's the last-resort fallback
    }
}