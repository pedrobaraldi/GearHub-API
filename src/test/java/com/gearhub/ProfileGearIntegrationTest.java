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
    void setupAceitaAteDozeFotosEnviadasAoSite() throws Exception {
        Person person = newPerson();
        String own = java.util.stream.IntStream.rangeClosed(1, 12)
                .mapToObj(i -> "\"/uploads/content/" + java.util.UUID.randomUUID() + ".jpg\"")
                .collect(java.util.stream.Collectors.joining(","));
        assertThat(call("PUT", "/api/v1/me/setup/photos", "{\"photos\":[" + own + "]}", person.auth()).statusCode()).isEqualTo(204);
        assertThat(call("GET", "/api/v1/users/" + person.username() + "/setup/photos", null, Map.of()).body().split(",")).hasSize(12);

        HttpResponse<String> many = call("PUT", "/api/v1/me/setup/photos",
                "{\"photos\":[" + own + ",\"/uploads/content/" + java.util.UUID.randomUUID() + ".jpg\"]}", person.auth());
        assertThat(many.statusCode()).isEqualTo(400);
        assertThat(many.body()).contains("Envie até 12 fotos");

        HttpResponse<String> external = call("PUT", "/api/v1/me/setup/photos", "{\"photos\":[\"https://exemplo.com/a.jpg\"]}", person.auth());
        assertThat(external.statusCode()).isEqualTo(400);
        assertThat(external.body()).contains("Envie as fotos pelo site");
        assertThat(call("PUT", "/api/v1/me/setup/photos", "{\"photos\":[]}", Map.of()).statusCode()).isEqualTo(401);
    }

    @Test
    void jogosTemLimiteDeTresECalculaEdpi() throws Exception {
        Person person = newPerson();
        String settings = "{\"settings\":{\"dpi\":\"800\",\"sensitivity\":\"0,35\",\"walk\":\"L-Shift\"}}";
        assertThat(call("PUT", "/api/v1/me/games/valorant", settings, person.auth()).statusCode()).isEqualTo(204);
        assertThat(call("PUT", "/api/v1/me/games/cs2", "{}", person.auth()).statusCode()).isEqualTo(204);
        assertThat(call("PUT", "/api/v1/me/games/fortnite", "{}", person.auth()).statusCode()).isEqualTo(204);

        HttpResponse<String> fourth = call("PUT", "/api/v1/me/games/apex-legends", "{}", person.auth());
        assertThat(fourth.statusCode()).isEqualTo(409);
        assertThat(fourth.body()).contains("Você pode cadastrar até 3 jogos");

        assertThat(call("GET", "/api/v1/users/" + person.username() + "/games", null, Map.of()).body())
                .contains("\"label\":\"eDPI\",\"value\":\"280\"", "\"name\":\"Valorant\"", "\"label\":\"Keybinds\"", "L-Shift");

        assertThat(call("DELETE", "/api/v1/me/games/cs2", null, person.auth()).statusCode()).isEqualTo(204);
        assertThat(call("PUT", "/api/v1/me/games/apex-legends", "{}", person.auth()).statusCode()).isEqualTo(204);
    }

    @Test
    void cadaJogoTemSeusCamposEValidaOsValores() throws Exception {
        Person person = newPerson();
        assertThat(call("GET", "/api/v1/games", null, Map.of()).body())
                .contains("Rainbow Six Siege", "Tactical Shooter").doesNotContain("dota-2", "warzone");
        assertThat(call("GET", "/api/v1/games/cs2", null, Map.of()).body())
                .contains("Crosshair", "Viewmodel", "Advanced Video", "Boost Player Contrast", "\"kind\":\"choice\"");
        assertThat(call("GET", "/api/v1/games/fortnite", null, Map.of()).body())
                .contains("3D Resolution").doesNotContain("Crosshair", "Viewmodel");
        assertThat(call("GET", "/api/v1/games/valorant", null, Map.of()).body())
                .contains("Keybinds", "Equip Spike").doesNotContain("Video Settings");

        assertThat(call("GET", "/api/v1/games/dota-2", null, Map.of()).statusCode()).isEqualTo(404);
        assertThat(call("PUT", "/api/v1/me/games/dota-2", "{}", person.auth()).statusCode()).isEqualTo(404);
        assertThat(call("PUT", "/api/v1/me/games/inexistente", "{}", person.auth()).statusCode()).isEqualTo(404);

        assertThat(call("PUT", "/api/v1/me/games/cs2", "{\"settings\":{\"dpi\":\"muito\"}}", person.auth()).body())
                .contains("Informe um número");
        assertThat(call("PUT", "/api/v1/me/games/cs2", "{\"settings\":{\"vsync\":\"Talvez\"}}", person.auth()).body())
                .contains("Opção inválida");
        assertThat(call("PUT", "/api/v1/me/games/fortnite", "{\"settings\":{\"ch_gap\":\"1\"}}", person.auth()).body())
                .contains("Campo inválido para este jogo");
        assertThat(call("PUT", "/api/v1/me/games/cs2", "{\"settings\":{\"edpi\":\"9\",\"dpi\":\"400\"}}", person.auth()).statusCode())
                .isEqualTo(204);
        assertThat(call("GET", "/api/v1/users/" + person.username() + "/games", null, Map.of()).body())
                .doesNotContain("\"edpi\":\"9\"");
    }

    @Test
    void equipamentoDoJogoApontaParaProdutoCadastrado() throws Exception {
        Person person = newPerson();
        String mouse = jdbc.queryForObject("SELECT id::text FROM products WHERE slug = 'razer-viper-v3-pro'", String.class);
        String pad = jdbc.queryForObject("SELECT id::text FROM products WHERE slug = 'artisan-zero-xsoft'", String.class);

        HttpResponse<String> wrong = call("PUT", "/api/v1/me/games/cs2", "{\"gear\":{\"mouse\":{\"productId\":\"" + pad + "\"}}}", person.auth());
        assertThat(wrong.statusCode()).isEqualTo(400);
        assertThat(wrong.body()).contains("Produto não encontrado nesta categoria");
        assertThat(call("PUT", "/api/v1/me/games/cs2", "{\"gear\":{\"gpu\":{\"productId\":\"" + pad + "\"}}}", person.auth()).body())
                .contains("Categoria inválida");

        assertThat(call("PUT", "/api/v1/me/games/cs2", "{\"gear\":{\"mouse\":{\"productId\":\"" + mouse + "\"}}}", person.auth()).statusCode()).isEqualTo(204);
        assertThat(call("GET", "/api/v1/users/" + person.username() + "/games", null, Map.of()).body())
                .contains("\"slug\":\"razer-viper-v3-pro\"", "\"categoryName\":\"Mouse\"");

        // Salvar sem o equipamento tira a escolha anterior
        assertThat(call("PUT", "/api/v1/me/games/cs2", "{}", person.auth()).statusCode()).isEqualTo(204);
        assertThat(call("GET", "/api/v1/users/" + person.username() + "/games", null, Map.of()).body())
                .doesNotContain("razer-viper-v3-pro");
        assertThat(call("DELETE", "/api/v1/me/games/cs2", null, person.auth()).statusCode()).isEqualTo(204);
    }

    @Test
    void seletorMostraReviewsMaisPopularesEGuardaAEscolhida() throws Exception {
        Person person = newPerson();
        String top = jdbc.queryForObject("""
                SELECT r.id::text FROM reviews r JOIN products p ON p.id = r.product_id
                JOIN categories c ON c.id = p.category_id
                WHERE c.slug = 'mouse' AND r.deleted_at IS NULL ORDER BY r.like_count DESC, r.comment_count DESC LIMIT 1
                """, String.class);
        String product = jdbc.queryForObject("SELECT product_id::text FROM reviews WHERE id = ?::uuid", String.class, top);
        // Uma curtida real deixa essa review isolada no topo
        assertThat(call("PUT", "/api/v1/reviews/" + top + "/like", null, person.auth()).statusCode()).isEqualTo(200);
        String other = jdbc.queryForObject("SELECT id::text FROM products WHERE slug = 'artisan-zero-xsoft'", String.class);

        HttpResponse<String> options = call("GET", "/api/v1/gear-options?category=mouse", null, Map.of());
        assertThat(options.statusCode()).isEqualTo(200);
        assertThat(options.body().indexOf("\"reviewId\":\"" + top)).isBetween(0, 300);
        java.util.List<Integer> likes = java.util.regex.Pattern.compile("\"likeCount\":(\\d+)").matcher(options.body()).results()
                .map(match -> Integer.valueOf(match.group(1))).toList();
        assertThat(likes).isSortedAccordingTo(java.util.Comparator.reverseOrder());

        assertThat(call("GET", "/api/v1/gear-options?category=mouse&q=viper", null, Map.of()).body())
                .contains("Viper V3 Pro").doesNotContain("Maya X");
        assertThat(call("GET", "/api/v1/gear-options?category=gpu", null, Map.of()).statusCode()).isEqualTo(400);

        assertThat(call("PUT", "/api/v1/me/games/cs2", "{\"gear\":{\"mouse\":{\"productId\":\"" + product + "\",\"reviewId\":\"" + top + "\"}}}", person.auth()).statusCode())
                .isEqualTo(204);
        assertThat(call("GET", "/api/v1/users/" + person.username() + "/games", null, Map.of()).body())
                .contains("\"reviewId\":\"" + top + "\"", "\"reviewAuthor\"");

        HttpResponse<String> wrong = call("PUT", "/api/v1/me/games/cs2", "{\"gear\":{\"mousepad\":{\"productId\":\"" + other + "\",\"reviewId\":\"" + top + "\"}}}", person.auth());
        assertThat(wrong.statusCode()).isEqualTo(400);
        assertThat(wrong.body()).contains("Essa review não é deste produto");
    }

    @Test
    void produtoTemPaginaEReviewsProprias() throws Exception {
        HttpResponse<String> product = call("GET", "/api/v1/products/razer-viper-v3-pro", null, Map.of());
        assertThat(product.statusCode()).isEqualTo(200);
        assertThat(product.body()).contains("Viper V3 Pro", "\"category\":\"Mouse\"", "\"label\":\"Sensor\",\"value\":9.8");
        assertThat(call("GET", "/api/v1/products/inexistente", null, Map.of()).statusCode()).isEqualTo(404);

        // Mouse e mousepad são do mesmo grupo; teclado e headset não
        String pad = jdbc.queryForObject("SELECT id::text FROM products WHERE slug = 'artisan-zero-xsoft'", String.class);
        assertThat(call("POST", "/api/v1/reviews", """
                {"productId":"%s","title":"Review do mousepad",
                 "specs":{"modelo":"Zero","valor":"R$ 400","dimensoes":"49x42","superficie":"Control","material":"Tecido"}}
                """.formatted(pad), newPerson().auth()).statusCode()).isEqualTo(201);

        String feed = call("GET", "/api/v1/feed?product=razer-viper-v3-pro&size=50", null, Map.of()).body();
        assertThat(feed).contains("Viper V3 Pro", "Maya X", "Review do mousepad")
                .doesNotContain("\"kind\":\"post\"", "Wooting 80HE", "Maxwell");
        assertThat(call("GET", "/api/v1/feed?product=wooting-wooting-80he&size=50", null, Map.of()).body())
                .contains("Wooting 80HE").doesNotContain("Viper V3 Pro", "Review do mousepad");
    }

    @Test
    void feedBuscaPorTextoProdutoEAutor() throws Exception {
        Person person = newPerson();
        String marker = "marcador" + person.username();
        assertThat(call("POST", "/api/v1/posts", "{\"body\":\"Texto com " + marker + "\"}", person.auth()).statusCode()).isEqualTo(201);

        assertThat(call("GET", "/api/v1/feed?q=" + marker, null, Map.of()).body()).contains(marker);
        assertThat(call("GET", "/api/v1/feed?q=viper%20v3", null, Map.of()).body()).contains("Viper V3 Pro").doesNotContain(marker);
        assertThat(call("GET", "/api/v1/feed?q=Pedro", null, Map.of()).body()).contains("\"username\":\"pedrolm\"");
        assertThat(call("GET", "/api/v1/feed?q=" + java.util.UUID.randomUUID(), null, Map.of()).body()).contains("\"items\":[]");
        // % digitado é texto, não curinga
        assertThat(call("GET", "/api/v1/feed?q=%25", null, Map.of()).body()).doesNotContain(marker);
    }

    @Test
    void hallMostraDestaquesReais() throws Exception {
        Person person = newPerson();
        String photo = "/uploads/content/" + java.util.UUID.randomUUID() + ".jpg";
        assertThat(call("PUT", "/api/v1/me/setup/photos", "{\"photos\":[\"" + photo + "\"]}", person.auth()).statusCode()).isEqualTo(204);

        HttpResponse<String> hall = call("GET", "/api/v1/hall", null, Map.of());
        assertThat(hall.statusCode()).isEqualTo(200);
        assertThat(hall.body()).contains("\"reviewers\"", "\"reviewCount\"", "\"rankName\"", "\"level\"", "\"photo\":\"" + photo + "\"", "\"avgScore\"")
                .doesNotContain("\"avgScore\":null");
    }

    @Test
    void catalogoTemTodasAsCategoriasComCamposObrigatorios() throws Exception {
        String categories = call("GET", "/api/v1/categories", null, Map.of()).body();
        assertThat(categories).contains("Placa-mãe", "Memória RAM", "Water Cooler", "Fone intra", "\"key\":\"valor\"", "\"IPS\"");
        assertThat(jdbc.queryForObject("""
                SELECT count(DISTINCT category_id) FROM spec_fields WHERE key IN ('modelo', 'valor') AND required
                """, Integer.class)).isEqualTo(17);
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
