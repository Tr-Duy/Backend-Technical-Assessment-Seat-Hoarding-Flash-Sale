package com.hoangha.flashsale;

import com.hoangha.flashsale.dto.BuyResponse;
import com.hoangha.flashsale.service.FlashSaleService;
import com.hoangha.flashsale.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OrderExpiryTest {

    @Autowired FlashSaleService flashSaleService;
    @Autowired OrderService orderService;
    @Autowired JdbcTemplate jdbc;

    @Test
    void expiredOrder_cancelledAndStockRestored() {
        String sku = "SKU-" + UUID.randomUUID();
        flashSaleService.initStock(sku, 1);

        // User 1 mua thành công
        BuyResponse resp = flashSaleService.buy(sku, 1L);
        assertThat(resp.result()).isEqualTo("SUCCESS");

        // Giả lập worker ghi DB thủ công
        jdbc.update("""
                INSERT INTO orders (order_id, sku_id, user_id, status, created_at)
                VALUES (?, ?, 1, 'PENDING_PAYMENT', NOW())
                """, resp.orderId(), sku);
        jdbc.update("UPDATE inventory SET stock = stock - 1 WHERE sku_id = ?", sku);

        // Kiểm tra trước khi hủy
        assertThat(flashSaleService.getDbStock(sku)).isEqualTo(0);
        assertThat(flashSaleService.getRedisStock(sku)).isEqualTo(0L);

        // Chạy logic hủy với cutoff = tương lai (tất cả đơn đều quá hạn)
        int cancelled = orderService.cancelExpiredOrders(LocalDateTime.now().plusMinutes(1));
        assertThat(cancelled).isGreaterThanOrEqualTo(1);

        // Kiểm tra đơn đã bị hủy
        String status = jdbc.queryForObject(
                "SELECT status FROM orders WHERE order_id = ?", String.class, resp.orderId());
        assertThat(status).isEqualTo("CANCELLED");

        // Kho DB và Redis được hoàn lại
        assertThat(flashSaleService.getDbStock(sku)).isEqualTo(1);
        assertThat(flashSaleService.getRedisStock(sku)).isEqualTo(1L);

        // User 1 vẫn không mua lại được (vẫn trong bought set)
        BuyResponse retry = flashSaleService.buy(sku, 1L);
        assertThat(retry.result()).isEqualTo("ALREADY_BOUGHT");
    }
}
