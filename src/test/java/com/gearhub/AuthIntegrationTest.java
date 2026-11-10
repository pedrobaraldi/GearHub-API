package com.gearhub;

import static org.assertj.core.api.Assertions.assertThat;

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

@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"gearhub.auth.login-limit-per-minute=1000", "gearhub.auth.register-limit-per-minute=1000"})
class AuthIntegrationTest {

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

    private record Account(String username, String email, String cookie) {
    }

    private HttpResponse<String> call(String method, String path, String json, Map<String, String> headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, json == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) {
            request.header("Content-Type", "application/json");
        }
        headers.forEach(request::header);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String registerJson(String username, String email, String password) {
        return """
                {"displayName":"Pessoa Teste","username":"%s","email":"%s","password":"%s","passwordConfirmation":"%s"}
                """.formatted(username, email, password, password);
    }

    private static String loginJson(String email, String password) {
        return """
                {"email":"%s","password":"%s"}
                """.formatted(email, password);
    }

    private static String cookieOf(HttpResponse<String> response) {
        return response.headers().firstValue("set-cookie").orElseThrow().split(";")[0];
    }

    private Map<String, String> viewer;

    // Todo o conteúdo exige conta: quem só olha também entra com uma sessão
    private Map<String, String> viewer() throws Exception {
        if (viewer == null) {
            viewer = Map.of("Cookie", newAccount().cookie());
        }
        return viewer;
    }

