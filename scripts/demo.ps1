# demo.ps1 - Kịch bản demo Flash Sale
# Chạy: .\scripts\demo.ps1 (khi ứng dụng đang chạy tại localhost:8080)

$base = "http://localhost:8080/api/flash-sale"
$ErrorActionPreference = "SilentlyContinue"

function Invoke-Api {
    param($Method = "GET", $Url, $Headers = @{})
    try {
        $resp = Invoke-RestMethod -Method $Method -Uri $Url -Headers $Headers
        return $resp
    } catch {
        $statusCode = $_.Exception.Response.StatusCode.value__
        $body = $_.ErrorDetails.Message | ConvertFrom-Json -ErrorAction SilentlyContinue
        return @{ statusCode = $statusCode; error = $body.error; message = $body.message }
    }
}

Write-Host "`n=== 1. Khởi tạo kho 100 sản phẩm ===" -ForegroundColor Cyan
Invoke-RestMethod -Method Post "$base/PHONE-X/init?qty=100"
Write-Host "OK - Đã init kho PHONE-X = 100"

Write-Host "`n=== 2. Mua không có token -> 403 ===" -ForegroundColor Cyan
$r = Invoke-Api -Method Post -Url "$base/PHONE-X/buy" -Headers @{ "X-User-Id" = "1" }
Write-Host "Status: $($r.statusCode), Error: $($r.error)"

Write-Host "`n=== 3. Mua quá nhanh -> 403 TOO_FAST ===" -ForegroundColor Cyan
$t = (Invoke-RestMethod "$base/challenge" -Headers @{ "X-User-Id" = "2" }).token
$r = Invoke-Api -Method Post -Url "$base/PHONE-X/buy" -Headers @{ "X-User-Id" = "2"; "X-Challenge-Token" = $t }
Write-Host "Status: $($r.statusCode), Error: $($r.error)"

Write-Host "`n=== 4. Người thật mua thành công -> 202 ===" -ForegroundColor Cyan
$token1 = (Invoke-RestMethod "$base/challenge" -Headers @{ "X-User-Id" = "1" }).token
Start-Sleep -Milliseconds 300
$r = Invoke-Api -Method Post -Url "$base/PHONE-X/buy" -Headers @{ "X-User-Id" = "1"; "X-Challenge-Token" = $token1 }
$orderId = $r.orderId
Write-Host "Status: $($r.statusCode), OrderId: $orderId, Result: $($r.result)"

Write-Host "`n=== 5. Dùng lại token -> 403 REPLAYED ===" -ForegroundColor Cyan
$r = Invoke-Api -Method Post -Url "$base/PHONE-X/buy" -Headers @{ "X-User-Id" = "1"; "X-Challenge-Token" = $token1 }
Write-Host "Status: $($r.statusCode), Error: $($r.error)"

Write-Host "`n=== 6. Mô phỏng 110 user mua song song (kỳ vọng đúng 99 thành công còn lại) ===" -ForegroundColor Cyan
$successCount = 0
$jobs = @()
2..111 | ForEach-Object {
    $uid = $_
    $jobs += Start-Job -ScriptBlock {
        param($base, $uid)
        $ErrorActionPreference = "SilentlyContinue"
        try {
            $t = (Invoke-RestMethod "$base/challenge" -Headers @{ "X-User-Id" = $uid }).token
            Start-Sleep -Milliseconds 300
            $r = Invoke-RestMethod -Method Post "$base/PHONE-X/buy" `
                -Headers @{ "X-User-Id" = $uid; "X-Challenge-Token" = $t }
            return $r.result
        } catch {
            return "FAILED"
        }
    } -ArgumentList $base, $uid
}

$results = $jobs | Wait-Job | Receive-Job
$jobs | Remove-Job

$successCount = ($results | Where-Object { $_ -eq "SUCCESS" }).Count
$soldOutCount = ($results | Where-Object { $_ -eq "SOLD_OUT" }).Count
Write-Host "Thành công: $successCount, Hết hàng: $soldOutCount"

Write-Host "`n=== 7. Xem tồn kho ===" -ForegroundColor Cyan
$stock = Invoke-RestMethod "$base/PHONE-X/stock"
Write-Host "Redis stock: $($stock.redisStock), DB stock: $($stock.dbStock)"

if ($orderId) {
    Write-Host "`n=== 8. Thanh toán đơn $orderId ===" -ForegroundColor Cyan
    Invoke-RestMethod -Method Post "http://localhost:8080/api/orders/$orderId/pay"
    $order = Invoke-RestMethod "http://localhost:8080/api/orders/$orderId"
    Write-Host "Trạng thái đơn: $($order.status)"
}

Write-Host "`n=== Demo hoàn tất ===" -ForegroundColor Green
