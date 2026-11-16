package com.gearhub.marketplace;

import com.gearhub.admin.AuditService;
import com.gearhub.config.FieldValidationException;
import com.gearhub.config.SearchText;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Loja aprovada e os produtos dela: o lojista edita, a vitrine pública mostra só o que está ativo, e o clique
 * em "Comprar na loja" passa por aqui (registra e leva ao site da loja). O admin tira do ar sem apagar.
 */
@Service
public class StoreService {

    private static final Set<String> AVAILABILITY = Set.of("in_stock", "out_of_stock", "preorder");
    private static final Set<String> PRODUCT_STATUS = Set.of("active", "paused", "archived");
    private static final int PAGE_SIZE = 24;
    private static final int MAX_PRODUCTS = 500;

    /** Produto aparece na vitrine: loja ativa, produto ativo, sem moderação e nada apagado. */
    // Começa com espaço: o bloco de texto que vem antes (terminado em WHERE/AND) perde o espaço do fim da linha
    private static final String VISIBLE = " " + """
            s.status = 'active' AND s.deleted_at IS NULL
            AND p.status = 'active' AND p.removed_at IS NULL AND p.deleted_at IS NULL""";

    @Schema(description = "Loja na vitrine")
    public record StoreCard(String slug, String name, String logoUrl, String segment, int productCount) {
    }

    @Schema(description = "Página pública da loja")
    public record StorePage(String slug, String name, String description, String logoUrl, String bannerUrl, String segment,
            String website, String instagram, List<ProductCard> products) {
    }

    @Schema(description = "Produto na vitrine")
    public record ProductCard(UUID id, String name, @Schema(description = "Informativo; quem vende é a loja") BigDecimal price,
            String image, String availability, String category, String storeSlug, String storeName) {
    }

    @Schema(description = "Página pública do produto. A compra acontece no site da loja, pelo link `buyPath`")
    public record ProductPage(UUID id, String name, String description, List<String> images, BigDecimal price,
            String availability, String category, String storeSlug, String storeName, String storeLogoUrl,
            @Schema(description = "Caminho que registra o clique e leva ao site da loja") String buyPath) {
    }

    @Schema(description = "Página da vitrine")
    public record ShowcasePage(List<ProductCard> items, @Schema(description = "Número da próxima página; nulo no fim") Integer nextPage) {
    }

    @Schema(description = "A loja, como o lojista vê")
    public record MyStore(UUID id, String slug, String name, String description, String logoUrl, String bannerUrl,
            String segment, String website, String instagram,
            @Schema(allowableValues = {"active", "suspended"}) String status, String suspendedNote,
            @Schema(description = "Cliques em \"Comprar na loja\" nos últimos 30 dias") long clicksLast30Days) {
    }

    @Schema(description = "Produto, como o lojista vê")
    public record MyProduct(UUID id, String name, String description, List<String> images, String category, BigDecimal price,
            String availability, String purchaseUrl, @Schema(allowableValues = {"active", "paused", "archived"}) String status,
            @Schema(description = "Motivo, se o admin tirou do ar") String removedNote, Instant createdAt, Instant updatedAt,
            long clicksLast30Days) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final StoreImageService images;
    private final AuditService audit;

    public StoreService(JdbcClient jdbc, ObjectMapper json, StoreImageService images, AuditService audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.images = images;
        this.audit = audit;
    }

