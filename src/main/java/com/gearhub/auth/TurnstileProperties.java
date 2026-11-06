package com.gearhub.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Com {@code required}, a API não sobe sem a chave: em produção a proteção não pode faltar. */
@ConfigurationProperties("gearhub.turnstile")
public record TurnstileProperties(String secretKey, String verifyUri, boolean required) {

    public TurnstileProperties {
        if (required && (secretKey == null || secretKey.isBlank())) {
            throw new IllegalStateException("TURNSTILE_SECRET_KEY é obrigatória neste ambiente");
        }
    }

    public boolean configured() {
        return secretKey != null && !secretKey.isBlank();
    }
}
