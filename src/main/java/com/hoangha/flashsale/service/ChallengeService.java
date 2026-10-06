package com.hoangha.flashsale.service;

import com.hoangha.flashsale.config.FlashSaleProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChallengeService {

    private final FlashSaleProperties props;
    private final StringRedisTemplate redis;

    private static final String USED_PREFIX = "challenge:used:";

    public String issue(long userId) {
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String payload = userId + ":" + System.currentTimeMillis() + ":" + nonce;
        String payloadB64 = base64url(payload.getBytes());
        String sig = base64url(hmac(payload));
        return payloadB64 + "." + sig;
    }

    public enum Verdict { OK, INVALID, TOO_FAST, EXPIRED, REPLAYED }

    public Verdict verify(long userId, String token, long nowMs) {
        // Kiểm tra định dạng token
        if (token == null) return Verdict.INVALID;
        String[] parts = token.split("\\.");
        if (parts.length != 2) return Verdict.INVALID;

        String payloadDecoded;
        try {
            payloadDecoded = new String(Base64.getUrlDecoder().decode(parts[0]));
        } catch (IllegalArgumentException e) {
            return Verdict.INVALID;
        }

        // Kiểm tra chữ ký bằng so sánh hằng thời gian (chống timing attack)
        String expectedSig = base64url(hmac(payloadDecoded));
        if (!MessageDigest.isEqual(expectedSig.getBytes(), parts[1].getBytes())) {
            return Verdict.INVALID;
        }

        // Phân tích payload: userId:issuedAt:nonce
        String[] fields = payloadDecoded.split(":");
        if (fields.length != 3) return Verdict.INVALID;

        long tokenUserId;
        long issuedAt;
        try {
            tokenUserId = Long.parseLong(fields[0]);
            issuedAt = Long.parseLong(fields[1]);
        } catch (NumberFormatException e) {
            return Verdict.INVALID;
        }

        // Kiểm tra đúng user
        if (tokenUserId != userId) return Verdict.INVALID;

        String nonce = fields[2];
        long age = nowMs - issuedAt;

        // Gửi quá nhanh: bot gửi ngay sau khi lấy token
        if (age < props.challenge().minHumanMs()) return Verdict.TOO_FAST;

        // Token quá cũ
        if (age > props.challenge().maxAgeMs()) return Verdict.EXPIRED;

        // Kiểm tra nonce đã dùng chưa (chống replay attack)
        String key = USED_PREFIX + nonce;
        Boolean isNew = redis.opsForValue().setIfAbsent(key, "1",
                props.challenge().maxAgeMs(), TimeUnit.MILLISECONDS);
        if (Boolean.FALSE.equals(isNew)) return Verdict.REPLAYED;

        return Verdict.OK;
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.challenge().secret().getBytes(), "HmacSHA256"));
            return mac.doFinal(data.getBytes());
        } catch (Exception e) {
            throw new IllegalStateException("HMAC error", e);
        }
    }

    private String base64url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }
}
