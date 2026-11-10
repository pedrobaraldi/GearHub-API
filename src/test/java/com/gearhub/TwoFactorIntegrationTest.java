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

/** Verificação em duas etapas: ativar, entrar com o código, recuperação e desativar. */
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"gearhub.auth.login-limit-per-minute=1000", "gearhub.auth.register-limit-per-minute=1000"})
class TwoFactorIntegrationTest {

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

    /** Conta com 2FA ativa: o segredo do app e os códigos de recuperação que a pessoa guardou. */
    private record Person(String email, Map<String, String> auth, String secret, List<String> recoveryCodes) {
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

    private static String cookie(HttpResponse<String> response, String name) {
        return response.headers().allValues("set-cookie").stream()
                .filter(value -> value.startsWith(name + "="))
                .map(value -> value.split(";")[0])
                .findFirst().orElse(null);
    }

    private static String code(String secret, long offset) {
        return Totp.code(secret, Totp.currentStep() + offset);
    }

    private Person withTwoFactor() throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String email = id + "@exemplo.com";
        HttpResponse<String> created = call("POST", "/api/v1/auth/register", """
                {"displayName":"Pessoa","username":"p%s","email":"%s","password":"%s","passwordConfirmation":"%s"}
                """.formatted(id, email, PASSWORD, PASSWORD), Map.of());
        assertThat(created.statusCode()).isEqualTo(201);
        Map<String, String> auth = Map.of("Cookie", cookie(created, "gearhub_session"));

        HttpResponse<String> setup = call("POST", "/api/v1/me/2fa/setup", null, auth);
        assertThat(setup.statusCode()).isEqualTo(200);
        assertThat(setup.body()).contains("otpauth://totp/GearHub%3Ap" + id + "?secret=", "issuer=GearHub");
        String secret = setup.body().replaceAll(".*\"secret\":\"([A-Z2-7]+)\".*", "$1");

        HttpResponse<String> enabled = call("POST", "/api/v1/me/2fa/enable", "{\"code\":\"" + code(secret, 0) + "\"}", auth);
        assertThat(enabled.statusCode()).isEqualTo(200);
        List<String> codes = List.of(enabled.body().replaceAll(".*\\[(.*)].*", "$1").replace("\"", "").split(","));
        return new Person(email, auth, secret, codes);
    }

    private HttpResponse<String> login(String email) throws Exception {
        return call("POST", "/api/v1/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}", Map.of());
    }

    private HttpResponse<String> verify(String challenge, String code) throws Exception {
        return call("POST", "/api/v1/auth/2fa/verify", "{\"code\":\"" + code + "\"}",
                challenge == null ? Map.of() : Map.of("Cookie", challenge));
    }

