package com.hoangha.flashsale.guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hoangha.flashsale.config.FlashSaleProperties;
import com.hoangha.flashsale.dto.ApiError;
import com.hoangha.flashsale.service.ChallengeService;
import com.hoangha.flashsale.service.ChallengeService.Verdict;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class BotGuardInterceptor implements HandlerInterceptor {

    private final ChallengeService challengeService;
    private final FlashSaleProperties props;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    private static final String RATE_PREFIX = "rate:";

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response, Object handler) throws Exception {

        String userIdHeader = request.getHeader("X-User-Id");
        String token = request.getHeader("X-Challenge-Token");

        // Thiếu header bắt buộc
        if (userIdHeader == null || token == null) {
            return reject(response, 403, "MISSING_CHALLENGE", "Missing X-User-Id or X-Challenge-Token", request);
        }

        // X-User-Id phải là số
        long userId;
        try {
            userId = Long.parseLong(userIdHeader);
        } catch (NumberFormatException e) {
            return reject(response, 400, "INVALID_USER_ID", "X-User-Id must be a number", request);
        }

        // Rate limit: tối đa maxPerSecond request/giây theo user
        String rateKey = RATE_PREFIX + userId;
        Long count = redis.opsForValue().increment(rateKey);
        if (count != null && count == 1) {
            redis.expire(rateKey, 1, TimeUnit.SECONDS);
        }
        if (count != null && count > props.rateLimit().maxPerSecond()) {
            return reject(response, 429, "RATE_LIMITED", "Too many requests", request);
        }

        // Xác minh challenge token
        Verdict verdict = challengeService.verify(userId, token, System.currentTimeMillis());
        if (verdict != Verdict.OK) {
            return reject(response, 403, verdict.name(), "Challenge token " + verdict.name().toLowerCase(), request);
        }

        return true;
    }

    private boolean reject(HttpServletResponse response, int status,
                           String error, String message, HttpServletRequest request) throws Exception {
        ApiError apiError = new ApiError(Instant.now(), status, error, message, request.getRequestURI());
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(apiError));
        return false;
    }
}
