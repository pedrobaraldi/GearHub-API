package com.gearhub.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Com {@code required}, a API não sobe sem a chave: em produção o e-mail precisa sair de verdade. */
@ConfigurationProperties("gearhub.mail")
public record MailProperties(String resendApiKey, String from, String apiUri, String siteUrl, boolean required,
        String resetTemplate) {

    public MailProperties {
        if (required && (resendApiKey == null || resendApiKey.isBlank())) {
            throw new IllegalStateException("RESEND_API_KEY é obrigatória neste ambiente");
        }
    }

    public boolean configured() {
        return resendApiKey != null && !resendApiKey.isBlank();
    }

    /** Com um template publicado no Resend, o texto pode mudar sem novo deploy. */
    public boolean hasResetTemplate() {
        return configured() && resetTemplate != null && !resetTemplate.isBlank();
    }
}
