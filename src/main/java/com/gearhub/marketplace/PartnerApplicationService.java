package com.gearhub.marketplace;

import com.gearhub.admin.AuditService;
import com.gearhub.config.FieldValidationException;
import com.gearhub.crypto.CryptoService;
import com.gearhub.notifications.NotificationService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Solicitação de loja parceira: a pessoa preenche e envia, o admin analisa (aprova, recusa ou pede informação),
 * e só na aprovação nasce a loja. Cada mudança fica no histórico da solicitação e na auditoria.
 */
@Service
public class PartnerApplicationService {

    private static final Set<String> DOCUMENT_KINDS = Set.of("cnpj_card", "social_contract", "responsible_id", "other");
    /** O que o admin pode fazer a partir de cada status. */
    private static final Map<String, Set<String>> ADMIN_MOVES = Map.of(
            "pending", Set.of("in_review", "awaiting_info", "approved", "rejected"),
            "in_review", Set.of("awaiting_info", "approved", "rejected"),
            "awaiting_info", Set.of("rejected"));

    @Schema(description = "Documento enviado (o arquivo só é aberto pelo admin)")
    public record Document(UUID id, @Schema(allowableValues = {"cnpj_card", "social_contract", "responsible_id", "other"}) String kind,
            String contentType, int sizeBytes, Instant createdAt) {
    }

    @Schema(description = "Mudança de status da solicitação")
    public record Event(String fromStatus, String toStatus, String note, Instant createdAt) {
    }

    @Schema(description = "A solicitação, como a pessoa vê. O CPF aparece mascarado")
    public record Application(UUID id,
            @Schema(allowableValues = {"draft", "pending", "in_review", "awaiting_info", "approved", "rejected"}) String status,
            String responsibleName, @Schema(example = "***.***.789-09") String cpfMasked, String contactEmail, String phone,
            String roleInCompany, String cnpj, String legalName, String tradeName, String segment, String address,
            String website, String description, Instant submittedAt,
            @Schema(description = "Mensagem do admin na última decisão (pedido de informação ou recusa)") String decisionNote,
            List<Document> documents, List<Event> history,
            @Schema(description = "Slug da loja, quando aprovada") String storeSlug) {
    }

    @Schema(description = "Item da fila de análise do admin")
    public record QueueItem(UUID id, String status, String tradeName, String cnpj, String applicantUsername,
            Instant submittedAt, Instant decidedAt) {
    }

    @Schema(description = "Solicitação completa para o admin, com o CPF à mostra")
    public record AdminView(Application application, String cpf, String applicantUsername, String applicantEmail,
            String decidedBy, Instant decidedAt) {
    }

    private final JdbcClient jdbc;
    private final CryptoService crypto;
    private final PartnerDocumentService documents;
    private final NotificationService notifications;
    private final AuditService audit;

