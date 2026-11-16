package com.gearhub.marketplace;

import com.gearhub.auth.RateLimiter;
import com.gearhub.reviews.ContentImageService;
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

/**
 * Logotipo, banner e fotos de produtos das lojas parceiras. Ficam numa pasta pública própria: a vitrine abre
 * sem login, e as fotos de posts (que exigem conta) continuam em outra pasta.
 */
@Service
public class StoreImageService {

    public static final String PUBLIC_PATH = "/uploads/store/";
    private static final Pattern OWN_FILE = Pattern.compile(Pattern.quote(PUBLIC_PATH) + "[0-9a-f-]{36}\\.(jpg|png|webp)");

    private final Path directory;
    private final RateLimiter rateLimiter;

    public StoreImageService(@Value("${gearhub.uploads.dir}") String uploads, RateLimiter rateLimiter) {
        this.directory = Path.of(uploads, "store").toAbsolutePath().normalize();
        this.rateLimiter = rateLimiter;
    }

    public Path directory() {
        return directory;
    }

    public String store(UUID userId, MultipartFile file) {
        rateLimiter.check(ContentImageService.UPLOAD_LIMIT_KEY + userId, ContentImageService.UPLOADS_PER_MINUTE);
        try {
            byte[] content = file.getBytes();
            AvatarService.requireImageSize(content);
            String extension = AvatarService.detectExtension(content);
            if (extension == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Envie uma imagem JPG, PNG ou WebP.");
            }
            // Sem localização GPS e dados do aparelho: a imagem fica pública
            content = AvatarService.withoutMetadata(content, extension);
            String name = UUID.randomUUID() + "." + extension;
            Files.createDirectories(directory);
            Files.write(directory.resolve(name), content);
            return PUBLIC_PATH + name;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Só endereços gerados aqui: a URL vem do navegador e aparece para todo mundo. */
    public boolean isOwnFile(String url) {
        return url != null && OWN_FILE.matcher(url).matches();
    }
}
