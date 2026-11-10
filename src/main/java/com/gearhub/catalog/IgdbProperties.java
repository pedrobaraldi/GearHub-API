package com.gearhub.catalog;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Credenciais do IGDB (app registrado no console da Twitch). Vazias, a área de catálogo responde 503. */
@ConfigurationProperties("gearhub.igdb")
public record IgdbProperties(String clientId, String clientSecret, String tokenUri, String apiUri) {

    public boolean configured() {
        return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }
}
