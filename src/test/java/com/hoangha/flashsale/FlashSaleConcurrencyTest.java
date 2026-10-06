package com.hoangha.flashsale;

import com.hoangha.flashsale.dto.BuyResponse;
import com.hoangha.flashsale.service.FlashSaleService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class FlashSaleConcurrencyTest {

    @Autowired FlashSaleService flashSaleService;
    @Autowired JdbcTemplate jdbc;

    @Test
    void concurrentBuy_exactly100Success() throws InterruptedException {
        String sku = "SKU-" + UUID.randomUUID();
        flashSaleService.initStock(sku, 100);

        int totalUsers = 1000;
        ExecutorService pool = Executors.newFixedThreadPool(100);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalUsers);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger soldOutCount = new AtomicInteger();
        List<Long> successUsers = new CopyOnWriteArrayList<>();

        for (int i = 0; i < totalUsers; i++) {
            final long userId = i + 1;
            pool.submit(() -> {
                try {
                    startLatch.await(); // Chờ tín hiệu xuất phát cùng lúc
                    BuyResponse resp = flashSaleService.buy(sku, userId);
                    if ("SUCCESS".equals(resp.result())) {
                        successCount.incrementAndGet();
                        successUsers.add(userId);
                    } else if ("SOLD_OUT".equals(resp.result())) {
                        soldOutCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Xuất phát!
        doneLatch.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        // Kiểm tra kết quả Redis ngay lập tức
        assertThat(successCount.get()).isEqualTo(100);
        assertThat(soldOutCount.get()).isEqualTo(900);
        assertThat(successUsers).doesNotHaveDuplicates();

        // Chờ worker bất đồng bộ xử lý xong
        Awaitility.await()
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    Integer dbStock = flashSaleService.getDbStock(sku);
                    assertThat(dbStock).isEqualTo(0);
                });

        // Kiểm tra DB sau khi worker xong
        Long redisStock = flashSaleService.getRedisStock(sku);
        assertThat(redisStock).isEqualTo(0L);

        Integer orderCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE sku_id = ?", Integer.class, sku);
        assertThat(orderCount).isEqualTo(100);

        // Không có user trùng
        Integer distinctUsers = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT user_id) FROM orders WHERE sku_id = ?", Integer.class, sku);
        assertThat(distinctUsers).isEqualTo(100);
    }
}
