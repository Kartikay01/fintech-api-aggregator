package com.aggregator.fintech.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RedisConfig {

    /**
     * StringRedisTemplate stores keys and values as plain strings.
     * We manually serialize PriceResponse to/from JSON using ObjectMapper.
     * This keeps Redis data human-readable — you can inspect it in redis-cli
     * with: GET price:crypto:BTC
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    /**
     * Shared ObjectMapper bean with Java 8 time support.
     * JavaTimeModule handles Instant serialization correctly.
     * Without it, Instant serializes as an array [seconds, nanos] instead of ISO string.
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}