package com.gearhub.users;

import com.gearhub.admin.AuditService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Fotos em pasta local. Para publicar, troca-se só o destino por um armazenamento em nuvem. */
@Service
public class AvatarService {

    public static final String PUBLIC_PATH = "/uploads/avatars/";

    private final Path directory;
    private final UserRepository users;
    private final AuditService audit;

    public AvatarService(@Value("${gearhub.uploads.dir}") String uploads, UserRepository users, AuditService audit) {
        this.directory = Path.of(uploads, "avatars").toAbsolutePath().normalize();
        this.users = users;
        this.audit = audit;
    }

    public Path directory() {
        return directory;
    }

    @Transactional
    public User replace(UUID userId, MultipartFile file) {
        User user = users.findById(userId).orElseThrow();
        String url = store(file);
        history(user, "profile.avatar", user.getAvatarUrl(), url);
        user.changeAvatar(url);
        return user;
    }

    @Transactional
    public User replaceBanner(UUID userId, MultipartFile file) {
        User user = users.findById(userId).orElseThrow();
        String url = store(file);
        history(user, "profile.banner", user.getBannerUrl(), url);
        user.changeBanner(url);
        return user;
    }

    @Transactional
    public User removeAvatar(UUID userId) {
        User user = users.findById(userId).orElseThrow();
        history(user, "profile.avatar", user.getAvatarUrl(), null);
        user.changeAvatar(null);
        return user;
    }

    @Transactional
    public User removeBanner(UUID userId) {
        User user = users.findById(userId).orElseThrow();
        history(user, "profile.banner", user.getBannerUrl(), null);
        user.changeBanner(null);
        return user;
    }

    private String store(MultipartFile file) {
        byte[] content = read(file);
        String extension = detectExtension(content);
        if (extension == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Envie uma imagem JPG, PNG ou WebP.");
        }
        String name = UUID.randomUUID() + "." + extension;
        try {
            Files.createDirectories(directory);
            Files.write(directory.resolve(name), content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return PUBLIC_PATH + name;
    }

    /** A imagem antiga continua no disco (nada é apagado de verdade): a troca fica no histórico do perfil. */
    private void history(User user, String action, String before, String after) {
        Map<String, Object> data = new HashMap<>();
        data.put("de", before);
        data.put("para", after);
        audit.record(user.getId(), action, "user", user.getId(), data);
    }

    private static byte[] read(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Confere o conteúdo real do arquivo: extensão e tipo informados podem ser falsos. */
    public static String detectExtension(byte[] data) {
        if (data.length > 12) {
            if (startsWith(data, 0, new int[] {0xFF, 0xD8, 0xFF})) {
                return "jpg";
            }
            if (startsWith(data, 0, new int[] {0x89, 0x50, 0x4E, 0x47})) {
                return "png";
            }
            boolean riff = "RIFF".equals(ascii(data, 0, 4));
            if (riff && "WEBP".equals(ascii(data, 8, 12))) {
                return "webp";
            }
        }
        return null;
    }

    private static boolean startsWith(byte[] data, int offset, int[] signature) {
        for (int i = 0; i < signature.length; i++) {
            if ((data[offset + i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private static String ascii(byte[] data, int from, int to) {
        return new String(Arrays.copyOfRange(data, from, to), StandardCharsets.US_ASCII);
    }
}
