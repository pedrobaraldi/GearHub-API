package com.gearhub.ads;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** O que cada formato de anúncio exige do criativo. O site desenha cada formato com um componente fixo. */
public final class AdFormats {

    public static final Set<String> ALL = Set.of("banner", "panel", "promo_card", "brand", "institutional");

    /** Campo obrigatório e o rótulo usado na mensagem de erro. */
    private static final Map<String, Map<String, String>> REQUIRED = Map.of(
            "banner", Map.of("imageUrl", "imagem"),
            "panel", Map.of("imageUrl", "imagem", "title", "título", "description", "texto"),
            "promo_card", Map.of("title", "título", "description", "texto"),
            "brand", Map.of("logoUrl", "logotipo", "title", "título", "description", "texto"),
            "institutional", Map.of("title", "título", "description", "texto"));

    private AdFormats() {
    }

    /** Conteúdo de um criativo, para conferir contra o formato do espaço. */
    public record Content(String title, String description, String imageUrl, String logoUrl) {
        String get(String field) {
            return switch (field) {
                case "title" -> title;
                case "description" -> description;
                case "imageUrl" -> imageUrl;
                case "logoUrl" -> logoUrl;
                default -> null;
            };
        }
    }

    /** O que falta no criativo para o formato (vazio se serve). */
    public static List<String> problems(String format, int textLimit, Content content) {
        List<String> problems = new ArrayList<>();
        REQUIRED.getOrDefault(format, Map.of()).forEach((field, label) -> {
            String value = content.get(field);
            if (value == null || value.isBlank()) {
                problems.add("falta " + label);
            }
        });
        if (content.description() != null && content.description().length() > textLimit) {
            problems.add("texto com mais de " + textLimit + " caracteres");
        }
        return problems;
    }

    public static boolean fits(String format, int textLimit, Content content) {
        return problems(format, textLimit, content).isEmpty();
    }
}
