package com.gearhub;

import static org.assertj.core.api.Assertions.assertThat;

import com.gearhub.catalog.CatalogService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Catálogo de jogos com um IGDB falso, que responde com a ficha real do Elden Ring. */
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"gearhub.auth.register-limit-per-minute=1000", "gearhub.market.collect=false"})
class CatalogIntegrationTest {

    private static final String PASSWORD = "Senha-Forte-123";
    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final List<String> QUERIES = new CopyOnWriteArrayList<>();
    private static final AtomicInteger TOKENS = new AtomicInteger();
    private static volatile boolean igdbDown;
    private static final HttpServer FAKE_IGDB = startFakeIgdb();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CatalogService catalog;

    private static EmbeddedPostgres startPostgres() {
        try {
            return EmbeddedPostgres.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String fixture() {
        try (InputStream in = CatalogIntegrationTest.class.getResourceAsStream("/igdb/elden-ring.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer startFakeIgdb() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/token", exchange -> {
                TOKENS.incrementAndGet();
                reply(exchange, 200, "{\"access_token\":\"token-falso\",\"expires_in\":5000000,\"token_type\":\"bearer\"}");
            });
            server.createContext("/v4/", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String path = exchange.getRequestURI().getPath();
                QUERIES.add(path + " " + body);
                if (igdbDown) {
                    reply(exchange, 503, "{}");
                    return;
                }
                if (!"Bearer token-falso".equals(exchange.getRequestHeaders().getFirst("Authorization"))
                        || !"cliente-igdb".equals(exchange.getRequestHeaders().getFirst("Client-ID"))) {
                    reply(exchange, 401, "{}");
                    return;
                }
                switch (path) {
                    case "/v4/games" -> {
                        if (body.startsWith("search")) {
                            reply(exchange, 200, """
                                    [{"id":119133,"name":"Elden Ring","slug":"elden-ring","cover":{"id":1,"image_id":"co4jni"},
                                      "first_release_date":1645747200,"platforms":[{"id":6,"abbreviation":"PC"},{"id":167,"abbreviation":"PS5"}],
                                      "total_rating":95.19},
                                     {"id":2,"name":"Sem slug válido","slug":"Inválido Com Espaço"}]
                                    """);
                        } else if (body.contains("where slug = \"elden-ring\"")) {
                            reply(exchange, 200, fixture());
                        } else {
                            reply(exchange, 200, "[]");
                        }
                    }
                    case "/v4/game_time_to_beats" -> reply(exchange, 200,
                            "[{\"id\":2585,\"game_id\":119133,\"hastily\":166400,\"normally\":428000,\"completely\":628294,\"count\":27}]");
                    case "/v4/external_games" -> reply(exchange, 200,
                            "[{\"id\":1,\"uid\":\"1245620\",\"game\":{\"id\":119133,\"slug\":\"elden-ring\"}}]");
                    default -> reply(exchange, 404, "[]");
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        String igdb = "http://localhost:" + FAKE_IGDB.getAddress().getPort();
        registry.add("gearhub.igdb.client-id", () -> "cliente-igdb");
        registry.add("gearhub.igdb.client-secret", () -> "segredo-igdb");
        registry.add("gearhub.igdb.token-uri", () -> igdb + "/token");
        registry.add("gearhub.igdb.api-uri", () -> igdb + "/v4");
    }

    @AfterAll
    static void stop() throws IOException {
        FAKE_IGDB.stop(0);
        POSTGRES.close();
    }

    @BeforeEach
    void reset() {
        igdbDown = false;
        jdbc.update("DELETE FROM catalog_games");
        jdbc.update("UPDATE market_games SET igdb_slug = NULL");
        QUERIES.clear();
    }

    private Map<String, String> session() throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        HttpResponse<String> created = call("/api/v1/auth/register", """
                {"displayName":"Gamer","username":"g%s","email":"%s@exemplo.com","password":"%s","passwordConfirmation":"%s"}
                """.formatted(id, id, PASSWORD, PASSWORD), Map.of());
        assertThat(created.statusCode()).isEqualTo(201);
        return Map.of("Cookie", created.headers().firstValue("set-cookie").orElseThrow().split(";")[0]);
    }

    private HttpResponse<String> call(String path, String json, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (json == null) {
            request.GET();
        } else {
            request.POST(HttpRequest.BodyPublishers.ofString(json)).header("Content-Type", "application/json");
        }
        headers.forEach(request::header);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private long gameQueries() {
        return QUERIES.stream().filter(query -> query.startsWith("/v4/games ")).count();
    }

    @Test
    void semContaNadaAbre() throws Exception {
        assertThat(call("/api/v1/catalog/games?q=elden", null, Map.of()).statusCode()).isEqualTo(401);
        assertThat(call("/api/v1/catalog/games/elden-ring", null, Map.of()).statusCode()).isEqualTo(401);
    }

    @Test
    void buscaTrazCardsSoDeJogosJogaveis() throws Exception {
        Map<String, String> auth = session();
        HttpResponse<String> found = call("/api/v1/catalog/games?q=elden%20ring", null, auth);
        assertThat(found.statusCode()).isEqualTo(200);
        assertThat(found.body()).isEqualTo("[{\"slug\":\"elden-ring\",\"name\":\"Elden Ring\","
                + "\"coverUrl\":\"https://images.igdb.com/igdb/image/upload/t_cover_big/co4jni.jpg\","
                + "\"year\":2022,\"platforms\":[\"PC\",\"PS5\"],\"rating\":95}]");
        assertThat(QUERIES.getFirst()).contains("search \"elden ring\";", "game_type = (0,4,8,9,10,11)", "version_parent = null");

        // Mesma busca de novo: sai do cache, sem outra chamada ao IGDB
        call("/api/v1/catalog/games?q=Elden%20Ring", null, auth);
        assertThat(gameQueries()).isEqualTo(1);

        assertThat(call("/api/v1/catalog/games?q=e", null, auth).statusCode()).isEqualTo(400);
    }

    @Test
    void buscaNaoDeixaMudarAConsulta() throws Exception {
        Map<String, String> auth = session();
        call("/api/v1/catalog/games?q=" + java.net.URLEncoder.encode("x\"; fields *; where id = 1;", StandardCharsets.UTF_8),
                null, auth);
        // Aspas e ponto e vírgula somem: o texto continua preso dentro do search "..."
        assertThat(QUERIES.getFirst()).startsWith("/v4/games search \"x fields * where id = 1\"; fields name,slug");
    }

    @Test
    void paginaDoJogoMontaAFichaCompleta() throws Exception {
        HttpResponse<String> page = call("/api/v1/catalog/games/elden-ring", null, session());
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains(
                "\"name\":\"Elden Ring\"", "\"type\":\"Main Game\"", "\"releaseDate\":\"2022-02-25\"",
                "\"coverUrl\":\"https://images.igdb.com/igdb/image/upload/t_cover_big_2x/co4jni.jpg\"",
                "\"artworkUrl\":\"https://images.igdb.com/igdb/image/upload/t_1080p/",
                "\"ratings\":{\"critics\":97,\"criticsCount\":10,\"users\":93,\"usersCount\":2337,\"total\":95}",
                "\"timeToBeat\":{\"main\":46,\"extras\":119,\"completionist\":175,\"count\":27}",
                "\"genres\":[\"Role-playing (RPG)\",\"Adventure\"]",
                "\"developers\":[\"FromSoftware\"]", "\"publishers\":[\"Bandai Namco Entertainment\",\"FromSoftware\"]",
                "\"portuguese\":{\"audio\":false,\"subtitles\":true,\"interfaceText\":true}",
                "\"multiplayer\":{\"onlineMax\":3,\"offlineMax\":1,\"onlineCoop\":true,\"offlineCoop\":false,\"campaignCoop\":true",
                "\"ageRating\":\"16\"", "\"steamAppid\":1245620",
                "{\"label\":\"Steam\",\"url\":\"https://store.steampowered.com/app/1245620",
                "\"youtubeId\":\"D1mDo1CEMuE\"",
                "\"expansions\":[{\"slug\":\"elden-ring-shadow-of-the-erdtree\"",
                "\"similar\":[{\"slug\":\"dark-souls\"");
        // Elden Ring vem nos jogos acompanhados da aba Games: a página traz o bloco da Steam
        assertThat(page.body()).contains("\"steam\":{\"appid\":1245620");
        assertThat(QUERIES).anyMatch(query -> query.startsWith("/v4/game_time_to_beats") && query.contains("game_id = 119133"));
    }

    @Test
    void fichaFicaGuardadaEServeSeOIgdbCair() throws Exception {
        Map<String, String> auth = session();
        call("/api/v1/catalog/games/elden-ring", null, auth);
        call("/api/v1/catalog/games/elden-ring", null, auth);
        assertThat(gameQueries()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT steam_appid FROM catalog_games WHERE slug = 'elden-ring'", Integer.class))
                .isEqualTo(1245620);

        // Cópia vencida e IGDB fora do ar: mostra a última versão em vez de erro
        jdbc.update("UPDATE catalog_games SET synced_at = now() - interval '10 days'");
        igdbDown = true;
        HttpResponse<String> stale = call("/api/v1/catalog/games/elden-ring", null, auth);
        assertThat(stale.statusCode()).isEqualTo(200);
        assertThat(stale.body()).contains("\"name\":\"Elden Ring\"");

        // Nunca guardado e IGDB fora: 503
        assertThat(call("/api/v1/catalog/games/hollow-knight", null, auth).statusCode()).isEqualTo(503);
    }

    @Test
    void jogoInexistenteOuSlugEstranhoDa404() throws Exception {
        Map<String, String> auth = session();
        assertThat(call("/api/v1/catalog/games/nao-existe", null, auth).statusCode()).isEqualTo(404);
        int before = QUERIES.size();
        assertThat(call("/api/v1/catalog/games/Slug%22;%20fields", null, auth).statusCode()).isIn(400, 404);
        assertThat(QUERIES).hasSize(before);
    }

    @Test
    void jogoAcompanhadoNaSteamTrazPrecoEJogadores() throws Exception {
        // Elden Ring já vem na lista de jogos acompanhados: só preenche preço e jogadores
        jdbc.update("INSERT INTO market_games (steam_appid, name) VALUES (1245620, 'ELDEN RING') ON CONFLICT (steam_appid) DO NOTHING");
        jdbc.update("""
                UPDATE market_games SET currency = 'BRL', initial_cents = 22990, final_cents = 11495, discount_percent = 50,
                    current_players = 40000 WHERE steam_appid = 1245620
                """);
        HttpResponse<String> page = call("/api/v1/catalog/games/elden-ring", null, session());
        assertThat(page.body()).contains("\"steam\":{\"appid\":1245620", "\"finalCents\":11495", "\"discountPercent\":50",
                "\"currentPlayers\":40000", "\"slug\":\"elden-ring\"");
    }

    @Test
    void ligaOsJogosDaSteamAoCatalogo() throws Exception {
        jdbc.update("INSERT INTO market_games (steam_appid, name) VALUES (1245620, 'ELDEN RING') ON CONFLICT (steam_appid) DO NOTHING");
        assertThat(catalog.linkMarketGames()).isEqualTo(1);
        assertThat(QUERIES).anyMatch(query -> query.startsWith("/v4/external_games")
                && query.contains("external_game_source = 1") && query.contains("\"1245620\""));

        Map<String, String> auth = session();
        assertThat(call("/api/v1/market/games?q=elden", null, auth).body()).contains("\"slug\":\"elden-ring\"");
        // Endereço antigo da aba Games encontra a página nova
        assertThat(call("/api/v1/catalog/steam/1245620", null, auth).body()).isEqualTo("{\"slug\":\"elden-ring\"}");
        assertThat(call("/api/v1/catalog/steam/999999", null, auth).statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> patch(String path, String json, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json)).header("Content-Type", "application/json");
        headers.forEach(request::header);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void postSobreUmJogoMostraACapaNoFeed() throws Exception {
        Map<String, String> auth = session();
        HttpResponse<String> created = call("/api/v1/posts",
                "{\"body\":\"Alguém já fechou a DLC?\",\"topic\":\"games\",\"game\":\"elden-ring\"}", auth);
        assertThat(created.statusCode()).isEqualTo(201);
        String id = created.body().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        // Nome e capa vêm do catálogo, não do navegador
        String game = "\"game\":{\"slug\":\"elden-ring\",\"name\":\"Elden Ring\","
                + "\"coverUrl\":\"https://images.igdb.com/igdb/image/upload/t_cover_big_2x/co4jni.jpg\"}";
        assertThat(call("/api/v1/feed?size=1", null, auth).body()).contains("Alguém já fechou a DLC?", game);
        assertThat(call("/api/v1/posts/" + id + "/edit", null, auth).body()).contains(game);

        // Editar sem jogo tira a ligação
        assertThat(patch("/api/v1/posts/" + id, "{\"body\":\"Alguém já fechou a DLC?\",\"topic\":\"games\"}", auth)
                .statusCode()).isEqualTo(204);
        assertThat(call("/api/v1/posts/" + id + "/edit", null, auth).body()).contains("\"game\":null");
    }

    @Test
    void postComJogoInexistenteERecusado() throws Exception {
        Map<String, String> auth = session();
        HttpResponse<String> missing = call("/api/v1/posts",
                "{\"body\":\"Post\",\"topic\":\"games\",\"game\":\"nao-existe\"}", auth);
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(missing.body()).contains("\"game\":\"Jogo não encontrado. Escolha outro.\"");

        igdbDown = true;
        HttpResponse<String> down = call("/api/v1/posts",
                "{\"body\":\"Post\",\"topic\":\"games\",\"game\":\"hollow-knight\"}", auth);
        assertThat(down.statusCode()).isEqualTo(400);
        assertThat(down.body()).contains("Não foi possível conferir o jogo agora");
    }

    private String gameScores(int value) {
        StringBuilder json = new StringBuilder("{");
        jdbc.queryForList("SELECT c.id FROM criteria c JOIN categories k ON k.id = c.category_id WHERE k.slug = 'jogos'",
                Integer.class).forEach(id -> json.append("\"").append(id).append("\":").append(value).append(","));
        return json.deleteCharAt(json.length() - 1).append("}").toString();
    }

    @Test
    void categoriaJogosTemNotasProprias() {
        assertThat(jdbc.queryForList("""
                SELECT c.label FROM criteria c JOIN categories k ON k.id = c.category_id WHERE k.slug = 'jogos' ORDER BY c.position
                """, String.class)).containsExactly("Gráficos", "Jogabilidade", "História", "Desempenho", "Diversão");
    }

    @Test
    void reviewDeJogoEntraNoJogoENaoNosProdutos() throws Exception {
        Map<String, String> auth = session();
        HttpResponse<String> created = call("/api/v1/reviews", """
                {"game":"elden-ring","title":"Obra-prima","body":"Mundo enorme","scores":%s,"pros":["Exploração"],"cons":["Difícil"]}
                """.formatted(gameScores(9)), auth);
        assertThat(created.statusCode()).isEqualTo(201);
        String id = created.body().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        // O jogo virou o produto da categoria Jogos, ligado ao catálogo
        assertThat(jdbc.queryForObject("""
                SELECT k.slug FROM products p JOIN categories k ON k.id = p.category_id JOIN catalog_games g ON g.igdb_id = p.game_id
                WHERE g.slug = 'elden-ring'
                """, String.class)).isEqualTo("jogos");

        String game = "\"game\":{\"slug\":\"elden-ring\",\"name\":\"Elden Ring\"";
        String feed = call("/api/v1/feed?game=elden-ring", null, auth).body();
        assertThat(feed).contains("Obra-prima", "\"category\":\"Jogos\"", game, "\"score\":9.0");
        assertThat(call("/api/v1/reviews/" + id + "/edit", null, auth).body())
                .contains("\"productName\":\"Elden Ring\"", "\"category\":\"jogos\"", game);

        // Nota da comunidade na página do jogo
        assertThat(call("/api/v1/catalog/games/elden-ring", null, auth).body())
                .contains("\"community\":{\"score\":9.0,\"reviewCount\":1}");

        // Jogo não aparece na lista de produtos (tem página própria em /games)
        assertThat(call("/api/v1/products", null, auth).body()).doesNotContain("Elden Ring");

        // A segunda review do mesmo jogo pela mesma pessoa é recusada
        assertThat(call("/api/v1/reviews", "{\"game\":\"elden-ring\",\"title\":\"De novo\",\"scores\":" + gameScores(8) + "}", auth)
                .statusCode()).isEqualTo(409);
    }

    @Test
    void feedDoJogoJuntaPostsEReviews() throws Exception {
        Map<String, String> auth = session();
        call("/api/v1/posts", "{\"body\":\"Post do feed do jogo\",\"topic\":\"games\",\"game\":\"elden-ring\"}", auth);
        call("/api/v1/posts", "{\"body\":\"Post sem jogo nenhum\",\"topic\":\"discussao\"}", auth);
        String feed = call("/api/v1/feed?game=elden-ring&size=50", null, auth).body();
        assertThat(feed).contains("Post do feed do jogo").doesNotContain("Post sem jogo nenhum");
    }

    @Test
    void reviewDeJogoInexistenteERecusada() throws Exception {
        HttpResponse<String> missing = call("/api/v1/reviews",
                "{\"game\":\"nao-existe\",\"title\":\"x\",\"scores\":" + gameScores(7) + "}", session());
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(missing.body()).contains("\"game\":\"Jogo não encontrado. Escolha outro.\"");
    }

    @Test
    void tokenEReaproveitado() throws Exception {
        Map<String, String> auth = session();
        int before = TOKENS.get();
        call("/api/v1/catalog/games?q=zelda", null, auth);
        call("/api/v1/catalog/games?q=mario", null, auth);
        call("/api/v1/catalog/games/elden-ring", null, auth);
        // No máximo um token novo (se nenhum teste anterior pediu), nunca um por chamada
        assertThat(TOKENS.get() - before).isLessThanOrEqualTo(1);
        assertThat(TOKENS.get()).isGreaterThanOrEqualTo(1);
    }
}
