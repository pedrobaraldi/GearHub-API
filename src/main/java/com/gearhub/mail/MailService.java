package com.gearhub.mail;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Envia e-mails pelo Resend. Sem chave, só fora de produção, o texto vai para o log. */
@Service
public class MailService {

    private static final Logger LOG = LoggerFactory.getLogger(MailService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Duration FIRST_WAIT = Duration.ofMillis(500);
    private static final Duration MAX_WAIT = Duration.ofSeconds(30);
    private static final int MAX_ATTEMPTS = 3;
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP = new ParameterizedTypeReference<>() {
    };

    private final MailProperties properties;
    private final RestClient http;

    public MailService(MailProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT);
        factory.setReadTimeout(TIMEOUT);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    /** Em segundo plano: o tempo de resposta não pode revelar se a conta existe. */
    public void sendInBackground(String to, String subject, String text) {
        if (!properties.configured()) {
            LOG.info("E-mail não enviado, RESEND_API_KEY ausente. Assunto: {}\n{}", subject, text);
            return;
        }
        CompletableFuture.runAsync(() -> send(
                Map.of("from", properties.from(), "to", List.of(to), "subject", subject, "text", text)));
    }

    /** Valores que vão para o HTML precisam chegar aqui já escapados. */
    public void sendTemplateInBackground(String to, String subject, String template, Map<String, String> variables) {
        CompletableFuture.runAsync(() -> send(Map.of("from", properties.from(), "to", List.of(to), "subject", subject,
                "template", Map.of("id", template, "variables", variables))));
    }

    private void send(Map<String, Object> mail) {
        // A mesma chave em todas as tentativas: o Resend não envia duas vezes
        String idempotencyKey = UUID.randomUUID().toString();
        Duration wait = FIRST_WAIT;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                Map<String, Object> sent = http.post().uri(properties.apiUri())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.resendApiKey())
                        .header(HttpHeaders.USER_AGENT, "GearHub-API/1.0")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(mail)
                        .retrieve().body(JSON_MAP);
                LOG.info("E-mail enviado pelo Resend: {}", sent == null ? null : sent.get("id"));
                return;
            } catch (RestClientResponseException e) {
                boolean temporary = e.getStatusCode().is5xxServerError() || e.getStatusCode().value() == 429;
                if (!temporary) {
                    LOG.error("Resend recusou o e-mail ({}): {}", e.getStatusCode().value(), withoutAddresses(e));
                    return;
                }
                wait = retryAfter(e, wait);
            } catch (ResourceAccessException e) {
                LOG.warn("Falha de rede ao enviar e-mail, tentativa {}", attempt);
            }
            if (attempt < MAX_ATTEMPTS && !pause(wait)) {
                return;
            }
            wait = wait.multipliedBy(2);
        }
        LOG.error("E-mail não enviado depois de {} tentativas", MAX_ATTEMPTS);
    }

    private static Duration retryAfter(RestClientResponseException e, Duration fallback) {
        String header = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        try {
            Duration wait = header == null ? fallback : Duration.ofSeconds(Long.parseLong(header.strip()));
            return wait.compareTo(MAX_WAIT) > 0 ? MAX_WAIT : wait;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean pause(Duration wait) {
        try {
            Thread.sleep(wait);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** E-mail é dado sensível: a resposta do Resend vai para o log sem endereços. */
    private static String withoutAddresses(RestClientResponseException e) {
        return e.getResponseBodyAsString().replaceAll("[^\\s@()\"]+@[^\\s@()\"]+", "***");
    }
}
