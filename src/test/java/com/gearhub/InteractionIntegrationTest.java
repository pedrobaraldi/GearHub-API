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

/** Editar, apagar, curtir, salvar, comentar e seguir, incluindo o que não pode acontecer. */
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"gearhub.auth.login-limit-per-minute=1000", "gearhub.auth.register-limit-per-minute=1000"})
class InteractionIntegrationTest {

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

    private static String idIn(HttpResponse<String> response) {
        return response.body().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
    }

    private String newPost(Person author) throws Exception {
        HttpResponse<String> created = call("POST", "/api/v1/posts", "{\"body\":\"Texto do post original\"}", author.auth());
        assertThat(created.statusCode()).isEqualTo(201);
        return idIn(created);
    }

    private String scores(String slug, int value) {
        StringBuilder json = new StringBuilder("{");
        jdbc.queryForList("SELECT c.id FROM criteria c JOIN categories k ON k.id = c.category_id WHERE k.slug = ?",
                Integer.class, slug).forEach(id -> json.append("\"").append(id).append("\":").append(value).append(","));
        return json.deleteCharAt(json.length() - 1).append("}").toString();
    }

    private static final String SPECS =
            "{\"modelo\":\"M\",\"formato\":\"F\",\"peso\":\"P\",\"sensor\":\"S\",\"conexao\":\"C\"}";

    private String newReview(Person author, String slug, int value) throws Exception {
        String product = jdbc.queryForObject("SELECT id::text FROM products WHERE slug = ?", String.class, slug);
        HttpResponse<String> created = call("POST", "/api/v1/reviews", """
                {"productId":"%s","title":"Review original","body":"Texto com tamanho suficiente",
                 "scores":%s,"specs":%s,"pros":["Bom"]}
                """.formatted(product, scores("mouse", value), SPECS), author.auth());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return idIn(created);
    }

    private String avgOf(String slug) {
        return jdbc.queryForObject("SELECT avg_score::text FROM products WHERE slug = ?", String.class, slug);
    }

    @Test
    void autorEditaEApagaOProprioPost() throws Exception {
        Person author = newPerson();
        String post = newPost(author);

        assertThat(call("GET", "/api/v1/posts/" + post + "/edit", null, author.auth()).body()).contains("Texto do post original");
        assertThat(call("PATCH", "/api/v1/posts/" + post,
                "{\"body\":\"Texto editado pela autora\",\"topic\":\"mouse\"}", author.auth()).statusCode())
                .isEqualTo(204);
        assertThat(call("GET", "/api/v1/feed?size=20", null, Map.of()).body()).contains("Texto editado pela autora");

        assertThat(call("DELETE", "/api/v1/posts/" + post, null, author.auth()).statusCode()).isEqualTo(204);
        assertThat(call("GET", "/api/v1/feed?size=50", null, Map.of()).body()).doesNotContain(post);
        assertThat(call("PATCH", "/api/v1/posts/" + post, "{\"title\":\"Ainda vivo\",\"body\":\"x\"}", author.auth()).statusCode())
                .isEqualTo(404);
    }

    @Test
    void ninguemAlteraPublicacaoDeOutraPessoa() throws Exception {
        Person author = newPerson();
        Person other = newPerson();
        String post = newPost(author);
        String review = newReview(author, "razer-viper-v3-pro", 8);

        HttpResponse<String> edit = call("PATCH", "/api/v1/posts/" + post, "{\"title\":\"Invadido\",\"body\":\"x\"}", other.auth());
        assertThat(edit.statusCode()).isEqualTo(403);
        assertThat(edit.body()).contains("Você só pode alterar as suas publicações");
        assertThat(call("DELETE", "/api/v1/posts/" + post, null, other.auth()).statusCode()).isEqualTo(403);
        assertThat(call("GET", "/api/v1/posts/" + post + "/edit", null, other.auth()).statusCode()).isEqualTo(403);
        assertThat(call("DELETE", "/api/v1/reviews/" + review, null, other.auth()).statusCode()).isEqualTo(403);
        assertThat(call("PATCH", "/api/v1/reviews/" + review, "{\"title\":\"Invadido\",\"body\":\"Texto suficiente\"}", other.auth()).statusCode())
                .isEqualTo(403);
        assertThat(call("DELETE", "/api/v1/posts/" + post, null, Map.of()).statusCode()).isEqualTo(401);
    }

