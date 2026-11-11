package com.gearhub.reviews;

import com.gearhub.auth.RateLimiter;
import com.gearhub.users.AvatarService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Imagens de posts e reviews, na mesma pasta local das fotos de perfil. */
@Service
public class ContentImageService {

    public static final String PUBLIC_PATH = "/uploads/content/";

    private static final int UPLOADS_PER_MINUTE = 20;
    private static final Pattern OWN_FILE = Pattern.compile(
            Pattern.quote(PUBLIC_PATH) + "[0-9a-f-]{36}\\.(jpg|png|webp)");

    private final Path directory;
    private final RateLimiter rateLimiter;

    public ContentImageService(@Value("${gearhub.uploads.dir}") String uploads, RateLimiter rateLimiter) {
        this.directory = Path.of(uploads, "content").toAbsolutePath().normalize();
        this.rateLimiter = rateLimiter;
    }

    public Path directory() {
        return directory;
    }

    public String store(UUID userId, MultipartFile file) {
        rateLimiter.check("upload:" + userId, UPLOADS_PER_MINUTE);
        try {
            byte[] content = file.getBytes();
            String extension = AvatarService.detectExtension(content);
            if (extension == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Envie uma imagem JPG, PNG ou WebP.");
            }
            // Sem EXIF (localização GPS, aparelho), XMP e comentários: qualquer pessoa logada vê o arquivo
            content = AvatarService.withoutMetadata(content, extension);
            String name = UUID.randomUUID() + "." + extension;
            Files.createDirectories(directory);
            Files.write(directory.resolve(name), content);
            return PUBLIC_PATH + name;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Só aceita endereços gerados aqui: a URL vem do navegador e aparece para todo mundo. */
    public boolean isOwnFile(String url) {
        return OWN_FILE.matcher(url).matches();
    }
}
