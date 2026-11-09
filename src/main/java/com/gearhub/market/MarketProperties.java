package com.gearhub.market;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Coleta da aba Games. Desligada nos testes, que não podem depender da Steam. */
@ConfigurationProperties("gearhub.market")
public record MarketProperties(
        boolean collect,
        String country,
        String language,
        String storeUri,
        String apiUri,
        long pauseMillis) {
}
