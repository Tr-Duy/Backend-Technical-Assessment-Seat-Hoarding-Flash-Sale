package com.hoangha.flashsale.controller;

import com.hoangha.flashsale.dto.OrderResponse;
import com.hoangha.flashsale.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping("/{orderId}/pay")
    public ResponseEntity<Void> pay(@PathVariable String orderId) {
        orderService.pay(orderId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> get(@PathVariable String orderId) {
        return ResponseEntity.ok(orderService.get(orderId));
    }
}
