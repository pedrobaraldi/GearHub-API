package com.gearhub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gearhub.auth.TurnstileProperties;
import com.sun.net.httpserver.HttpServer;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Cadastro e login com a verificação ligada, contra uma Cloudflare falsa. */
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"gearhub.auth.login-limit-per-minute=1000", "gearhub.auth.register-limit-per-minute=1000"})
class TurnstileIntegrationTest {

    private static final String PASSWORD = "Senha-Forte-123";
    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final HttpServer FAKE_CLOUDFLARE = startFake();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static volatile String lastForm = "";

    @Value("${local.server.port}")
    private int port;

    private static EmbeddedPostgres startPostgres() {
        try {
            return EmbeddedPostgres.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer startFake() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/verify", exchange -> {
                lastForm = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (lastForm.contains("response=quebrado")) {
                    exchange.sendResponseHeaders(500, -1);
                    exchange.close();
                    return;
                }
                boolean valid = lastForm.contains("response=valido");
                byte[] body = ("{\"success\":" + valid + "}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("gearhub.turnstile.secret-key", () -> "segredo-teste");
        registry.add("gearhub.turnstile.verify-uri",
                () -> "http://localhost:" + FAKE_CLOUDFLARE.getAddress().getPort() + "/verify");
    }

    @AfterAll
    static void stop() throws IOException {
        FAKE_CLOUDFLARE.stop(0);
        POSTGRES.close();
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> register(String id, String token) throws Exception {
        return post("/api/v1/auth/register", """
                {"displayName":"Pessoa","username":"t%s","email":"%s@exemplo.com","password":"%s","passwordConfirmation":"%s","turnstileToken":%s}
                """.formatted(id, id, PASSWORD, PASSWORD, token == null ? "null" : "\"" + token + "\""));
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @Test
    void cadastroSoPassaComVerificacaoValida() throws Exception {
        String id = newId();

        HttpResponse<String> semToken = register(id, null);
        assertThat(semToken.statusCode()).isEqualTo(400);
        assertThat(semToken.body()).contains("Confirme que você não é um robô");

        assertThat(register(id, "falso").statusCode()).isEqualTo(400);

        assertThat(register(id, "valido").statusCode()).isEqualTo(201);
        assertThat(lastForm).contains("secret=segredo-teste", "response=valido");
    }

    @Test
    void loginExigeVerificacaoAntesDeConferirASenha() throws Exception {
        String id = newId();
        register(id, "valido");
        String login = "{\"email\":\"%s@exemplo.com\",\"password\":\"%s\",\"turnstileToken\":%s}";

        HttpResponse<String> semToken = post("/api/v1/auth/login", login.formatted(id, PASSWORD, "null"));
        assertThat(semToken.statusCode()).isEqualTo(400);
        assertThat(semToken.body()).contains("Confirme que você não é um robô");

        assertThat(post("/api/v1/auth/login", login.formatted(id, PASSWORD, "\"valido\"")).statusCode()).isEqualTo(200);
    }

    @Test
    void campoInvalidoNaoGastaOToken() throws Exception {
        lastForm = "";
        HttpResponse<String> invalid = post("/api/v1/auth/register", "{\"turnstileToken\":\"nao-usar\"}");
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(lastForm).isEmpty();
    }

    @Test
    void serviceFalhandoNinguemPassa() throws Exception {
        HttpResponse<String> response = register(newId(), "quebrado");
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("Não foi possível fazer a verificação de segurança");
    }

    @Test
    void producaoNaoSobeSemAChave() {
        assertThatThrownBy(() -> new TurnstileProperties("", "http://localhost", true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("TURNSTILE_SECRET_KEY");
        assertThat(new TurnstileProperties("", "http://localhost", false).configured()).isFalse();
    }

    @Test
    void respostaNuncaTrazOSegredo() throws Exception {
        assertThat(register(newId(), "falso").body()).doesNotContain("segredo-teste");
    }
}
