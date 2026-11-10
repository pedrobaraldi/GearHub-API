package com.gearhub;

import static org.assertj.core.api.Assertions.assertThat;

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

/** Denúncias e a API do painel de admin (pré-pronta): denunciados, ficha do perfil e auditoria. */
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"gearhub.auth.register-limit-per-minute=1000", "gearhub.market.collect=false"})
class ReportAdminIntegrationTest {

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

    private record Person(String username, Map<String, String> auth) {
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

    private Person newPerson() throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        HttpResponse<String> created = call("POST", "/api/v1/auth/register", """
                {"displayName":"Pessoa","username":"p%s","email":"%s@exemplo.com","password":"%s","passwordConfirmation":"%s"}
                """.formatted(id, id, PASSWORD, PASSWORD), Map.of());
        assertThat(created.statusCode()).isEqualTo(201);
        return new Person("p" + id, Map.of("Cookie", created.headers().firstValue("set-cookie").orElseThrow().split(";")[0]));
    }

    private Person admin() throws Exception {
        Person admin = newPerson();
        jdbc.update("UPDATE users SET role = 'admin', two_factor_enabled_at = now() WHERE username = ?", admin.username());
        return admin;
    }

    private String newPost(Person author, String body) throws Exception {
        return call("POST", "/api/v1/posts", "{\"body\":\"" + body + "\",\"topic\":\"discussao\"}", author.auth()).body()
                .replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
    }

    private HttpResponse<String> report(Person reporter, String kind, String id, String reason) throws Exception {
        return call("POST", "/api/v1/reports", "{\"kind\":\"" + kind + "\",\"id\":\"" + id + "\",\"reason\":\"" + reason
                + "\",\"details\":\"Propaganda de site estranho\"}", reporter.auth());
    }

    @Test
    void motivosDeDenuncia() throws Exception {
        assertThat(call("GET", "/api/v1/report-reasons", null, newPerson().auth()).body())
                .startsWith("[{\"key\":\"spam\",\"label\":\"Spam ou propaganda\"}").contains("\"key\":\"impersonation\"");
    }

    @Test
    void denunciarPostUmaVezENuncaOProprio() throws Exception {
        Person author = newPerson();
        Person reporter = newPerson();
        String post = newPost(author, "Compre seguidores aqui");

        assertThat(report(reporter, "post", post, "spam").statusCode()).isEqualTo(201);
        assertThat(report(reporter, "post", post, "spam").statusCode()).isEqualTo(409);
        assertThat(report(author, "post", post, "spam").statusCode()).isEqualTo(400);
        assertThat(report(reporter, "post", UUID.randomUUID().toString(), "spam").statusCode()).isEqualTo(404);
        assertThat(report(reporter, "post", post, "motivo-inventado").statusCode()).isEqualTo(400);
        assertThat(call("POST", "/api/v1/reports", "{\"kind\":\"post\",\"id\":\"" + post + "\",\"reason\":\"spam\"}", Map.of())
                .statusCode()).isEqualTo(401);
    }

    @Test
    void denunciarPerfilPeloNomeDeUsuario() throws Exception {
        Person target = newPerson();
        Person reporter = newPerson();
        assertThat(call("POST", "/api/v1/reports", "{\"kind\":\"user\",\"username\":\"" + target.username() + "\",\"reason\":\"impersonation\"}",
                reporter.auth()).statusCode()).isEqualTo(201);
        assertThat(call("POST", "/api/v1/reports", "{\"kind\":\"user\",\"username\":\"" + reporter.username() + "\",\"reason\":\"spam\"}",
                reporter.auth()).statusCode()).isEqualTo(400);
    }

    @Test
    void painelSoParaAdminComDuasEtapas() throws Exception {
        Person member = newPerson();
        assertThat(call("GET", "/api/v1/admin/overview", null, member.auth()).statusCode()).isEqualTo(403);
        jdbc.update("UPDATE users SET role = 'admin' WHERE username = ?", member.username());
        HttpResponse<String> no2fa = call("GET", "/api/v1/admin/overview", null, member.auth());
        assertThat(no2fa.statusCode()).isEqualTo(403);
        assertThat(no2fa.body()).contains("Ative a verificação em duas etapas");
        assertThat(call("GET", "/api/v1/admin/overview", null, Map.of()).statusCode()).isEqualTo(401);
    }