    private Account newAccount() throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String username = "u" + id;
        String email = id + "@exemplo.com";
        HttpResponse<String> response = call("POST", "/api/v1/auth/register", registerJson(username, email, PASSWORD), Map.of());
        assertThat(response.statusCode()).isEqualTo(201);
        return new Account(username, email, cookieOf(response));
    }

    @Test
    void cadastroCriaSessaoComCookieSeguro() throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> response = call("POST", "/api/v1/auth/register",
                registerJson("c" + id.replace("-", ""), id + "@exemplo.com", PASSWORD), Map.of());
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("set-cookie").orElseThrow())
                .contains("gearhub_session=", "HttpOnly", "SameSite=Lax", "Path=/");
        assertThat(response.body()).doesNotContain("password", "emailHash", "hash");
    }

    @Test
    void bancoNaoGuardaNadaSensivelLegivel() throws Exception {
        Account account = newAccount();
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT email_encrypted, email_hash, password_hash FROM users WHERE username = ?", account.username());
        assertThat(row.get("email_encrypted").toString()).startsWith("v1:").doesNotContain(account.email(), "@");
        assertThat(row.get("email_hash").toString()).hasSize(64).doesNotContain("@");
        assertThat(row.get("password_hash").toString()).startsWith("$argon2id$").doesNotContain(PASSWORD);

        String token = account.cookie().split("=", 2)[1];
        Map<String, Object> session = jdbc.queryForMap("""
                SELECT s.token_hash, s.ip_encrypted, s.user_agent_encrypted FROM sessions s
                JOIN users u ON u.id = s.user_id WHERE u.username = ?
                """, account.username());
        assertThat(session.get("token_hash").toString()).hasSize(64).isNotEqualTo(token);
        assertThat(session.get("ip_encrypted").toString()).startsWith("v1:");
        assertThat(session.get("user_agent_encrypted").toString()).startsWith("v1:");
    }

    @Test
    void meDevolveAContaEEmailDescriptografado() throws Exception {
        Account account = newAccount();
        HttpResponse<String> me = call("GET", "/api/v1/auth/me", null, Map.of("Cookie", account.cookie()));
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).contains(account.username(), account.email());
    }

    @Test
    void semSessaoOuComCookieFalsoDevolve401() throws Exception {
        HttpResponse<String> anonymous = call("GET", "/api/v1/auth/me", null, Map.of());
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(detail(anonymous)).isEqualTo("Entre na sua conta para continuar.");
        assertThat(call("GET", "/api/v1/auth/me", null, Map.of("Cookie", "gearhub_session=falso")).statusCode())
                .isEqualTo(401);
    }

    @Test
    void loginAceitaEmailComOutraCaixaESenhaCerta() throws Exception {
        Account account = newAccount();
        HttpResponse<String> login = call("POST", "/api/v1/auth/login",
                loginJson(" " + account.email().toUpperCase() + " ", PASSWORD), Map.of());
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(call("GET", "/api/v1/auth/me", null, Map.of("Cookie", cookieOf(login))).statusCode()).isEqualTo(200);
    }

    @Test
    void senhaErradaEContaInexistenteRespondemIgual() throws Exception {
        Account account = newAccount();
        HttpResponse<String> wrong = call("POST", "/api/v1/auth/login", loginJson(account.email(), "senha-errada"), Map.of());
        HttpResponse<String> unknown = call("POST", "/api/v1/auth/login", loginJson("ninguem@exemplo.com", "senha-errada"), Map.of());
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(unknown.statusCode()).isEqualTo(401);
        assertThat(detail(wrong)).isEqualTo(detail(unknown)).isEqualTo("E-mail ou senha incorretos");
    }

    private static String detail(HttpResponse<String> response) {
        return response.body().replaceAll(".*\"detail\":\"([^\"]*)\".*", "$1");
    }

    @Test
    void logoutEncerraASessaoNoServidor() throws Exception {
        Account account = newAccount();
        Map<String, String> auth = Map.of("Cookie", account.cookie());
        HttpResponse<String> logout = call("POST", "/api/v1/auth/logout", null, auth);
        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(logout.headers().firstValue("set-cookie").orElseThrow()).contains("Max-Age=0");
        assertThat(call("GET", "/api/v1/auth/me", null, auth).statusCode()).isEqualTo(401);
    }

    @Test
    void naoPermiteEmailOuUsuarioRepetidos() throws Exception {
        Account account = newAccount();
        HttpResponse<String> sameEmail = call("POST", "/api/v1/auth/register",
                registerJson("outronome1", account.email().toUpperCase(), PASSWORD), Map.of());
        HttpResponse<String> sameUsername = call("POST", "/api/v1/auth/register",
                registerJson(account.username(), "novo@exemplo.com", PASSWORD), Map.of());
        assertThat(sameEmail.statusCode()).isEqualTo(409);
        assertThat(sameUsername.statusCode()).isEqualTo(409);
    }

    @Test
    void validaOsCamposDoCadastro() throws Exception {
        HttpResponse<String> response = call("POST", "/api/v1/auth/register",
                registerJson("AB", "nao-e-email", "123"), Map.of());
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"errors\"", "\"username\"", "\"email\"", "\"password\"");
    }

    @Test
    void jsonInvalidoDevolveMensagemCompreensivel() throws Exception {
        HttpResponse<String> response = call("POST", "/api/v1/auth/login", "{quebrado", Map.of());
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(detail(response)).startsWith("Não entendemos os dados enviados");
    }

    @Test
    void exigeSenhaForte() throws Exception {
        for (String weak : List.of("Se-1", "senha-forte-123", "Senha-Forte-sem", "SenhaForte123")) {
            String id = UUID.randomUUID().toString().substring(0, 8).replace("-", "");
            HttpResponse<String> response = call("POST", "/api/v1/auth/register",
                    registerJson("f" + id, id + "@exemplo.com", weak), Map.of());
            assertThat(response.statusCode()).as(weak).isEqualTo(400);
            assertThat(response.body()).as(weak).contains("\"password\"");
        }
    }

    @Test
    void exigeConfirmacaoIgualASenha() throws Exception {
        HttpResponse<String> response = call("POST", "/api/v1/auth/register", """
                {"displayName":"Pessoa","username":"confirma1","email":"confirma1@exemplo.com",
                 "password":"Senha-Forte-123","passwordConfirmation":"Senha-Forte-124"}
                """, Map.of());
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"passwordConfirmed\"", "As senhas não coincidem");

        HttpResponse<String> missing = call("POST", "/api/v1/auth/register", """
                {"displayName":"Pessoa","username":"confirma2","email":"confirma2@exemplo.com","password":"Senha-Forte-123"}
                """, Map.of());
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(missing.body()).contains("\"passwordConfirmation\"");
    }

    @Test
    void senhaCertaEntraMesmoComRegrasNoCadastro() throws Exception {
        Account account = newAccount();
        assertThat(call("POST", "/api/v1/auth/login", loginJson(account.email(), PASSWORD), Map.of()).statusCode())
                .isEqualTo(200);
    }

    @Test
    void recusaOrigemDesconhecidaEAceitaAConhecida() throws Exception {
        Account account = newAccount();
        HttpResponse<String> attack = call("POST", "/api/v1/auth/logout", null,
                Map.of("Cookie", account.cookie(), "Origin", "https://site-malicioso.com"));
        assertThat(attack.statusCode()).isEqualTo(403);
        assertThat(detail(attack)).isEqualTo("Esta ação não é permitida a partir deste endereço.");
        assertThat(call("GET", "/api/v1/auth/me", null, Map.of("Cookie", account.cookie())).statusCode()).isEqualTo(200);

        HttpResponse<String> site = call("POST", "/api/v1/auth/logout", null,
                Map.of("Cookie", account.cookie(), "Origin", "http://localhost:3000"));
        assertThat(site.statusCode()).isEqualTo(204);
    }

    @Test
    void perfilPublicoNaoExpoeDadosPrivados() throws Exception {
        Account account = newAccount();
        HttpResponse<String> profile = call("GET", "/api/v1/users/" + account.username(), null, viewer());
        assertThat(profile.statusCode()).isEqualTo(200);
        assertThat(profile.body()).contains(account.username()).doesNotContain(account.email(), "\"email\"", "role");
        assertThat(call("GET", "/api/v1/users/nao-existe", null, viewer()).statusCode()).isEqualTo(404);
    }

    @Test
    void atualizaOProprioPerfil() throws Exception {
        Account account = newAccount();
        Map<String, String> auth = Map.of("Cookie", account.cookie());
        HttpResponse<String> updated = call("PATCH", "/api/v1/users/me",
                "{\"displayName\":\"  Novo Nome  \",\"bio\":\"Jogador de FPS\"}", auth);
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(updated.body()).contains("\"displayName\":\"Novo Nome\"", "Jogador de FPS");

        assertThat(call("PATCH", "/api/v1/users/me", "{\"bio\":\"\"}", auth).body()).contains("\"bio\":null");
        assertThat(call("PATCH", "/api/v1/users/me", "{\"displayName\":\"   \"}", auth).statusCode()).isEqualTo(400);
        assertThat(call("PATCH", "/api/v1/users/me", "{\"bio\":\"x\"}", Map.of()).statusCode()).isEqualTo(401);
    }

    @Test
    void swaggerDocumentaAuthSemExporHashes() throws Exception {
        String docs = call("GET", "/v3/api-docs", null, Map.of()).body();
        assertThat(docs).contains("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/users/me");
        assertThat(docs).doesNotContain("passwordHash", "emailHash", "tokenHash");
        assertThat(List.of("Razer", "Wooting")).noneMatch(docs::contains);
    }
}
