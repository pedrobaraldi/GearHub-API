package com.gearhub.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/** Único ponto do sistema que conhece algoritmos e chaves. */
@Service
public class CryptoService {

    private static final String VERSION = "v1";
    private static final String AES_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BYTES = 16;

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec aesKey;
    private final SecretKeySpec hmacKey;
    private final SecureRandom random = new SecureRandom();

    public CryptoService(CryptoProperties properties) {
        this.aesKey = new SecretKeySpec(decodeKey("GEARHUB_AES_KEY", properties.aesKey()), "AES");
        this.hmacKey = new SecretKeySpec(decodeKey("GEARHUB_HMAC_KEY", properties.hmacKey()), HMAC_ALGORITHM);
    }

    private static byte[] decodeKey(String name, String value) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(value == null ? "" : value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(name + " deve estar em base64");
        }
        if (key.length != KEY_BYTES) {
            throw new IllegalStateException(name + " deve ter 256 bits em base64");
        }
        return key;
    }

    /** AES-256-GCM com IV aleatório. Formato: {@code v1:iv:dados:tag}. */
    public String encrypt(String plainText) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BYTES * 8, iv));
            byte[] sealed = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            int dataLength = sealed.length - TAG_BYTES;
            return String.join(":",
                    VERSION,
                    ENCODER.encodeToString(iv),
                    ENCODER.encodeToString(Arrays.copyOf(sealed, dataLength)),
                    ENCODER.encodeToString(Arrays.copyOfRange(sealed, dataLength, sealed.length)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao criptografar", e);
        }
    }

    public String decrypt(String encrypted) {
        String[] parts = encrypted.split(":", -1);
        if (parts.length != 4 || !VERSION.equals(parts[0])) {
            throw new IllegalArgumentException("Formato criptografado inválido");
        }
        try {
            byte[] iv = DECODER.decode(parts[1]);
            byte[] data = DECODER.decode(parts[2]);
            byte[] tag = DECODER.decode(parts[3]);
            byte[] sealed = ByteBuffer.allocate(data.length + tag.length).put(data).put(tag).array();
            Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BYTES * 8, iv));
            return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            throw new IllegalArgumentException("Dado criptografado foi adulterado", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao descriptografar", e);
        }
    }

    /** HMAC-SHA-256 determinístico: permite buscar e garantir unicidade sem descriptografar. */
    public String blindIndex(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(hmacKey);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao gerar índice", e);
        }
    }

    public String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Token aleatório de 256 bits, seguro para cookies. */
    public String newToken() {
        byte[] token = new byte[KEY_BYTES];
        random.nextBytes(token);
        return ENCODER.encodeToString(token);
    }
}
