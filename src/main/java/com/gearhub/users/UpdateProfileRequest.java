package com.gearhub.users;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Campos não enviados continuam como estão; enviados vazios são apagados")
public record UpdateProfileRequest(
        @Pattern(regexp = "^[a-z0-9]{3,30}$", message = "Use de 3 a 30 letras minúsculas ou números")
        String username,

        @Size(max = 60, message = "Use no máximo 60 caracteres")
        @Pattern(regexp = "(?s).*\\S.*", message = "Informe seu nome")
        String displayName,

        @Size(max = 240, message = "Use no máximo 240 caracteres")
        String bio,

        @Size(max = 200, message = "Use no máximo 200 caracteres")
        @Pattern(regexp = "^$|^https?://\\S+$", message = "Use um endereço começando com https://")
        String website,

        @Pattern(regexp = "^$|^@?[A-Za-z0-9_]{1,15}$", message = "Informe um usuário válido do Twitter / X")
        String twitter,

        @Pattern(regexp = "^$|^@?[A-Za-z0-9._]{1,30}$", message = "Informe um usuário válido do Instagram")
        String instagram,

        @Size(max = 200, message = "Use no máximo 200 caracteres")
        @Pattern(regexp = "^$|^https://(www\\.)?youtube\\.com/\\S+$", message = "Use um link como https://youtube.com/@canal")
        String youtube) {
}
