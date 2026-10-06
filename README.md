# Flash Sale Demo

Demo cho bài test đánh giá năng lực ứng viên BE (Bài toán 2): bán **100 sản phẩm** cho khoảng **100.000 người** bấm "Mua ngay" gần như cùng lúc.

Dự án giải quyết hai câu hỏi của đề:

1. **Chống over-selling:** không bao giờ bán quá 100 sản phẩm dù có hàng chục nghìn request ghi cùng lúc.
2. **Phân biệt người thật và bot:** chặn script gửi request trong vài mili-giây đầu để gom hàng, vẫn công bằng cho khách thật.

## Công nghệ

| Thành phần | Công nghệ |
| --- | --- |
| Ngôn ngữ / Framework | Java 17, Spring Boot 3.3.5, Maven |
| Trừ kho nguyên tử | Redis + Lua script |
| Lưu trữ | MySQL 8, quản lý schema bằng Flyway |
| Xử lý bất đồng bộ | Queue + Order Worker (queue trong bộ nhớ, thay được bằng Kafka/RabbitMQ qua interface `OrderQueue`) |
| Chống bot | Challenge token ký HMAC-SHA256, rate limit, Redis SETNX |
| Test | JUnit 5, MockMvc, Awaitility |
| Hạ tầng chạy thử | Docker Compose (Redis + MySQL) |

## Kiến trúc và luồng xử lý

```text
Client
  -> BotGuardInterceptor (token, rate limit)
  -> FlashSaleController
  -> FlashSaleService -> Redis (Lua nguyên tử: kiểm tra + trừ kho)
  -> OrderQueue
  -> OrderWorker -> MySQL (UPDATE ... WHERE stock > 0, tạo đơn PENDING_PAYMENT)
  -> OrderExpiryJob (hủy đơn quá hạn thanh toán, hoàn kho)
```

Luồng mua hàng:

1. Client lấy token ở `GET /api/flash-sale/challenge`, rồi gửi `POST /api/flash-sale/{sku}/buy` kèm token.
2. `BotGuardInterceptor` kiểm tra token và rate limit. Request không hợp lệ bị loại trước khi chạm Redis.
3. Nếu service đã biết hết hàng (cờ cục bộ), trả `409` ngay, không gọi Redis.
4. Lua script chạy nguyên tử trên Redis: kiểm tra user đã mua chưa, kiểm tra `stock > 0`, trừ kho và ghi nhận người mua trong một bước.
5. Thành công: tạo `orderId`, đẩy vào queue, trả `202 Accepted`.
6. `OrderWorker` ghi database bất đồng bộ, trừ kho DB bằng câu lệnh có điều kiện `stock > 0` và tạo đơn `PENDING_PAYMENT`. Đây là chốt chặn cuối nếu Redis lệch dữ liệu.
7. Đơn không thanh toán trong thời hạn cấu hình (mặc định 10 phút) bị hủy, kho được hoàn lại cho Redis và DB.

Các lớp bảo vệ chống over-selling:

| Lớp | Cơ chế |
| --- | --- |
| 1 | Cờ sold-out cục bộ trong bộ nhớ, không gọi Redis |
| 2 | Redis Lua script nguyên tử, chống race condition |
| 3 | DB conditional update `WHERE stock > 0` |
| 4 | `UNIQUE (user_id, sku_id)`, mỗi người một sản phẩm và chống ghi trùng |

Các lớp chống bot:

| Kiểm tra | Kết quả khi vi phạm |
| --- | --- |
| Thiếu token | `403 MISSING_CHALLENGE` |
| Chữ ký sai hoặc sai user | `403 INVALID` |
| Gửi nhanh hơn 200 ms sau khi cấp token | `403 TOO_FAST` |
| Token quá 120 giây | `403 EXPIRED` |
| Dùng lại token | `403 REPLAYED` |
| Quá 5 request/giây theo user | `429 RATE_LIMITED` |

Đây là một lớp bảo vệ ở tầng ứng dụng. Hệ thống thật cần thêm: WAF (Web Application Firewall), nhận diện TLS fingerprint (JA4), CAPTCHA ẩn, phòng chờ ảo (virtual waiting room), và giới hạn 1 sản phẩm mỗi người đã có sẵn ở lớp Redis (`bought:{sku}` set) và DB (`UNIQUE(user_id, sku_id)`).

