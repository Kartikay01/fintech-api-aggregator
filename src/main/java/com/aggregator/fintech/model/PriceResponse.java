package com.aggregator.fintech.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PriceResponse {

    private String symbol;
    private Double price;
    private String currency;
    private String type;          // "crypto" or "stock"
    private String source;        // which provider answered
    private boolean fallbackUsed;
    private boolean cachedResponse;
    private Instant timestamp;
}