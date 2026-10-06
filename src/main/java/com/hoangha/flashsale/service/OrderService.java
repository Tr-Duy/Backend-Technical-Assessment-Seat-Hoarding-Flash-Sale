package com.hoangha.flashsale.service;

import com.hoangha.flashsale.dto.OrderResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final JdbcTemplate jdbc;
    private final FlashSaleService flashSaleService;

    @Transactional
    public void pay(String orderId) {
        Map<String, Object> row = jdbc.queryForList(
                "SELECT status FROM orders WHERE order_id = ?", orderId)
                .stream().findFirst()
                .orElseThrow(() -> new NoSuchElementException("Order not found: " + orderId));

        String status = (String) row.get("status");
        if (!"PENDING_PAYMENT".equals(status)) {
            throw new IllegalStateException("Order " + orderId + " is not in PENDING_PAYMENT state");
        }

        jdbc.update("""
                UPDATE orders SET status = 'PAID', paid_at = NOW()
                WHERE order_id = ? AND status = 'PENDING_PAYMENT'
                """, orderId);
    }

    public OrderResponse get(String orderId) {
        return jdbc.queryForList(
                "SELECT * FROM orders WHERE order_id = ?", orderId)
                .stream().findFirst()
                .map(this::mapRow)
                .orElseThrow(() -> new NoSuchElementException("Order not found: " + orderId));
    }

    /**
     * Hủy đơn quá hạn và hoàn kho. Nhận tham số cutoff để test được dễ dàng.
     */
    @Transactional
    public int cancelExpiredOrders(LocalDateTime cutoff) {
        // Lấy danh sách đơn quá hạn
        List<Map<String, Object>> expired = jdbc.queryForList("""
                SELECT order_id, sku_id FROM orders
                WHERE status = 'PENDING_PAYMENT' AND created_at < ?
                """, cutoff);

        if (expired.isEmpty()) return 0;

        for (Map<String, Object> row : expired) {
            String orderId = (String) row.get("order_id");
            String sku = (String) row.get("sku_id");

            // Cập nhật trạng thái đơn
            int updated = jdbc.update("""
                    UPDATE orders SET status = 'CANCELLED'
                    WHERE order_id = ? AND status = 'PENDING_PAYMENT'
                    """, orderId);

            if (updated > 0) {
                // Cộng lại kho DB
                jdbc.update("UPDATE inventory SET stock = stock + 1 WHERE sku_id = ?", sku);
                // Cộng lại kho Redis (giữ user trong bought)
                flashSaleService.releaseAfterExpiry(sku);
                log.info("Đã hủy đơn {} (sku={}), hoàn kho", orderId, sku);
            }
        }
        return expired.size();
    }

    private OrderResponse mapRow(Map<String, Object> row) {
        return new OrderResponse(
                (String) row.get("order_id"),
                (String) row.get("sku_id"),
                ((Number) row.get("user_id")).longValue(),
                (String) row.get("status"),
                toLocalDateTime(row.get("created_at")),
                toLocalDateTime(row.get("paid_at"))
        );
    }

    private LocalDateTime toLocalDateTime(Object val) {
        if (val == null) return null;
        if (val instanceof LocalDateTime ldt) return ldt;
        if (val instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        return null;
    }
}
