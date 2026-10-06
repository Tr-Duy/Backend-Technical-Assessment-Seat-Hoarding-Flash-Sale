package com.hoangha.flashsale;

import com.hoangha.flashsale.service.ChallengeService;
import com.hoangha.flashsale.service.FlashSaleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class BotGuardTest {

    @Autowired MockMvc mockMvc;
    @Autowired ChallengeService challengeService;
    @Autowired FlashSaleService flashSaleService;

    private String sku;

    @BeforeEach
    void setUp() {
        sku = "SKU-" + UUID.randomUUID();
        flashSaleService.initStock(sku, 100);
    }

    @Test
    void noToken_returns403() throws Exception {
        mockMvc.perform(post("/api/flash-sale/{sku}/buy", sku)
                        .header("X-User-Id", "100"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("MISSING_CHALLENGE"));
    }

    @Test
    void tooFast_returns403() throws Exception {
        String token = challengeService.issue(200L);
        // Gửi ngay không chờ, bị chặn TOO_FAST
        mockMvc.perform(post("/api/flash-sale/{sku}/buy", sku)
                        .header("X-User-Id", "200")
                        .header("X-Challenge-Token", token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("TOO_FAST"));
    }

    @Test
    void humanUser_returns202() throws Exception {
        String token = challengeService.issue(300L);
        Thread.sleep(300); // Chờ như người thật
        mockMvc.perform(post("/api/flash-sale/{sku}/buy", sku)
                        .header("X-User-Id", "300")
                        .header("X-Challenge-Token", token))
                .andExpect(status().isAccepted());
    }

    @Test
    void replayedToken_returns403() throws Exception {
        String token = challengeService.issue(400L);
        Thread.sleep(300);

        // Lần đầu thành công
        mockMvc.perform(post("/api/flash-sale/{sku}/buy", sku)
                        .header("X-User-Id", "400")
                        .header("X-Challenge-Token", token))
                .andExpect(status().isAccepted());

        // Dùng lại token bị chặn
        mockMvc.perform(post("/api/flash-sale/{sku}/buy", sku)
                        .header("X-User-Id", "400")
                        .header("X-Challenge-Token", token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("REPLAYED"));
    }

    @Test
    void invalidSignature_returns403() throws Exception {
        String token = "aW52YWxpZA.invalidsignature";
        mockMvc.perform(post("/api/flash-sale/{sku}/buy", sku)
                        .header("X-User-Id", "500")
                        .header("X-Challenge-Token", token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INVALID"));
    }

    @Test
    void rateLimit_returns429() throws Exception {
        long userId = 600L;
        // Gửi 6 request liên tiếp, request thứ 6 bị chặn (max 5/giây)
        for (int i = 0; i < 5; i++) {
            String token = challengeService.issue(userId);
            Thread.sleep(300);
            mockMvc.perform(post("/api/flash-sale/{sku}/buy", sku)
                    .header("X-User-Id", userId)
                    .header("X-Challenge-Token", token));
        }
        // Request thứ 6 trong cùng giây
        String token = challengeService.issue(userId);
        Thread.sleep(300);
        mockMvc.perform(post("/api/flash-sale/{sku}/buy", sku)
                        .header("X-User-Id", userId)
                        .header("X-Challenge-Token", token))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("RATE_LIMITED"));
    }
}
