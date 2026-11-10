package com.gearhub.catalog;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Chamadas ao IGDB. Regras deles: no máximo 4 chamadas por segundo, e o token dura 60 dias com até 25 ativos
 * por app. Por isso o token é guardado e reaproveitado, e as chamadas saem com um intervalo mínimo entre si.
 */
@Component
public class IgdbClient {

    /** IGDB fora do ar, sem chaves ou recusando a chamada. */
    public static class IgdbUnavailableException extends RuntimeException {
        public IgdbUnavailableException(String message) {
            super(message);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(IgdbClient.class);
    private static final ParameterizedTypeReference<List<Map<String, Object>>> ROWS = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<Map<String, Object>> OBJECT = new ParameterizedTypeReference<>() {
    };
    // 4 por segundo, com folga
    private static final long MIN_INTERVAL_NANOS = Duration.ofMillis(260).toNanos();
    // Renova antes de vencer, para nenhuma chamada sair com token expirado
    private static final Duration TOKEN_MARGIN = Duration.ofDays(1);

    private final IgdbProperties properties;
    private final RestClient http = RestClient.create();

    private String token;
    private Instant tokenExpiresAt = Instant.EPOCH;
    private long lastRequestNanos;

    public IgdbClient(IgdbProperties properties) {
        this.properties = properties;
    }

    public boolean configured() {
        return properties.configured();
    }

    /** Consulta em Apicalypse, por exemplo {@code fields name; where slug = "elden-ring";}. */
    public List<Map<String, Object>> query(String endpoint, String body) {
        if (!configured()) {
            throw new IgdbUnavailableException("IGDB sem credenciais");
        }
        try {
            return send(endpoint, body);
        } catch (HttpClientErrorException.Unauthorized e) {
            // Token revogado antes da hora (por exemplo, mais de 25 ativos): pede outro e tenta uma vez
            invalidateToken();
            return send(endpoint, body);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                throw new IgdbUnavailableException("IGDB recusou por excesso de chamadas");
            }
            log.warn("IGDB recusou a consulta em {}: {}", endpoint, e.getStatusCode());
            throw new IgdbUnavailableException("IGDB recusou a consulta");
        } catch (RestClientException e) {
            log.warn("IGDB fora do ar em {}: {}", endpoint, e.getMessage());
            throw new IgdbUnavailableException("IGDB fora do ar");
        }
    }

    private List<Map<String, Object>> send(String endpoint, String body) {
        String bearer = token();
        waitForSlot();
        List<Map<String, Object>> rows = http.post()
                .uri(properties.apiUri() + "/" + endpoint)
                .header("Client-ID", properties.clientId())
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.TEXT_PLAIN)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(ROWS);
        return rows == null ? List.of() : rows;
    }

    private synchronized String token() {
        if (token != null && Instant.now().isBefore(tokenExpiresAt.minus(TOKEN_MARGIN))) {
            return token;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("grant_type", "client_credentials");
        Map<String, Object> response;
        try {
            response = http.post().uri(properties.tokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(OBJECT);
        } catch (RestClientException e) {
            log.warn("Twitch não entregou o token do IGDB: {}", e.getMessage());
            throw new IgdbUnavailableException("Sem token do IGDB");
        }
        if (response == null || !(response.get("access_token") instanceof String accessToken)) {
            throw new IgdbUnavailableException("Sem token do IGDB");
        }
        long seconds = response.get("expires_in") instanceof Number number ? number.longValue() : 3600;
        token = accessToken;
        tokenExpiresAt = Instant.now().plusSeconds(seconds);
        return token;
    }

    private synchronized void invalidateToken() {
        token = null;
        tokenExpiresAt = Instant.EPOCH;
    }

    private synchronized void waitForSlot() {
        long wait = lastRequestNanos + MIN_INTERVAL_NANOS - System.nanoTime();
        if (wait > 0) {
            try {
                Thread.sleep(Duration.ofNanos(wait));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IgdbUnavailableException("Interrompido");
            }
        }
        lastRequestNanos = System.nanoTime();
    }
}
