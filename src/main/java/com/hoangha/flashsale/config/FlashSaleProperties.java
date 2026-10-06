package com.hoangha.flashsale.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "flash-sale")
public record FlashSaleProperties(
        Challenge challenge,
        RateLimit rateLimit,
        Order order,
        Worker worker
) {
    public record Challenge(
            String secret,
            long minHumanMs,
            long maxAgeMs
    ) {}

    public record RateLimit(
            int maxPerSecond
    ) {}

    public record Order(
            int payTimeoutMinutes,
            long expiryJobIntervalMs
    ) {}

    public record Worker(
            int threads
    ) {}
}
