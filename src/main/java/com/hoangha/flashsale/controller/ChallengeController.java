package com.hoangha.flashsale.controller;

import com.hoangha.flashsale.service.ChallengeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/flash-sale")
@RequiredArgsConstructor
public class ChallengeController {

    private final ChallengeService challengeService;

    @GetMapping("/challenge")
    public ResponseEntity<Map<String, String>> challenge(
            @RequestHeader("X-User-Id") long userId) {
        String token = challengeService.issue(userId);
        return ResponseEntity.ok(Map.of("token", token));
    }
}
