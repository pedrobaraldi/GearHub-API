package com.gearhub.config;

import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Cliente HTTP para serviços de fora (IGDB, Steam, Google, Discord) com tempo limite. Sem ele, um serviço
 * que trava prende a thread da requisição para sempre, e poucas chamadas assim derrubam a API.
 */
public final class HttpClients {

    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    private HttpClients() {
    }

    public static RestClient withTimeouts() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder().requestFactory(factory).build();
    }
}
