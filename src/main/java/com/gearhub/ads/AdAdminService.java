package com.gearhub.ads;

import com.gearhub.admin.AuditService;
import com.gearhub.config.FieldValidationException;
import com.gearhub.crypto.CryptoService;
import com.gearhub.marketplace.BrazilianDocuments;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Publicidade no painel de admin: espaços, anunciantes, campanhas, criativos, aprovação e relatório.
 * Editar uma campanha aprovada (ou o criativo dela) a devolve para aprovação. Tudo vai para a auditoria.
 */
@Service
public class AdAdminService {

    private static final Set<String> COMMERCIAL = Set.of("fixed", "cpm", "cpc", "package", "exclusive");
    private static final Set<String> NEEDS_NEW_APPROVAL = Set.of("approved", "paused");

    @Schema(description = "Espaço de anúncio")
    public record Slot(int id, String slotKey, String name, String description, String page, String position, String format,
            Integer width, Integer height, String aspectRatio, int textLimit, int maxItems, int priority, boolean rotationEnabled,
            String status) {
    }

    @Schema(description = "Anunciante. O documento aparece inteiro só na ficha (o acesso fica na auditoria)")
    public record Advertiser(UUID id, String displayName, String legalName, String documentNumber, String contactName,
            String email, String phone, String logoUrl, String websiteUrl, List<String> socialLinks, String notes, String status,
            Instant createdAt) {
    }

    @Schema(description = "Criativo")
    public record Creative(UUID id, String title, String description, String imageUrl, String mobileImageUrl, String logoUrl,
            String destinationUrl, String ctaText, String altText, String status,
            @Schema(description = "Problemas para cada espaço da campanha, pelo formato; vazio se serve em todos") Map<String, List<String>> fitProblems) {
    }

    @Schema(description = "Campanha")
    public record Campaign(UUID id, UUID advertiserId, String advertiserName, String name, String description, Instant startAt,
            Instant endAt, int priority, String commercialModel, BigDecimal commercialValue, Integer impressionLimit,
            @Schema(allowableValues = {"draft", "pending_approval", "approved", "paused", "rejected"}) String status,
            @Schema(description = "O que vale agora: rascunho, aguardando aprovação, agendada, ativa, pausada, encerrada ou reprovada",
                    allowableValues = {"draft", "pending_approval", "scheduled", "active", "paused", "ended", "rejected"}) String displayStatus,
            String reviewNote, List<String> slotKeys, List<Creative> creatives, long impressions, long clicks) {
    }

    @Schema(description = "Relatório da campanha")
    public record Report(UUID campaignId, String name, long impressions, long clicks,
            @Schema(description = "Cliques / impressões x 100") double ctr, List<Row> bySlot, List<Row> byDay, List<Row> byCreative) {
        public record Row(String key, long impressions, long clicks, double ctr) {
        }
    }

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final CryptoService crypto;
    private final AdImageService images;
    private final AuditService audit;

    public AdAdminService(JdbcClient jdbc, ObjectMapper json, CryptoService crypto, AdImageService images, AuditService audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.crypto = crypto;
        this.images = images;
        this.audit = audit;
    }