## Yêu cầu cài đặt

- JDK 17 trở lên
- Maven 3.9 trở lên
- Docker Desktop (để chạy Redis và MySQL)
- Windows PowerShell (các lệnh bên dưới viết cho PowerShell)

## Cách chạy

### 1. Clone dự án

```powershell
git clone https://github.com/Tr-Duy/Backend-Technical-Assessment-Seat-Hoarding-Flash-Sale
cd flash-sale-demo
```

### 2. Khởi động Redis và MySQL

```powershell
docker compose up -d
docker compose ps
```

Chờ đến khi cả hai container ở trạng thái `running` hoặc `healthy`.

### 3. (Tùy chọn) Đặt biến môi trường

```powershell
$env:CHALLENGE_SECRET = "doi-thanh-chuoi-bi-mat-cua-ban"
$env:DB_USER = "flashsale"
$env:DB_PASS = "flashsale"
```

### 4. Chạy test

```powershell
mvn clean verify
```

Các test chính (cần Redis và MySQL đang chạy):

| Test | Kiểm chứng |
| --- | --- |
| `FlashSaleConcurrencyTest` | 1.000 user mua cùng lúc, đúng 100 thành công, 900 hết hàng, DB còn `stock = 0` và có đúng 100 đơn |
| `BotGuardTest` | Không token, token quá nhanh, người thật qua, dùng lại token, vượt rate limit |
| `OrderExpiryTest` | Đơn quá hạn bị hủy và kho được hoàn |

### 5. Chạy ứng dụng

```powershell
mvn spring-boot:run
```

Ứng dụng chạy tại `http://localhost:8080`.

## Demo bằng PowerShell

Mở một cửa sổ PowerShell khác trong khi ứng dụng đang chạy.

```powershell
$base = "http://localhost:8080/api/flash-sale"

# 1. Khởi tạo kho 100 sản phẩm
Invoke-RestMethod -Method Post "$base/PHONE-X/init?qty=100"

# 2. Lấy token chống bot cho user 1
$token = (Invoke-RestMethod "$base/challenge" -Headers @{ "X-User-Id" = "1" }).token

# 3. Chờ như người thật (token gửi quá nhanh sẽ bị chặn)
Start-Sleep -Milliseconds 300

# 4. Mua hàng
Invoke-RestMethod -Method Post "$base/PHONE-X/buy" -Headers @{ "X-User-Id" = "1"; "X-Challenge-Token" = $token }

# 5. Xem tồn kho (Redis và DB)
Invoke-RestMethod "$base/PHONE-X/stock"
```

Các tình huống chặn bot để quan sát:

```powershell
# Gửi ngay sau khi lấy token: bị 403 TOO_FAST
$t = (Invoke-RestMethod "$base/challenge" -Headers @{ "X-User-Id" = "2" }).token
Invoke-RestMethod -Method Post "$base/PHONE-X/buy" -Headers @{ "X-User-Id" = "2"; "X-Challenge-Token" = $t }

# Dùng lại token đã dùng: bị 403 REPLAYED
Invoke-RestMethod -Method Post "$base/PHONE-X/buy" -Headers @{ "X-User-Id" = "1"; "X-Challenge-Token" = $token }

# Không có token: bị 403
Invoke-RestMethod -Method Post "$base/PHONE-X/buy" -Headers @{ "X-User-Id" = "3" }
```

Thanh toán và xem đơn (thay `<orderId>` bằng `orderId` nhận được ở bước mua):

```powershell
Invoke-RestMethod -Method Post "http://localhost:8080/api/orders/<orderId>/pay"
Invoke-RestMethod "http://localhost:8080/api/orders/<orderId>"
```

Chạy toàn bộ kịch bản demo tự động:

```powershell
.\scripts\demo.ps1
```

Ngoài ra có thể import `postman/FlashSale.postman_collection.json` vào Postman để chạy các request theo đúng thứ tự demo.

## API

