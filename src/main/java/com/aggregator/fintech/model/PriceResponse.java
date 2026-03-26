package com.aggregator.fintech.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonDeserialize(builder = PriceResponse.PriceResponseBuilder.class)
public class PriceResponse {

    private String symbol;
    private Double price;
    private String currency;
    private String type;          // "crypto" or "stock"
    private String source;        // which provider answered
    private boolean fallbackUsed;
    private boolean cachedResponse;
    private Instant timestamp;

    @JsonPOJOBuilder(withPrefix = "")   // ← add this as a nested annotation target
    public static class PriceResponseBuilder {
        // Lombok fills this in — leave it empty
    }
}