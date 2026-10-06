package com.hoangha.flashsale.service;

import com.hoangha.flashsale.dto.BuyResponse;
import com.hoangha.flashsale.dto.OrderMessage;
import com.hoangha.flashsale.queue.OrderQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class FlashSaleService {

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final OrderQueue orderQueue;

    // Tập SKU đã hết hàng cục bộ, tránh gọi Redis không cần thiết
    private final Set<String> localSoldOut = ConcurrentHashMap.newKeySet();

    private static final DefaultRedisScript<Long> BUY_SCRIPT;

    static {
        BUY_SCRIPT = new DefaultRedisScript<>();
        BUY_SCRIPT.setScriptSource(new ResourceScriptSource(
                new ClassPathResource("scripts/buy_stock.lua")));
        BUY_SCRIPT.setResultType(Long.class);
    }

    public BuyResponse buy(String sku, long userId) {
        // Lớp 1: kiểm tra sold-out cục bộ, không cần gọi Redis
        if (localSoldOut.contains(sku)) {
            return new BuyResponse("SOLD_OUT", null);
        }

        // Lớp 2: Lua script nguyên tử trên Redis
        Long result = redis.execute(BUY_SCRIPT,
                List.of("stock:" + sku, "bought:" + sku),
                String.valueOf(userId));

        long r = result == null ? 0L : result;
        if (r == -2L) return new BuyResponse("NOT_STARTED", null);
        if (r == -1L) return new BuyResponse("ALREADY_BOUGHT", null);
        if (r == 1L) {
            String orderId = UUID.randomUUID().toString();
            orderQueue.publish(new OrderMessage(orderId, sku, userId));
            return new BuyResponse("SUCCESS", orderId);
        }
        // r == 0 hoặc giá trị khác: hết hàng
        localSoldOut.add(sku);
        return new BuyResponse("SOLD_OUT", null);
    }

    public void initStock(String sku, int qty) {
        // Upsert tồn kho DB
        jdbc.update("""
                INSERT INTO inventory (sku_id, stock) VALUES (?, ?)
                ON DUPLICATE KEY UPDATE stock = ?
                """, sku, qty, qty);

        // Xóa đơn hàng cũ của SKU này
        jdbc.update("DELETE FROM orders WHERE sku_id = ?", sku);

        // Đặt lại Redis
        redis.opsForValue().set("stock:" + sku, String.valueOf(qty));
        redis.delete("bought:" + sku);

        // Bỏ cờ sold-out cục bộ
        localSoldOut.remove(sku);
        log.info("Đã khởi tạo kho {} = {} sản phẩm", sku, qty);
    }

    public Long getRedisStock(String sku) {
        String val = redis.opsForValue().get("stock:" + sku);
        return val == null ? null : Long.parseLong(val);
    }

    public Integer getDbStock(String sku) {
        return jdbc.queryForObject(
                "SELECT stock FROM inventory WHERE sku_id = ?",
                Integer.class, sku);
    }

    /**
     * Hoàn kho khi worker thất bại (chưa tạo đơn): INCR stock + xóa user khỏi bought
     */
    public void releaseAfterFailure(String sku, long userId) {
        redis.opsForValue().increment("stock:" + sku);
        redis.opsForSet().remove("bought:" + sku, String.valueOf(userId));
        localSoldOut.remove(sku);
    }

    /**
     * Hoàn kho khi đơn hết hạn: chỉ INCR stock, GIỮ user trong bought
     * (mỗi người chỉ một lượt mua trong sự kiện)
     */
    public void releaseAfterExpiry(String sku) {
        redis.opsForValue().increment("stock:" + sku);
        localSoldOut.remove(sku);
    }
}
