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

/** Campos do perfil, setup, jogos, busca e imagens das publicações. */
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"gearhub.auth.login-limit-per-minute=1000", "gearhub.auth.register-limit-per-minute=1000"})
class ProfileGearIntegrationTest {

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

    @Test
    void perfilGuardaRedesEValidaLinks() throws Exception {
        Person person = newPerson();
        HttpResponse<String> saved = call("PATCH", "/api/v1/users/me", """
                {"website":"https://gearhub.gg","twitter":"@pedro_lm","instagram":"pedro.lm","youtube":"https://youtube.com/@canal"}
                """, person.auth());
        assertThat(saved.statusCode()).isEqualTo(200);
        assertThat(call("GET", "/api/v1/users/" + person.username(), null, Map.of()).body())
                .contains("\"website\":\"https://gearhub.gg\"", "\"twitter\":\"pedro_lm\"", "\"instagram\":\"pedro.lm\"");

        HttpResponse<String> script = call("PATCH", "/api/v1/users/me", "{\"website\":\"javascript:alert(1)\"}", person.auth());
        assertThat(script.statusCode()).isEqualTo(400);
        assertThat(script.body()).contains("Use um endereço começando com https://");
        assertThat(call("PATCH", "/api/v1/users/me", "{\"bio\":\"" + "x".repeat(241) + "\"}", person.auth()).statusCode())
                .isEqualTo(400);
        assertThat(call("PATCH", "/api/v1/users/me", "{\"website\":\"\"}", person.auth()).body()).contains("\"website\":null");
    }

    @Test
    void trocaUsernameSemRepetir() throws Exception {
        Person person = newPerson();
        Person other = newPerson();
        HttpResponse<String> taken = call("PATCH", "/api/v1/users/me", "{\"username\":\"" + other.username() + "\"}", person.auth());
        assertThat(taken.statusCode()).isEqualTo(409);
        assertThat(taken.body()).contains("Esse nome de usuário já está em uso");
        assertThat(call("PATCH", "/api/v1/users/me", "{\"username\":\"Com Espaco\"}", person.auth()).statusCode()).isEqualTo(400);

        String next = "n" + person.username().substring(1);
        assertThat(call("PATCH", "/api/v1/users/me", "{\"username\":\"" + next + "\"}", person.auth()).body())
                .contains("\"username\":\"" + next + "\"");
        assertThat(call("GET", "/api/v1/users/" + person.username(), null, Map.of()).statusCode()).isEqualTo(404);
    }

    @Test
    void setupMostraSoOPreenchidoNoPerfil() throws Exception {
        Person person = newPerson();
        assertThat(call("PUT", "/api/v1/me/setup", "{\"mouse\":\"x\"}", Map.of()).statusCode()).isEqualTo(401);
        assertThat(call("PUT", "/api/v1/me/setup", "{\"mouse\":\"Viper V3\",\"placa_mae\":\"  \",\"gpu\":\"RTX\"}", person.auth()).statusCode())
                .isEqualTo(204);

        assertThat(call("GET", "/api/v1/me/setup", null, person.auth()).body())
                .contains("\"key\":\"placa_mae\",\"label\":\"Placa-mãe\",\"section\":\"hardware\",\"value\":null");
        assertThat(call("GET", "/api/v1/users/" + person.username() + "/setup", null, Map.of()).body())
                .contains("Viper V3", "RTX").doesNotContain("placa_mae");

        HttpResponse<String> unknown = call("PUT", "/api/v1/me/setup", "{\"cadeira\":\"x\"}", person.auth());
        assertThat(unknown.statusCode()).isEqualTo(400);
        assertThat(unknown.body()).contains("Campo de setup inválido");
    }

