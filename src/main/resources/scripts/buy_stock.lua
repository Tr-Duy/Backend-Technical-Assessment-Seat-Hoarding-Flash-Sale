-- KEYS[1] = stock:{sku}, KEYS[2] = bought:{sku}, ARGV[1] = userId
-- Trả về: -2 chưa mở bán, -1 đã mua, 0 hết hàng, 1 thành công

-- Kiểm tra key stock có tồn tại không (chưa init thì chưa mở bán)
if redis.call('EXISTS', KEYS[1]) == 0 then
    return -2
end

-- Kiểm tra user đã mua chưa
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return -1
end

-- Kiểm tra còn hàng không
local stock = tonumber(redis.call('GET', KEYS[1]))
if stock <= 0 then
    return 0
end

-- Nguyên tử: trừ kho và ghi nhận người mua
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
return 1
