package com.gearhub;

import static org.assertj.core.api.Assertions.assertThat;

import com.gearhub.marketplace.BrazilianDocuments;
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

/**
 * Marketplace, pelo critério de conclusão da v1: alguém pede a loja, o admin aprova, o lojista publica produtos e o
 * visitante (sem login) abre a loja externa por um link que registra o clique. Mais os casos de abuso.
 */
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"gearhub.auth.register-limit-per-minute=1000", "gearhub.market.collect=false"})
class MarketplaceIntegrationTest {

    private static final String PASSWORD = "Senha-Forte-123";
    private static final String CPF = "529.982.247-25";
    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final byte[] PDF = "%PDF-1.4\n% documento de teste\n".getBytes(StandardCharsets.ISO_8859_1);

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
        Path uploads = Files.createTempDirectory("gearhub-marketplace");
        registry.add("gearhub.uploads.dir", uploads::toString);
    }

    @AfterAll
    static void stopPostgres() throws IOException {
        POSTGRES.close();
    }

    private record Person(String username, Map<String, String> auth) {
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

    private HttpResponse<String> upload(String path, String fileName, String type, byte[] file, Map<String, String> headers)
            throws Exception {
        String boundary = "limite";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName
                + "\"\r\nContent-Type: " + type + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(file);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        headers.forEach(request::header);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Person newPerson() throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        HttpResponse<String> created = call("POST", "/api/v1/auth/register", """
                {"displayName":"Pessoa","username":"m%s","email":"%s@exemplo.com","password":"%s","passwordConfirmation":"%s"}
                """.formatted(id, id, PASSWORD, PASSWORD), Map.of());
        assertThat(created.statusCode()).isEqualTo(201);
        return new Person("m" + id, Map.of("Cookie", created.headers().firstValue("set-cookie").orElseThrow().split(";")[0]));
    }

    private Person admin() throws Exception {
        Person admin = newPerson();
        jdbc.update("UPDATE users SET role = 'admin', two_factor_enabled_at = now() WHERE username = ?", admin.username());
        return admin;
    }

    /** CNPJ válido e diferente a cada teste (12 dígitos aleatórios e os dois verificadores calculados). */
    private static String newCnpj() {
        String base = String.format("%08d", (int) (Math.random() * 100_000_000)) + "0001";
        for (int d = 0; d < 100; d++) {
            String candidate = base + String.format("%02d", d);
            if (BrazilianDocuments.isValidCnpj(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException();
    }

    private String applicationJson(String cnpj, String tradeName) {
        return """
                {"responsibleName":"Maria Souza","cpf":"%s","contactEmail":"contato@loja.com","phone":"(11) 98765-4321",
                 "roleInCompany":"Sócia","cnpj":"%s","legalName":"%s Comércio LTDA","tradeName":"%s","segment":"Periféricos",
                 "address":"Rua A, 100, São Paulo","website":"https://loja.exemplo.com","description":"Loja de periféricos gamer"}
                """.formatted(CPF, cnpj, tradeName, tradeName);
    }

    /** Pede a loja, envia o documento e a solicitação; o admin aprova. Devolve o slug da loja. */
    private String approvedStore(Person owner, Person admin, String tradeName) throws Exception {
        assertThat(call("PUT", "/api/v1/marketplace/application", applicationJson(newCnpj(), tradeName), owner.auth()).statusCode())
                .isEqualTo(200);
        assertThat(upload("/api/v1/marketplace/application/documents?kind=cnpj_card", "cartao.pdf", "application/pdf", PDF,
                owner.auth()).statusCode()).isEqualTo(201);
        String submitted = call("POST", "/api/v1/marketplace/application/submit", null, owner.auth()).body();
        String id = submitted.replaceAll("^\\{\"id\":\"([0-9a-f-]{36})\".*", "$1");
        assertThat(call("POST", "/api/v1/admin/marketplace/applications/" + id + "/review", "{\"status\":\"approved\"}",
                admin.auth()).statusCode()).isEqualTo(200);
        return call("GET", "/api/v1/marketplace/my-store", null, owner.auth()).body().replaceAll(".*\"slug\":\"([^\"]+)\".*", "$1");
    }

    private String productJson(String name, String url) {
        return """
                {"name":"%s","description":"Mouse leve","price":399.90,"availability":"in_stock","category":"mouse","purchaseUrl":"%s"}
                """.formatted(name, url);
    }

    @Test
    void lojaDaSolicitacaoAoCliqueNaVitrine() throws Exception {
        Person owner = newPerson();
        Person admin = admin();

        // Rascunho salvo aos poucos; o envio cobra o que falta, inclusive o documento
        assertThat(call("PUT", "/api/v1/marketplace/application", "{\"tradeName\":\"Loja Rápida\"}", owner.auth()).statusCode())
                .isEqualTo(200);
        HttpResponse<String> incomplete = call("POST", "/api/v1/marketplace/application/submit", null, owner.auth());
        assertThat(incomplete.statusCode()).isEqualTo(400);
        assertThat(incomplete.body()).contains("\"cnpj\":", "\"documents\":");

        String tradeName = "Mega Loja " + UUID.randomUUID().toString().substring(0, 6);
        String slug = approvedStore(owner, admin, tradeName);
        String application = call("GET", "/api/v1/marketplace/application", null, owner.auth()).body();
        assertThat(application).contains("\"status\":\"approved\"", "\"cpfMasked\":\"***.***.247-25\"").doesNotContain("52998224725");
        // Notificação para o solicitante
        assertThat(call("GET", "/api/v1/me/notifications", null, owner.auth()).body()).contains("Sua loja foi aprovada");

        // O lojista publica um produto, com foto enviada pelo site
        String image = upload("/api/v1/marketplace/my-store/images", "foto.png", "image/png", pixelPng(), owner.auth()).body()
                .replaceAll(".*\"url\":\"([^\"]+)\".*", "$1");
        assertThat(image).startsWith("/uploads/store/");
        String body = productJson("Mouse Pro X", "https://loja.exemplo.com/mouse-pro-x?ref=gh")
                .replace("\"price\"", "\"images\":[\"" + image + "\"],\"price\"");
        HttpResponse<String> created = call("POST", "/api/v1/marketplace/my-store/products", body, owner.auth());
        assertThat(created.statusCode()).isEqualTo(201);
        String productId = created.body().replaceAll("^\\{\"id\":\"([0-9a-f-]{36})\".*", "$1");

        // Visitante, sem login: vitrine, loja, produto e a imagem pública
        assertThat(call("GET", "/api/v1/marketplace/products?q=mouse%20pro", null, Map.of()).body()).contains("Mouse Pro X");
        assertThat(call("GET", "/api/v1/marketplace/stores/" + slug, null, Map.of()).body()).contains(tradeName, "Mouse Pro X");
        assertThat(call("GET", "/api/v1/marketplace/products/" + productId, null, Map.of()).body())
                .contains("\"buyPath\":\"/api/v1/marketplace/go/" + productId + "\"");
        assertThat(call("GET", image, null, Map.of()).statusCode()).isEqualTo(200);

        // "Comprar na loja": registra o clique e leva à loja com o click_id
        HttpResponse<String> go = call("GET", "/api/v1/marketplace/go/" + productId + "?origem=produto", null, Map.of());
        assertThat(go.statusCode()).isEqualTo(302);
        String location = go.headers().firstValue("location").orElseThrow();
        assertThat(location).startsWith("https://loja.exemplo.com/mouse-pro-x?ref=gh&gh_click=");
        String clickId = location.replaceAll(".*gh_click=([0-9a-f-]{36}).*", "$1");
        assertThat(jdbc.queryForObject("SELECT source FROM click_events WHERE id = ?::uuid", String.class, clickId)).isEqualTo("produto");
        assertThat(call("GET", "/api/v1/marketplace/my-store", null, owner.auth()).body()).contains("\"clicksLast30Days\":1");

        // Pausado some da vitrine e o link para de funcionar
        call("POST", "/api/v1/marketplace/my-store/products/" + productId + "/status", "{\"status\":\"paused\"}", owner.auth());
        assertThat(call("GET", "/api/v1/marketplace/products/" + productId, null, Map.of()).statusCode()).isEqualTo(404);
        assertThat(call("GET", "/api/v1/marketplace/go/" + productId, null, Map.of()).statusCode()).isEqualTo(404);
    }

    @Test
    void solicitacaoValidaDocumentosEDados() throws Exception {
        Person person = newPerson();
        HttpResponse<String> invalid = call("PUT", "/api/v1/marketplace/application",
                "{\"cpf\":\"111.111.111-11\",\"cnpj\":\"12.345.678/0001-00\"}", person.auth());
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(invalid.body()).contains("\"cpf\":\"CPF inválido\"", "\"cnpj\":\"CNPJ inválido\"");

        // Documento: o tipo vem do conteúdo, não do nome
        HttpResponse<String> fake = upload("/api/v1/marketplace/application/documents?kind=cnpj_card", "falso.pdf",
                "application/pdf", "<html>não é pdf</html>".getBytes(StandardCharsets.UTF_8), person.auth());
        assertThat(fake.statusCode()).isEqualTo(400);

        // Guardado criptografado, fora das pastas publicadas
        assertThat(upload("/api/v1/marketplace/application/documents?kind=cnpj_card", "cartao.pdf", "application/pdf", PDF,
                person.auth()).statusCode()).isEqualTo(201);
        String stored = jdbc.queryForObject("""
                SELECT d.stored_name FROM partner_documents d JOIN partner_applications a ON a.id = d.application_id
                JOIN users u ON u.id = a.user_id WHERE u.username = ?
                """, String.class, person.username());
        assertThat(call("GET", "/uploads/partner-docs/" + stored, null, Map.of()).statusCode()).isIn(401, 403, 404);
        assertThat(call("GET", "/uploads/store/" + stored, null, Map.of()).statusCode()).isEqualTo(404);
    }

    @Test
    void adminAnalisaComHistoricoEJustificativa() throws Exception {
        Person owner = newPerson();
        Person admin = admin();
        call("PUT", "/api/v1/marketplace/application", applicationJson(newCnpj(), "Loja Análise"), owner.auth());
        upload("/api/v1/marketplace/application/documents?kind=cnpj_card", "cartao.pdf", "application/pdf", PDF, owner.auth());
        String id = call("POST", "/api/v1/marketplace/application/submit", null, owner.auth()).body()
                .replaceAll("^\\{\"id\":\"([0-9a-f-]{36})\".*", "$1");
        String review = "/api/v1/admin/marketplace/applications/" + id + "/review";

        // Só admin com 2FA
        assertThat(call("GET", "/api/v1/admin/marketplace/applications", null, owner.auth()).statusCode()).isEqualTo(403);
        assertThat(call("POST", review, "{\"status\":\"approved\"}", owner.auth()).statusCode()).isEqualTo(403);

        // Fila, ficha com CPF e documento, tudo na auditoria
        assertThat(call("GET", "/api/v1/admin/marketplace/applications?status=pending", null, admin.auth()).body()).contains(id);
        String file = call("GET", "/api/v1/admin/marketplace/applications/" + id, null, admin.auth()).body();
        assertThat(file).contains("\"cpf\":\"52998224725\"");
        String documentId = file.replaceAll(".*\"documents\":\\[\\{\"id\":\"([0-9a-f-]{36})\".*", "$1");
        HttpResponse<String> document = call("GET", "/api/v1/admin/marketplace/applications/" + id + "/documents/" + documentId,
                null, admin.auth());
        assertThat(document.statusCode()).isEqualTo(200);
        assertThat(document.body()).startsWith("%PDF");

        // Pedir informação exige justificativa; o solicitante corrige e reenvia
        assertThat(call("POST", review, "{\"status\":\"awaiting_info\"}", admin.auth()).statusCode()).isEqualTo(400);
        assertThat(call("POST", review, "{\"status\":\"awaiting_info\",\"note\":\"Envie o contrato social\"}", admin.auth())
                .statusCode()).isEqualTo(200);
        assertThat(call("GET", "/api/v1/marketplace/application", null, owner.auth()).body())
                .contains("\"status\":\"awaiting_info\"", "Envie o contrato social");
        upload("/api/v1/marketplace/application/documents?kind=social_contract", "contrato.pdf", "application/pdf", PDF, owner.auth());
        assertThat(call("POST", "/api/v1/marketplace/application/submit", null, owner.auth()).body()).contains("\"status\":\"pending\"");

        // Recusa com motivo; sem loja criada
        assertThat(call("POST", review, "{\"status\":\"rejected\",\"note\":\"CNPJ inativo\"}", admin.auth()).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/api/v1/marketplace/my-store", null, owner.auth()).statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM partner_application_events WHERE application_id = ?::uuid",
                Integer.class, id)).isGreaterThanOrEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action IN ('admin.partner_view', 'admin.partner_document')",
                Integer.class)).isGreaterThanOrEqualTo(2);
    }

    @Test
    void lojistaSoMexeNoQueEDeleELinksSeguros() throws Exception {
        Person admin = admin();
        Person owner = newPerson();
        Person intruder = newPerson();
        approvedStore(owner, admin, "Loja Dona " + UUID.randomUUID().toString().substring(0, 6));

        // Quem não tem loja não cadastra produto nem envia imagem pública
        assertThat(call("POST", "/api/v1/marketplace/my-store/products", productJson("Teste", "https://a.com.br/x"), intruder.auth())
                .statusCode()).isEqualTo(403);
        assertThat(upload("/api/v1/marketplace/my-store/images", "foto.png", "image/png", pixelPng(), intruder.auth()).statusCode())
                .isEqualTo(404);

        // Links de compra perigosos
        for (String url : new String[] {"http://loja.com/x", "https://localhost/x", "https://127.0.0.1/x", "javascript:alert(1)",
                "https://user@loja.com/x", "https://intranet/x"}) {
            assertThat(call("POST", "/api/v1/marketplace/my-store/products", productJson("Produto", url), owner.auth()).statusCode())
                    .as(url).isEqualTo(400);
        }
        // Imagem que não foi enviada pelo site
        String external = productJson("Produto", "https://loja.com.br/p").replace("\"price\"", "\"images\":[\"https://outro.site/x.png\"],\"price\"");
        assertThat(call("POST", "/api/v1/marketplace/my-store/products", external, owner.auth()).statusCode()).isEqualTo(400);

        // Produto da loja de outra pessoa
        String productId = call("POST", "/api/v1/marketplace/my-store/products", productJson("Teclado", "https://loja.com.br/t"),
                owner.auth()).body().replaceAll("^\\{\"id\":\"([0-9a-f-]{36})\".*", "$1");
        assertThat(call("PUT", "/api/v1/marketplace/my-store/products/" + productId, productJson("Golpe", "https://golpe.com/x"),
                intruder.auth()).statusCode()).isEqualTo(403);

        // Admin tira do ar sem apagar; some da vitrine, o lojista vê o motivo
        assertThat(call("POST", "/api/v1/admin/marketplace/products/" + productId + "/remove", "{\"note\":\"Produto falsificado\"}",
                admin.auth()).statusCode()).isEqualTo(204);
        assertThat(call("GET", "/api/v1/marketplace/products/" + productId, null, Map.of()).statusCode()).isEqualTo(404);
        assertThat(call("GET", "/api/v1/marketplace/my-store/products", null, owner.auth()).body()).contains("Produto falsificado");

        // Remover pelo lojista é exclusão lógica
        assertThat(call("DELETE", "/api/v1/marketplace/my-store/products/" + productId, null, owner.auth()).statusCode()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM store_products WHERE id = ?::uuid", Boolean.class, productId))
                .isTrue();

        // Um CNPJ, uma loja: outra pessoa não pede loja com o mesmo CNPJ em andamento
        Person copycat = newPerson();
        String cnpj = jdbc.queryForObject("""
                SELECT a.cnpj FROM partner_applications a JOIN users u ON u.id = a.user_id WHERE u.username = ? AND a.status = 'approved'
                """, String.class, owner.username());
        call("PUT", "/api/v1/marketplace/application", applicationJson(cnpj, "Cópia"), copycat.auth());
        upload("/api/v1/marketplace/application/documents?kind=cnpj_card", "cartao.pdf", "application/pdf", PDF, copycat.auth());
        assertThat(call("POST", "/api/v1/marketplace/application/submit", null, copycat.auth()).body())
                .contains("Esse CNPJ já tem uma loja");
    }

    /** PNG 1x1 de verdade, gerado aqui. */
    private static byte[] pixelPng() throws IOException {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
