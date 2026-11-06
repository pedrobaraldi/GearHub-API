package com.gearhub.auth;

import java.time.Duration;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/** Confirma com a Cloudflare o teste "não sou um robô" feito na tela. */
@Service
public class TurnstileService {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP = new ParameterizedTypeReference<>() {
    };

    private final TurnstileProperties properties;
    private final RestClient http;

    public TurnstileService(TurnstileProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT);
        factory.setReadTimeout(TIMEOUT);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    /** Sem chave configurada não há verificação: só acontece fora de produção. */
    public void verify(String token) {
        if (!properties.configured()) {
            return;
        }
        if (token == null || token.isBlank() || !accepted(token)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Confirme que você não é um robô e tente novamente.");
        }
    }

    private boolean accepted(String token) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("secret", properties.secretKey());
        form.add("response", token);
        try {
            Map<String, Object> result = http.post().uri(properties.verifyUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve().body(JSON_MAP);
            return result != null && Boolean.TRUE.equals(result.get("success"));
        } catch (RestClientException e) {
            // Sem resposta, ninguém passa: a proteção não pode cair junto com o serviço
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Não foi possível fazer a verificação de segurança. Tente novamente em instantes.", e);
        }
    }
}
