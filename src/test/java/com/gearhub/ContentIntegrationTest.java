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

    private Map<String, String> viewer;

    // Todo o conteúdo exige conta: quem só olha também entra com uma sessão
    private Map<String, String> viewer() throws Exception {
        if (viewer == null) {
            viewer = newUser();
        }
        return viewer;
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
            "{\"modelo\":\"M1\",\"valor\":\"R$ 500\",\"sensor\":\"S1\",\"dpi\":\"26000\",\"peso\":\"60 g\",\"conexao\":\"USB-C\"}";

    @Test
    void buscaIgnoraAcentoEOlhaTodosOsCampos() throws Exception {
        Map<String, String> user = newUser();
        String author = call("GET", "/api/v1/auth/me", null, user).body().replaceAll(".*\"username\":\"([^\"]+)\".*", "$1");
        call("POST", "/api/v1/posts", "{\"body\":\"Upgrade de Memória DDR5 concluído\",\"topic\":\"discussao\",\"tags\":[\"Overclock\"]}", user);

        // Sem acento, só pela tag, pelo @ do autor, e com palavras separadas
        assertThat(call("GET", "/api/v1/feed?q=memoria", null, user).body()).contains("Upgrade de Memória DDR5");
        assertThat(call("GET", "/api/v1/feed?q=OVERCLOCK", null, user).body()).contains("Upgrade de Memória DDR5");
        assertThat(call("GET", "/api/v1/feed?q=" + author, null, user).body()).contains("Upgrade de Memória DDR5");
        assertThat(call("GET", "/api/v1/feed?q=upgrade%20ddr5", null, user).body()).contains("Upgrade de Memória DDR5");
        assertThat(call("GET", "/api/v1/feed?q=50%25", null, user).body()).doesNotContain("Upgrade de Memória DDR5");

        // Busca geral: produto pela categoria, sem acento
        assertThat(call("GET", "/api/v1/search?q=teclado", null, user).body()).contains("\"category\":\"Teclado\"");
    }

    @Test
    void publicaPostEApareceNoFeed() throws Exception {
        Map<String, String> user = newUser();
        HttpResponse<String> created = call("POST", "/api/v1/posts",
                "{\"body\":\"Oi, comunidade #Sensor\",\"topic\":\"mouse\",\"tags\":[\"#Viper\"]}", user);
        assertThat(created.statusCode()).isEqualTo(201);

        String feed = call("GET", "/api/v1/feed?size=3", null, viewer()).body();
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
    void reviewExigeProdutoEFichaEssencialMasNaoTextoNemNotas() throws Exception {
        HttpResponse<String> semProduto = call("POST", "/api/v1/reviews", "{}", newUser());
        assertThat(semProduto.statusCode()).isEqualTo(400);
        assertThat(semProduto.body()).contains("Escolha a categoria do produto", "Informe a marca", "Informe o modelo");

        HttpResponse<String> semFicha = call("POST", "/api/v1/reviews",
                "{\"productId\":\"%s\"}".formatted(productId("razer-viper-v3-pro")), newUser());
        assertThat(semFicha.statusCode()).isEqualTo(400);
        assertThat(semFicha.body()).contains("specs.valor", "Preencha o campo Valor (R$)");

        HttpResponse<String> soFicha = call("POST", "/api/v1/reviews",
                "{\"productId\":\"%s\",\"specs\":%s}".formatted(productId("razer-viper-v3-pro"), MOUSE_SPECS), newUser());
        assertThat(soFicha.statusCode()).isEqualTo(201);
        assertThat(call("GET", "/api/v1/feed?size=1", null, viewer()).body())
                .contains("\"title\":null", "\"score\":null");
    }

    @Test
    void reviewAceitaNotasParciaisENaoAceitaNotaForaDaFaixa() throws Exception {
        String sensor = String.valueOf(jdbc.queryForObject("""
                SELECT c.id FROM criteria c JOIN categories k ON k.id = c.category_id
                WHERE k.slug = 'mouse' AND c.label = 'Sensor'
                """, Integer.class));

        HttpResponse<String> foraDaFaixa = call("POST", "/api/v1/reviews",
                "{\"productId\":\"%s\",\"specs\":%s,\"scores\":{\"%s\":11}}".formatted(productId("razer-viper-v3-pro"), MOUSE_SPECS, sensor),
                newUser());
        assertThat(foraDaFaixa.statusCode()).isEqualTo(400);
        assertThat(foraDaFaixa.body()).contains("A nota deve ser de 0 a 10");

        HttpResponse<String> parcial = call("POST", "/api/v1/reviews",
                "{\"productId\":\"%s\",\"specs\":%s,\"scores\":{\"%s\":8}}".formatted(productId("razer-viper-v3-pro"), MOUSE_SPECS, sensor),
                newUser());
        assertThat(parcial.statusCode()).isEqualTo(201);
        assertThat(call("GET", "/api/v1/feed?size=1", null, viewer()).body()).contains("\"score\":8.0");
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

        String feed = call("GET", "/api/v1/feed?size=5", null, viewer()).body();
        assertThat(feed).contains("Review completa", "\"score\":8.0", "\"label\":\"Tipo de sensor\"", "\"value\":\"S1\"", "Leve", "Caro");
        assertThat(jdbc.queryForObject("SELECT review_count FROM products WHERE id = ?::uuid", Integer.class, mouse))
                .isEqualTo(2);

        HttpResponse<String> repeated = call("POST", "/api/v1/reviews", body, user);
        assertThat(repeated.statusCode()).isEqualTo(409);
        assertThat(repeated.body()).contains("Você já publicou uma review desse produto");
    }

    @Test
    void feedPaginaEFiltra() throws Exception {
        HttpResponse<String> first = call("GET", "/api/v1/feed?size=2&category=teclado", null, viewer());
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.body()).contains("\"nextCursor\":null").doesNotContain("\"category\":\"Mouse\"");
        assertThat(call("GET", "/api/v1/feed?author=pedrolm", null, viewer()).body()).contains("Razer acerta em cheio");
        assertThat(call("GET", "/api/v1/products?category=mouse", null, viewer()).body())
                .contains("Viper V3 Pro").doesNotContain("Wooting");
    }

    @Test
    void feedNaoPulaPublicacoesDoMesmoInstante() throws Exception {
        Map<String, String> user = newUser();
        for (int n = 1; n <= 3; n++) {
            assertThat(call("POST", "/api/v1/posts", "{\"body\":\"Mesmo instante " + n + "\"}", user).statusCode())
                    .isEqualTo(201);
        }
        String username = jdbc.queryForObject("""
                SELECT u.username FROM posts p JOIN users u ON u.id = p.user_id WHERE p.body = 'Mesmo instante 1'
                """, String.class);
        jdbc.update("UPDATE posts SET created_at = '2026-01-01T12:00:00Z' WHERE body LIKE 'Mesmo instante %'");

        // Uma por página: com o marcador só de data, as outras duas sumiriam depois da primeira
        java.util.Set<String> seen = new java.util.HashSet<>();
        String cursor = null;
        for (int page = 0; page < 5; page++) {
            String path = "/api/v1/feed?size=1&author=" + username
                    + (cursor == null ? "" : "&before=" + java.net.URLEncoder.encode(cursor, StandardCharsets.UTF_8));
            HttpResponse<String> response = call("GET", path, null, viewer());
            assertThat(response.statusCode()).isEqualTo(200);
            java.util.regex.Matcher body = java.util.regex.Pattern.compile("Mesmo instante \\d").matcher(response.body());
            while (body.find()) {
                seen.add(body.group());
            }
            java.util.regex.Matcher next = java.util.regex.Pattern.compile("\"nextCursor\":\"([^\"]+)\"").matcher(response.body());
            if (!next.find()) {
                break;
            }
            cursor = next.group(1);
        }
        assertThat(seen).containsExactlyInAnyOrder("Mesmo instante 1", "Mesmo instante 2", "Mesmo instante 3");

        // Marcador antigo, só com a data, continua aceito; lixo vira 400
        assertThat(call("GET", "/api/v1/feed?before=2026-01-01T12:00:01Z", null, viewer()).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/api/v1/feed?before=qualquer-coisa", null, viewer()).statusCode()).isEqualTo(400);
    }

    @Test
    void buscaAchaPessoaPeloNomeSemAcento() throws Exception {
        String body = call("GET", "/api/v1/search?q=" + java.net.URLEncoder.encode("autor", StandardCharsets.UTF_8),
                null, viewer()).body();
        assertThat(body).contains("\"people\":[{");
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
        // PNG de verdade com um bloco de texto escondido (como o GPS que celulares gravam)
        byte[] png = com.gearhub.users.ImageMetadataTest.pngWithText("GPSLatitude=-23.55");

        HttpResponse<String> ok = uploadAvatar(png, user);
        assertThat(ok.statusCode()).isEqualTo(200);
        String url = ok.body().replaceAll(".*\"avatarUrl\":\"([^\"]+)\".*", "$1");
        assertThat(url).startsWith("/uploads/avatars/").endsWith(".png");
        HttpResponse<byte[]> stored = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + url))
                .headers(viewer().entrySet().stream().flatMap(entry -> java.util.stream.Stream.of(entry.getKey(), entry.getValue()))
                        .toArray(String[]::new)).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(stored.statusCode()).isEqualTo(200);
        // Chega sem o texto escondido e continua sendo uma imagem válida
        assertThat(new String(stored.body(), StandardCharsets.ISO_8859_1)).doesNotContain("GPSLatitude");
        assertThat(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(stored.body())).getWidth()).isEqualTo(4);

        // PNG cortado no meio, como um envio que falhou
        HttpResponse<String> broken = uploadAvatar(java.util.Arrays.copyOf(png, 60), user);
        assertThat(broken.statusCode()).isEqualTo(400);
        assertThat(broken.body()).contains("Imagem inválida ou corrompida");

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

    @Test
    void googleTiraASenhaDeQuemCriouAContaSemProvarOEmail() throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String email = "vitima" + id + "@gmail.com";
        // Alguém cria conta com o e-mail da vítima e uma senha dele (o e-mail nunca foi confirmado)
        HttpResponse<String> attacker = call("POST", "/api/v1/auth/register", """
                {"displayName":"Falso","username":"falso%s","email":"%s","password":"%s","passwordConfirmation":"%s"}
                """.formatted(id, email, PASSWORD, PASSWORD), Map.of());
        assertThat(attacker.statusCode()).isEqualTo(201);
        Map<String, String> attackerSession = Map.of("Cookie", attacker.headers().firstValue("set-cookie").orElseThrow().split(";")[0]);

        // A dona do e-mail entra pelo Google: prova que o e-mail é dela
        googleProfile = """
                {"sub":"%s","email":"%s","email_verified":true,"name":"Vítima"}
                """.formatted(id, email);
        String state = startGoogleLogin();
        HttpResponse<String> google = call("GET", "/api/v1/auth/google/callback?code=abc&state=" + state.split("=", 2)[1], null,
                Map.of("Cookie", state));
        Map<String, String> ownerSession = Map.of("Cookie", google.headers().firstValue("set-cookie").orElseThrow().split(";")[0]);

        // A senha e a sessão de quem criou a conta deixam de valer; a dona entra
        assertThat(call("GET", "/api/v1/auth/me", null, attackerSession).statusCode()).isEqualTo(401);
        assertThat(call("POST", "/api/v1/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}", Map.of())
                .statusCode()).isEqualTo(401);
        assertThat(call("GET", "/api/v1/auth/me", null, ownerSession).body()).contains("\"emailVerified\":true", "\"hasPassword\":false");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'account.email_proven'", Integer.class))
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void googleComDuasEtapasPedeOCodigoAntesDaSessao() throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        googleProfile = """
                {"sub":"%s","email":"g%s@gmail.com","email_verified":true,"name":"Conta Google 2FA"}
                """.formatted(id, id);
        String state = startGoogleLogin();
        String callbackPath = "/api/v1/auth/google/callback?code=abc&state=" + state.split("=", 2)[1];
        HttpResponse<String> first = call("GET", callbackPath, null, Map.of("Cookie", state));
        Map<String, String> auth = Map.of("Cookie", first.headers().firstValue("set-cookie").orElseThrow().split(";")[0]);

        String secret = call("POST", "/api/v1/me/2fa/setup", null, auth).body()
                .replaceAll(".*\"secret\":\"([A-Z2-7]+)\".*", "$1");
        HttpResponse<String> enabled = call("POST", "/api/v1/me/2fa/enable",
                "{\"code\":\"" + com.gearhub.auth.Totp.code(secret, com.gearhub.auth.Totp.currentStep()) + "\"}", auth);
        assertThat(enabled.statusCode()).isEqualTo(200);
        String recovery = enabled.body().replaceAll(".*\\[\"([a-z2-9-]+)\".*", "$1");

        // Volta do Google com 2FA: vai para a etapa do código, sem cookie de sessão
        HttpResponse<String> second = call("GET", callbackPath, null, Map.of("Cookie", state));
        assertThat(second.statusCode()).isEqualTo(302);
        assertThat(second.headers().firstValue("location")).hasValue("http://localhost:3000/auth?etapa=codigo");
        String challenge = second.headers().firstValue("set-cookie").orElseThrow().split(";")[0];
        assertThat(challenge).startsWith("gearhub_2fa=");

        String next = com.gearhub.auth.Totp.code(secret, com.gearhub.auth.Totp.currentStep() + 1);
        HttpResponse<String> verified = call("POST", "/api/v1/auth/2fa/verify", "{\"code\":\"" + next + "\"}",
                Map.of("Cookie", challenge));
        assertThat(verified.statusCode()).isEqualTo(200);
        assertThat(verified.body()).contains("Conta Google 2FA");

        // Conta sem senha: para desligar, basta o código
        assertThat(call("POST", "/api/v1/me/2fa/disable", "{\"code\":\"" + recovery + "\"}", auth).statusCode())
                .isEqualTo(204);
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
