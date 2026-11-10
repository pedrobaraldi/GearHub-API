package com.gearhub.users;

import com.gearhub.config.FieldValidationException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contorno da foto do perfil. A pessoa escolhe entre os ranks que já alcançou e, se for fundadora, o RGB.
 * Se a escolha deixar de valer (o nível caiu), o contorno volta a ser automático.
 */
@Service
public class RingStyleService {

    public static final String FOUNDER = "founder";

    @Schema(description = "Opção de contorno da foto")
    public record Option(
            @Schema(description = "Nome do rank ou founder", example = "Esmeralda") String value,
            @Schema(example = "Esmeralda") String label,
            @Schema(description = "Se a pessoa já liberou essa opção") boolean available,
            @Schema(description = "Nível que libera a opção; nulo no Fundador") Integer minLevel) {
    }

    private final JdbcClient jdbc;

    public RingStyleService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<Option> options(UUID userId) {
        List<Option> options = new ArrayList<>(jdbc.sql("""
                SELECT r.name AS value, r.name AS label, r.min_level <= u.level AS available, r.min_level
                FROM ranks r, users u WHERE u.id = :user ORDER BY r.min_level
                """)
                .param("user", userId)
                .query(Option.class)
                .list());
        boolean founder = jdbc.sql("SELECT founder_number IS NOT NULL FROM users WHERE id = :user")
                .param("user", userId).query(Boolean.class).single();
        if (founder) {
            options.add(new Option(FOUNDER, "Fundador (RGB)", true, null));
        }
        return options;
    }

    /** Contorno que vale agora: a escolha, se ainda estiver liberada, ou nulo (automático). */
    @Transactional(readOnly = true)
    public String effective(UUID userId) {
        return jdbc.sql("""
                SELECT CASE
                    WHEN u.ring_style = 'founder' AND u.founder_number IS NOT NULL THEN 'founder'
                    WHEN EXISTS (SELECT 1 FROM ranks r WHERE r.name = u.ring_style AND r.min_level <= u.level) THEN u.ring_style
                END
                FROM users u WHERE u.id = :user
                """)
                .param("user", userId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    /** Valida a escolha: vazio volta ao automático; qualquer outra precisa estar liberada. */
    public String validated(UUID userId, String choice) {
        if (choice == null || choice.isBlank()) {
            return null;
        }
        boolean allowed = options(userId).stream().anyMatch(option -> option.available() && option.value().equals(choice));
        if (!allowed) {
            throw new FieldValidationException(Map.of("ringStyle", "Esse contorno ainda não foi liberado"));
        }
        return choice;
    }
}
