package com.hoangha.flashsale.dto;

import java.time.LocalDateTime;

public record OrderResponse(
        String orderId,
        String skuId,
        long userId,
        String status,
        LocalDateTime createdAt,
        LocalDateTime paidAt
) {}
