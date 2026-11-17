package com.gearhub;

import static org.assertj.core.api.Assertions.assertThat;

import com.gearhub.auth.Totp;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Login do painel de admin (programa de desktop): token curto, só admin com 2FA, só nas rotas de admin. */
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"gearhub.auth.register-limit-per-minute=1000", "gearhub.auth.login-limit-per-minute=1000",
                "gearhub.market.collect=false"})
class AdminAppIntegrationTest {

    private static final String PASSWORD = "Senha-Forte-123";
    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

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

    private HttpResponse<String> call(String method, String path, String json, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) {
            request.header("Content-Type", "application/json");
        }
        headers.forEach(request::header);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Conta com e-mail confirmado e 2FA ativo; devolve o e-mail, a sessão do site e os códigos de recuperação. */
    private record Account(String username, String email, Map<String, String> cookie, List<String> recovery) {
    }

    private Account account(boolean admin) throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String email = id + "@exemplo.com";
        HttpResponse<String> created = call("POST", "/api/v1/auth/register", """
                {"displayName":"Pessoa","username":"g%s","email":"%s","password":"%s","passwordConfirmation":"%s"}
                """.formatted(id, email, PASSWORD, PASSWORD), Map.of());
        Map<String, String> cookie = Map.of("Cookie", created.headers().firstValue("set-cookie").orElseThrow().split(";")[0]);
        jdbc.update("UPDATE users SET email_verified_at = now() WHERE username = ?", "g" + id);
        String secret = call("POST", "/api/v1/me/2fa/setup", null, cookie).body().replaceAll(".*\"secret\":\"([A-Z2-7]+)\".*", "$1");
        String enabled = call("POST", "/api/v1/me/2fa/enable",
                "{\"password\":\"" + PASSWORD + "\",\"code\":\"" + Totp.code(secret, Totp.currentStep()) + "\"}", cookie).body();
        List<String> recovery = List.of(enabled.replaceAll(".*\\[(.*)].*", "$1").replace("\"", "").split(","));
        if (admin) {
            jdbc.update("UPDATE users SET role = 'admin' WHERE username = ?", "g" + id);
        }
        return new Account("g" + id, email, cookie, recovery);
    }

    private HttpResponse<String> login(String email, String password, String code) throws Exception {
        return call("POST", "/api/v1/auth/admin/login", """
                {"email":"%s","password":"%s","code":"%s","deviceName":"Notebook de teste"}
                """.formatted(email, password, code), Map.of("User-Agent", "GearHub-Admin/0.1"));
    }

    private static Map<String, String> bearer(HttpResponse<String> login) {
        return Map.of("Authorization", "Bearer " + login.body().replaceAll(".*\"token\":\"([^\"]+)\".*", "$1"));
    }

    @Test
    void adminEntraNoPainelComSenhaE2fa() throws Exception {
        Account admin = account(true);
        HttpResponse<String> login = login(admin.email(), PASSWORD, admin.recovery().get(0));
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.body()).contains("\"token\":", "\"expiresAt\":");
        Map<String, String> token = bearer(login);

        // Vale nas rotas de admin
        assertThat(call("GET", "/api/v1/admin/overview", null, token).statusCode()).isEqualTo(200);
        // A ação registrada leva o aparelho
        assertThat(call("POST", "/api/v1/admin/reserved-usernames", "{\"pattern\":\"teste" + admin.username().substring(1, 7) + "\"}",
                token).statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForObject("""
                SELECT data ->> 'via' FROM audit_events WHERE action = 'admin.username_reserve' ORDER BY id DESC LIMIT 1
                """, String.class)).isEqualTo("Notebook de teste");

        // Não vale no resto do site: um token vazado não abre perfil, feed ou configurações
        assertThat(call("GET", "/api/v1/auth/me", null, token).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/api/v1/feed", null, token).statusCode()).isEqualTo(401);
        // Token inventado: 401 na hora
        assertThat(call("GET", "/api/v1/admin/overview", null, Map.of("Authorization", "Bearer inventado")).statusCode()).isEqualTo(401);

        // Lista de aparelhos e saída
        assertThat(call("GET", "/api/v1/admin/app-tokens", null, token).body()).contains("Notebook de teste", "\"current\":true");
        assertThat(call("POST", "/api/v1/auth/admin/logout", null, token).statusCode()).isEqualTo(204);
        assertThat(call("GET", "/api/v1/admin/overview", null, token).statusCode()).isEqualTo(401);
        // Só o hash fica guardado
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_app_tokens WHERE token_hash = ?", Integer.class,
                token.get("Authorization").substring(7))).isZero();
    }

    @Test
    void qualquerFalhaDaAMesmaResposta() throws Exception {
        Account admin = account(true);
        Account user = account(false);
        String message = login(admin.email(), "errada", admin.recovery().get(0)).body();
        assertThat(message).contains("E-mail, senha ou código incorretos.");
        // Código errado, conta que não é admin e e-mail que não existe: tudo igual
        assertThat(login(admin.email(), PASSWORD, "000000").body()).isEqualTo(message);
        assertThat(login(user.email(), PASSWORD, user.recovery().get(0)).body()).isEqualTo(message);
        assertThat(login("ninguem@exemplo.com", PASSWORD, "000000").body()).isEqualTo(message);
        assertThat(login(user.email(), PASSWORD, user.recovery().get(1)).statusCode()).isEqualTo(401);
    }

    @Test
    void trocarSenhaOuPerderOAdminEncerraOPainel() throws Exception {
        Account admin = account(true);
        Map<String, String> first = bearer(login(admin.email(), PASSWORD, admin.recovery().get(0)));
        Map<String, String> second = bearer(login(admin.email(), PASSWORD, admin.recovery().get(1)));

        // Outro admin corta um aparelho perdido
        String other = call("GET", "/api/v1/admin/app-tokens", null, first).body();
        String secondId = other.replaceAll(".*\\{\"id\":\"([0-9a-f-]{36})\"[^}]*\"current\":false.*", "$1");
        assertThat(call("POST", "/api/v1/admin/app-tokens/" + secondId + "/revoke", null, first).statusCode()).isEqualTo(204);
        assertThat(call("GET", "/api/v1/admin/overview", null, second).statusCode()).isEqualTo(401);

        // Deixou de ser admin: o token para de valer
        jdbc.update("UPDATE users SET role = 'user' WHERE username = ?", admin.username());
        assertThat(call("GET", "/api/v1/admin/overview", null, first).statusCode()).isEqualTo(401);
        jdbc.update("UPDATE users SET role = 'admin' WHERE username = ?", admin.username());

        // Trocar a senha encerra todos os acessos do painel
        assertThat(call("POST", "/api/v1/me/account/password", """
                {"currentPassword":"%s","password":"Outra-Senha-456","passwordConfirmation":"Outra-Senha-456","code":"%s"}
                """.formatted(PASSWORD, admin.recovery().get(2)), admin.cookie()).statusCode()).isEqualTo(204);
        assertThat(call("GET", "/api/v1/admin/overview", null, first).statusCode()).isEqualTo(401);
    }
}
