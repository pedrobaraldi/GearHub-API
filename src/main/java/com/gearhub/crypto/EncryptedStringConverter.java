package com.gearhub.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/** Criptografa a coluna ao gravar e descriptografa ao ler. Use com {@code @Convert}. */
@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final CryptoService crypto;

    public EncryptedStringConverter(CryptoService crypto) {
        this.crypto = crypto;
    }

    @Override
    public String convertToDatabaseColumn(String value) {
        return value == null ? null : crypto.encrypt(value);
    }

    @Override
    public String convertToEntityAttribute(String value) {
        return value == null ? null : crypto.decrypt(value);
    }
}