    @Test
    void ativarGuardaOSegredoCriptografadoEDezCodigos() throws Exception {
        Person person = withTwoFactor();
        assertThat(person.recoveryCodes()).hasSize(10).allMatch(code -> code.matches("[a-z2-9]{5}-[a-z2-9]{5}"));
        assertThat(call("GET", "/api/v1/auth/me", null, person.auth()).body()).contains("\"twoFactorEnabled\":true");
        assertThat(call("GET", "/api/v1/me/2fa", null, person.auth()).body())
                .isEqualTo("{\"enabled\":true,\"recoveryCodesLeft\":10}");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT totp_secret_encrypted FROM users WHERE email_hash IS NOT NULL ORDER BY created_at DESC LIMIT 1");
        assertThat((String) row.get("totp_secret_encrypted")).startsWith("v1:").doesNotContain(person.secret());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM two_factor_recovery_codes WHERE code_hash LIKE '%-%'",
                Integer.class)).isZero();

        // Já ativa: não dá para gerar outro segredo por cima
        assertThat(call("POST", "/api/v1/me/2fa/setup", null, person.auth()).statusCode()).isEqualTo(409);
    }

    @Test
    void ativarEncerraAsOutrasSessoes() throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String email = id + "@exemplo.com";
        HttpResponse<String> created = call("POST", "/api/v1/auth/register", """
                {"displayName":"Pessoa","username":"p%s","email":"%s","password":"%s","passwordConfirmation":"%s"}
                """.formatted(id, email, PASSWORD, PASSWORD), Map.of());
        Map<String, String> here = Map.of("Cookie", cookie(created, "gearhub_session"));
        Map<String, String> elsewhere = Map.of("Cookie", cookie(login(email), "gearhub_session"));

        String secret = call("POST", "/api/v1/me/2fa/setup", null, here).body()
                .replaceAll(".*\"secret\":\"([A-Z2-7]+)\".*", "$1");
        assertThat(call("POST", "/api/v1/me/2fa/enable", "{\"code\":\"000000\"}", here).statusCode()).isEqualTo(400);
        assertThat(call("POST", "/api/v1/me/2fa/enable", "{\"code\":\"" + code(secret, 0) + "\"}", here).statusCode())
                .isEqualTo(200);

        assertThat(call("GET", "/api/v1/auth/me", null, here).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/api/v1/auth/me", null, elsewhere).statusCode()).isEqualTo(401);
    }

    @Test
    void senhaCertaSemCodigoNaoAbreSessao() throws Exception {
        Person person = withTwoFactor();
        HttpResponse<String> login = login(person.email());
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.body()).isEqualTo("{\"twoFactorRequired\":true}");
        assertThat(cookie(login, "gearhub_session")).isNull();
        String challenge = cookie(login, "gearhub_2fa");
        assertThat(challenge).isNotNull();
        assertThat(login.headers().allValues("set-cookie").toString()).contains("Path=/api/v1/auth/2fa", "HttpOnly");

        // O cookie de espera não é sessão
        assertThat(call("GET", "/api/v1/auth/me", null, Map.of("Cookie", challenge)).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/api/v1/feed", null, Map.of("Cookie", challenge)).statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM two_factor_challenges WHERE token_hash = ?",
                Integer.class, challenge.split("=", 2)[1])).isZero();

        // Sem o cookie de espera, nenhum código adianta
        assertThat(verify(null, code(person.secret(), 0)).statusCode()).isEqualTo(401);
    }

    @Test
    void codigoCertoAbreSessaoEValeUmaVezSo() throws Exception {
        Person person = withTwoFactor();
        String challenge = cookie(login(person.email()), "gearhub_2fa");

        HttpResponse<String> wrong = verify(challenge, "123456".equals(code(person.secret(), 1)) ? "654321" : "123456");
        assertThat(wrong.statusCode()).isEqualTo(400);
        assertThat(wrong.body()).contains("\"code\":\"Código inválido. Confira o app e tente de novo.\"");

        String next = code(person.secret(), 1);
        HttpResponse<String> ok = verify(challenge, next.substring(0, 3) + " " + next.substring(3));
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).contains("\"twoFactorEnabled\":true");
        String session = cookie(ok, "gearhub_session");
        assertThat(call("GET", "/api/v1/feed", null, Map.of("Cookie", session)).statusCode()).isEqualTo(200);
        assertThat(ok.headers().allValues("set-cookie").toString()).contains("gearhub_2fa=;");

        // O mesmo login em espera não serve duas vezes, e o mesmo código também não
        assertThat(verify(challenge, next).statusCode()).isEqualTo(401);
        String again = cookie(login(person.email()), "gearhub_2fa");
        assertThat(verify(again, next).statusCode()).isEqualTo(400);
    }

    @Test
    void codigoDeRecuperacaoEntraUmaVez() throws Exception {
        Person person = withTwoFactor();
        String recovery = person.recoveryCodes().getFirst();

        HttpResponse<String> ok = verify(cookie(login(person.email()), "gearhub_2fa"), recovery.toUpperCase());
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(call("GET", "/api/v1/me/2fa", null, person.auth()).body()).contains("\"recoveryCodesLeft\":9");

        assertThat(verify(cookie(login(person.email()), "gearhub_2fa"), recovery).statusCode()).isEqualTo(400);
    }

    @Test
    void cincoErrosCancelamOLoginEmEspera() throws Exception {
        Person person = withTwoFactor();
        String challenge = cookie(login(person.email()), "gearhub_2fa");
        for (int i = 0; i < 4; i++) {
            assertThat(verify(challenge, "abcde-fghij").statusCode()).isEqualTo(400);
        }
        HttpResponse<String> last = verify(challenge, "abcde-fghij");
        assertThat(last.statusCode()).isEqualTo(401);
        assertThat(last.body()).contains("Muitas tentativas com o código errado. Entre de novo.");
        // Nem o código certo salva: precisa da senha de novo
        assertThat(verify(challenge, code(person.secret(), 1)).statusCode()).isEqualTo(401);
    }

    @Test
    void novosCodigosDeRecuperacaoAnulamOsAntigos() throws Exception {
        Person person = withTwoFactor();
        HttpResponse<String> renewed = call("POST", "/api/v1/me/2fa/recovery-codes",
                "{\"code\":\"" + person.recoveryCodes().getFirst() + "\"}", person.auth());
        assertThat(renewed.statusCode()).isEqualTo(200);
        assertThat(renewed.body()).doesNotContain(person.recoveryCodes().get(1));
        assertThat(verify(cookie(login(person.email()), "gearhub_2fa"), person.recoveryCodes().get(1)).statusCode())
                .isEqualTo(400);
    }

    @Test
    void desativarPedeSenhaECodigo() throws Exception {
        Person person = withTwoFactor();
        String recovery = person.recoveryCodes().getFirst();

        HttpResponse<String> noPassword = call("POST", "/api/v1/me/2fa/disable",
                "{\"code\":\"" + recovery + "\"}", person.auth());
        assertThat(noPassword.statusCode()).isEqualTo(400);
        assertThat(noPassword.body()).contains("\"password\":\"Senha incorreta\"");
        assertThat(call("POST", "/api/v1/me/2fa/disable", "{\"password\":\"" + PASSWORD + "\",\"code\":\"000000\"}",
                person.auth()).statusCode()).isEqualTo(400);

        assertThat(call("POST", "/api/v1/me/2fa/disable",
                "{\"password\":\"" + PASSWORD + "\",\"code\":\"" + recovery + "\"}", person.auth()).statusCode())
                .isEqualTo(204);
        assertThat(call("GET", "/api/v1/me/2fa", null, person.auth()).body())
                .isEqualTo("{\"enabled\":false,\"recoveryCodesLeft\":0}");

        // Sem 2FA, a senha volta a bastar
        HttpResponse<String> login = login(person.email());
        assertThat(cookie(login, "gearhub_session")).isNotNull();
        assertThat(login.body()).contains("\"twoFactorEnabled\":false");
    }

    @Test
    void semContaNaoGerenciaA2fa() throws Exception {
        assertThat(call("GET", "/api/v1/me/2fa", null, Map.of()).statusCode()).isEqualTo(401);
        assertThat(call("POST", "/api/v1/me/2fa/setup", null, Map.of()).statusCode()).isEqualTo(401);
    }
}