    // Lojista -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public MyStore myStore(UUID userId) {
        return jdbc.sql("""
                SELECT s.*, (SELECT count(*) FROM click_events c WHERE c.store_id = s.id AND c.created_at > now() - interval '30 days') AS clicks
                FROM partner_stores s WHERE s.owner_id = :owner AND s.deleted_at IS NULL
                """).param("owner", userId)
                .query((rs, row) -> new MyStore(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("name"),
                        rs.getString("description"), rs.getString("logo_url"), rs.getString("banner_url"), rs.getString("segment"),
                        rs.getString("website"), rs.getString("instagram"), rs.getString("status"), rs.getString("suspended_note"),
                        rs.getLong("clicks")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Você ainda não tem uma loja. Peça a sua em Marketplace."));
    }

    @Transactional
    public MyStore updateStore(UUID userId, MarketplaceRequests.SaveStore request) {
        UUID storeId = storeIdOf(userId);
        Map<String, Object> before = jdbc.sql("SELECT name, description, logo_url, banner_url, segment, website, instagram FROM partner_stores WHERE id = :id")
                .param("id", storeId).query().singleRow();
        requireOwnImage("logoUrl", request.logoUrl());
        requireOwnImage("bannerUrl", request.bannerUrl());
        jdbc.sql("""
                UPDATE partner_stores SET name = :name, description = :description, logo_url = :logo, banner_url = :banner,
                       segment = :segment, website = :website, instagram = :instagram, updated_at = now()
                WHERE id = :id
                """)
                .param("name", request.name().strip())
                .param("description", blank(request.description()), Types.VARCHAR)
                .param("logo", blank(request.logoUrl()), Types.VARCHAR)
                .param("banner", blank(request.bannerUrl()), Types.VARCHAR)
                .param("segment", blank(request.segment()), Types.VARCHAR)
                .param("website", blank(request.website()), Types.VARCHAR)
                .param("instagram", request.instagram() == null ? null : blank(request.instagram().replaceFirst("^@", "")), Types.VARCHAR)
                .param("id", storeId)
                .update();
        audit.record(userId, "store.update", "partner_store", storeId, Map.of("before", before));
        return myStore(userId);
    }

    @Transactional(readOnly = true)
    public List<MyProduct> myProducts(UUID userId) {
        UUID storeId = storeIdOf(userId);
        return jdbc.sql("""
                SELECT p.*, c.slug AS category_slug,
                       (SELECT count(*) FROM click_events e WHERE e.product_id = p.id AND e.created_at > now() - interval '30 days') AS clicks
                FROM store_products p LEFT JOIN categories c ON c.id = p.category_id
                WHERE p.store_id = :store AND p.deleted_at IS NULL
                ORDER BY p.created_at DESC
                """).param("store", storeId)
                .query((rs, row) -> myProduct(rs))
                .list();
    }

    @Transactional
    public MyProduct createProduct(UUID userId, MarketplaceRequests.SaveProduct request) {
        UUID storeId = storeIdOf(userId);
        long count = jdbc.sql("SELECT count(*) FROM store_products WHERE store_id = :store AND deleted_at IS NULL")
                .param("store", storeId).query(Long.class).single();
        if (count >= MAX_PRODUCTS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Sua loja chegou ao limite de " + MAX_PRODUCTS + " produtos.");
        }
        ValidProduct valid = validate(request);
        UUID id = jdbc.sql("""
                INSERT INTO store_products (store_id, name, description, images, category_id, price, availability, purchase_url)
                VALUES (:store, :name, :description, cast(:images AS jsonb), :category, :price, :availability, :url)
                RETURNING id
                """)
                .param("store", storeId)
                .param("name", request.name().strip())
                .param("description", blank(request.description()), Types.VARCHAR)
                .param("images", json.writeValueAsString(valid.images()))
                .param("category", valid.categoryId(), Types.INTEGER)
                .param("price", request.price(), Types.NUMERIC)
                .param("availability", valid.availability())
                .param("url", valid.purchaseUrl())
                .query(UUID.class).single();
        audit.record(userId, "store.product_create", "store_product", id, Map.of("name", request.name().strip()));
        return product(storeId, id);
    }

    @Transactional
    public MyProduct updateProduct(UUID userId, UUID productId, MarketplaceRequests.SaveProduct request) {
        UUID storeId = storeIdOf(userId);
        Map<String, Object> before = ownProduct(storeId, productId);
        ValidProduct valid = validate(request);
        jdbc.sql("""
                UPDATE store_products SET name = :name, description = :description, images = cast(:images AS jsonb),
                       category_id = :category, price = :price, availability = :availability, purchase_url = :url, updated_at = now()
                WHERE id = :id
                """)
                .param("name", request.name().strip())
                .param("description", blank(request.description()), Types.VARCHAR)
                .param("images", json.writeValueAsString(valid.images()))
                .param("category", valid.categoryId(), Types.INTEGER)
                .param("price", request.price(), Types.NUMERIC)
                .param("availability", valid.availability())
                .param("url", valid.purchaseUrl())
                .param("id", productId)
                .update();
        // Versão anterior na auditoria, inclusive o link de compra (onde mora o risco de golpe)
        audit.record(userId, "store.product_update", "store_product", productId, Map.of("before", before));
        return product(storeId, productId);
    }

    @Transactional
    public MyProduct setProductStatus(UUID userId, UUID productId, String status) {
        if (!PRODUCT_STATUS.contains(status)) {
            throw new FieldValidationException(Map.of("status", "Use active, paused ou archived"));
        }
        UUID storeId = storeIdOf(userId);
        Map<String, Object> before = ownProduct(storeId, productId);
        jdbc.sql("UPDATE store_products SET status = :status, updated_at = now() WHERE id = :id")
                .param("status", status).param("id", productId).update();
        audit.record(userId, "store.product_status", "store_product", productId, Map.of("from", before.get("status"), "to", status));
        return product(storeId, productId);
    }

    /** Remover tira o produto da loja e da vitrine (exclusão lógica: o registro e os cliques ficam). */
    @Transactional
    public void deleteProduct(UUID userId, UUID productId) {
        UUID storeId = storeIdOf(userId);
        ownProduct(storeId, productId);
        jdbc.sql("UPDATE store_products SET deleted_at = now() WHERE id = :id").param("id", productId).update();
        audit.record(userId, "store.product_delete", "store_product", productId, Map.of());
    }

    // Vitrine pública -----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<StoreCard> stores() {
        return jdbc.sql("""
                SELECT s.slug, s.name, s.logo_url, s.segment,
                       (SELECT count(*) FROM store_products p WHERE p.store_id = s.id AND p.status = 'active'
                          AND p.removed_at IS NULL AND p.deleted_at IS NULL) AS products
                FROM partner_stores s WHERE s.status = 'active' AND s.deleted_at IS NULL
                ORDER BY s.name LIMIT 200
                """)
                .query((rs, row) -> new StoreCard(rs.getString("slug"), rs.getString("name"), rs.getString("logo_url"),
                        rs.getString("segment"), rs.getInt("products")))
                .list();
    }

