package com.gearhub;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

/** Posts, reviews, feed, foto de perfil e login com um Google falso, sem sair da máquina. */
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"gearhub.auth.login-limit-per-minute=1000", "gearhub.auth.register-limit-per-minute=1000"})
class ContentIntegrationTest {

    private static final String PASSWORD = "Senha-Forte-123";
    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final HttpServer FAKE_GOOGLE = startFakeGoogle();
    private static volatile String googleProfile = "{}";

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

    private static HttpServer startFakeGoogle() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/token", exchange -> reply(exchange, "{\"access_token\":\"token-falso\"}"));
            server.createContext("/userinfo", exchange -> reply(exchange, googleProfile));
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        Path uploads = Files.createTempDirectory("gearhub-uploads");
        registry.add("gearhub.uploads.dir", uploads::toString);
        String google = "http://localhost:" + FAKE_GOOGLE.getAddress().getPort();
        registry.add("gearhub.google.client-id", () -> "cliente-teste");
        registry.add("gearhub.google.client-secret", () -> "segredo-teste");
        registry.add("gearhub.google.token-uri", () -> google + "/token");
        registry.add("gearhub.google.user-info-uri", () -> google + "/userinfo");
        registry.add("gearhub.discord.client-id", () -> "discord-teste");
        registry.add("gearhub.discord.client-secret", () -> "segredo-discord");
        registry.add("gearhub.discord.token-uri", () -> google + "/token");
        registry.add("gearhub.discord.user-info-uri", () -> google + "/userinfo");
    }

    @AfterAll
    static void stop() throws IOException {
        FAKE_GOOGLE.stop(0);
        POSTGRES.close();
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

    private Map<String, String> newUser() throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        HttpResponse<String> response = call("POST", "/api/v1/auth/register", """
                {"displayName":"Autor","username":"a%s","email":"%s@exemplo.com","password":"%s","passwordConfirmation":"%s"}
                """.formatted(id, id, PASSWORD, PASSWORD), Map.of());
        assertThat(response.statusCode()).isEqualTo(201);
        return Map.of("Cookie", response.headers().firstValue("set-cookie").orElseThrow().split(";")[0]);
    }

    private String productId(String slug) {
        return jdbc.queryForObject("SELECT id::text FROM products WHERE slug = ?", String.class, slug);
    }

    private String mouseScoresJson() {
        StringBuilder json = new StringBuilder("{");
        jdbc.queryForList("""
                SELECT c.id FROM criteria c JOIN categories k ON k.id = c.category_id WHERE k.slug = 'mouse'
                """, Integer.class).forEach(id -> json.append("\"").append(id).append("\":8,"));
        return json.deleteCharAt(json.length() - 1).append("}").toString();
    }

    private static final String MOUSE_SPECS =
            "{\"modelo\":\"M1\",\"formato\":\"Simetrico\",\"peso\":\"60 g\",\"sensor\":\"S1\",\"conexao\":\"USB-C\"}";

    @Test
    void publicaPostEApareceNoFeed() throws Exception {
        Map<String, String> user = newUser();
        HttpResponse<String> created = call("POST", "/api/v1/posts",
                "{\"body\":\"Oi, comunidade #Sensor\",\"topic\":\"mouse\",\"tags\":[\"#Viper\"]}", user);
        assertThat(created.statusCode()).isEqualTo(201);

        String feed = call("GET", "/api/v1/feed?size=3", null, Map.of()).body();
        assertThat(feed).contains("\"kind\":\"post\"", "Oi, comunidade", "\"tags\":[\"viper\",\"sensor\"]", "\"category\":\"Mouse\"");
    }

    @Test
    void postExigeLoginETexto() throws Exception {
        assertThat(call("POST", "/api/v1/posts", "{\"title\":\"Sem login\",\"body\":\"x\"}", Map.of()).statusCode())
                .isEqualTo(401);
        HttpResponse<String> invalid = call("POST", "/api/v1/posts", "{\"body\":\"\"}", newUser());
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(invalid.body()).contains("Escreva o conteúdo do post");
    }

    @Test
    void reviewSoExigeOProduto() throws Exception {
        HttpResponse<String> semProduto = call("POST", "/api/v1/reviews", "{}", newUser());
        assertThat(semProduto.statusCode()).isEqualTo(400);
        assertThat(semProduto.body()).contains("Escolha o produto");

        HttpResponse<String> soProduto = call("POST", "/api/v1/reviews",
                "{\"productId\":\"%s\"}".formatted(productId("razer-viper-v3-pro")), newUser());
        assertThat(soProduto.statusCode()).isEqualTo(201);
        assertThat(call("GET", "/api/v1/feed?size=1", null, Map.of()).body())
                .contains("\"title\":null", "\"score\":null");
    }

    @Test
    void reviewAceitaNotasParciaisENaoAceitaNotaForaDaFaixa() throws Exception {
        String sensor = String.valueOf(jdbc.queryForObject("""
                SELECT c.id FROM criteria c JOIN categories k ON k.id = c.category_id
                WHERE k.slug = 'mouse' AND c.label = 'Sensor'
                """, Integer.class));

        HttpResponse<String> foraDaFaixa = call("POST", "/api/v1/reviews",
                "{\"productId\":\"%s\",\"scores\":{\"%s\":11}}".formatted(productId("razer-viper-v3-pro"), sensor),
                newUser());
        assertThat(foraDaFaixa.statusCode()).isEqualTo(400);
        assertThat(foraDaFaixa.body()).contains("A nota deve ser de 0 a 10");

        HttpResponse<String> parcial = call("POST", "/api/v1/reviews",
                "{\"productId\":\"%s\",\"scores\":{\"%s\":8}}".formatted(productId("razer-viper-v3-pro"), sensor),
                newUser());
        assertThat(parcial.statusCode()).isEqualTo(201);
        assertThat(call("GET", "/api/v1/feed?size=1", null, Map.of()).body()).contains("\"score\":8.0");
    }

    @Test
    void publicaReviewCalculaNotaEAtualizaOProduto() throws Exception {
        String product = productId("artisan-zero-xsoft");
        HttpResponse<String> notMouse = call("POST", "/api/v1/reviews", """
                {"productId":"%s","title":"Categoria errada","body":"Texto com tamanho suficiente","scores":%s}
                """.formatted(product, mouseScoresJson()), newUser());
        assertThat(notMouse.statusCode()).isEqualTo(400);

        Map<String, String> user = newUser();
        String mouse = productId("lamzu-maya-x");
        String body = """
                {"productId":"%s","title":"Review completa","body":"Texto com tamanho suficiente",
                 "scores":%s,"specs":%s,"pros":["Leve"],"cons":["Caro"]}
                """.formatted(mouse, mouseScoresJson(), MOUSE_SPECS);
        assertThat(call("POST", "/api/v1/reviews", body, user).statusCode()).isEqualTo(201);

        String feed = call("GET", "/api/v1/feed?size=5", null, Map.of()).body();
        assertThat(feed).contains("Review completa", "\"score\":8.0", "\"label\":\"Sensor\"", "\"value\":\"S1\"", "Leve", "Caro");
        assertThat(jdbc.queryForObject("SELECT review_count FROM products WHERE id = ?::uuid", Integer.class, mouse))
                .isEqualTo(2);

        HttpResponse<String> repeated = call("POST", "/api/v1/reviews", body, user);
        assertThat(repeated.statusCode()).isEqualTo(409);
        assertThat(repeated.body()).contains("Você já publicou uma review desse produto");
    }

    @Test
    void feedPaginaEFiltra() throws Exception {
        HttpResponse<String> first = call("GET", "/api/v1/feed?size=2&category=teclado", null, Map.of());
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.body()).contains("\"nextCursor\":null").doesNotContain("\"category\":\"Mouse\"");
        assertThat(call("GET", "/api/v1/feed?author=pedrolm", null, Map.of()).body()).contains("Razer acerta em cheio");
        assertThat(call("GET", "/api/v1/products?category=mouse", null, Map.of()).body())
                .contains("Viper V3 Pro").doesNotContain("Wooting");
    }

    private HttpResponse<String> uploadAvatar(byte[] file, Map<String, String> headers) throws Exception {
        String boundary = "limite";
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[head.length + file.length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(file, 0, body, head.length, file.length);
        System.arraycopy(tail, 0, body, head.length + file.length, tail.length);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/users/me/avatar"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(request::header);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void trocaFotoEValidaOArquivoDeVerdade() throws Exception {
        Map<String, String> user = newUser();
        byte[] png = new byte[64];
        System.arraycopy(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47}, 0, png, 0, 4);

        HttpResponse<String> ok = uploadAvatar(png, user);
        assertThat(ok.statusCode()).isEqualTo(200);
        String url = ok.body().replaceAll(".*\"avatarUrl\":\"([^\"]+)\".*", "$1");
        assertThat(url).startsWith("/uploads/avatars/").endsWith(".png");
        assertThat(call("GET", url, null, Map.of()).statusCode()).isEqualTo(200);

        HttpResponse<String> fake = uploadAvatar("<script>alert(1)</script> nao e imagem".getBytes(StandardCharsets.UTF_8), user);
        assertThat(fake.statusCode()).isEqualTo(400);
        assertThat(fake.body()).contains("Envie uma imagem JPG, PNG ou WebP.");

        HttpResponse<String> big = uploadAvatar(new byte[3 * 1024 * 1024], user);
        assertThat(big.statusCode()).isEqualTo(413);
        assertThat(big.body()).contains("no máximo 2 MB");

        assertThat(uploadAvatar(png, Map.of()).statusCode()).isEqualTo(401);
    }

    private String startGoogleLogin() throws Exception {
        HttpResponse<String> url = call("GET", "/api/v1/auth/google/url", null, Map.of());
        assertThat(url.statusCode()).isEqualTo(200);
        assertThat(url.body()).contains("accounts.google.com", "cliente-teste", "state=");
        return url.headers().firstValue("set-cookie").orElseThrow().split(";")[0];
    }

    @Test
    void googleCriaContaEDepoisReconhece() throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        googleProfile = """
                {"sub":"%s","email":"g%s@gmail.com","email_verified":true,"name":"Conta Google"}
                """.formatted(id, id);
        String state = startGoogleLogin();
        String value = state.split("=", 2)[1];

        HttpResponse<String> callback = call("GET", "/api/v1/auth/google/callback?code=abc&state=" + value, null,
                Map.of("Cookie", state));
        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(callback.headers().firstValue("location")).hasValue("http://localhost:3000/");
        String session = callback.headers().firstValue("set-cookie").orElseThrow().split(";")[0];

        HttpResponse<String> me = call("GET", "/api/v1/auth/me", null, Map.of("Cookie", session));
        assertThat(me.body()).contains("Conta Google", "\"emailVerified\":true");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM oauth_accounts o JOIN users u ON u.id = o.user_id WHERE u.display_name = 'Conta Google' AND u.password_hash IS NULL AND o.provider_user_id_encrypted LIKE 'v1:%'", Integer.class))
                .isGreaterThanOrEqualTo(1);

        // Segundo login: mesma conta
        Integer before = jdbc.queryForObject("SELECT count(*) FROM users", Integer.class);
        call("GET", "/api/v1/auth/google/callback?code=abc&state=" + value, null, Map.of("Cookie", state));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(before);
    }

    private String startDiscordLogin() throws Exception {
        HttpResponse<String> url = call("GET", "/api/v1/auth/discord/url", null, Map.of());
        assertThat(url.statusCode()).isEqualTo(200);
        assertThat(url.body()).contains("discord.com/oauth2/authorize", "discord-teste", "identify%20email");
        return url.headers().firstValue("set-cookie").orElseThrow().split(";")[0];
    }

    private HttpResponse<String> discordCallback(String stateCookie) throws Exception {
        return call("GET", "/api/v1/auth/discord/callback?code=abc&state=" + stateCookie.split("=", 2)[1], null,
                Map.of("Cookie", stateCookie));
    }

    @Test
    void discordCriaContaEDepoisReconhece() throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        googleProfile = """
                {"id":"%s","username":"pessoa","global_name":"Conta Discord","email":"d%s@exemplo.com","verified":true}
                """.formatted(id, id);

        HttpResponse<String> callback = discordCallback(startDiscordLogin());
        assertThat(callback.statusCode()).isEqualTo(302);
        String session = callback.headers().firstValue("set-cookie").orElseThrow().split(";")[0];
        assertThat(call("GET", "/api/v1/auth/me", null, Map.of("Cookie", session)).body())
                .contains("Conta Discord", "\"emailVerified\":true");

        discordCallback(startDiscordLogin());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM oauth_accounts o JOIN users u ON u.id = o.user_id
                WHERE o.provider = 'discord' AND u.username = ?
                """, Integer.class, "d" + id)).isEqualTo(1);
    }

    @Test
    void discordLigaAContaDoGoogleComOMesmoEmail() throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String email = "m" + id + "@exemplo.com";
        googleProfile = "{\"sub\":\"%s\",\"email\":\"%s\",\"email_verified\":true,\"name\":\"Mesma Pessoa\"}".formatted(id, email);
        String state = startGoogleLogin();
        call("GET", "/api/v1/auth/google/callback?code=abc&state=" + state.split("=", 2)[1], null, Map.of("Cookie", state));

        googleProfile = "{\"id\":\"%s\",\"username\":\"outro\",\"email\":\"%s\",\"verified\":true}".formatted(id, email);
        discordCallback(startDiscordLogin());

        assertThat(jdbc.queryForObject("""
                SELECT count(DISTINCT user_id) FROM oauth_accounts o JOIN users u ON u.id = o.user_id
                WHERE u.username = ?
                """, Integer.class, "m" + id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM oauth_accounts o JOIN users u ON u.id = o.user_id WHERE u.username = ?
                """, Integer.class, "m" + id)).isEqualTo(2);
    }

    @Test
    void discordRecusaEstadoFalsoECancelamentoEEmailNaoConfirmado() throws Exception {
        String state = startDiscordLogin();
        assertThat(call("GET", "/api/v1/auth/discord/callback?code=abc&state=forjado", null, Map.of("Cookie", state))
                .headers().firstValue("location").orElseThrow()).endsWith("/auth?erro=discord-falhou");
        assertThat(call("GET", "/api/v1/auth/discord/callback?error=access_denied", null, Map.of())
                .headers().firstValue("location").orElseThrow()).endsWith("/auth?erro=discord-cancelado");

        googleProfile = "{\"id\":\"sem-email\",\"username\":\"x\",\"email\":\"x@exemplo.com\",\"verified\":false}";
        assertThat(discordCallback(state).headers().firstValue("location").orElseThrow())
                .endsWith("/auth?erro=discord-email-nao-verificado");
        assertThat(call("GET", "/api/v1/auth/errors/discord-email-nao-verificado", null, Map.of()).body())
                .contains("Confirme o e-mail da sua conta Discord");
    }

    @Test
    void googleRecusaEstadoFalsoEEmailNaoConfirmado() throws Exception {
        String state = startGoogleLogin();
        HttpResponse<String> forged = call("GET", "/api/v1/auth/google/callback?code=abc&state=forjado", null,
                Map.of("Cookie", state));
        assertThat(forged.headers().firstValue("location").orElseThrow()).endsWith("/auth?erro=google-falhou");
        assertThat(forged.headers().firstValue("set-cookie")).isEmpty();

        googleProfile = "{\"sub\":\"nao-confirmado\",\"email\":\"x@gmail.com\",\"email_verified\":false}";
        HttpResponse<String> unverified = call("GET", "/api/v1/auth/google/callback?code=abc&state=" + state.split("=", 2)[1],
                null, Map.of("Cookie", state));
        assertThat(unverified.headers().firstValue("location").orElseThrow()).endsWith("/auth?erro=email-nao-verificado");

        assertThat(call("GET", "/api/v1/auth/errors/email-nao-verificado", null, Map.of()).body())
                .contains("não tem o e-mail confirmado");
    }
}
