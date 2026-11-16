package com.gearhub.marketplace;

import com.gearhub.auth.RateLimiter;
import com.gearhub.crypto.CryptoService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Arquivos dos documentos de validação (cartão CNPJ, contrato social, documento do responsável). Ficam
 * criptografados numa pasta que o site não publica: só saem descriptografados pela API, para o admin.
 */
@Service
public class PartnerDocumentService {

    public static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final int UPLOADS_PER_MINUTE = 10;

    /** Tipo e extensão conferidos pelo conteúdo do arquivo, nunca pelo nome ou tipo informados. */
    public record Stored(String storedName, String contentType, int size) {
    }

    private final Path directory;
    private final CryptoService crypto;
    private final RateLimiter rateLimiter;

    public PartnerDocumentService(@Value("${gearhub.uploads.dir}") String uploads, CryptoService crypto, RateLimiter rateLimiter) {
        this.directory = Path.of(uploads, "partner-docs").toAbsolutePath().normalize();
        this.crypto = crypto;
        this.rateLimiter = rateLimiter;
    }

    public Stored store(UUID userId, MultipartFile file) {
        rateLimiter.check("partner-doc:" + userId, UPLOADS_PER_MINUTE);
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (content.length == 0 || content.length > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "O documento deve ter no máximo 5 MB.");
        }
        String contentType = detect(content);
        if (contentType == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Envie o documento em PDF, JPG ou PNG.");
        }
        String name = UUID.randomUUID() + ".bin";
        try {
            Files.createDirectories(directory);
            Files.write(directory.resolve(name), crypto.encryptBytes(content));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Stored(name, contentType, content.length);
    }

    /** Conteúdo original. O nome vem do banco, nunca de quem pede. */
    public byte[] read(String storedName) {
        if (!storedName.matches("[0-9a-f-]{36}\\.bin")) {
            throw new IllegalArgumentException("Nome de documento inválido");
        }
        try {
            return crypto.decryptBytes(Files.readAllBytes(directory.resolve(storedName)));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Arquivo do documento não encontrado");
        }
    }

    private static String detect(byte[] data) {
        if (data.length > 4 && data[0] == '%' && data[1] == 'P' && data[2] == 'D' && data[3] == 'F') {
            return "application/pdf";
        }
        if (data.length > 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (data.length > 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
            return "image/png";
        }
        return null;
    }
}
