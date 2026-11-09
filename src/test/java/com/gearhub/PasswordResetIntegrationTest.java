package com.gearhub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gearhub.mail.MailProperties;
import com.gearhub.mail.MailService;
import com.sun.net.httpserver.HttpServer;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Recuperação de senha, com um Resend falso. */
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"gearhub.auth.login-limit-per-minute=1000", "gearhub.auth.register-limit-per-minute=1000",
                "gearhub.auth.reset-limit-per-minute=1000"})
class PasswordResetIntegrationTest {

    private static final String PASSWORD = "Senha-Forte-123";
    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String NEW_PASSWORD = "Outra-Senha-456";
    private static final List<String> MAILS = new CopyOnWriteArrayList<>();
    private static final List<String> ATTEMPTS = new CopyOnWriteArrayList<>();
    private static final AtomicInteger FAILURES_LEFT = new AtomicInteger();
    private static volatile int failureStatus = 503;
    private static final HttpServer FAKE_RESEND = startFakeResend();

    private static HttpServer startFakeResend() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/emails", exchange -> {
                String mail = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                ATTEMPTS.add(exchange.getRequestHeaders().getFirst("Idempotency-Key") + " " + mail);
                if (FAILURES_LEFT.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
                    exchange.sendResponseHeaders(failureStatus, -1);
                    exchange.close();
                    return;
                }
                MAILS.add(mail);
                byte[] body = "{\"id\":\"1\"}".getBytes(StandardCharsets.UTF_8);
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
        registry.add("gearhub.mail.resend-api-key", () -> "chave-teste");
        registry.add("gearhub.mail.reset-template", () -> "redefinir-senha");
        registry.add("gearhub.mail.api-uri", () -> "http://localhost:" + FAKE_RESEND.getAddress().getPort() + "/emails");
    }

    @AfterAll
    static void stopPostgres() throws IOException {
        FAKE_RESEND.stop(0);
        POSTGRES.close();
    }

    private record Person(String username, Map<String, String> auth) {
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

    private Person newPerson() throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        HttpResponse<String> response = call("POST", "/api/v1/auth/register", """
                {"displayName":"Pessoa","username":"p%s","email":"%s@exemplo.com","password":"%s","passwordConfirmation":"%s"}
                """.formatted(id, id, PASSWORD, PASSWORD), Map.of());
        assertThat(response.statusCode()).isEqualTo(201);
        return new Person("p" + id, Map.of("Cookie", response.headers().firstValue("set-cookie").orElseThrow().split(";")[0]));
    }

    private String emailOf(Person person) {
        return person.username().substring(1) + "@exemplo.com";
    }

    private HttpResponse<String> forgot(String email) throws Exception {
        return call("POST", "/api/v1/auth/forgot-password", "{\"email\":\"" + email + "\"}", Map.of());
    }

    /** O envio é em segundo plano: espera o e-mail da pessoa chegar. */
    private String tokenFromMail(Person person, int mailsBefore) throws Exception {
        for (int i = 0; i < 100; i++) {
            List<String> found = MAILS.stream().filter(mail -> mail.contains(emailOf(person))).toList();
            if (found.size() > mailsBefore) {
                return found.getLast().replaceAll(".*redefinir-senha\\?token=([A-Za-z0-9_-]+).*", "$1");
            }
            Thread.sleep(Duration.ofMillis(30));
        }
        throw new AssertionError("E-mail não chegou");
    }

    private String askToken(Person person) throws Exception {
        int before = (int) MAILS.stream().filter(mail -> mail.contains(emailOf(person))).count();
        assertThat(forgot(emailOf(person)).statusCode()).isEqualTo(200);
        return tokenFromMail(person, before);
    }

    private HttpResponse<String> reset(String token, String password) throws Exception {
        return call("POST", "/api/v1/auth/reset-password", """
                {"token":"%s","password":"%s","passwordConfirmation":"%s"}
                """.formatted(token, password, password), Map.of());
    }

    private int login(Person person, String password) throws Exception {
        return call("POST", "/api/v1/auth/login",
                "{\"email\":\"" + emailOf(person) + "\",\"password\":\"" + password + "\"}", Map.of()).statusCode();
    }

    @Test
    void trocaASenhaEEncerraAsSessoes() throws Exception {
        Person person = newPerson();
        assertThat(call("GET", "/api/v1/auth/me", null, person.auth()).statusCode()).isEqualTo(200);

        assertThat(reset(askToken(person), NEW_PASSWORD).statusCode()).isEqualTo(204);

        assertThat(login(person, PASSWORD)).isEqualTo(401);
        assertThat(login(person, NEW_PASSWORD)).isEqualTo(200);
        assertThat(call("GET", "/api/v1/auth/me", null, person.auth()).statusCode()).isEqualTo(401);
    }

    @Test
    void respondeIgualComOuSemContaESoEnviaParaQuemExiste() throws Exception {
        Person person = newPerson();
        String withAccount = forgot(emailOf(person)).body();
        String ghost = "ninguem" + UUID.randomUUID().toString().substring(0, 8) + "@exemplo.com";
        int before = MAILS.size();

        assertThat(forgot(ghost).body()).isEqualTo(withAccount);
        Thread.sleep(Duration.ofMillis(200));
        assertThat(MAILS.stream().skip(before).filter(mail -> mail.contains(ghost))).isEmpty();
    }

    @Test
    void linkValeUmaVezENaoFicaNoBanco() throws Exception {
        Person person = newPerson();
        String token = askToken(person);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_tokens WHERE token_hash = ?", Integer.class, token))
                .isZero();

        assertThat(reset(token, NEW_PASSWORD).statusCode()).isEqualTo(204);
        HttpResponse<String> again = reset(token, "Mais-Uma-Senha-789");
        assertThat(again.statusCode()).isEqualTo(400);
        assertThat(again.body()).contains("Este link é inválido ou expirou");
    }

    @Test
    void pedidoNovoCancelaOLinkAnterior() throws Exception {
        Person person = newPerson();
        String first = askToken(person);
        String second = askToken(person);

        assertThat(reset(first, NEW_PASSWORD).statusCode()).isEqualTo(400);
        assertThat(reset(second, NEW_PASSWORD).statusCode()).isEqualTo(204);
    }

    @Test
    void linkExpiradoNaoFunciona() throws Exception {
        Person person = newPerson();
        String token = askToken(person);
        jdbc.update("UPDATE email_tokens SET expires_at = now() - interval '1 minute' WHERE purpose = 'reset_password'");

        assertThat(reset(token, NEW_PASSWORD).statusCode()).isEqualTo(400);
        assertThat(login(person, PASSWORD)).isEqualTo(200);
    }

    @Test
    void senhaNovaSegueAsRegras() throws Exception {
        Person person = newPerson();
        String token = askToken(person);

        HttpResponse<String> weak = reset(token, "fraca");
        assertThat(weak.statusCode()).isEqualTo(400);
        assertThat(weak.body()).contains("\"password\"");

        HttpResponse<String> different = call("POST", "/api/v1/auth/reset-password", """
                {"token":"%s","password":"%s","passwordConfirmation":"Outra-Coisa-1!"}
                """.formatted(token, NEW_PASSWORD), Map.of());
        assertThat(different.statusCode()).isEqualTo(400);
        assertThat(different.body()).contains("As senhas não coincidem");

        assertThat(reset(token, NEW_PASSWORD).statusCode()).isEqualTo(204);
    }

    private List<String> attemptsOf(Person person) {
        return ATTEMPTS.stream().filter(attempt -> attempt.contains(emailOf(person))).toList();
    }

    @Test
    void templateRecebeONomeEscapado() throws Exception {
        Person person = newPerson();
        assertThat(call("PATCH", "/api/v1/users/me", "{\"displayName\":\"<b>Pessoa</b>\"}", person.auth()).statusCode())
                .isEqualTo(200);

        askToken(person);

        String mail = attemptsOf(person).getLast();
        assertThat(mail).contains("\"template\"", "redefinir-senha", "\"MINUTOS\":\"30\"", "&lt;b&gt;Pessoa&lt;/b&gt;")
                .doesNotContain("<b>", "\"text\"");
    }

    @Test
    void semTemplateEnviaTextoSimples() throws Exception {
        String to = "texto" + UUID.randomUUID().toString().substring(0, 8) + "@exemplo.com";
        new MailService(new MailProperties("chave", "GearHub <a@exemplo.com>",
                "http://localhost:" + FAKE_RESEND.getAddress().getPort() + "/emails", "http://site", false, ""))
                .sendInBackground(to, "Assunto", "Corpo do e-mail");

        for (int i = 0; i < 100 && MAILS.stream().noneMatch(mail -> mail.contains(to)); i++) {
            Thread.sleep(Duration.ofMillis(30));
        }
        assertThat(MAILS.stream().filter(mail -> mail.contains(to)).findFirst().orElseThrow())
                .contains("\"text\":\"Corpo do e-mail\"").doesNotContain("template");
    }

    @Test
    void falhaTemporariaTentaDeNovoComAMesmaChave() throws Exception {
        Person person = newPerson();
        failureStatus = 503;
        FAILURES_LEFT.set(2);

        assertThat(reset(askToken(person), NEW_PASSWORD).statusCode()).isEqualTo(204);

        List<String> attempts = attemptsOf(person);
        assertThat(attempts).hasSize(3);
        assertThat(attempts.stream().map(attempt -> attempt.split(" ")[0]).distinct()).hasSize(1);
    }

    @Test
    void recusaDoResendNaoTentaDeNovo() throws Exception {
        Person person = newPerson();
        failureStatus = 422;
        FAILURES_LEFT.set(5);

        assertThat(forgot(emailOf(person)).statusCode()).isEqualTo(200);
        Thread.sleep(Duration.ofMillis(900));
        FAILURES_LEFT.set(0);

        assertThat(attemptsOf(person)).hasSize(1);
    }

    @Test
    void emailInvalidoEProducaoSemChave() throws Exception {
        assertThat(forgot("nao-e-email").statusCode()).isEqualTo(400);
        assertThatThrownBy(() -> new MailProperties("", "a", "b", "c", true, ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("RESEND_API_KEY");
    }
}