    @Test
    void jogosTemLimiteDeTresECalculaEdpi() throws Exception {
        Person person = newPerson();
        String settings = "{\"dpi\":800,\"sensitivity\":0.35,\"resolution\":\"1920x1080\",\"aspectRatio\":\"16:9\",\"refreshRate\":240}";
        assertThat(call("PUT", "/api/v1/me/games/valorant", settings, person.auth()).statusCode()).isEqualTo(204);
        assertThat(call("PUT", "/api/v1/me/games/cs2", "{}", person.auth()).statusCode()).isEqualTo(204);
        assertThat(call("PUT", "/api/v1/me/games/fortnite", "{}", person.auth()).statusCode()).isEqualTo(204);

        HttpResponse<String> fourth = call("PUT", "/api/v1/me/games/dota-2", "{}", person.auth());
        assertThat(fourth.statusCode()).isEqualTo(409);
        assertThat(fourth.body()).contains("Você pode cadastrar até 3 jogos");
        assertThat(call("PUT", "/api/v1/me/games/cs2", "{\"dpi\":400}", person.auth()).statusCode()).isEqualTo(204);

        assertThat(call("GET", "/api/v1/users/" + person.username() + "/games", null, Map.of()).body())
                .contains("\"edpi\":280.0", "\"name\":\"Valorant\"", "\"refreshRate\":240");
        assertThat(call("PUT", "/api/v1/me/games/inexistente", "{}", person.auth()).statusCode()).isEqualTo(404);
        assertThat(call("PUT", "/api/v1/me/games/cs2", "{\"dpi\":1}", person.auth()).body()).contains("O DPI deve ser de 50 a 100000");

        assertThat(call("DELETE", "/api/v1/me/games/cs2", null, person.auth()).statusCode()).isEqualTo(204);
        assertThat(call("PUT", "/api/v1/me/games/dota-2", "{}", person.auth()).statusCode()).isEqualTo(204);
    }

    @Test
    void buscaAchaProdutosPessoasEPublicacoes() throws Exception {
        Person person = newPerson();
        String marker = "palavra" + person.username();
        assertThat(call("POST", "/api/v1/posts", "{\"body\":\"Texto com " + marker + "\"}", person.auth()).statusCode()).isEqualTo(201);

        assertThat(call("GET", "/api/v1/search?q=" + marker, null, Map.of()).body()).contains("\"kind\":\"post\"", marker);
        assertThat(call("GET", "/api/v1/search?q=viper", null, Map.of()).body()).contains("razer-viper-v3-pro");
        assertThat(call("GET", "/api/v1/search?q=" + person.username(), null, Map.of()).body())
                .contains("\"people\":[{\"username\":\"" + person.username() + "\"");
        assertThat(call("GET", "/api/v1/search?q=%25%25", null, Map.of()).body())
                .contains("\"products\":[]", "\"people\":[]");

        HttpResponse<String> shortTerm = call("GET", "/api/v1/search?q=a", null, Map.of());
        assertThat(shortTerm.statusCode()).isEqualTo(400);
        assertThat(shortTerm.body()).contains("Digite ao menos 2 caracteres");
    }

    @Test
    void publicacaoSoAceitaAteQuatroImagensEnviadasAqui() throws Exception {
        Person person = newPerson();
        String own = "/uploads/content/" + UUID.randomUUID() + ".png";

        HttpResponse<String> foreign = call("POST", "/api/v1/posts",
                "{\"body\":\"Imagem externa\",\"images\":[\"https://site-ruim.exemplo/a.png\"]}", person.auth());
        assertThat(foreign.statusCode()).isEqualTo(400);
        assertThat(foreign.body()).contains("Imagem inválida");

        String five = String.join(",", java.util.Collections.nCopies(5, "\"" + own + "\""));
        HttpResponse<String> tooMany = call("POST", "/api/v1/posts", "{\"body\":\"Muitas\",\"images\":[" + five + "]}", person.auth());
        assertThat(tooMany.statusCode()).isEqualTo(400);
        assertThat(tooMany.body()).contains("Envie no máximo 4 imagens");

        assertThat(call("POST", "/api/v1/posts", "{\"body\":\"Com imagem\",\"images\":[\"" + own + "\"]}", person.auth()).statusCode())
                .isEqualTo(201);
        assertThat(call("GET", "/api/v1/feed?size=1", null, Map.of()).body()).contains("\"images\":[\"" + own + "\"]");
    }
}
