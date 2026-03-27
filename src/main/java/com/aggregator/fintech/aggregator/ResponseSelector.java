package com.aggregator.fintech.aggregator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Selects the best response from a list of provider results.
 *
 * Selection priority (in order):
 *   1. Successful responses only (failures are discarded)
 *   2. Prefer non-fallback sources (real data > hardcoded data)
 *   3. Among equal-quality responses, prefer the fastest provider
 *
 * This is what makes the system an aggregator rather than a fallback chain.
 * In a fallback chain you take the first success. Here you collect all
 * successes and apply a quality ranking — a slow real price beats a fast
 * mock price every time.
 */
@Slf4j
@Component
public class ResponseSelector {

    public Optional<ProviderResult> selectBest(List<ProviderResult> results) {
        List<ProviderResult> successes = results.stream()
                .filter(ProviderResult::isSuccess)
                .filter(r -> r.getResponse() != null)
                .filter(r -> r.getResponse().getPrice() != null
                        && r.getResponse().getPrice() > 0)
                .toList();

        if (successes.isEmpty()) {
            log.warn("[ResponseSelector] No successful results from any provider");
            return Optional.empty();
        }

        // Score: fallbackUsed=false scores 0 (better), true scores 1 (worse).
        // Within the same score, lower durationMs wins.
        ProviderResult best = successes.stream()
                .min(Comparator
                        .comparingInt((ProviderResult r) ->
                                r.getResponse().isFallbackUsed() ? 1 : 0)
                        .thenComparingLong(ProviderResult::getDurationMs))
                .orElseThrow();

        log.info("[ResponseSelector] Selected provider: {} (fallbackUsed={}, durationMs={})",
                best.getProviderName(),
                best.getResponse().isFallbackUsed(),
                best.getDurationMs());

        // Log what was not selected so the decision is auditable
        successes.stream()
                .filter(r -> r != best)
                .forEach(r -> log.debug(
                        "[ResponseSelector] Discarded provider: {} (fallbackUsed={}, durationMs={})",
                        r.getProviderName(),
                        r.getResponse().isFallbackUsed(),
                        r.getDurationMs()));

        return Optional.of(best);
    }
}