package com.hoangha.flashsale.worker;

import com.hoangha.flashsale.config.FlashSaleProperties;
import com.hoangha.flashsale.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderExpiryJob {

    private final OrderService orderService;
    private final FlashSaleProperties props;

    @Scheduled(fixedDelayString = "${flash-sale.order.expiry-job-interval-ms}")
    public void run() {
        LocalDateTime cutoff = LocalDateTime.now()
                .minusMinutes(props.order().payTimeoutMinutes());
        int cancelled = orderService.cancelExpiredOrders(cutoff);
        if (cancelled > 0) {
            log.info("OrderExpiryJob: đã hủy {} đơn quá hạn", cancelled);
        }
    }
}
