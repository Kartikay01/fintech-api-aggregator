package com.aggregator.fintech.deduplication;

import com.aggregator.fintech.model.PriceResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thundering herd protection for the aggregator.
 *
 * Problem: 500 concurrent requests for BTC arrive simultaneously.
 * Without deduplication, all 500 fan out to providers — 500x the load.
 * With deduplication, the first request starts a CompletableFuture,
 * and the remaining 499 attach to it and wait for the same result.
 *
 * Deduplication window = in-flight duration + ttlSeconds buffer.
 * This is intentionally longer than just "while in-flight" — it absorbs
 * request bursts that arrive slightly after the first response completes
 * but before the Redis cache has been populated and read.
 *
 * Key design: we store the future itself, not the result.
 * All waiters block on the same future — one provider call serves all.
 */
@Slf4j
@Service
public class DeduplicationService {

    @Value("${deduplication.ttl-seconds:2}")
    private long ttlSeconds;

    private record Entry(CompletableFuture<PriceResponse> future, Instant expiresAt) {}

    private final ConcurrentHashMap<String, Entry> inFlight = new ConcurrentHashMap<>();

    /**
     * Returns an existing in-flight future for this key if one exists and
     * has not yet expired. Otherwise registers the provided future as the
     * canonical future for this key and returns it.
     *
     * The caller should check whether the returned future is the same object
     * as the one it passed in — if it is, the caller owns the future and must
     * complete it. If it is a different object, another request is already
     * doing the work and the caller should just wait on the returned future.
     */
    public CompletableFuture<PriceResponse> getOrRegister(
            String key, CompletableFuture<PriceResponse> newFuture) {

        cleanup();

        Entry existing = inFlight.get(key);
        if (existing != null && !existing.future().isDone()
                && Instant.now().isBefore(existing.expiresAt())) {
            log.info("[Dedup] Key {} already in-flight — coalescing request", key);
            return existing.future();
        }

        Instant expiresAt = Instant.now().plusSeconds(ttlSeconds);
        inFlight.put(key, new Entry(newFuture, expiresAt));
        log.debug("[Dedup] Registered new future for key: {} (expires in {}s)", key, ttlSeconds);
        return newFuture;
    }

    /**
     * Remove the entry for this key once the future completes.
     * Called by AggregatorService after the fan-out resolves.
     */
    public void complete(String key) {
        inFlight.remove(key);
        log.debug("[Dedup] Completed and removed key: {}", key);
    }

    /**
     * Periodic cleanup of expired entries to prevent memory leak.
     * Called on every getOrRegister — cheap O(n) scan, n is always tiny.
     */
    private void cleanup() {
        Instant now = Instant.now();
        inFlight.entrySet().removeIf(e ->
                e.getValue().future().isDone() || now.isAfter(e.getValue().expiresAt()));
    }

    public int inFlightCount() {
        return inFlight.size();
    }
}