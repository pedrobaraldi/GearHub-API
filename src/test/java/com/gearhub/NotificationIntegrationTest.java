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

/** Notificações: curtidas, comentários, respostas, seguidores, subida de rank e preferências. */
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"gearhub.auth.register-limit-per-minute=1000", "gearhub.market.collect=false"})
class NotificationIntegrationTest {

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
                {"displayName":"Pessoa %s","username":"p%s","email":"%s@exemplo.com","password":"%s","passwordConfirmation":"%s"}
                """.formatted(id, id, id, PASSWORD, PASSWORD), Map.of());
        assertThat(created.statusCode()).isEqualTo(201);
        Person person = new Person("p" + id, Map.of("Cookie", created.headers().firstValue("set-cookie").orElseThrow().split(";")[0]));
        // Os 100 primeiros viram Fundador e já recebem essa notificação: o teste começa do zero
        call("POST", "/api/v1/me/notifications/read", null, person.auth());
        return person;
    }

    private String newPost(Person author, String body) throws Exception {
        HttpResponse<String> created = call("POST", "/api/v1/posts", "{\"body\":\"" + body + "\",\"topic\":\"discussao\"}", author.auth());
        return created.body().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
    }

    private String inbox(Person person) throws Exception {
        return call("GET", "/api/v1/me/notifications", null, person.auth()).body();
    }

    private int unread(Person person) throws Exception {
        String body = call("GET", "/api/v1/me/notifications/unread-count", null, person.auth()).body();
        return Integer.parseInt(body.replaceAll("\\D", ""));
    }

    @Test
    void curtidaAvisaODonoUmaVezSoEDescurtirApaga() throws Exception {
        Person author = newPerson();
        Person fan = newPerson();
        String post = newPost(author, "Post para curtir");

        call("PUT", "/api/v1/posts/" + post + "/like", null, fan.auth());
        assertThat(inbox(author)).contains("\"type\":\"like\"", "\"username\":\"" + fan.username() + "\"",
                "\"message\":\"curtiu seu post\"", "\"snippet\":\"Post para curtir\"", "\"link\":\"/profile/" + author.username() + "\"");
        assertThat(unread(author)).isEqualTo(1);

        // Curtir de novo não repete; descurtir apaga
        call("DELETE", "/api/v1/posts/" + post + "/like", null, fan.auth());
        assertThat(unread(author)).isZero();
        call("PUT", "/api/v1/posts/" + post + "/like", null, fan.auth());
        call("PUT", "/api/v1/posts/" + post + "/like", null, fan.auth());
        assertThat(unread(author)).isEqualTo(1);

        // A própria curtida não notifica
        call("PUT", "/api/v1/posts/" + post + "/like", null, author.auth());
        assertThat(unread(author)).isEqualTo(1);
    }

    @Test
    void comentarioERespostaAvisamQuemDeve() throws Exception {
        Person author = newPerson();
        Person commenter = newPerson();
        Person replier = newPerson();
        String post = newPost(author, "Post com conversa");

        HttpResponse<String> comment = call("POST", "/api/v1/posts/" + post + "/comments", "{\"body\":\"Ótimo post!\"}", commenter.auth());
        String commentId = comment.body().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        assertThat(inbox(author)).contains("\"message\":\"comentou no seu post\"", "\"snippet\":\"Ótimo post!\"");

        call("POST", "/api/v1/posts/" + post + "/comments", "{\"body\":\"Concordo\",\"parentId\":\"" + commentId + "\"}", replier.auth());
        assertThat(inbox(commenter)).contains("\"message\":\"respondeu seu comentário\"", "\"snippet\":\"Concordo\"");
        assertThat(inbox(author)).contains("\"snippet\":\"Concordo\"");

        call("PUT", "/api/v1/comments/" + commentId + "/like", null, author.auth());
        assertThat(inbox(commenter)).contains("\"message\":\"curtiu seu comentário\"");
    }

    @Test
    void seguidorERankUp() throws Exception {
        Person person = newPerson();
        Person follower = newPerson();
        call("PUT", "/api/v1/users/" + person.username() + "/follow", null, follower.auth());
        assertThat(inbox(person)).contains("\"message\":\"começou a seguir você\"", "\"link\":\"/profile/" + follower.username() + "\"");
        call("DELETE", "/api/v1/users/" + person.username() + "/follow", null, follower.auth());
        assertThat(inbox(person)).doesNotContain("começou a seguir você");

        // Subida de rank vem do banco
        String next = jdbc.queryForObject("SELECT name FROM ranks ORDER BY min_level OFFSET 1 LIMIT 1", String.class);
        jdbc.update("UPDATE users SET rank_name = ? WHERE username = ?", next, person.username());
        assertThat(inbox(person)).contains("\"type\":\"rank_up\"", "\"actor\":null", "\"message\":\"Você subiu para " + next + "!\"",
                "\"link\":\"/niveis/" + person.username() + "\"");
    }

    @Test
    void preferenciasDesligamCadaTipo() throws Exception {
        Person author = newPerson();
        Person fan = newPerson();
        assertThat(call("GET", "/api/v1/me/notifications/settings", null, author.auth()).body())
                .isEqualTo("{\"likes\":true,\"comments\":true,\"follows\":true,\"rankUps\":true}");
        assertThat(call("PUT", "/api/v1/me/notifications/settings",
                "{\"likes\":false,\"comments\":true,\"follows\":true,\"rankUps\":true}", author.auth()).statusCode()).isEqualTo(200);

        String post = newPost(author, "Sem aviso de curtida");
        call("PUT", "/api/v1/posts/" + post + "/like", null, fan.auth());
        call("POST", "/api/v1/posts/" + post + "/comments", "{\"body\":\"Comentário avisa\"}", fan.auth());
        String inbox = inbox(author);
        assertThat(inbox).contains("comentou no seu post").doesNotContain("curtiu seu post");
    }

    @Test
    void marcarComoLidasZeraOContador() throws Exception {
        Person author = newPerson();
        Person fan = newPerson();
        call("PUT", "/api/v1/posts/" + newPost(author, "Um") + "/like", null, fan.auth());
        call("PUT", "/api/v1/posts/" + newPost(author, "Dois") + "/like", null, fan.auth());
        assertThat(unread(author)).isEqualTo(2);
        assertThat(call("POST", "/api/v1/me/notifications/read", null, author.auth()).statusCode()).isEqualTo(204);
        assertThat(unread(author)).isZero();
        assertThat(inbox(author)).contains("\"read\":true").doesNotContain("\"read\":false");
        assertThat(call("GET", "/api/v1/me/notifications", null, Map.of()).statusCode()).isEqualTo(401);
    }
}
