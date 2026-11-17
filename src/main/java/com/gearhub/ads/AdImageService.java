package com.gearhub.ads;

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

/** Imagens dos anúncios e logotipos dos anunciantes. Só o admin envia; ficam numa pasta pública própria. */
@Service
public class AdImageService {

    public static final String PUBLIC_PATH = "/uploads/ads/";
    private static final Pattern OWN_FILE = Pattern.compile(Pattern.quote(PUBLIC_PATH) + "[0-9a-f-]{36}\\.(jpg|png|webp)");

    private final Path directory;
    private final RateLimiter rateLimiter;

    public AdImageService(@Value("${gearhub.uploads.dir}") String uploads, RateLimiter rateLimiter) {
        this.directory = Path.of(uploads, "ads").toAbsolutePath().normalize();
        this.rateLimiter = rateLimiter;
    }

    public Path directory() {
        return directory;
    }

    public String store(UUID adminId, MultipartFile file) {
        rateLimiter.check(ContentImageService.UPLOAD_LIMIT_KEY + adminId, ContentImageService.UPLOADS_PER_MINUTE);
        try {
            byte[] content = file.getBytes();
            AvatarService.requireImageSize(content);
            String extension = AvatarService.detectExtension(content);
            if (extension == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Envie uma imagem JPG, PNG ou WebP.");
            }
            content = AvatarService.withoutMetadata(content, extension);
            String name = UUID.randomUUID() + "." + extension;
            Files.createDirectories(directory);
            Files.write(directory.resolve(name), content);
            return PUBLIC_PATH + name;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Só endereços gerados aqui: nada de imagem de fora num anúncio. */
    public boolean isOwnFile(String url) {
        return url != null && OWN_FILE.matcher(url).matches();
    }
}
