package com.gearhub;

import static org.assertj.core.api.Assertions.assertThat;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Limite geral de requisições por IP e o IP real vindo do proxy da rede interna (o site). */
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"gearhub.auth.requests-per-minute=5", "gearhub.market.collect=false"})
class RequestLimitIntegrationTest {

    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    private static EmbeddedPostgres startPostgres() {
        try {
            return EmbeddedPostgres.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @AfterAll
    static void stopPostgres() throws IOException {
        POSTGRES.close();
    }

    private HttpResponse<String> get(String path, String forwardedFor) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void cadaIpTemSeuLimiteEOProxyInternoInformaOIpReal() throws Exception {
        // Vindo do site (rede interna), o X-Forwarded-For identifica a pessoa: cada IP tem o seu balde
        for (int i = 0; i < 5; i++) {
            assertThat(get("/api/v1/report-reasons", "203.0.113.10").statusCode()).isEqualTo(401);
        }
        HttpResponse<String> blocked = get("/api/v1/report-reasons", "203.0.113.10");
        assertThat(blocked.statusCode()).isEqualTo(429);
        assertThat(blocked.headers().firstValue("retry-after")).hasValue("60");
        assertThat(blocked.body()).contains("Muitas requisições");

        // Outro visitante não é afetado pelo excesso do primeiro
        assertThat(get("/api/v1/report-reasons", "203.0.113.20").statusCode()).isEqualTo(401);

        // A saúde da API fica fora do limite
        for (int i = 0; i < 8; i++) {
            assertThat(get("/actuator/health", "203.0.113.10").statusCode()).isEqualTo(200);
        }
    }
}
