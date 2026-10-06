package com.hoangha.flashsale.config;

import com.hoangha.flashsale.guard.BotGuardInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final BotGuardInterceptor botGuardInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Chỉ áp dụng interceptor cho endpoint mua hàng
        registry.addInterceptor(botGuardInterceptor)
                .addPathPatterns("/api/flash-sale/*/buy");
    }
}