    @Test
    void adminVeAgrupaEFechaAsDenuncias() throws Exception {
        Person author = newPerson();
        Person first = newPerson();
        Person second = newPerson();
        Person admin = admin();
        String post = newPost(author, "Post muito denunciado");
        report(first, "post", post, "spam");
        report(second, "post", post, "scam");

        assertThat(call("GET", "/api/v1/admin/overview", null, admin.auth()).body()).contains("\"pendingReports\":");

        String pending = call("GET", "/api/v1/admin/reports?username=" + author.username(), null, admin.auth()).body();
        assertThat(pending).contains("\"kind\":\"post\"", "\"targetId\":\"" + post + "\"", "\"snippet\":\"Post muito denunciado\"",
                "\"authorUsername\":\"" + author.username() + "\"", "\"reports\":2", "\"spam\":1", "\"scam\":1",
                "Propaganda de site estranho", "\"link\":\"/profile/" + author.username() + "\"", "\"status\":\"pending\"");

        assertThat(call("POST", "/api/v1/admin/reports/review",
                "{\"kind\":\"post\",\"targetId\":\"" + post + "\",\"status\":\"resolved\",\"note\":\"Post apagado\"}", admin.auth()).body())
                .isEqualTo("{\"closed\":2}");
        assertThat(call("GET", "/api/v1/admin/reports?username=" + author.username(), null, admin.auth()).body()).isEqualTo("[]");
        assertThat(call("GET", "/api/v1/admin/reports?status=resolved&username=" + author.username(), null, admin.auth()).body())
                .contains("\"targetId\":\"" + post + "\"", "\"status\":\"resolved\"");
        assertThat(call("POST", "/api/v1/admin/reports/review",
                "{\"kind\":\"post\",\"targetId\":\"" + post + "\",\"status\":\"resolved\"}", admin.auth()).statusCode()).isEqualTo(404);
    }

    @Test
    void fichaEAuditoriaDoPerfil() throws Exception {
        Person person = newPerson();
        Person reporter = newPerson();
        Person admin = admin();
        String post = newPost(person, "Primeiro post");
        call("PATCH", "/api/v1/posts/" + post, "{\"body\":\"Primeiro post editado\",\"topic\":\"discussao\"}", person.auth());
        call("POST", "/api/v1/posts/" + post + "/comments", "{\"body\":\"Comentei\"}", reporter.auth());
        call("DELETE", "/api/v1/posts/" + post, null, person.auth());
        report(reporter, "user", null, "spam");
        call("POST", "/api/v1/reports", "{\"kind\":\"user\",\"username\":\"" + person.username() + "\",\"reason\":\"spam\"}", reporter.auth());

        String file = call("GET", "/api/v1/admin/users/" + person.username(), null, admin.auth()).body();
        assertThat(file).contains("\"username\":\"" + person.username() + "\"", "\"email\":\"" + person.username().substring(1) + "@exemplo.com\"",
                "\"pendingReportsAgainst\":1", "\"twoFactorEnabled\":false");

        call("PATCH", "/api/v1/users/me", "{\"bio\":\"Bio nova\"}", person.auth());

        String activity = call("GET", "/api/v1/admin/users/" + person.username() + "/activity", null, admin.auth()).body();
        assertThat(activity).contains("\"action\":\"post.delete\"", "\"action\":\"post.update\"", "\"action\":\"post.create\"",
                "\"text\":\"Primeiro post\"", "\"createdAt\":\"20");
        // Histórico: a edição guarda o antes e o depois, e o perfil guarda o que mudou
        assertThat(activity).contains("\"before\":{", "\"texto\":\"Primeiro post\"", "\"texto\":\"Primeiro post editado\"",
                "\"action\":\"profile.update\"", "\"bio\":{\"de\":null,\"para\":\"Bio nova\"}");
        // O post apagado continua guardado (exclusão lógica)
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM posts WHERE body = 'Primeiro post editado'", Boolean.class)).isTrue();
        // Mais novo primeiro
        assertThat(activity.indexOf("post.delete")).isLessThan(activity.indexOf("post.create"));

        assertThat(call("GET", "/api/v1/admin/users/" + reporter.username() + "/activity", null, admin.auth()).body())
                .contains("\"action\":\"comment.create\"", "\"action\":\"report.create\"");
        assertThat(call("GET", "/api/v1/admin/users/nao-existe", null, admin.auth()).statusCode()).isEqualTo(404);
    }
}
