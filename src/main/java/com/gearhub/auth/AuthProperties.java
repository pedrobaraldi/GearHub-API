package com.gearhub.auth;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gearhub.auth")
public record AuthProperties(
        boolean secureCookie,
        Duration sessionTtl,
        int loginLimitPerMinute,
        int registerLimitPerMinute,
        int resetLimitPerMinute,
        List<String> allowedOrigins) {
}