    public PartnerApplicationService(JdbcClient jdbc, CryptoService crypto, PartnerDocumentService documents,
            NotificationService notifications, AuditService audit) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.documents = documents;
        this.notifications = notifications;
        this.audit = audit;
    }

    // Solicitante ---------------------------------------------------------------

    /** A solicitação mais recente da pessoa; vazia se nunca pediu. */
    @Transactional(readOnly = true)
    public Optional<Application> mine(UUID userId) {
        return latestId(userId).map(this::load);
    }

    @Transactional
    public Application save(UUID userId, MarketplaceRequests.SaveApplication request) {
        UUID id = editableOrNew(userId);
        Map<String, String> errors = new LinkedHashMap<>();
        String cpf = BrazilianDocuments.digits(request.cpf());
        if (!cpf.isEmpty() && !BrazilianDocuments.isValidCpf(cpf)) {
            errors.put("cpf", "CPF inválido");
        }
        String cnpj = BrazilianDocuments.digits(request.cnpj());
        if (!cnpj.isEmpty() && !BrazilianDocuments.isValidCnpj(cnpj)) {
            errors.put("cnpj", "CNPJ inválido");
        }
        String phone = BrazilianDocuments.digits(request.phone());
        if (!phone.isEmpty() && !phone.matches("[0-9]{10,11}")) {
            errors.put("phone", "Informe o telefone com DDD");
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
        jdbc.sql("""
                UPDATE partner_applications SET
                    responsible_name = :responsible, cpf_encrypted = coalesce(:cpfEncrypted, cpf_encrypted),
                    cpf_hash = coalesce(:cpfHash, cpf_hash), contact_email = :email, phone = :phone, role_in_company = :role,
                    cnpj = :cnpj, legal_name = :legal, trade_name = :trade, segment = :segment, address = :address,
                    website = :website, description = :description, updated_at = now()
                WHERE id = :id
                """)
                .param("responsible", blank(request.responsibleName()), java.sql.Types.VARCHAR)
                // CPF não é devolvido ao navegador: campo vazio mantém o que já foi salvo
                .param("cpfEncrypted", cpf.isEmpty() ? null : crypto.encrypt(cpf), java.sql.Types.VARCHAR)
                .param("cpfHash", cpf.isEmpty() ? null : crypto.blindIndex("cpf:" + cpf), java.sql.Types.VARCHAR)
                .param("email", blank(request.contactEmail()), java.sql.Types.VARCHAR)
                .param("phone", phone.isEmpty() ? null : phone, java.sql.Types.VARCHAR)
                .param("role", blank(request.roleInCompany()), java.sql.Types.VARCHAR)
                .param("cnpj", cnpj.isEmpty() ? null : cnpj, java.sql.Types.VARCHAR)
                .param("legal", blank(request.legalName()), java.sql.Types.VARCHAR)
                .param("trade", blank(request.tradeName()), java.sql.Types.VARCHAR)
                .param("segment", blank(request.segment()), java.sql.Types.VARCHAR)
                .param("address", blank(request.address()), java.sql.Types.VARCHAR)
                .param("website", blank(request.website()), java.sql.Types.VARCHAR)
                .param("description", blank(request.description()), java.sql.Types.VARCHAR)
                .param("id", id)
                .update();
        return load(id);
    }

    @Transactional
    public Document addDocument(UUID userId, String kind, MultipartFile file) {
        if (!DOCUMENT_KINDS.contains(kind)) {
            throw new FieldValidationException(Map.of("kind", "Tipo de documento inválido"));
        }
        UUID id = editableOrNew(userId);
        long count = jdbc.sql("SELECT count(*) FROM partner_documents WHERE application_id = :id AND deleted_at IS NULL")
                .param("id", id).query(Long.class).single();
        if (count >= 10) {
            throw new FieldValidationException(Map.of("file", "Envie no máximo 10 documentos"));
        }
        PartnerDocumentService.Stored stored = documents.store(userId, file);
        UUID documentId = jdbc.sql("""
                INSERT INTO partner_documents (application_id, kind, stored_name, content_type, size_bytes)
                VALUES (:application, :kind, :stored, :type, :size) RETURNING id
                """)
                .param("application", id).param("kind", kind).param("stored", stored.storedName())
                .param("type", stored.contentType()).param("size", stored.size())
                .query(UUID.class).single();
        audit.record(userId, "partner.document_add", "partner_application", id, Map.of("kind", kind));
        return new Document(documentId, kind, stored.contentType(), stored.size(), Instant.now());
    }

    /** Exclusão lógica: o documento sai da solicitação, mas o registro e o arquivo ficam. */
    @Transactional
    public void removeDocument(UUID userId, UUID documentId) {
        UUID id = editable(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                "A solicitação não pode ser alterada agora."));
        int removed = jdbc.sql("""
                UPDATE partner_documents SET deleted_at = now()
                WHERE id = :doc AND application_id = :application AND deleted_at IS NULL
                """).param("doc", documentId).param("application", id).update();
        if (removed == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Documento não encontrado");
        }
        audit.record(userId, "partner.document_remove", "partner_application", id, Map.of("document", documentId.toString()));
    }

    /** Envia para análise (ou reenvia, depois de um pedido de informação). Tudo obrigatório precisa estar certo. */
    @Transactional
    public Application submit(UUID userId) {
        UUID id = editable(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                "Não há solicitação para enviar. Preencha os dados primeiro."));
        Map<String, Object> row = jdbc.sql("SELECT * FROM partner_applications WHERE id = :id").param("id", id).query().singleRow();
        Map<String, String> errors = new LinkedHashMap<>();
        require(row, "responsible_name", "responsibleName", "Informe o nome completo do responsável", errors);
        require(row, "cpf_encrypted", "cpf", "Informe o CPF do responsável", errors);
        require(row, "contact_email", "contactEmail", "Informe um e-mail de contato", errors);
        require(row, "phone", "phone", "Informe o telefone com DDD", errors);
        require(row, "role_in_company", "roleInCompany", "Informe a função na empresa", errors);
        require(row, "cnpj", "cnpj", "Informe o CNPJ", errors);
        require(row, "legal_name", "legalName", "Informe a razão social", errors);
        require(row, "trade_name", "tradeName", "Informe o nome fantasia", errors);
        require(row, "segment", "segment", "Informe o segmento", errors);
        require(row, "address", "address", "Informe o endereço", errors);
        require(row, "description", "description", "Conte um pouco sobre a operação da loja", errors);
        long docs = jdbc.sql("SELECT count(*) FROM partner_documents WHERE application_id = :id AND deleted_at IS NULL")
                .param("id", id).query(Long.class).single();
        if (docs == 0) {
            errors.put("documents", "Envie ao menos um documento para a validação, como o cartão CNPJ");
        }
        String cnpj = (String) row.get("cnpj");
        if (cnpj != null && cnpjInUse(cnpj, userId)) {
            errors.put("cnpj", "Esse CNPJ já tem uma loja ou uma solicitação em andamento");
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
        String from = (String) row.get("status");
        jdbc.sql("UPDATE partner_applications SET status = 'pending', submitted_at = now(), updated_at = now() WHERE id = :id")
                .param("id", id).update();
        event(id, from, "pending", userId, null);
        audit.record(userId, "partner.submit", "partner_application", id, Map.of("from", from));
        return load(id);
    }

    // Admin ------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<QueueItem> queue(String status) {
        boolean all = status == null || status.isBlank();
        return jdbc.sql("""
                SELECT a.id, a.status, a.trade_name, a.cnpj, u.username, a.submitted_at, a.decided_at
                FROM partner_applications a JOIN users u ON u.id = a.user_id
                WHERE a.status <> 'draft' AND (:all OR a.status = :status)
                ORDER BY a.submitted_at NULLS LAST LIMIT 200
                """)
                .param("all", all).param("status", all ? "" : status)
                .query((rs, row) -> new QueueItem(rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getString("trade_name"), rs.getString("cnpj"), rs.getString("username"),
                        instant(rs, "submitted_at"), instant(rs, "decided_at")))
                .list();
    }

    /** Ficha completa, com CPF e e-mail da conta: dado pessoal visto pela equipe fica na auditoria. */
    @Transactional
    public AdminView adminView(UUID adminId, UUID id) {
        Application application = load(id);
        Map<String, Object> extra = jdbc.sql("""
                SELECT a.cpf_encrypted, u.username, u.email_encrypted, d.username AS decided_by, a.decided_at
                FROM partner_applications a JOIN users u ON u.id = a.user_id LEFT JOIN users d ON d.id = a.decided_by
                WHERE a.id = :id
                """).param("id", id).query().singleRow();
        audit.record(adminId, "admin.partner_view", "partner_application", id, Map.of());
        return new AdminView(application, decrypt((String) extra.get("cpf_encrypted")), (String) extra.get("username"),
                decrypt((String) extra.get("email_encrypted")), (String) extra.get("decided_by"),
                extra.get("decided_at") == null ? null : ((java.sql.Timestamp) extra.get("decided_at")).toInstant());
    }

    public record DocumentFile(byte[] content, String contentType, String fileName) {
    }

    @Transactional
    public DocumentFile document(UUID adminId, UUID applicationId, UUID documentId) {
        Map<String, Object> doc = jdbc.sql("""
                SELECT kind, stored_name, content_type FROM partner_documents WHERE id = :doc AND application_id = :application
                """).param("doc", documentId).param("application", applicationId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Documento não encontrado"));
        audit.record(adminId, "admin.partner_document", "partner_application", applicationId,
                Map.of("document", documentId.toString()));
        String type = (String) doc.get("content_type");
        String extension = switch (type) {
            case "application/pdf" -> "pdf";
            case "image/png" -> "png";
            default -> "jpg";
        };
        return new DocumentFile(documents.read((String) doc.get("stored_name")), type, doc.get("kind") + "." + extension);
    }

    /** Decide a solicitação. Aprovar cria a loja; pedir informação ou recusar exige a justificativa. */
    @Transactional
    public AdminView review(UUID adminId, UUID id, MarketplaceRequests.ReviewApplication request) {
        Map<String, Object> row = jdbc.sql("SELECT user_id, status, trade_name, segment, website, cnpj FROM partner_applications WHERE id = :id FOR UPDATE")
                .param("id", id).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Solicitação não encontrada"));
        String from = (String) row.get("status");
        String to = request.status();
        if (!ADMIN_MOVES.getOrDefault(from, Set.of()).contains(to)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Não dá para mudar de " + from + " para " + to + ".");
        }
        String note = blank(request.note());
        if (("awaiting_info".equals(to) || "rejected".equals(to)) && note == null) {
            throw new FieldValidationException(Map.of("note", "Explique para o solicitante o que falta ou o motivo da recusa"));
        }
        UUID applicant = (UUID) row.get("user_id");
        if ("approved".equals(to)) {
            if (cnpjInUse((String) row.get("cnpj"), applicant)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Esse CNPJ já tem uma loja ativa.");
            }
            createStore(id, applicant, (String) row.get("trade_name"), (String) row.get("segment"), (String) row.get("website"));
        }
        jdbc.sql("""
                UPDATE partner_applications SET status = :to, decided_by = :admin, decided_at = now(), decision_note = :note,
                       updated_at = now()
                WHERE id = :id
                """).param("to", to).param("admin", adminId).param("note", note, java.sql.Types.VARCHAR).param("id", id).update();
        event(id, from, to, adminId, note);
        audit.record(adminId, "admin.partner_review", "partner_application", id, Map.of("from", from, "to", to));
        notifications.partnerStatus(applicant, to, note);
        return adminView(adminId, id);
    }

    // Apoio ---------------------------------------------------------------------------

    private void createStore(UUID applicationId, UUID ownerId, String tradeName, String segment, String website) {
        boolean hasStore = jdbc.sql("SELECT count(*) FROM partner_stores WHERE owner_id = :owner AND deleted_at IS NULL")
                .param("owner", ownerId).query(Long.class).single() > 0;
        if (hasStore) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Essa pessoa já tem uma loja.");
        }
        String base = slugOf(tradeName);
        String slug = base;
        for (int n = 2; jdbc.sql("SELECT count(*) FROM partner_stores WHERE slug = :slug").param("slug", slug)
                .query(Long.class).single() > 0; n++) {
            slug = base + "-" + n;
        }
        jdbc.sql("""
                INSERT INTO partner_stores (application_id, owner_id, slug, name, segment, website)
                VALUES (:application, :owner, :slug, :name, :segment, :website)
                """)
                .param("application", applicationId).param("owner", ownerId).param("slug", slug)
                .param("name", tradeName.length() > 80 ? tradeName.substring(0, 80) : tradeName)
                .param("segment", segment, java.sql.Types.VARCHAR)
                .param("website", website != null && website.startsWith("https://") ? website : null, java.sql.Types.VARCHAR)
                .update();
    }

    private boolean cnpjInUse(String cnpj, UUID exceptUser) {
        return jdbc.sql("""
                SELECT count(*) FROM partner_applications a
                WHERE a.cnpj = :cnpj AND a.user_id <> :user
                  AND (a.status IN ('pending', 'in_review', 'awaiting_info')
                       OR (a.status = 'approved' AND EXISTS (SELECT 1 FROM partner_stores s
                                                             WHERE s.application_id = a.id AND s.deleted_at IS NULL)))
                """).param("cnpj", cnpj).param("user", exceptUser).query(Long.class).single() > 0;
    }

    private Optional<UUID> latestId(UUID userId) {
        return jdbc.sql("SELECT id FROM partner_applications WHERE user_id = :user ORDER BY created_at DESC LIMIT 1")
                .param("user", userId).query(UUID.class).optional();
    }

    private Optional<UUID> editable(UUID userId) {
        return jdbc.sql("SELECT id FROM partner_applications WHERE user_id = :user AND status IN ('draft', 'awaiting_info')")
                .param("user", userId).query(UUID.class).optional();
    }

    /** Rascunho ou pedido de informação em aberto; senão cria um rascunho, se a pessoa pode pedir loja. */
    private UUID editableOrNew(UUID userId) {
        Optional<UUID> open = editable(userId);
        if (open.isPresent()) {
            return open.get();
        }
        boolean inReview = jdbc.sql("SELECT count(*) FROM partner_applications WHERE user_id = :user AND status IN ('pending', 'in_review')")
                .param("user", userId).query(Long.class).single() > 0;
        if (inReview) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Sua solicitação está em análise. Aguarde a resposta.");
        }
        boolean hasStore = jdbc.sql("SELECT count(*) FROM partner_stores WHERE owner_id = :user AND deleted_at IS NULL")
                .param("user", userId).query(Long.class).single() > 0;
        if (hasStore) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Você já tem uma loja parceira.");
        }
        UUID id = jdbc.sql("INSERT INTO partner_applications (user_id) VALUES (:user) RETURNING id")
                .param("user", userId).query(UUID.class).single();
        event(id, null, "draft", userId, null);
        return id;
    }

    private Application load(UUID id) {
        List<Document> docs = jdbc.sql("""
                SELECT id, kind, content_type, size_bytes, created_at FROM partner_documents
                WHERE application_id = :id AND deleted_at IS NULL ORDER BY created_at
                """).param("id", id)
                .query((rs, row) -> new Document(rs.getObject("id", UUID.class), rs.getString("kind"),
                        rs.getString("content_type"), rs.getInt("size_bytes"), instant(rs, "created_at")))
                .list();
        List<Event> history = jdbc.sql("""
                SELECT from_status, to_status, note, created_at FROM partner_application_events
                WHERE application_id = :id ORDER BY created_at, id
                """).param("id", id)
                .query((rs, row) -> new Event(rs.getString("from_status"), rs.getString("to_status"), rs.getString("note"),
                        instant(rs, "created_at")))
                .list();
        return jdbc.sql("""
                SELECT a.*, s.slug AS store_slug FROM partner_applications a
                LEFT JOIN partner_stores s ON s.application_id = a.id AND s.deleted_at IS NULL
                WHERE a.id = :id
                """).param("id", id)
                .query((rs, row) -> new Application(rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getString("responsible_name"), maskCpf(decrypt(rs.getString("cpf_encrypted"))),
                        rs.getString("contact_email"), rs.getString("phone"), rs.getString("role_in_company"),
                        rs.getString("cnpj"), rs.getString("legal_name"), rs.getString("trade_name"), rs.getString("segment"),
                        rs.getString("address"), rs.getString("website"), rs.getString("description"),
                        instant(rs, "submitted_at"), rs.getString("decision_note"), docs, history, rs.getString("store_slug")))
                .single();
    }

    private void event(UUID applicationId, String from, String to, UUID actor, String note) {
        jdbc.sql("""
                INSERT INTO partner_application_events (application_id, from_status, to_status, actor_id, note)
                VALUES (:application, :from, :to, :actor, :note)
                """)
                .param("application", applicationId).param("from", from, java.sql.Types.VARCHAR).param("to", to)
                .param("actor", actor).param("note", note, java.sql.Types.VARCHAR).update();
    }

    private static void require(Map<String, Object> row, String column, String field, String message, Map<String, String> errors) {
        Object value = row.get(column);
        if (value == null || value.toString().isBlank()) {
            errors.put(field, message);
        }
    }

    private String decrypt(String value) {
        return value == null ? null : crypto.decrypt(value);
    }

    private static String maskCpf(String cpf) {
        return cpf == null || cpf.length() != 11 ? null : "***.***." + cpf.substring(6, 9) + "-" + cpf.substring(9);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    static String slugOf(String text) {
        String plain = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String slug = plain.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (slug.length() > 50) {
            slug = slug.substring(0, 50).replaceAll("-$", "");
        }
        return slug.length() < 2 ? "loja" : slug;
    }
}
