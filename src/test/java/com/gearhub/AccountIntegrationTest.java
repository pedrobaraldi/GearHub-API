package com.gearhub;

import static org.assertj.core.api.Assertions.assertThat;

import com.gearhub.account.AccountService;
import com.gearhub.auth.Totp;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

/** Configurações da conta: senha, e-mail, contas ligadas, sessões, seus dados, exclusão e insígnia do 2FA. */
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"gearhub.auth.login-limit-per-minute=1000", "gearhub.auth.register-limit-per-minute=1000",
                "gearhub.market.collect=false"})
class AccountIntegrationTest {

    private static final String PASSWORD = "Senha-Forte-123";
    private static final String NEW_PASSWORD = "Outra-Senha-456";
    private static final String CHROME = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";
    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AccountService accounts;

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

    private record Person(String username, String email, Map<String, String> auth) {
    }

    private HttpResponse<String> call(String method, String path, String json, Map<String, String> headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) {
            request.header("Content-Type", "application/json");
        }
        headers.forEach(request::header);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String cookie(HttpResponse<String> response) {
        return response.headers().allValues("set-cookie").stream()
                .filter(value -> value.startsWith("gearhub_session="))
                .map(value -> value.split(";")[0]).findFirst().orElse(null);
    }

    private Person newPerson() throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String email = id + "@exemplo.com";
        HttpResponse<String> created = call("POST", "/api/v1/auth/register", """
                {"displayName":"Pessoa","username":"p%s","email":"%s","password":"%s","passwordConfirmation":"%s"}
                """.formatted(id, email, PASSWORD, PASSWORD), Map.of("User-Agent", CHROME));
        assertThat(created.statusCode()).isEqualTo(201);
        return new Person("p" + id, email, Map.of("Cookie", cookie(created)));
    }

    private HttpResponse<String> login(String email, String password) throws Exception {
        return call("POST", "/api/v1/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}",
                Map.of("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Version/18.0 Mobile/15E148 Safari/604.1"));
    }

    private int status(Map<String, String> auth) throws Exception {
        return call("GET", "/api/v1/auth/me", null, auth).statusCode();
    }

    @Test
    void resumoDaConta() throws Exception {
        Person person = newPerson();
        assertThat(call("GET", "/api/v1/me/account", null, person.auth()).body()).isEqualTo(
                "{\"email\":\"" + person.email() + "\",\"emailVerified\":false,\"hasPassword\":true,\"providers\":[],\"twoFactorEnabled\":false}");
        assertThat(call("GET", "/api/v1/me/account", null, Map.of()).statusCode()).isEqualTo(401);
    }

    @Test
    void trocarSenhaPedeAAtualEEncerraOsOutrosAparelhos() throws Exception {
        Person person = newPerson();
        Map<String, String> phone = Map.of("Cookie", cookie(login(person.email(), PASSWORD)));

        HttpResponse<String> wrong = call("POST", "/api/v1/me/account/password", """
                {"currentPassword":"errada","password":"%s","passwordConfirmation":"%s"}
                """.formatted(NEW_PASSWORD, NEW_PASSWORD), person.auth());
        assertThat(wrong.statusCode()).isEqualTo(400);
        assertThat(wrong.body()).contains("\"currentPassword\":\"Senha incorreta\"");

        assertThat(call("POST", "/api/v1/me/account/password", """
                {"currentPassword":"%s","password":"%s","passwordConfirmation":"%s"}
                """.formatted(PASSWORD, NEW_PASSWORD, NEW_PASSWORD), person.auth()).statusCode()).isEqualTo(204);
        assertThat(status(person.auth())).isEqualTo(200);
        assertThat(status(phone)).isEqualTo(401);
        assertThat(login(person.email(), PASSWORD).statusCode()).isEqualTo(401);
        assertThat(login(person.email(), NEW_PASSWORD).statusCode()).isEqualTo(200);
    }

    @Test
    void quemSoEntraComGoogleCriaUmaSenha() throws Exception {
        Person person = newPerson();
        jdbc.update("UPDATE users SET password_hash = NULL WHERE username = ?", person.username());
        assertThat(call("POST", "/api/v1/me/account/password", """
                {"password":"%s","passwordConfirmation":"%s"}
                """.formatted(NEW_PASSWORD, NEW_PASSWORD), person.auth()).statusCode()).isEqualTo(204);
        assertThat(login(person.email(), NEW_PASSWORD).statusCode()).isEqualTo(200);
    }

    @Test
    void trocarEmailPedeASenhaENaoAceitaEmailEmUso() throws Exception {
        Person person = newPerson();
        Person other = newPerson();
        String newEmail = "novo-" + person.email();

        assertThat(call("POST", "/api/v1/me/account/email", "{\"email\":\"" + newEmail + "\",\"password\":\"errada\"}",
                person.auth()).body()).contains("\"password\":\"Senha incorreta\"");
        assertThat(call("POST", "/api/v1/me/account/email", "{\"email\":\"" + other.email() + "\",\"password\":\"" + PASSWORD + "\"}",
                person.auth()).body()).contains("\"email\":\"Esse e-mail já tem uma conta\"");

        assertThat(call("POST", "/api/v1/me/account/email", "{\"email\":\"" + newEmail.toUpperCase() + "\",\"password\":\"" + PASSWORD + "\"}",
                person.auth()).statusCode()).isEqualTo(204);
        assertThat(call("GET", "/api/v1/auth/me", null, person.auth()).body()).contains("\"email\":\"" + newEmail + "\"", "\"emailVerified\":false");
        assertThat(login(newEmail, PASSWORD).statusCode()).isEqualTo(200);
        assertThat(login(person.email(), PASSWORD).statusCode()).isEqualTo(401);
    }

    @Test
    void desligarGoogleSoComOutroJeitoDeEntrar() throws Exception {
        Person person = newPerson();
        jdbc.update("""
                INSERT INTO oauth_accounts (user_id, provider, provider_user_id_encrypted, provider_user_id_hash)
                SELECT id, 'google', 'v1:x:y:z', ? FROM users WHERE username = ?
                """, UUID.randomUUID().toString(), person.username());
        assertThat(call("GET", "/api/v1/me/account", null, person.auth()).body()).contains("\"providers\":[\"google\"]");

        jdbc.update("UPDATE users SET password_hash = NULL WHERE username = ?", person.username());
        assertThat(call("DELETE", "/api/v1/me/account/providers/google", null, person.auth()).statusCode()).isEqualTo(409);

        call("POST", "/api/v1/me/account/password", "{\"password\":\"" + NEW_PASSWORD + "\",\"passwordConfirmation\":\"" + NEW_PASSWORD + "\"}", person.auth());
        assertThat(call("DELETE", "/api/v1/me/account/providers/google", null, person.auth()).statusCode()).isEqualTo(204);
        assertThat(call("GET", "/api/v1/me/account", null, person.auth()).body()).contains("\"providers\":[]");
        assertThat(call("DELETE", "/api/v1/me/account/providers/discord", null, person.auth()).statusCode()).isEqualTo(404);
    }

    @Test
    void sessoesMostramOAparelhoEEncerram() throws Exception {
        Person person = newPerson();
        Map<String, String> phone = Map.of("Cookie", cookie(login(person.email(), PASSWORD)));
        Map<String, String> tablet = Map.of("Cookie", cookie(login(person.email(), PASSWORD)));

        String list = call("GET", "/api/v1/me/sessions", null, person.auth()).body();
        assertThat(list).contains("\"device\":\"Chrome no Windows\"", "\"device\":\"Safari no iPhone\"", "\"current\":true");
        String phoneId = list.replaceAll("(?s).*\"id\":\"([0-9a-f-]{36})\",\"device\":\"Safari no iPhone\".*", "$1");

        assertThat(call("DELETE", "/api/v1/me/sessions/" + phoneId, null, person.auth()).statusCode()).isEqualTo(204);
        assertThat(call("DELETE", "/api/v1/me/sessions/" + UUID.randomUUID(), null, person.auth()).statusCode()).isEqualTo(404);
        int open = status(phone) == 200 ? 1 : 0;
        open += status(tablet) == 200 ? 1 : 0;
        assertThat(open).isEqualTo(1);

        assertThat(call("POST", "/api/v1/me/sessions/sign-out-others", null, person.auth()).body()).isEqualTo("{\"closed\":1}");
        assertThat(status(phone)).isEqualTo(401);
        assertThat(status(tablet)).isEqualTo(401);
        assertThat(status(person.auth())).isEqualTo(200);
    }

    @Test
    void baixarMeusDadosSemSegredos() throws Exception {
        Person person = newPerson();
        call("POST", "/api/v1/posts", "{\"body\":\"Meu post para exportar\",\"topic\":\"discussao\"}", person.auth());
        HttpResponse<String> export = call("GET", "/api/v1/me/export", null, person.auth());
        assertThat(export.statusCode()).isEqualTo(200);
        assertThat(export.headers().firstValue("content-disposition")).hasValue("attachment; filename=\"gearhub-meus-dados.json\"");
        assertThat(export.body()).contains("\"usuario\":\"" + person.username() + "\"", "\"email\":\"" + person.email() + "\"",
                "Meu post para exportar", "\"sessoes\":[");
        assertThat(export.body()).doesNotContain("password", "totp", "token");
    }

    @Test
    void apagarContaSomeDoSiteEVoltaAoEntrarNoPrazo() throws Exception {
        Person person = newPerson();
        call("POST", "/api/v1/posts", "{\"body\":\"Post de quem vai sair\",\"topic\":\"discussao\"}", person.auth());
        Person viewer = newPerson();

        HttpResponse<String> wrong = call("POST", "/api/v1/me/account/delete",
                "{\"confirmation\":\"outro-nome\",\"password\":\"errada\"}", person.auth());
        assertThat(wrong.statusCode()).isEqualTo(400);
        assertThat(wrong.body()).contains("\"confirmation\":\"Digite exatamente o seu nome de usuário\"", "\"password\":\"Senha incorreta\"");

        HttpResponse<String> deleted = call("POST", "/api/v1/me/account/delete",
                "{\"confirmation\":\"" + person.username() + "\",\"password\":\"" + PASSWORD + "\"}", person.auth());
        assertThat(deleted.statusCode()).isEqualTo(200);
        assertThat(deleted.body()).contains("\"deleteAt\":\"20", "\"graceDays\":30");
        assertThat(deleted.headers().allValues("set-cookie").toString()).contains("gearhub_session=;", "Max-Age=0");

        assertThat(status(person.auth())).isEqualTo(401);
        assertThat(call("GET", "/api/v1/feed?size=50", null, viewer.auth()).body()).doesNotContain("Post de quem vai sair");
        assertThat(call("GET", "/api/v1/users/" + person.username(), null, viewer.auth()).statusCode()).isEqualTo(404);

        // Entrou de novo dentro do prazo: a conta volta como estava
        assertThat(login(person.email(), PASSWORD).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM users WHERE username = ?", Boolean.class, person.username())).isTrue();
        assertThat(call("GET", "/api/v1/feed?size=50", null, viewer.auth()).body()).contains("Post de quem vai sair");
    }

    @Test
    void passadosOsTrintaDiasApagaDeVez() throws Exception {
        Person person = newPerson();
        call("POST", "/api/v1/posts", "{\"body\":\"Post que some de vez\",\"topic\":\"discussao\"}", person.auth());
        call("POST", "/api/v1/me/account/delete", "{\"confirmation\":\"" + person.username() + "\",\"password\":\"" + PASSWORD + "\"}", person.auth());
        jdbc.update("UPDATE users SET deleted_at = now() - interval '31 days' WHERE username = ?", person.username());

        // Fora do prazo não restaura
        assertThat(login(person.email(), PASSWORD).statusCode()).isEqualTo(401);

        assertThat(accounts.purgeExpired()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE username = ?", Integer.class, person.username())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM posts WHERE body = 'Post que some de vez'", Integer.class)).isZero();
    }

    @Test
    void acoesSensiveisPedemOCodigoComDuasEtapasEDaoAInsignia() throws Exception {
        Person person = newPerson();
        String secret = call("POST", "/api/v1/me/2fa/setup", null, person.auth()).body()
                .replaceAll(".*\"secret\":\"([A-Z2-7]+)\".*", "$1");
        HttpResponse<String> enabled = call("POST", "/api/v1/me/2fa/enable",
                "{\"code\":\"" + Totp.code(secret, Totp.currentStep()) + "\"}", person.auth());
        String recovery = enabled.body().replaceAll(".*\\[\"([a-z2-9-]+)\".*", "$1");

        // Insígnia "Conta protegida" com XP
        assertThat(call("GET", "/api/v1/users/" + person.username(), null, person.auth()).body()).contains("\"key\":\"protected\"");
        assertThat(call("GET", "/api/v1/users/" + person.username() + "/progress", null, person.auth()).body())
                .contains("\"key\":\"protected\",\"label\":\"Conta protegida (2FA)\",\"points\":25,\"count\":1,\"xp\":25");

        HttpResponse<String> noCode = call("POST", "/api/v1/me/account/password", """
                {"currentPassword":"%s","password":"%s","passwordConfirmation":"%s"}
                """.formatted(PASSWORD, NEW_PASSWORD, NEW_PASSWORD), person.auth());
        assertThat(noCode.statusCode()).isEqualTo(400);
        assertThat(noCode.body()).contains("\"code\":\"Código inválido");
        assertThat(call("POST", "/api/v1/me/account/password", """
                {"currentPassword":"%s","password":"%s","passwordConfirmation":"%s","code":"%s"}
                """.formatted(PASSWORD, NEW_PASSWORD, NEW_PASSWORD, recovery), person.auth()).statusCode()).isEqualTo(204);

        // Desativar tira a insígnia e o XP
        String nextRecovery = call("POST", "/api/v1/me/2fa/recovery-codes",
                "{\"code\":\"" + Totp.code(secret, Totp.currentStep() + 1) + "\"}", person.auth()).body()
                .replaceAll(".*\\[\"([a-z2-9-]+)\".*", "$1");
        assertThat(call("POST", "/api/v1/me/2fa/disable",
                "{\"password\":\"" + NEW_PASSWORD + "\",\"code\":\"" + nextRecovery + "\"}", person.auth()).statusCode()).isEqualTo(204);
        assertThat(call("GET", "/api/v1/users/" + person.username(), null, person.auth()).body()).doesNotContain("\"key\":\"protected\"");
    }
}
