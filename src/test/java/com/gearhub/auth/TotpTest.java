package com.gearhub.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Confere com os exemplos oficiais da RFC 6238: é o que garante que os apps autenticadores batem com a API. */
class TotpTest {

    // "12345678901234567890" em base32, o segredo dos exemplos da RFC
    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void bateComOsExemplosDaRfc6238() {
        assertThat(Totp.code(RFC_SECRET, 59 / 30)).isEqualTo("287082");
        assertThat(Totp.code(RFC_SECRET, 1111111109L / 30)).isEqualTo("081804");
        assertThat(Totp.code(RFC_SECRET, 1234567890L / 30)).isEqualTo("005924");
        assertThat(Totp.code(RFC_SECRET, 20000000000L / 30)).isEqualTo("353130");
    }

    @Test
    void base32VaiEVolta() {
        byte[] raw = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        assertThat(Totp.encodeBase32(raw)).isEqualTo(RFC_SECRET);
        assertThat(Totp.decodeBase32(RFC_SECRET)).isEqualTo(raw);
        assertThat(Totp.newSecret()).matches("[A-Z2-7]{32}");
    }
}
