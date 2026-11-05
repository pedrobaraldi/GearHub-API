package com.gearhub.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class CryptoServiceTest {

    private static final String KEY_A = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String KEY_B = Base64.getEncoder().encodeToString("b".repeat(32).getBytes());

    private final CryptoService crypto = new CryptoService(new CryptoProperties(KEY_A, KEY_B));

    @Test
    void cifraEDecifra() {
        String encrypted = crypto.encrypt("pessoa@exemplo.com");
        assertThat(encrypted).startsWith("v1:").doesNotContain("pessoa");
        assertThat(crypto.decrypt(encrypted)).isEqualTo("pessoa@exemplo.com");
    }

    @Test
    void mesmoValorGeraCifrasDiferentes() {
        assertThat(crypto.encrypt("igual")).isNotEqualTo(crypto.encrypt("igual"));
    }

    @Test
    void dadoAdulteradoFalha() {
        String[] parts = crypto.encrypt("pessoa@exemplo.com").split(":");
        char first = parts[2].charAt(0);
        parts[2] = (first == 'A' ? 'B' : 'A') + parts[2].substring(1);
        assertThatThrownBy(() -> crypto.decrypt(String.join(":", parts)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void chaveErradaNaoDecifra() {
        CryptoService other = new CryptoService(new CryptoProperties(KEY_B, KEY_A));
        String encrypted = crypto.encrypt("segredo");
        assertThatThrownBy(() -> other.decrypt(encrypted)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void indiceCegoEDeterministicoEDependeDaChave() {
        CryptoService other = new CryptoService(new CryptoProperties(KEY_B, KEY_A));
        assertThat(crypto.blindIndex("a@b.com")).isEqualTo(crypto.blindIndex("a@b.com"));
        assertThat(crypto.blindIndex("a@b.com")).isNotEqualTo(other.blindIndex("a@b.com"));
    }

    @Test
    void recusaChaveAusenteOuCurta() {
        assertThatThrownBy(() -> new CryptoService(new CryptoProperties("", KEY_B)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("GEARHUB_AES_KEY");
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new CryptoService(new CryptoProperties(KEY_A, shortKey)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("GEARHUB_HMAC_KEY");
    }
}
