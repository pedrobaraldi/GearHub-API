package com.gearhub.auth;

import java.util.UUID;

/** Usuário da requisição, resolvido pela sessão. */
public record CurrentUser(UUID id, UUID sessionId, String role) {
}
