package com.aggregator.fintech.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.LinkedBlockingQueue;

@Configuration
public class AsyncConfig {

    @Value("${async.provider-pool.core-size:5}")
    private int coreSize;

    @Value("${async.provider-pool.max-size:10}")
    private int maxSize;

    @Value("${async.provider-pool.queue-capacity:50}")
    private int queueCapacity;

    @Value("${async.provider-pool.timeout-seconds:5}")
    private int timeoutSeconds;

    /**
     * Dedicated thread pool for parallel provider fan-out.
     *
     * Sizing rationale:
     *   core=5  — one thread per provider with headroom
     *   max=10  — burst capacity for concurrent requests
     *   queue=50 — backpressure buffer before rejection
     *
     * We use a fixed named pool rather than ForkJoinPool.commonPool()
     * because provider calls are blocking I/O — they would stall
     * the common pool and degrade unrelated async operations in the JVM.
     */
    @Bean(name = "providerExecutor")
    public ExecutorService providerExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                coreSize,
                maxSize,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                r -> {
                    Thread t = new Thread(r);
                    t.setName("provider-pool-" + t.getId());
                    t.setDaemon(true);
                    return t;
                }
        );
        executor.allowCoreThreadTimeOut(false);
        return executor;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }
}