    @Test
    void editarEApagarReviewRecalculamONotaDoProduto() throws Exception {
        Person author = newPerson();
        String slug = "logitech-g-pro-x-superlight-2";
        String before = avgOf(slug);
        String review = newReview(author, slug, 10);
        assertThat(avgOf(slug)).isNotEqualTo(before);

        String draft = call("GET", "/api/v1/reviews/" + review + "/edit", null, author.auth()).body();
        assertThat(draft).contains("Review original", "\"modelo\":\"M\"", "Bom");

        HttpResponse<String> semNotas = call("PATCH", "/api/v1/reviews/" + review,
                "{\"title\":\"Sem notas\"}", author.auth());
        assertThat(semNotas.statusCode()).isEqualTo(204);

        assertThat(call("PATCH", "/api/v1/reviews/" + review, """
                {"title":"Review editada","body":"Texto com tamanho suficiente","scores":%s,"specs":%s,"cons":["Caro"]}
                """.formatted(scores("mouse", 2), SPECS), author.auth()).statusCode()).isEqualTo(204);
        String edited = call("GET", "/api/v1/feed?size=30", null, Map.of()).body();
        assertThat(edited).contains("Review editada", "\"score\":2.0", "Caro");

        assertThat(call("DELETE", "/api/v1/reviews/" + review, null, author.auth()).statusCode()).isEqualTo(204);
        assertThat(avgOf(slug)).isEqualTo(before);

        // Depois de apagar, a pessoa pode avaliar o produto de novo
        newReview(author, slug, 7);
    }

    @Test
    void curtirNaoContaDuasVezes() throws Exception {
        Person author = newPerson();
        Person fan = newPerson();
        String post = newPost(author);

        assertThat(call("PUT", "/api/v1/posts/" + post + "/like", null, fan.auth()).body()).contains("\"likeCount\":1", "\"liked\":true");
        assertThat(call("PUT", "/api/v1/posts/" + post + "/like", null, fan.auth()).body()).contains("\"likeCount\":1");
        assertThat(call("GET", "/api/v1/feed?size=30", null, fan.auth()).body()).contains("\"likedByMe\":true");
        assertThat(call("DELETE", "/api/v1/posts/" + post + "/like", null, fan.auth()).body()).contains("\"likeCount\":0", "\"liked\":false");
        assertThat(call("DELETE", "/api/v1/posts/" + post + "/like", null, fan.auth()).body()).contains("\"likeCount\":0");
        assertThat(call("PUT", "/api/v1/posts/" + post + "/like", null, Map.of()).statusCode()).isEqualTo(401);
        assertThat(call("PUT", "/api/v1/posts/" + UUID.randomUUID() + "/like", null, fan.auth()).statusCode()).isEqualTo(404);
    }

    @Test
    void salvosSaoPorContaEFuncionamEmPostsEReviews() throws Exception {
        Person author = newPerson();
        Person reader = newPerson();
        Person stranger = newPerson();
        String post = newPost(author);
        String review = newReview(author, "lamzu-maya-x", 8);

        call("PUT", "/api/v1/posts/" + post + "/save", null, reader.auth());
        call("PUT", "/api/v1/reviews/" + review + "/save", null, reader.auth());

        String saved = call("GET", "/api/v1/me/saved", null, reader.auth()).body();
        assertThat(saved).contains(post, review, "\"savedByMe\":true");
        assertThat(call("GET", "/api/v1/me/saved", null, stranger.auth()).body()).doesNotContain(post);
        assertThat(call("GET", "/api/v1/me/saved", null, Map.of()).statusCode()).isEqualTo(401);

        call("DELETE", "/api/v1/posts/" + post + "/save", null, reader.auth());
        assertThat(call("GET", "/api/v1/me/saved", null, reader.auth()).body()).doesNotContain(post).contains(review);
    }

