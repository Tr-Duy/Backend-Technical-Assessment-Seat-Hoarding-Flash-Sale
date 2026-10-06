package com.hoangha.flashsale.controller;

import com.hoangha.flashsale.dto.BuyResponse;
import com.hoangha.flashsale.dto.StockResponse;
import com.hoangha.flashsale.service.FlashSaleService;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/flash-sale")
@RequiredArgsConstructor
@Validated
public class FlashSaleController {

    private final FlashSaleService flashSaleService;

    @PostMapping("/{sku}/init")
    public ResponseEntity<Void> init(
            @PathVariable String sku,
            @RequestParam @Min(1) int qty) {
        flashSaleService.initStock(sku, qty);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{sku}/stock")
    public ResponseEntity<StockResponse> stock(@PathVariable String sku) {
        Long redisStock = flashSaleService.getRedisStock(sku);
        Integer dbStock = flashSaleService.getDbStock(sku);
        return ResponseEntity.ok(new StockResponse(redisStock, dbStock));
    }

    @PostMapping("/{sku}/buy")
    public ResponseEntity<BuyResponse> buy(
            @PathVariable String sku,
            @RequestHeader("X-User-Id") long userId) {

        BuyResponse resp = flashSaleService.buy(sku, userId);
        return switch (resp.result()) {
            case "SUCCESS" -> ResponseEntity.status(HttpStatus.ACCEPTED).body(resp);
            case "SOLD_OUT", "ALREADY_BOUGHT" -> ResponseEntity.status(HttpStatus.CONFLICT).body(resp);
            case "NOT_STARTED" -> ResponseEntity.status(425).body(resp);
            default -> ResponseEntity.status(HttpStatus.CONFLICT).body(resp);
        };
    }
}
