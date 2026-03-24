package com.aggregator.fintech.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PriceRequest {

    private String symbol;   // e.g. "BTC", "AAPL"
    private String type;     // "crypto" or "stock"
}