    @Test
    void comentariosFuncionamEContam() throws Exception {
        Person author = newPerson();
        Person reader = newPerson();
        String post = newPost(author);

        HttpResponse<String> blank = call("POST", "/api/v1/posts/" + post + "/comments", "{\"body\":\"  \"}", reader.auth());
        assertThat(blank.statusCode()).isEqualTo(400);
        assertThat(blank.body()).contains("Escreva o comentário");

        HttpResponse<String> created = call("POST", "/api/v1/posts/" + post + "/comments", "{\"body\":\"Muito bom\"}", reader.auth());
        assertThat(created.statusCode()).isEqualTo(201);
        String comment = idIn(created);

        assertThat(call("GET", "/api/v1/posts/" + post + "/comments", null, Map.of()).body())
                .contains("Muito bom", reader.username(), "\"mine\":false");
        assertThat(call("GET", "/api/v1/posts/" + post + "/comments", null, reader.auth()).body()).contains("\"mine\":true");
        assertThat(call("GET", "/api/v1/feed?size=30", null, Map.of()).body()).contains("\"commentCount\":1");

        assertThat(call("PUT", "/api/v1/comments/" + comment + "/like", null, author.auth()).body()).contains("\"likeCount\":1");
        assertThat(call("PUT", "/api/v1/comments/" + comment + "/like", null, author.auth()).body()).contains("\"likeCount\":1");

        assertThat(call("DELETE", "/api/v1/comments/" + comment, null, author.auth()).statusCode()).isEqualTo(403);
        assertThat(call("DELETE", "/api/v1/comments/" + comment, null, reader.auth()).statusCode()).isEqualTo(204);
        assertThat(call("GET", "/api/v1/posts/" + post + "/comments", null, Map.of()).body()).doesNotContain("Muito bom");
        assertThat(jdbc.queryForObject("SELECT comment_count FROM posts WHERE id = ?::uuid", Integer.class, post)).isZero();
        assertThat(call("POST", "/api/v1/posts/" + post + "/comments", "{\"body\":\"x\"}", Map.of()).statusCode()).isEqualTo(401);
    }

    @Test
    void comentarioFuncionaEmReview() throws Exception {
        Person author = newPerson();
        String review = newReview(author, "lamzu-maya-x", 9);
        assertThat(call("POST", "/api/v1/reviews/" + review + "/comments", "{\"body\":\"Boa review\"}", author.auth()).statusCode())
                .isEqualTo(201);
        assertThat(call("GET", "/api/v1/reviews/" + review + "/comments", null, Map.of()).body()).contains("Boa review");
    }

    @Test
    void seguirAtualizaOsContadores() throws Exception {
        Person a = newPerson();
        Person b = newPerson();

        assertThat(call("PUT", "/api/v1/users/" + b.username() + "/follow", null, a.auth()).body())
                .contains("\"followers\":1", "\"followedByMe\":true");
        call("PUT", "/api/v1/users/" + b.username() + "/follow", null, a.auth());

        String seen = call("GET", "/api/v1/users/" + b.username(), null, a.auth()).body();
        assertThat(seen).contains("\"followerCount\":1", "\"followedByMe\":true");
        assertThat(call("GET", "/api/v1/users/" + b.username(), null, Map.of()).body()).contains("\"followedByMe\":false");
        assertThat(call("GET", "/api/v1/users/" + a.username(), null, Map.of()).body()).contains("\"followingCount\":1");

        HttpResponse<String> self = call("PUT", "/api/v1/users/" + a.username() + "/follow", null, a.auth());
        assertThat(self.statusCode()).isEqualTo(400);
        assertThat(self.body()).contains("Você não pode seguir a si mesmo");

        assertThat(call("DELETE", "/api/v1/users/" + b.username() + "/follow", null, a.auth()).body()).contains("\"followers\":0");
        assertThat(call("PUT", "/api/v1/users/" + b.username() + "/follow", null, Map.of()).statusCode()).isEqualTo(401);
        assertThat(call("PUT", "/api/v1/users/naoexiste/follow", null, a.auth()).statusCode()).isEqualTo(404);
    }

    @Test
    void feedIndicaOQueEDeQuemEstaLogado() throws Exception {
        Person author = newPerson();
        Person other = newPerson();
        newPost(author);
        assertThat(call("GET", "/api/v1/feed?author=" + author.username(), null, author.auth()).body()).contains("\"mine\":true");
        assertThat(call("GET", "/api/v1/feed?author=" + author.username(), null, other.auth()).body()).contains("\"mine\":false");
    }
}