    @Transactional(readOnly = true)
    public StorePage store(String slug) {
        Map<String, Object> store = jdbc.sql("""
                SELECT id, slug, name, description, logo_url, banner_url, segment, website, instagram FROM partner_stores
                WHERE slug = :slug AND status = 'active' AND deleted_at IS NULL
                """).param("slug", slug).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Loja não encontrada"));
        List<ProductCard> products = jdbc.sql(CARD_SQL + " AND s.id = :store ORDER BY p.created_at DESC LIMIT 200")
                .param("store", store.get("id"))
                .query((rs, row) -> card(rs)).list();
        return new StorePage((String) store.get("slug"), (String) store.get("name"), (String) store.get("description"),
                (String) store.get("logo_url"), (String) store.get("banner_url"), (String) store.get("segment"),
                (String) store.get("website"), (String) store.get("instagram"), products);
    }

    private static final String CARD_SQL = """
            SELECT p.id, p.name, p.price, p.images ->> 0 AS image, p.availability, c.name AS category,
                   s.slug AS store_slug, s.name AS store_name
            FROM store_products p JOIN partner_stores s ON s.id = p.store_id
            LEFT JOIN categories c ON c.id = p.category_id
            WHERE """ + VISIBLE;

    /** Vitrine: busca sem acento por nome, descrição, loja e categoria; filtro por categoria. */
    @Transactional(readOnly = true)
    public ShowcasePage showcase(String q, String category, int page) {
        int current = Math.clamp(page, 1, 100);
        boolean search = q != null && q.strip().length() >= 2;
        boolean filtered = category != null && !category.isBlank();
        String sql = CARD_SQL
                + (search ? " AND gh_unaccent(concat_ws(' ', p.name, p.description, s.name, c.name)) LIKE :q" : "")
                + (filtered ? " AND c.slug = :category" : "")
                + " ORDER BY p.created_at DESC, p.id DESC LIMIT :limit OFFSET :offset";
        JdbcClient.StatementSpec statement = jdbc.sql(sql).param("limit", PAGE_SIZE + 1).param("offset", (current - 1) * PAGE_SIZE);
        if (search) {
            statement = statement.param("q", SearchText.pattern(q));
        }
        if (filtered) {
            statement = statement.param("category", category);
        }
        List<ProductCard> items = statement.query((rs, row) -> card(rs)).list();
        boolean more = items.size() > PAGE_SIZE;
        return new ShowcasePage(more ? items.subList(0, PAGE_SIZE) : items, more ? current + 1 : null);
    }

