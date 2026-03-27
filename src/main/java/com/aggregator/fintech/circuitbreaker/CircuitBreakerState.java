package com.aggregator.fintech.circuitbreaker;

import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-provider circuit breaker — a 3-state machine:
 *
 *   CLOSED (normal) ──[threshold failures]──> OPEN (blocking)
 *   OPEN             ──[recovery timeout]───> HALF_OPEN (probing)
 *   HALF_OPEN        ──[probe succeeds]────> CLOSED
 *   HALF_OPEN        ──[probe fails]────────> OPEN (reset timeout)
 *
 * All mutable state is atomic — safe for concurrent provider calls (Day 5).
 */
@Slf4j
public class CircuitBreakerState {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final String providerName;
    private final int failureThreshold;        // consecutive failures to trip
    private final long recoveryTimeoutSeconds; // how long to stay OPEN before probing

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicReference<Instant> openedAt = new AtomicReference<>(null);

    // Tracks whether the single HALF_OPEN probe has been dispatched.
    // Prevents multiple concurrent requests all acting as probes simultaneously.
    private final AtomicReference<Boolean> probeDispatched = new AtomicReference<>(false);

    public CircuitBreakerState(String providerName, int failureThreshold, long recoveryTimeoutSeconds) {
        this.providerName = providerName;
        this.failureThreshold = failureThreshold;
        this.recoveryTimeoutSeconds = recoveryTimeoutSeconds;
    }

    /**
     * Returns true if this provider is allowed to handle the current request.
     * CLOSED   -> always allowed.
     * OPEN     -> check if recovery timeout has elapsed; if yes, transition to HALF_OPEN.
     * HALF_OPEN-> only one probe allowed at a time.
     */
    public boolean allowRequest() {
        State current = state.get();

        switch (current) {
            case CLOSED -> { return true; }

            case OPEN -> {
                Instant opened = openedAt.get();
                if (opened != null &&
                        Instant.now().isAfter(opened.plusSeconds(recoveryTimeoutSeconds))) {
                    // Recovery timeout elapsed — transition to HALF_OPEN for a probe
                    if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                        probeDispatched.set(false);
                        log.info("[CircuitBreaker][{}] OPEN -> HALF_OPEN (recovery timeout elapsed)",
                                providerName);
                    }
                    return tryDispatchProbe();
                }
                log.debug("[CircuitBreaker][{}] OPEN — blocking request", providerName);
                return false;
            }

            case HALF_OPEN -> { return tryDispatchProbe(); }

            default -> { return false; }
        }
    }

    /**
     * Only one probe at a time in HALF_OPEN.
     * CAS on probeDispatched ensures exactly one thread gets through.
     */
    private boolean tryDispatchProbe() {
        boolean dispatched = probeDispatched.compareAndSet(false, true);
        if (dispatched) {
            log.info("[CircuitBreaker][{}] HALF_OPEN — probe dispatched", providerName);
        } else {
            log.debug("[CircuitBreaker][{}] HALF_OPEN — probe already in-flight, blocking", providerName);
        }
        return dispatched;
    }

    /**
     * Called by AggregatorService after a successful provider call.
     * Resets failure count. If in HALF_OPEN, closes the breaker.
     */
    public void recordSuccess() {
        consecutiveFailures.set(0);
        State current = state.get();
        if (current == State.HALF_OPEN) {
            state.set(State.CLOSED);
            openedAt.set(null);
            probeDispatched.set(false);
            log.info("[CircuitBreaker][{}] HALF_OPEN -> CLOSED (probe succeeded)", providerName);
        }
    }

    /**
     * Called by AggregatorService after a failed provider call.
     * In CLOSED: increments failures, trips to OPEN at threshold.
     * In HALF_OPEN: probe failed — reopen and reset timeout.
     */
    public void recordFailure() {
        State current = state.get();

        if (current == State.HALF_OPEN) {
            // Probe failed — go back to OPEN and restart the recovery clock
            state.set(State.OPEN);
            openedAt.set(Instant.now());
            probeDispatched.set(false);
            log.warn("[CircuitBreaker][{}] HALF_OPEN -> OPEN (probe failed)", providerName);
            return;
        }

        int failures = consecutiveFailures.incrementAndGet();
        log.debug("[CircuitBreaker][{}] Failure recorded ({}/{})", providerName, failures, failureThreshold);

        if (failures >= failureThreshold && state.compareAndSet(State.CLOSED, State.OPEN)) {
            openedAt.set(Instant.now());
            log.warn("[CircuitBreaker][{}] CLOSED -> OPEN ({} consecutive failures)",
                    providerName, failures);
        }
    }

    // ── Getters for /health endpoint (Day 6) ──────────────────────────────────

    public State getState() { return state.get(); }

    public int getConsecutiveFailures() { return consecutiveFailures.get(); }

    public Instant getOpenedAt() { return openedAt.get(); }

    public String getProviderName() { return providerName; }
}