    // Espaços ----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Slot> slots() {
        return jdbc.sql("SELECT * FROM ad_slots WHERE deleted_at IS NULL ORDER BY page, name").query((rs, row) -> slot(rs)).list();
    }

    @Transactional
    public Slot saveSlot(UUID adminId, Integer id, AdRequests.SaveSlot request) {
        if (!AdFormats.ALL.contains(request.format())) {
            throw new FieldValidationException(Map.of("format", "Use banner, panel, promo_card, brand ou institutional"));
        }
        String status = request.status() == null || request.status().isBlank() ? "active" : request.status();
        if (!Set.of("active", "inactive").contains(status)) {
            throw new FieldValidationException(Map.of("status", "Use active ou inactive"));
        }
        String sql = id == null ? """
                INSERT INTO ad_slots (slot_key, name, description, page, position, format, width, height, aspect_ratio,
                                      text_limit, max_items, priority, rotation_enabled, status)
                VALUES (:key, :name, :description, :page, :position, :format, :width, :height, :ratio,
                        :textLimit, :maxItems, :priority, :rotation, :status)
                RETURNING id
                """ : """
                UPDATE ad_slots SET slot_key = :key, name = :name, description = :description, page = :page, position = :position,
                       format = :format, width = :width, height = :height, aspect_ratio = :ratio, text_limit = :textLimit,
                       max_items = :maxItems, priority = :priority, rotation_enabled = :rotation, status = :status, updated_at = now()
                WHERE id = :id AND deleted_at IS NULL
                RETURNING id
                """;
        try {
            Integer saved = jdbc.sql(sql)
                    .param("key", request.slotKey()).param("name", request.name().strip())
                    .param("description", blank(request.description()), Types.VARCHAR)
                    .param("page", request.page().strip()).param("position", blank(request.position()), Types.VARCHAR)
                    .param("format", request.format())
                    .param("width", request.width(), Types.INTEGER).param("height", request.height(), Types.INTEGER)
                    .param("ratio", blank(request.aspectRatio()), Types.VARCHAR)
                    .param("textLimit", request.textLimit() == null ? 140 : request.textLimit())
                    .param("maxItems", request.maxItems() == null ? 1 : request.maxItems())
                    .param("priority", request.priority() == null ? 0 : request.priority())
                    .param("rotation", request.rotationEnabled() == null || request.rotationEnabled())
                    .param("status", status)
                    .param("id", id, Types.INTEGER)
                    .query(Integer.class).optional()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Espaço não encontrado"));
            audit.record(adminId, id == null ? "admin.ad_slot_create" : "admin.ad_slot_update", null, null,
                    Map.of("slot", request.slotKey()));
            return jdbc.sql("SELECT * FROM ad_slots WHERE id = :id").param("id", saved).query((rs, row) -> slot(rs)).single();
        } catch (DuplicateKeyException e) {
            throw new FieldValidationException(Map.of("slotKey", "Já existe um espaço com essa chave"));
        }
    }

    /** Exclusão lógica: o espaço some do site e do painel, mas o histórico de eventos fica. */
    @Transactional
    public void deleteSlot(UUID adminId, int id) {
        int changed = jdbc.sql("UPDATE ad_slots SET deleted_at = now(), status = 'inactive' WHERE id = :id AND deleted_at IS NULL")
                .param("id", id).update();
        if (changed == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Espaço não encontrado");
        }
        audit.record(adminId, "admin.ad_slot_delete", null, null, Map.of("slotId", id));
    }

    // Anunciantes ----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Advertiser> advertisers() {
        return jdbc.sql("SELECT * FROM advertisers WHERE deleted_at IS NULL ORDER BY display_name")
                .query((rs, row) -> advertiser(rs, false)).list();
    }

    @Transactional
    public Advertiser advertiser(UUID adminId, UUID id) {
        Advertiser found = jdbc.sql("SELECT * FROM advertisers WHERE id = :id AND deleted_at IS NULL").param("id", id)
                .query((rs, row) -> advertiser(rs, true)).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Anunciante não encontrado"));
        audit.record(adminId, "admin.advertiser_view", null, null, Map.of("advertiser", id.toString()));
        return found;
    }

    @Transactional
    public Advertiser saveAdvertiser(UUID adminId, UUID id, AdRequests.SaveAdvertiser request) {
        String document = BrazilianDocuments.digits(request.documentNumber());
        if (!document.isEmpty() && !(document.length() == 11 ? BrazilianDocuments.isValidCpf(document)
                : document.length() == 14 && BrazilianDocuments.isValidCnpj(document))) {
            throw new FieldValidationException(Map.of("documentNumber", "CPF ou CNPJ inválido"));
        }
        String phone = BrazilianDocuments.digits(request.phone());
        if (!phone.isEmpty() && !phone.matches("[0-9]{10,11}")) {
            throw new FieldValidationException(Map.of("phone", "Informe o telefone com DDD"));
        }
        requireOwnImage("logoUrl", request.logoUrl());
        String status = request.status() == null || request.status().isBlank() ? "active" : request.status();
        if (!Set.of("active", "inactive").contains(status)) {
            throw new FieldValidationException(Map.of("status", "Use active ou inactive"));
        }
        String sql = id == null ? """
                INSERT INTO advertisers (display_name, legal_name, document_encrypted, contact_name, email, phone, logo_url,
                                         website_url, social_links, notes, status)
                VALUES (:name, :legal, :document, :contact, :email, :phone, :logo, :website, cast(:social AS jsonb), :notes, :status)
                RETURNING id
                """ : """
                UPDATE advertisers SET display_name = :name, legal_name = :legal, document_encrypted = coalesce(:document, document_encrypted),
                       contact_name = :contact, email = :email, phone = :phone, logo_url = :logo, website_url = :website,
                       social_links = cast(:social AS jsonb), notes = :notes, status = :status, updated_at = now()
                WHERE id = :id AND deleted_at IS NULL
                RETURNING id
                """;
        UUID saved = jdbc.sql(sql)
                .param("name", request.displayName().strip())
                .param("legal", blank(request.legalName()), Types.VARCHAR)
                .param("document", document.isEmpty() ? null : crypto.encrypt(document), Types.VARCHAR)
                .param("contact", blank(request.contactName()), Types.VARCHAR)
                .param("email", blank(request.email()), Types.VARCHAR)
                .param("phone", phone.isEmpty() ? null : phone, Types.VARCHAR)
                .param("logo", blank(request.logoUrl()), Types.VARCHAR)
                .param("website", blank(request.websiteUrl()), Types.VARCHAR)
                .param("social", json.writeValueAsString(request.socialLinks() == null ? List.of() : request.socialLinks()))
                .param("notes", blank(request.notes()), Types.VARCHAR)
                .param("status", status)
                .param("id", id, Types.OTHER)
                .query(UUID.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Anunciante não encontrado"));
        audit.record(adminId, id == null ? "admin.advertiser_create" : "admin.advertiser_update", null, null,
                Map.of("advertiser", saved.toString()));
        return advertiser(adminId, saved);
    }

    /** Exclusão lógica. As campanhas dele deixam de aparecer no site. */
    @Transactional
    public void deleteAdvertiser(UUID adminId, UUID id) {
        int changed = jdbc.sql("UPDATE advertisers SET deleted_at = now(), status = 'inactive' WHERE id = :id AND deleted_at IS NULL")
                .param("id", id).update();
        if (changed == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Anunciante não encontrado");
        }
        audit.record(adminId, "admin.advertiser_delete", null, null, Map.of("advertiser", id.toString()));
    }

    // Campanhas --------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Campaign> campaigns(String advertiserId) {
        UUID advertiser = advertiserId == null || advertiserId.isBlank() ? null : parseId(advertiserId, "advertiserId");
        return jdbc.sql("""
                SELECT id FROM ad_campaigns WHERE deleted_at IS NULL AND (CAST(:advertiser AS uuid) IS NULL OR advertiser_id = :advertiser)
                ORDER BY start_at DESC LIMIT 200
                """).param("advertiser", advertiser, Types.OTHER)
                .query(UUID.class).list().stream().map(this::campaign).toList();
    }

    @Transactional(readOnly = true)
    public Campaign campaign(UUID id) {
        Map<String, Object> row = jdbc.sql("""
                SELECT c.*, a.display_name AS advertiser_name,
                       (SELECT count(*) FROM ad_events e WHERE e.campaign_id = c.id AND e.event_type = 'impression') AS impressions,
                       (SELECT count(*) FROM ad_events e WHERE e.campaign_id = c.id AND e.event_type = 'click') AS clicks
                FROM ad_campaigns c JOIN advertisers a ON a.id = c.advertiser_id
                WHERE c.id = :id AND c.deleted_at IS NULL
                """).param("id", id).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Campanha não encontrada"));
        List<Map<String, Object>> slots = jdbc.sql("""
                SELECT s.slot_key, s.format, s.text_limit FROM ad_campaign_slots cs JOIN ad_slots s ON s.id = cs.slot_id
                WHERE cs.campaign_id = :id AND s.deleted_at IS NULL ORDER BY s.slot_key
                """).param("id", id).query().listOfRows();
        List<Creative> creatives = jdbc.sql("SELECT * FROM ad_creatives WHERE campaign_id = :id AND deleted_at IS NULL ORDER BY created_at")
                .param("id", id).query((rs, n) -> creative(rs, slots)).list();
        long impressions = ((Number) row.get("impressions")).longValue();
        Integer limit = (Integer) row.get("impression_limit");
        Instant start = ((Timestamp) row.get("start_at")).toInstant();
        Instant end = ((Timestamp) row.get("end_at")).toInstant();
        return new Campaign(id, (UUID) row.get("advertiser_id"), (String) row.get("advertiser_name"), (String) row.get("name"),
                (String) row.get("description"), start, end, (Integer) row.get("priority"), (String) row.get("commercial_model"),
                (BigDecimal) row.get("commercial_value"), limit, (String) row.get("status"),
                displayStatus((String) row.get("status"), start, end, impressions, limit), (String) row.get("review_note"),
                slots.stream().map(slot -> (String) slot.get("slot_key")).toList(), creatives, impressions,
                ((Number) row.get("clicks")).longValue());
    }

    /** O que vale agora para uma campanha. */
    static String displayStatus(String status, Instant start, Instant end, long impressions, Integer limit) {
        if (!"approved".equals(status)) {
            return status;
        }
        Instant now = Instant.now();
        if (now.isBefore(start)) {
            return "scheduled";
        }
        if (!now.isBefore(end) || (limit != null && impressions >= limit)) {
            return "ended";
        }
        return "active";
    }

    @Transactional
    public Campaign saveCampaign(UUID adminId, UUID id, AdRequests.SaveCampaign request) {
        UUID advertiserId = parseId(request.advertiserId(), "advertiserId");
        boolean advertiserOk = jdbc.sql("SELECT count(*) FROM advertisers WHERE id = :id AND deleted_at IS NULL")
                .param("id", advertiserId).query(Long.class).single() > 0;
        if (!advertiserOk) {
            throw new FieldValidationException(Map.of("advertiserId", "Anunciante não encontrado"));
        }
        if (!request.endAt().isAfter(request.startAt())) {
            throw new FieldValidationException(Map.of("endAt", "O fim precisa ser depois do início"));
        }
        String model = request.commercialModel() == null || request.commercialModel().isBlank() ? "fixed" : request.commercialModel();
        if (!COMMERCIAL.contains(model)) {
            throw new FieldValidationException(Map.of("commercialModel", "Use fixed, cpm, cpc, package ou exclusive"));
        }
        List<Integer> slotIds = slotIds(request.slotKeys());
        UUID saved;
        if (id == null) {
            saved = jdbc.sql("""
                    INSERT INTO ad_campaigns (advertiser_id, name, description, start_at, end_at, priority, commercial_model,
                                              commercial_value, impression_limit, created_by)
                    VALUES (:advertiser, :name, :description, :start, :end, :priority, :model, :value, :limit, :admin)
                    RETURNING id
                    """)
                    .param("advertiser", advertiserId).param("name", request.name().strip())
                    .param("description", blank(request.description()), Types.VARCHAR)
                    .param("start", Timestamp.from(request.startAt())).param("end", Timestamp.from(request.endAt()))
                    .param("priority", request.priority() == null ? 0 : request.priority())
                    .param("model", model).param("value", request.commercialValue(), Types.NUMERIC)
                    .param("limit", request.impressionLimit(), Types.INTEGER).param("admin", adminId)
                    .query(UUID.class).single();
        } else {
            Map<String, Object> before = before(id);
            jdbc.sql("""
                    UPDATE ad_campaigns SET advertiser_id = :advertiser, name = :name, description = :description, start_at = :start,
                           end_at = :end, priority = :priority, commercial_model = :model, commercial_value = :value,
                           impression_limit = :limit, updated_at = now()
                    WHERE id = :id
                    """)
                    .param("advertiser", advertiserId).param("name", request.name().strip())
                    .param("description", blank(request.description()), Types.VARCHAR)
                    .param("start", Timestamp.from(request.startAt())).param("end", Timestamp.from(request.endAt()))
                    .param("priority", request.priority() == null ? 0 : request.priority())
                    .param("model", model).param("value", request.commercialValue(), Types.NUMERIC)
                    .param("limit", request.impressionLimit(), Types.INTEGER).param("id", id)
                    .update();
            needsApprovalAgain(id, (String) before.get("status"));
            saved = id;
            audit.record(adminId, "admin.ad_campaign_update", null, null, Map.of("campaign", id.toString(), "before", before));
        }
        jdbc.sql("DELETE FROM ad_campaign_slots WHERE campaign_id = :id").param("id", saved).update();
        for (Integer slotId : slotIds) {
            jdbc.sql("INSERT INTO ad_campaign_slots (campaign_id, slot_id) VALUES (:campaign, :slot)")
                    .param("campaign", saved).param("slot", slotId).update();
        }
        if (id == null) {
            audit.record(adminId, "admin.ad_campaign_create", null, null, Map.of("campaign", saved.toString()));
        }
        return campaign(saved);
    }

    @Transactional
    public void deleteCampaign(UUID adminId, UUID id) {
        int changed = jdbc.sql("UPDATE ad_campaigns SET deleted_at = now() WHERE id = :id AND deleted_at IS NULL").param("id", id).update();
        if (changed == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Campanha não encontrada");
        }
        audit.record(adminId, "admin.ad_campaign_delete", null, null, Map.of("campaign", id.toString()));
    }

    /** Enviar para aprovação, aprovar, reprovar, pausar ou retomar. */
    @Transactional
    public Campaign act(UUID adminId, UUID id, AdRequests.CampaignAction request) {
        String status = (String) before(id).get("status");
        String note = blank(request.note());
        String next = switch (request.action()) {
            case "submit" -> Set.of("draft", "rejected").contains(status) ? "pending_approval" : null;
            case "approve" -> "pending_approval".equals(status) ? "approved" : null;
            case "reject" -> "pending_approval".equals(status) ? "rejected" : null;
            case "pause" -> "approved".equals(status) ? "paused" : null;
            case "resume" -> "paused".equals(status) ? "approved" : null;
            default -> throw new FieldValidationException(Map.of("action", "Use submit, approve, reject, pause ou resume"));
        };
        if (next == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Essa ação não vale para uma campanha " + status + ".");
        }
        if ("reject".equals(request.action()) && note == null) {
            throw new FieldValidationException(Map.of("note", "Explique o motivo da reprovação"));
        }
        if ("approve".equals(request.action()) || "submit".equals(request.action())) {
            requireServable(id);
        }
        boolean review = Set.of("approve", "reject").contains(request.action());
        jdbc.sql("""
                UPDATE ad_campaigns SET status = :status, updated_at = now(),
                       review_note = CASE WHEN :review THEN :note ELSE review_note END,
                       reviewed_by = CASE WHEN :review THEN CAST(:admin AS uuid) ELSE reviewed_by END,
                       reviewed_at = CASE WHEN :review THEN now() ELSE reviewed_at END
                WHERE id = :id
                """)
                .param("status", next).param("review", review).param("note", note, Types.VARCHAR)
                .param("admin", adminId).param("id", id).update();
        audit.record(adminId, "admin.ad_campaign_" + request.action(), null, null,
                Map.of("campaign", id.toString(), "from", status, "to", next));
        return campaign(id);
    }

    /** Para ir ao ar: ao menos um espaço e, em cada espaço, ao menos um criativo ativo que sirva no formato dele. */
    private void requireServable(UUID id) {
        Campaign campaign = campaign(id);
        Map<String, String> errors = new LinkedHashMap<>();
        if (campaign.slotKeys().isEmpty()) {
            errors.put("slotKeys", "Escolha ao menos um espaço");
        }
        List<Creative> active = campaign.creatives().stream().filter(c -> "active".equals(c.status())).toList();
        if (active.isEmpty()) {
            errors.put("creatives", "Cadastre ao menos um criativo ativo");
        }
        for (String slot : campaign.slotKeys()) {
            boolean served = active.stream().anyMatch(c -> c.fitProblems().getOrDefault(slot, List.of()).isEmpty());
            if (!active.isEmpty() && !served) {
                errors.put("slot." + slot, "Nenhum criativo serve no espaço " + slot + ": "
                        + String.join(", ", active.getFirst().fitProblems().getOrDefault(slot, List.of())));
            }
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
    }

    // Criativos ------------------------------------------------------------------------------

    @Transactional
    public Campaign saveCreative(UUID adminId, UUID campaignId, UUID creativeId, AdRequests.SaveCreative request) {
        String status = (String) before(campaignId).get("status");
        requireOwnImage("imageUrl", request.imageUrl());
        requireOwnImage("mobileImageUrl", request.mobileImageUrl());
        requireOwnImage("logoUrl", request.logoUrl());
        String destination = safeUrl(request.destinationUrl());
        String creativeStatus = request.status() == null || request.status().isBlank() ? "active" : request.status();
        if (!Set.of("active", "inactive").contains(creativeStatus)) {
            throw new FieldValidationException(Map.of("status", "Use active ou inactive"));
        }
        String sql = creativeId == null ? """
                INSERT INTO ad_creatives (campaign_id, title, description, image_url, mobile_image_url, logo_url, destination_url,
                                          cta_text, alt_text, status)
                VALUES (:campaign, :title, :description, :image, :mobile, :logo, :destination, :cta, :alt, :status)
                RETURNING id
                """ : """
                UPDATE ad_creatives SET title = :title, description = :description, image_url = :image, mobile_image_url = :mobile,
                       logo_url = :logo, destination_url = :destination, cta_text = :cta, alt_text = :alt, status = :status,
                       updated_at = now()
                WHERE id = :id AND campaign_id = :campaign AND deleted_at IS NULL
                RETURNING id
                """;
        UUID saved = jdbc.sql(sql)
                .param("campaign", campaignId)
                .param("title", blank(request.title()), Types.VARCHAR)
                .param("description", blank(request.description()), Types.VARCHAR)
                .param("image", blank(request.imageUrl()), Types.VARCHAR)
                .param("mobile", blank(request.mobileImageUrl()), Types.VARCHAR)
                .param("logo", blank(request.logoUrl()), Types.VARCHAR)
                .param("destination", destination)
                .param("cta", blank(request.ctaText()), Types.VARCHAR)
                .param("alt", blank(request.altText()), Types.VARCHAR)
                .param("status", creativeStatus)
                .param("id", creativeId, Types.OTHER)
                .query(UUID.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Criativo não encontrado"));
        needsApprovalAgain(campaignId, status);
        audit.record(adminId, creativeId == null ? "admin.ad_creative_create" : "admin.ad_creative_update", null, null,
                Map.of("campaign", campaignId.toString(), "creative", saved.toString()));
        return campaign(campaignId);
    }

    @Transactional
    public Campaign deleteCreative(UUID adminId, UUID campaignId, UUID creativeId) {
        String status = (String) before(campaignId).get("status");
        int changed = jdbc.sql("UPDATE ad_creatives SET deleted_at = now() WHERE id = :id AND campaign_id = :campaign AND deleted_at IS NULL")
                .param("id", creativeId).param("campaign", campaignId).update();
        if (changed == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Criativo não encontrado");
        }
        needsApprovalAgain(campaignId, status);
        audit.record(adminId, "admin.ad_creative_delete", null, null,
                Map.of("campaign", campaignId.toString(), "creative", creativeId.toString()));
        return campaign(campaignId);
    }

    // Relatório ---------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Report report(UUID id) {
        Campaign campaign = campaign(id);
        List<Report.Row> bySlot = rows("""
                SELECT s.slot_key AS key, count(*) FILTER (WHERE e.event_type = 'impression') AS impressions,
                       count(*) FILTER (WHERE e.event_type = 'click') AS clicks
                FROM ad_events e JOIN ad_slots s ON s.id = e.slot_id WHERE e.campaign_id = :id
                GROUP BY s.slot_key ORDER BY s.slot_key
                """, id);
        List<Report.Row> byDay = rows("""
                SELECT to_char(e.created_at AT TIME ZONE 'America/Sao_Paulo', 'YYYY-MM-DD') AS key,
                       count(*) FILTER (WHERE e.event_type = 'impression') AS impressions,
                       count(*) FILTER (WHERE e.event_type = 'click') AS clicks
                FROM ad_events e WHERE e.campaign_id = :id
                GROUP BY 1 ORDER BY 1
                """, id);
        List<Report.Row> byCreative = rows("""
                SELECT coalesce(c.title, c.id::text) AS key, count(*) FILTER (WHERE e.event_type = 'impression') AS impressions,
                       count(*) FILTER (WHERE e.event_type = 'click') AS clicks
                FROM ad_events e JOIN ad_creatives c ON c.id = e.creative_id WHERE e.campaign_id = :id
                GROUP BY 1 ORDER BY 1
                """, id);
        return new Report(id, campaign.name(), campaign.impressions(), campaign.clicks(),
                ctr(campaign.clicks(), campaign.impressions()), bySlot, byDay, byCreative);
    }

    private List<Report.Row> rows(String sql, UUID id) {
        return jdbc.sql(sql).param("id", id).query((rs, row) -> {
            long impressions = rs.getLong("impressions");
            long clicks = rs.getLong("clicks");
            return new Report.Row(rs.getString("key"), impressions, clicks, ctr(clicks, impressions));
        }).list();
    }

    static double ctr(long clicks, long impressions) {
        return impressions == 0 ? 0 : Math.round(clicks * 10000.0 / impressions) / 100.0;
    }

    // Apoio -------------------------------------------------------------------------------------

    private Map<String, Object> before(UUID id) {
        return jdbc.sql("""
                SELECT name, status, start_at, end_at, priority, commercial_model, commercial_value, impression_limit
                FROM ad_campaigns WHERE id = :id AND deleted_at IS NULL
                """).param("id", id).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Campanha não encontrada"));
    }

    /** Mexeu numa campanha que estava no ar (ou pausada): volta para aprovação antes de aparecer de novo. */
    private void needsApprovalAgain(UUID id, String status) {
        if (NEEDS_NEW_APPROVAL.contains(status)) {
            jdbc.sql("UPDATE ad_campaigns SET status = 'pending_approval', updated_at = now() WHERE id = :id").param("id", id).update();
        }
    }

    private List<Integer> slotIds(List<String> keys) {
        List<Integer> ids = new ArrayList<>();
        for (String key : keys == null ? List.<String>of() : keys.stream().distinct().toList()) {
            ids.add(jdbc.sql("SELECT id FROM ad_slots WHERE slot_key = :key AND deleted_at IS NULL").param("key", key)
                    .query(Integer.class).optional()
                    .orElseThrow(() -> new FieldValidationException(Map.of("slotKeys", "Espaço não encontrado: " + key))));
        }
        return ids;
    }

    private void requireOwnImage(String field, String url) {
        if (url != null && !url.isBlank() && !images.isOwnFile(url)) {
            throw new FieldValidationException(Map.of(field, "Envie a imagem pelo painel"));
        }
    }

    /** Link de destino: https com um domínio de verdade (sem IP nem endereço interno). */
    static String safeUrl(String url) {
        String clean = url == null ? "" : url.strip();
        try {
            URI uri = new URI(clean);
            String host = uri.getHost();
            if ("https".equalsIgnoreCase(uri.getScheme()) && host != null && host.contains(".") && !host.matches("[0-9.]+")
                    && uri.getUserInfo() == null && !host.equalsIgnoreCase("localhost")
                    && !host.toLowerCase().endsWith(".local") && !host.toLowerCase().endsWith(".internal")) {
                return clean;
            }
        } catch (URISyntaxException e) {
            // cai no erro abaixo
        }
        throw new FieldValidationException(Map.of("destinationUrl", "Use um link de destino começando com https://"));
    }

    private static UUID parseId(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new FieldValidationException(Map.of(field, "Identificador inválido"));
        }
    }

    private static Slot slot(ResultSet rs) throws SQLException {
        return new Slot(rs.getInt("id"), rs.getString("slot_key"), rs.getString("name"), rs.getString("description"),
                rs.getString("page"), rs.getString("position"), rs.getString("format"), (Integer) rs.getObject("width"),
                (Integer) rs.getObject("height"), rs.getString("aspect_ratio"), rs.getInt("text_limit"), rs.getInt("max_items"),
                rs.getInt("priority"), rs.getBoolean("rotation_enabled"), rs.getString("status"));
    }

    private Advertiser advertiser(ResultSet rs, boolean withDocument) throws SQLException {
        String document = rs.getString("document_encrypted");
        String plain = document == null ? null : crypto.decrypt(document);
        return new Advertiser(rs.getObject("id", UUID.class), rs.getString("display_name"), rs.getString("legal_name"),
                plain == null ? null : withDocument ? plain : "***" + plain.substring(Math.max(0, plain.length() - 4)),
                rs.getString("contact_name"), rs.getString("email"), rs.getString("phone"), rs.getString("logo_url"),
                rs.getString("website_url"), json.readValue(rs.getString("social_links"), new TypeReference<List<String>>() {
                }), rs.getString("notes"), rs.getString("status"), rs.getTimestamp("created_at").toInstant());
    }

    private static Creative creative(ResultSet rs, List<Map<String, Object>> slots) throws SQLException {
        AdFormats.Content content = new AdFormats.Content(rs.getString("title"), rs.getString("description"),
                rs.getString("image_url"), rs.getString("logo_url"));
        Map<String, List<String>> problems = new LinkedHashMap<>();
        for (Map<String, Object> slot : slots) {
            problems.put((String) slot.get("slot_key"),
                    AdFormats.problems((String) slot.get("format"), ((Number) slot.get("text_limit")).intValue(), content));
        }
        return new Creative(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("description"),
                rs.getString("image_url"), rs.getString("mobile_image_url"), rs.getString("logo_url"),
                rs.getString("destination_url"), rs.getString("cta_text"), rs.getString("alt_text"), rs.getString("status"), problems);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