    @Transactional(readOnly = true)
    public ProductPage productPage(UUID id) {
        return jdbc.sql("""
                SELECT p.id, p.name, p.description, p.images::text AS images, p.price, p.availability, c.name AS category,
                       s.slug AS store_slug, s.name AS store_name, s.logo_url
                FROM store_products p JOIN partner_stores s ON s.id = p.store_id
                LEFT JOIN categories c ON c.id = p.category_id
                WHERE p.id = :id AND """ + VISIBLE)
                .param("id", id)
                .query((rs, row) -> new ProductPage(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description"),
                        list(rs.getString("images")), rs.getBigDecimal("price"), rs.getString("availability"),
                        rs.getString("category"), rs.getString("store_slug"), rs.getString("store_name"), rs.getString("logo_url"),
                        "/api/v1/marketplace/go/" + rs.getObject("id", UUID.class)))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Produto não encontrado"));
    }

    /**
     * "Comprar na loja": registra o clique e devolve o link da loja com o click_id ({@code gh_click}), para a venda
     * poder ser atribuída depois. Só leva ao link cadastrado no produto: não é um redirecionamento aberto.
     */
    @Transactional
    public URI click(UUID productId, String source, UUID userId) {
        Map<String, Object> product = jdbc.sql("""
                SELECT p.store_id, p.purchase_url FROM store_products p JOIN partner_stores s ON s.id = p.store_id
                WHERE p.id = :id AND """ + VISIBLE)
                .param("id", productId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Produto não encontrado"));
        String cleanSource = source == null ? null : source.replaceAll("[^a-z0-9_-]", "");
        UUID clickId = jdbc.sql("""
                INSERT INTO click_events (product_id, store_id, user_id, source) VALUES (:product, :store, :user, :source)
                RETURNING id
                """)
                .param("product", productId).param("store", product.get("store_id"))
                .param("user", userId, Types.OTHER)
                .param("source", cleanSource == null || cleanSource.isEmpty() ? null : cleanSource.substring(0, Math.min(40, cleanSource.length())), Types.VARCHAR)
                .query(UUID.class).single();
        String url = (String) product.get("purchase_url");
        try {
            URI target = new URI(url);
            int hash = url.indexOf('#');
            String base = hash < 0 ? url : url.substring(0, hash);
            String fragment = hash < 0 ? "" : url.substring(hash);
            return new URI(base + (target.getRawQuery() == null ? "?" : "&") + "gh_click=" + clickId + fragment);
        } catch (URISyntaxException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Link da loja indisponível");
        }
    }

    // Admin ------------------------------------------------------------------------------------

    @Transactional
    public void removeProduct(UUID adminId, UUID productId, String note) {
        int changed = jdbc.sql("""
                UPDATE store_products SET removed_at = now(), removed_by = :admin, removed_note = :note
                WHERE id = :id AND removed_at IS NULL AND deleted_at IS NULL
                """).param("admin", adminId).param("note", note).param("id", productId).update();
        if (changed == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Produto não encontrado ou já fora do ar");
        }
        audit.record(adminId, "admin.store_product_remove", "store_product", productId, Map.of("note", note));
    }

    @Transactional
    public void restoreProduct(UUID adminId, UUID productId) {
        int changed = jdbc.sql("UPDATE store_products SET removed_at = NULL, removed_by = NULL, removed_note = NULL WHERE id = :id AND removed_at IS NOT NULL")
                .param("id", productId).update();
        if (changed == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Produto não encontrado ou já no ar");
        }
        audit.record(adminId, "admin.store_product_restore", "store_product", productId, Map.of());
    }

    @Transactional
    public void setStoreStatus(UUID adminId, String slug, boolean active, String note) {
        int changed = jdbc.sql("""
                UPDATE partner_stores SET status = :status, suspended_note = :note, updated_at = now()
                WHERE slug = :slug AND deleted_at IS NULL
                """).param("status", active ? "active" : "suspended").param("note", active ? null : note, Types.VARCHAR)
                .param("slug", slug).update();
        if (changed == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Loja não encontrada");
        }
        audit.record(adminId, active ? "admin.store_reactivate" : "admin.store_suspend", "partner_store", null,
                Map.of("slug", slug, "note", note == null ? "" : note));
    }

    // Apoio --------------------------------------------------------------------------------------

    private record ValidProduct(List<String> images, Integer categoryId, String availability, String purchaseUrl) {
    }

    private ValidProduct validate(MarketplaceRequests.SaveProduct request) {
        List<String> sent = request.images() == null ? List.of()
                : request.images().stream().filter(url -> url != null && !url.isBlank()).distinct().toList();
        if (!sent.stream().allMatch(images::isOwnFile)) {
            throw new FieldValidationException(Map.of("images", "Envie as fotos pelo site"));
        }
        String availability = request.availability() == null || request.availability().isBlank() ? "in_stock" : request.availability();
        if (!AVAILABILITY.contains(availability)) {
            throw new FieldValidationException(Map.of("availability", "Disponibilidade inválida"));
        }
        Integer categoryId = null;
        if (request.category() != null && !request.category().isBlank()) {
            categoryId = jdbc.sql("SELECT id FROM categories WHERE slug = :slug").param("slug", request.category())
                    .query(Integer.class).optional()
                    .orElseThrow(() -> new FieldValidationException(Map.of("category", "Categoria não encontrada")));
        }
        return new ValidProduct(sent, categoryId, availability, safePurchaseUrl(request.purchaseUrl()));
    }

    /** Link de compra: https com um domínio de verdade (sem IP nem endereço interno), para levar só a sites públicos. */
    static String safePurchaseUrl(String url) {
        String clean = url == null ? "" : url.strip();
        try {
            URI uri = new URI(clean);
            String host = uri.getHost();
            boolean valid = "https".equalsIgnoreCase(uri.getScheme()) && host != null && host.contains(".")
                    && !host.matches("[0-9.]+") && !host.contains(":") && uri.getUserInfo() == null
                    && !host.equalsIgnoreCase("localhost") && !host.toLowerCase().endsWith(".local")
                    && !host.toLowerCase().endsWith(".internal");
            if (valid) {
                return clean;
            }
        } catch (URISyntaxException e) {
            // cai no erro abaixo
        }
        throw new FieldValidationException(Map.of("purchaseUrl", "Use o link do produto no site da sua loja, começando com https://"));
    }

    private void requireOwnImage(String field, String url) {
        if (url != null && !url.isBlank() && !images.isOwnFile(url)) {
            throw new FieldValidationException(Map.of(field, "Envie a imagem pelo site"));
        }
    }

    private UUID storeIdOf(UUID userId) {
        return jdbc.sql("SELECT id FROM partner_stores WHERE owner_id = :owner AND deleted_at IS NULL")
                .param("owner", userId).query(UUID.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Só lojas parceiras aprovadas gerenciam produtos."));
    }

    private Map<String, Object> ownProduct(UUID storeId, UUID productId) {
        return jdbc.sql("""
                SELECT name, description, images::text AS images, category_id, price, availability, purchase_url, status
                FROM store_products WHERE id = :id AND store_id = :store AND deleted_at IS NULL
                """).param("id", productId).param("store", storeId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Produto não encontrado"));
    }

    private MyProduct product(UUID storeId, UUID productId) {
        return jdbc.sql("""
                SELECT p.*, c.slug AS category_slug,
                       (SELECT count(*) FROM click_events e WHERE e.product_id = p.id AND e.created_at > now() - interval '30 days') AS clicks
                FROM store_products p LEFT JOIN categories c ON c.id = p.category_id
                WHERE p.id = :id AND p.store_id = :store
                """).param("id", productId).param("store", storeId)
                .query((rs, row) -> myProduct(rs)).single();
    }

    private MyProduct myProduct(ResultSet rs) throws SQLException {
        return new MyProduct(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description"),
                list(rs.getString("images")), rs.getString("category_slug"), rs.getBigDecimal("price"), rs.getString("availability"),
                rs.getString("purchase_url"), rs.getString("status"), rs.getString("removed_note"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(), rs.getLong("clicks"));
    }

    private static ProductCard card(ResultSet rs) throws SQLException {
        return new ProductCard(rs.getObject("id", UUID.class), rs.getString("name"), rs.getBigDecimal("price"),
                rs.getString("image"), rs.getString("availability"), rs.getString("category"),
                rs.getString("store_slug"), rs.getString("store_name"));
    }

    private List<String> list(String jsonList) {
        return jsonList == null ? List.of() : json.readValue(jsonList, new TypeReference<List<String>>() {
        });
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
