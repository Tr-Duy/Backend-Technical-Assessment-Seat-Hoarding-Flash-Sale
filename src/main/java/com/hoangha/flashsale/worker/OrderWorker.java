package com.hoangha.flashsale.worker;

import com.hoangha.flashsale.config.FlashSaleProperties;
import com.hoangha.flashsale.dto.OrderMessage;
import com.hoangha.flashsale.queue.OrderQueue;
import com.hoangha.flashsale.service.FlashSaleService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderWorker {

    private final OrderQueue orderQueue;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final FlashSaleService flashSaleService;
    private final FlashSaleProperties props;

    private ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @PostConstruct
    public void start() {
        running.set(true);
        executor = Executors.newFixedThreadPool(props.worker().threads());
        for (int i = 0; i < props.worker().threads(); i++) {
            executor.submit(this::processLoop);
        }
        log.info("OrderWorker khởi động với {} thread", props.worker().threads());
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("OrderWorker đã dừng");
    }

    private void processLoop() {
        while (running.get()) {
            try {
                OrderMessage msg = orderQueue.poll(500);
                if (msg != null) {
                    process(msg);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void process(OrderMessage msg) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                // Lớp chốt chặn cuối: trừ kho DB có điều kiện stock > 0
                int rows = jdbc.update("""
                        UPDATE inventory SET stock = stock - 1
                        WHERE sku_id = ? AND stock > 0
                        """, msg.sku());

                if (rows == 0) {
                    // DB không còn hàng, hoàn kho Redis
                    status.setRollbackOnly();
                    flashSaleService.releaseAfterFailure(msg.sku(), msg.userId());
                    log.warn("DB hết hàng cho sku={}, hoàn kho Redis user={}", msg.sku(), msg.userId());
                    return;
                }

                // Tạo đơn PENDING_PAYMENT
                jdbc.update("""
                        INSERT INTO orders (order_id, sku_id, user_id, status, created_at)
                        VALUES (?, ?, ?, 'PENDING_PAYMENT', NOW())
                        """, msg.orderId(), msg.sku(), msg.userId());
            });
        } catch (DuplicateKeyException e) {
            // Idempotent: đơn đã tồn tại (user_id, sku_id) unique, bỏ qua
            log.info("Đơn trùng bỏ qua: orderId={} user={} sku={}", msg.orderId(), msg.userId(), msg.sku());
        } catch (Exception e) {
            log.error("Lỗi xử lý đơn orderId={}: {}", msg.orderId(), e.getMessage(), e);
        }
    }
}
