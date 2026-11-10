package com.gearhub.config;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Termo de busca no mesmo formato da função gh_unaccent do banco: sem acento, minúsculo. As palavras viram
 * um padrão LIKE com curinga entre elas ("razer viper" acha "Razer Viper V3 Pro").
 */
public final class SearchText {

    private SearchText() {
    }

    public static String normalize(String text) {
        String plain = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return plain.toLowerCase(Locale.ROOT).strip();
    }

    /** Para {@code gh_unaccent(...) LIKE :padrao}. % e _ digitados são texto comum, não curingas. */
    public static String pattern(String term) {
        String escaped = normalize(term).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped.replaceAll("\\s+", "%") + "%";
    }
}
