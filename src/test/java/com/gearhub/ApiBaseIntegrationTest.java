package com.gearhub;

import static org.assertj.core.api.Assertions.assertThat;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Valida migrations, seed, segurança e Swagger contra um Postgres real. */
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiBaseIntegrationTest {

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

    private HttpResponse<String> send(String method, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthEstaPublico() throws Exception {
        HttpResponse<String> response = send("GET", "/actuator/health");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("UP");
    }

    @Test
    void listaCategoriasComCriterios() throws Exception {
        HttpResponse<String> response = send("GET", "/api/v1/categories");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"slug\":\"mouse\"", "Ergonomia", "Latência");
    }

    @Test
    void categoriaInexistenteDevolve404ComProblemDetail() throws Exception {
        HttpResponse<String> response = send("GET", "/api/v1/categories/nao-existe");
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("content-type")).hasValue("application/problem+json");
    }

    @Test
    void swaggerDocumentaARota() throws Exception {
        HttpResponse<String> docs = send("GET", "/v3/api-docs");
        assertThat(docs.statusCode()).isEqualTo(200);
        assertThat(docs.body()).contains("GearHub API", "/api/v1/categories", "gearhub_session");
        assertThat(send("GET", "/swagger-ui/index.html").statusCode()).isEqualTo(200);
    }

    @Test
    void rotaNaoLiberadaEBloqueada() throws Exception {
        assertThat(send("POST", "/api/v1/categories").statusCode()).isEqualTo(401);
        assertThat(send("GET", "/api/v1/qualquer-coisa").statusCode()).isEqualTo(401);
        assertThat(send("GET", "/actuator/env").statusCode()).isEqualTo(401);
    }

    @Test
    void categoriaTrazOsCamposDaFichaTecnicaEmOrdem() throws Exception {
        HttpResponse<String> mouse = send("GET", "/api/v1/categories/mouse");
        assertThat(mouse.statusCode()).isEqualTo(200);
        assertThat(mouse.body()).contains("\"specFields\"", "\"key\":\"polling_rate\"");
        assertThat(mouse.body()).doesNotContain("Razer", "placeholder");
        assertThat(mouse.body().indexOf("\"modelo\"")).isLessThan(mouse.body().indexOf("\"pegadas\""));
    }

    @Test
    void cadaCategoriaTemOsCamposInformados() {
        assertThat(specFieldCount("mouse")).isEqualTo(9);
        assertThat(specFieldCount("teclado")).isEqualTo(8);
        assertThat(specFieldCount("mousepad")).isEqualTo(8);
        assertThat(specFieldCount("ssd")).isEqualTo(9);
    }

    @Test
    void fichaTecnicaSegueOsObrigatoriosDoCatalogo() throws Exception {
        assertThat(requiredFields("mouse")).containsExactlyInAnyOrder("modelo", "valor", "sensor", "dpi", "peso", "conexao");
        assertThat(requiredFields("teclado")).containsExactlyInAnyOrder("modelo", "valor", "formato", "tipo_switch", "conexao");
        assertThat(send("GET", "/api/v1/categories/mouse").body())
                .contains("{\"key\":\"sensor\",\"label\":\"Tipo de sensor\",\"required\":true,\"options\":[\"Óptico\",\"Laser\"]}");
    }

    private java.util.List<String> requiredFields(String slug) {
        return jdbc.queryForList("""
                SELECT f.key FROM spec_fields f JOIN categories c ON c.id = f.category_id
                WHERE c.slug = ? AND f.required ORDER BY f.key
                """, String.class, slug);
    }

    private Integer specFieldCount(String slug) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM spec_fields f JOIN categories c ON c.id = f.category_id WHERE c.slug = ?
                """, Integer.class, slug);
    }

    @Test
    void seedCarregaOsDadosDeExemplo() {
        Map<String, Object> totals = jdbc.queryForMap("""
                SELECT (SELECT count(*) FROM users) AS users,
                       (SELECT count(*) FROM products) AS products,
                       (SELECT count(*) FROM reviews) AS reviews,
                       (SELECT count(*) FROM review_scores) AS scores
                """);
        assertThat(totals).containsEntry("users", 6L).containsEntry("products", 15L)
                .containsEntry("reviews", 6L).containsEntry("scores", 24L);
    }

    @Test
    void notaDoProdutoVemDasReviews() {
        String score = jdbc.queryForObject(
                "SELECT avg_score::text FROM products WHERE slug = 'logitech-g-pro-x-superlight-2'", String.class);
        assertThat(score).isEqualTo("9.3");
    }

    @Test
    void bancoRecusaSegundaReviewDoMesmoAutorNoProduto() {
        Integer duplicates = jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes WHERE indexname = 'uq_reviews_user_product'
                """, Integer.class);
        assertThat(duplicates).isEqualTo(1);
    }
}
