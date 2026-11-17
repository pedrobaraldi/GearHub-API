package com.gearhub;

import static org.assertj.core.api.Assertions.assertThat;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

/** Publicidade (MVP): cadastros no painel, aprovação, vigência, exibição, impressões, cliques e relatório. */
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"gearhub.auth.register-limit-per-minute=1000", "gearhub.market.collect=false"})
class AdsIntegrationTest {

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
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        Path uploads = Files.createTempDirectory("gearhub-ads");
        registry.add("gearhub.uploads.dir", uploads::toString);
    }

    @AfterAll
    static void stopPostgres() throws IOException {
        POSTGRES.close();
    }

    private HttpResponse<String> call(String method, String path, String json, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) {
            request.header("Content-Type", "application/json");
        }
        headers.forEach(request::header);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, String> newUser() throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        HttpResponse<String> created = call("POST", "/api/v1/auth/register", """
                {"displayName":"Pessoa","username":"d%s","email":"%s@exemplo.com","password":"%s","passwordConfirmation":"%s"}
                """.formatted(id, id, PASSWORD, PASSWORD), Map.of());
        assertThat(created.statusCode()).isEqualTo(201);
        return Map.of("Cookie", created.headers().firstValue("set-cookie").orElseThrow().split(";")[0]);
    }

    private Map<String, String> admin() throws Exception {
        Map<String, String> auth = newUser();
        String username = call("GET", "/api/v1/auth/me", null, auth).body().replaceAll(".*\"username\":\"([^\"]+)\".*", "$1");
        jdbc.update("UPDATE users SET role = 'admin', two_factor_enabled_at = now() WHERE username = ?", username);
        return auth;
    }

    private static String field(String json, String name) {
        return json.replaceAll("(?s).*?\"" + name + "\":\"([^\"]+)\".*", "$1");
    }

    /** Espaço só deste teste, no formato card promocional. */
    private String newSlot(Map<String, String> admin) throws Exception {
        String key = "teste_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        assertThat(call("POST", "/api/v1/admin/ads/slots", """
                {"slotKey":"%s","name":"Espaço de teste","page":"feed","format":"promo_card","textLimit":80,"maxItems":1}
                """.formatted(key), admin).statusCode()).isEqualTo(201);
        return key;
    }

    private String newAdvertiser(Map<String, String> admin) throws Exception {
        HttpResponse<String> created = call("POST", "/api/v1/admin/ads/advertisers", """
                {"displayName":"Marca X","legalName":"Marca X LTDA","documentNumber":"11.222.333/0001-81","email":"ads@marcax.com",
                 "phone":"(11) 3216-5432","websiteUrl":"https://marcax.com.br"}
                """, admin);
        assertThat(created.statusCode()).isEqualTo(201);
        return field(created.body(), "id");
    }

    private String newCampaign(Map<String, String> admin, String advertiser, String slot, Instant start, Instant end, Integer limit)
            throws Exception {
        HttpResponse<String> created = call("POST", "/api/v1/admin/ads/campaigns", """
                {"advertiserId":"%s","name":"Campanha de teste","startAt":"%s","endAt":"%s","priority":10,"commercialModel":"cpm",
                 "commercialValue":25.00,%s"slotKeys":["%s"]}
                """.formatted(advertiser, start, end, limit == null ? "" : "\"impressionLimit\":" + limit + ",", slot), admin);
        assertThat(created.statusCode()).isEqualTo(201);
        return field(created.body(), "id");
    }

    private void addCreative(Map<String, String> admin, String campaign, String description) throws Exception {
        assertThat(call("POST", "/api/v1/admin/ads/campaigns/" + campaign + "/creatives", """
                {"title":"Mouse Marca X","description":"%s","destinationUrl":"https://marcax.com.br/mouse","ctaText":"Conhecer"}
                """.formatted(description), admin).statusCode()).isEqualTo(201);
    }

    private HttpResponse<String> act(Map<String, String> admin, String campaign, String action, String note) throws Exception {
        return call("POST", "/api/v1/admin/ads/campaigns/" + campaign + "/actions",
                "{\"action\":\"" + action + "\"" + (note == null ? "" : ",\"note\":\"" + note + "\"") + "}", admin);
    }

    private String ads(String slot) throws Exception {
        return call("GET", "/api/v1/ads/" + slot, null, Map.of()).body();
    }

    private HttpResponse<String> impression(String ad, String slot, String sid) throws Exception {
        return call("POST", "/api/v1/ads/impressions", """
                {"slotKey":"%s","campaignId":"%s","creativeId":"%s","signature":"%s","sessionId":"%s"}
                """.formatted(slot, field(ad, "campaignId"), field(ad, "creativeId"), field(ad, "signature"), sid), Map.of());
    }

    @Test
    void campanhaDoCadastroAoRelatorio() throws Exception {
        Map<String, String> admin = admin();
        String slot = newSlot(admin);
        String advertiser = newAdvertiser(admin);
        Instant now = Instant.now();
        String campaign = newCampaign(admin, advertiser, slot, now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.DAYS), null);
        addCreative(admin, campaign, "O mouse mais leve da marca");

        // Rascunho e aguardando aprovação não vão ao ar
        assertThat(ads(slot)).contains("\"items\":[]");
        assertThat(act(admin, campaign, "submit", null).body()).contains("\"status\":\"pending_approval\"");
        assertThat(ads(slot)).contains("\"items\":[]");

        // Aprovada e na vigência: no ar, para qualquer visitante (sem login)
        assertThat(act(admin, campaign, "approve", null).body()).contains("\"displayStatus\":\"active\"");
        String ad = ads(slot);
        assertThat(ad).contains("\"title\":\"Mouse Marca X\"", "\"advertiser\":\"Marca X\"", "\"format\":\"promo_card\"");

        // Impressão conta uma vez por sessão; assinatura errada não conta
        String sid = UUID.randomUUID().toString();
        assertThat(impression(ad, slot, sid).statusCode()).isEqualTo(204);
        assertThat(impression(ad, slot, sid).statusCode()).isEqualTo(204);
        assertThat(impression(ad, slot, UUID.randomUUID().toString()).statusCode()).isEqualTo(204);
        assertThat(impression(ad.replace(field(ad, "signature"), "0".repeat(32)), slot, sid).statusCode()).isEqualTo(400);

        // Clique registra e leva ao destino
        HttpResponse<String> go = call("GET", field(ad, "clickPath") + "&sid=" + sid, null, Map.of());
        assertThat(go.statusCode()).isEqualTo(302);
        assertThat(go.headers().firstValue("location")).hasValue("https://marcax.com.br/mouse");

        String report = call("GET", "/api/v1/admin/ads/campaigns/" + campaign + "/report", null, admin).body();
        assertThat(report).contains("\"impressions\":2", "\"clicks\":1", "\"ctr\":50.0", "\"key\":\"" + slot + "\"");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ad_events WHERE session_hash = ?", Integer.class, sid)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'admin.ad_campaign_approve'", Integer.class))
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void regrasDeAprovacaoVigenciaELimite() throws Exception {
        Map<String, String> admin = admin();
        String slot = newSlot(admin);
        String advertiser = newAdvertiser(admin);
        Instant now = Instant.now();

        // Criativo que não serve no formato (texto acima do limite do espaço) barra o envio
        String campaign = newCampaign(admin, advertiser, slot, now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.DAYS), 1);
        addCreative(admin, campaign, "x".repeat(120));
        HttpResponse<String> blocked = act(admin, campaign, "submit", null);
        assertThat(blocked.statusCode()).isEqualTo(400);
        assertThat(blocked.body()).contains("texto com mais de 80 caracteres");

        addCreative(admin, campaign, "Texto curto");
        act(admin, campaign, "submit", null);
        // Reprovar exige motivo
        assertThat(act(admin, campaign, "reject", null).statusCode()).isEqualTo(400);
        act(admin, campaign, "approve", null);
        String ad = ads(slot);
        assertThat(ad).contains("Texto curto");

        // Limite de impressões atingido: encerra
        impression(ad, slot, UUID.randomUUID().toString());
        assertThat(ads(slot)).contains("\"items\":[]");
        assertThat(call("GET", "/api/v1/admin/ads/campaigns/" + campaign, null, admin).body()).contains("\"displayStatus\":\"ended\"");

        // Editar uma campanha aprovada a devolve para aprovação
        String other = newCampaign(admin, advertiser, slot, now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.DAYS), null);
        addCreative(admin, other, "Outro texto");
        act(admin, other, "submit", null);
        act(admin, other, "approve", null);
        assertThat(ads(slot)).contains("Outro texto");
        addCreative(admin, other, "Mudança sem aprovação");
        assertThat(call("GET", "/api/v1/admin/ads/campaigns/" + other, null, admin).body()).contains("\"status\":\"pending_approval\"");
        assertThat(ads(slot)).contains("\"items\":[]");

        // Pausada não aparece; agendada (início no futuro) também não
        act(admin, other, "approve", null);
        act(admin, other, "pause", null);
        assertThat(ads(slot)).contains("\"items\":[]");
        String future = newCampaign(admin, advertiser, slot, now.plus(2, ChronoUnit.DAYS), now.plus(3, ChronoUnit.DAYS), null);
        addCreative(admin, future, "Ainda não começou");
        act(admin, future, "submit", null);
        assertThat(act(admin, future, "approve", null).body()).contains("\"displayStatus\":\"scheduled\"");
        assertThat(ads(slot)).doesNotContain("Ainda não começou");
    }

    @Test
    void soAdminGerenciaEOConteudoESeguro() throws Exception {
        Map<String, String> user = newUser();
        Map<String, String> admin = admin();
        assertThat(call("GET", "/api/v1/admin/ads/campaigns", null, user).statusCode()).isEqualTo(403);
        assertThat(call("POST", "/api/v1/admin/ads/slots", "{\"slotKey\":\"x_y\",\"name\":\"Xis\",\"page\":\"feed\",\"format\":\"banner\"}",
                user).statusCode()).isEqualTo(403);

        String advertiser = newAdvertiser(admin);
        // Documento mascarado na lista, inteiro só na ficha (com auditoria)
        assertThat(call("GET", "/api/v1/admin/ads/advertisers", null, admin).body()).contains("\"documentNumber\":\"***0181\"");
        assertThat(call("GET", "/api/v1/admin/ads/advertisers/" + advertiser, null, admin).body()).contains("\"documentNumber\":\"11222333000181\"");
        assertThat(call("POST", "/api/v1/admin/ads/advertisers", "{\"displayName\":\"Doc errado\",\"documentNumber\":\"111.111.111-11\"}",
                admin).statusCode()).isEqualTo(400);

        String slot = newSlot(admin);
        Instant now = Instant.now();
        String campaign = newCampaign(admin, advertiser, slot, now, now.plus(1, ChronoUnit.DAYS), null);
        for (String body : new String[] {
                "{\"title\":\"A\",\"description\":\"B\",\"destinationUrl\":\"http://marcax.com.br\"}",
                "{\"title\":\"A\",\"description\":\"B\",\"destinationUrl\":\"https://localhost/x\"}",
                "{\"title\":\"A\",\"description\":\"B\",\"destinationUrl\":\"javascript:alert(1)\"}",
                "{\"title\":\"A\",\"description\":\"B\",\"imageUrl\":\"https://outro.site/x.png\",\"destinationUrl\":\"https://marcax.com.br\"}"}) {
            assertThat(call("POST", "/api/v1/admin/ads/campaigns/" + campaign + "/creatives", body, admin).statusCode())
                    .as(body).isEqualTo(400);
        }

        // Imagem enviada pelo painel: pública e sem metadados
        String image = upload(admin);
        assertThat(image).startsWith("/uploads/ads/");
        assertThat(call("GET", image, null, Map.of()).statusCode()).isEqualTo(200);

        // Espaço inativo não mostra nada; espaço que não existe também não
        assertThat(ads("nao_existe")).contains("\"items\":[]");
    }

    private String upload(Map<String, String> admin) throws Exception {
        java.awt.image.BufferedImage picture = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(picture, "png", png);
        String boundary = "limite";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.png\"\r\nContent-Type: image/png\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.writeBytes(png.toByteArray());
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/admin/ads/images"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        admin.forEach(request::header);
        return field(HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString()).body(), "url");
    }
}