| Method | Endpoint | Mô tả |
| --- | --- | --- |
| POST | `/api/flash-sale/{sku}/init?qty=` | Khởi tạo kho trên Redis từ DB (`qty >= 1`) |
| GET | `/api/flash-sale/{sku}/stock` | Xem tồn kho Redis và DB |
| GET | `/api/flash-sale/challenge` | Cấp challenge token (header `X-User-Id`) |
| POST | `/api/flash-sale/{sku}/buy` | Mua hàng (header `X-User-Id`, `X-Challenge-Token`) |
| POST | `/api/orders/{orderId}/pay` | Thanh toán đơn |
| GET | `/api/orders/{orderId}` | Xem đơn hàng |

Mã trạng thái của `buy`:

| Mã | Ý nghĩa |
| --- | --- |
| 202 | Giữ hàng thành công, kèm `orderId`, chờ thanh toán |
| 409 | Hết hàng hoặc user đã mua |
| 425 | Sự kiện chưa mở bán (kho chưa khởi tạo) |
| 403 | Bị chặn bởi bot guard |
| 429 | Vượt giới hạn request |

`X-User-Id` trong demo thay cho user lấy từ JWT. Lỗi trả về dạng JSON thống nhất (`ApiError`).

## Cấu trúc thư mục

```text
flash-sale-demo/
├── pom.xml
├── docker-compose.yml
├── README.md
├── .gitignore
├── scripts/
│   └── demo.ps1
├── postman/
│   └── FlashSale.postman_collection.json
└── src/
    ├── main/
    │   ├── java/com/hoangha/flashsale/
    │   │   ├── FlashSaleApplication.java
    │   │   ├── config/            FlashSaleProperties, WebConfig, SchedulingConfig
    │   │   ├── controller/        FlashSaleController, ChallengeController, OrderController
    │   │   ├── service/           FlashSaleService, ChallengeService, OrderService
    │   │   ├── guard/             BotGuardInterceptor
    │   │   ├── queue/             OrderQueue, InMemoryOrderQueue
    │   │   ├── worker/            OrderWorker, OrderExpiryJob
    │   │   ├── dto/               BuyResponse, OrderMessage, StockResponse, OrderResponse, ApiError
    │   │   └── exception/         GlobalExceptionHandler
    │   └── resources/
    │       ├── application.yml
    │       ├── scripts/buy_stock.lua
    │       └── db/migration/      V1__create_tables.sql, V2__seed_inventory.sql
    └── test/java/com/hoangha/flashsale/
        FlashSaleConcurrencyTest, BotGuardTest, OrderExpiryTest
```

## Xử lý sự cố thường gặp

| Hiện tượng | Cách xử lý |
| --- | --- |
| `Unable to connect to Redis` | Chạy `docker compose up -d`, kiểm tra port 6379 không bị chiếm |
| `Communications link failure` (MySQL) | Chờ MySQL khởi động xong, kiểm tra port 3306, kiểm tra thông tin kết nối trong `application.yml` |
| Cổng 8080 đang bị dùng | Đổi `server.port` trong `application.yml` |
| Test lần 2 báo sai số lượng | Gọi lại `init` hoặc chạy `docker compose down -v` rồi `docker compose up -d` để làm sạch dữ liệu |
| Không dùng Docker | Cài Redis cho Windows (Memurai) và MySQL riêng, rồi sửa host/port trong `application.yml` |

Dừng và dọn môi trường:

```powershell
docker compose down -v
```

## Hạn chế của bản demo

- Queue nằm trong bộ nhớ nên mất message khi khởi động lại; production dùng Kafka hoặc RabbitMQ.
- Ngưỡng chống bot (200 ms, 5 request/giây) chỉ là giá trị minh họa, cần chỉnh theo dữ liệu thực tế.
- Chống bot trong demo là một lớp ở tầng ứng dụng; hệ thống thật cần thêm WAF, nhận diện TLS fingerprint (JA4), CAPTCHA ẩn và phòng chờ ảo.

## Tác giả

- Họ tên: Đoàn Trường Duy
- Email: doantruongduy8@gmail.com
- Bài test: Công ty TNHH Giải pháp số Hoàng Hà, vị trí Backend Developer
