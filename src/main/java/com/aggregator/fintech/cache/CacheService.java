package com.aggregator.fintech.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aggregator.fintech.model.PriceResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
public class CacheService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${cache.ttl-seconds}")
    private long ttlSeconds;

    @Value("${cache.key-prefix}")
    private String keyPrefix;

    public CacheService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public String buildKey(String type, String symbol) {
        return keyPrefix + ":" + type.toLowerCase() + ":" + symbol.toUpperCase();
    }

    public PriceResponse get(String key) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                log.debug("[Cache] MISS for key: {}", key);
                return null;
            }
            PriceResponse response = objectMapper.readValue(json, PriceResponse.class);
            log.debug("[Cache] HIT for key: {} price={}", key, response.getPrice());
            return response;
        } catch (Exception e) {
            log.warn("[Cache] Deserialization failed for key: {} treating as miss. Error: {}", key, e.getMessage());
            return null;
        }
    }

    public void set(String key, PriceResponse response) {
        if (response.isFallbackUsed()) {
            log.debug("[Cache] Skipping cache write for key: {} fallbackUsed=true", key);
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(key, json, Duration.ofSeconds(ttlSeconds));
            log.debug("[Cache] SET key: {} (TTL={}s)", key, ttlSeconds);
        } catch (Exception e) {
            log.warn("[Cache] Failed to write key: {} Error: {}", key, e.getMessage());
        }
    }

    public void evict(String key) {
        redisTemplate.delete(key);
        log.info("[Cache] Evicted key: {}", key);
    }
}