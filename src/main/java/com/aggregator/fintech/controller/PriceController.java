package com.aggregator.fintech.controller;

import com.aggregator.fintech.aggregator.AggregatorService;
import com.aggregator.fintech.model.PriceRequest;
import com.aggregator.fintech.model.PriceResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
public class PriceController {

    private final AggregatorService aggregatorService;

    /**
     * GET /price?symbol=BTC&type=crypto
     * GET /price?symbol=AAPL&type=stock
     */
    @GetMapping("/price")
    public ResponseEntity<PriceResponse> getPrice(
            @RequestParam String symbol,
            @RequestParam String type) {

        log.info("[Controller] GET /price?symbol={}&type={}", symbol, type);

        PriceResponse response = aggregatorService.getPrice(new PriceRequest(symbol, type));
        return ResponseEntity.ok(response);
    }
}