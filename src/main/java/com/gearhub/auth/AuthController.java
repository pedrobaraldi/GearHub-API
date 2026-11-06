package com.gearhub.auth;

import com.gearhub.config.OpenApiConfig;
import com.gearhub.crypto.CryptoService;
import com.gearhub.users.User;
import com.gearhub.users.UserResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Autenticação", description = "Cadastro, login e sessão por cookie")
public class AuthController {

    private static final String STATE_COOKIE = "gearhub_oauth_state";

    /** Mensagens do login social: o site recebe só o código e pede o texto aqui. */
    private static final Map<String, String> LOGIN_ERRORS = Map.of(
            GoogleAuthService.ERROR_FAILED, "Não conseguimos entrar com o Google. Tente novamente.",
            GoogleAuthService.ERROR_UNVERIFIED, "Sua conta Google não tem o e-mail confirmado.",
            "google-cancelado", "O login com Google foi cancelado.",
            DiscordAuthService.ERROR_FAILED, "Não conseguimos entrar com o Discord. Tente novamente.",
            DiscordAuthService.ERROR_UNVERIFIED, "Confirme o e-mail da sua conta Discord e tente novamente.",
            "discord-cancelado", "O login com Discord foi cancelado.");

    private final AuthService auth;
    private final GoogleAuthService google;
    private final DiscordAuthService discord;
    private final GoogleProperties googleProperties;
    private final CryptoService crypto;
    private final AuthProperties properties;

    public AuthController(AuthService auth, GoogleAuthService google, DiscordAuthService discord,
            GoogleProperties googleProperties, CryptoService crypto, AuthProperties properties) {
        this.auth = auth;
        this.google = google;
        this.discord = discord;
        this.googleProperties = googleProperties;
        this.crypto = crypto;
        this.properties = properties;
    }

    @GetMapping("/google/url")
    @Operation(summary = "Devolve o endereço de login do Google")
    @ApiResponse(responseCode = "503", description = "Login com Google não configurado")
    public ResponseEntity<Map<String, String>> googleUrl() {
        String state = crypto.newToken();
        return withStateCookie(state, google.authorizationUrl(state));
    }

    @GetMapping("/discord/url")
    @Operation(summary = "Devolve o endereço de login do Discord")
    @ApiResponse(responseCode = "503", description = "Login com Discord não configurado")
    public ResponseEntity<Map<String, String>> discordUrl() {
        String state = crypto.newToken();
        return withStateCookie(state, discord.authorizationUrl(state));
    }

    private ResponseEntity<Map<String, String>> withStateCookie(String state, String url) {
        ResponseCookie cookie = ResponseCookie.from(STATE_COOKIE, state)
                .httpOnly(true).secure(properties.secureCookie()).sameSite("Lax").path("/")
                .maxAge(Duration.ofMinutes(10)).build();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString()).body(Map.of("url", url));
    }

    @GetMapping("/google/callback")
    @Operation(summary = "Retorno do Google: inicia a sessão e volta ao site")
    public ResponseEntity<Void> googleCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            HttpServletRequest request) {
        if (error != null) {
            return redirectWithError("google-cancelado");
        }
        if (code == null || !sameState(state, stateCookie(request))) {
            return redirectWithError(GoogleAuthService.ERROR_FAILED);
        }
        return finishLogin(() -> google.authenticate(code), request);
    }

    @GetMapping("/discord/callback")
    @Operation(summary = "Retorno do Discord: inicia a sessão e volta ao site")
    public ResponseEntity<Void> discordCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            HttpServletRequest request) {
        if (error != null) {
            return redirectWithError("discord-cancelado");
        }
        if (code == null || !sameState(state, stateCookie(request))) {
            return redirectWithError(DiscordAuthService.ERROR_FAILED);
        }
        return finishLogin(() -> discord.authenticate(code), request);
    }

    private ResponseEntity<Void> finishLogin(Supplier<User> authenticate, HttpServletRequest request) {
        try {
            String token = auth.openOAuthSession(authenticate.get(), request.getRemoteAddr(),
                    request.getHeader("User-Agent"));
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(googleProperties.siteUrl() + "/"))
                    .header(HttpHeaders.SET_COOKIE, sessionCookie(token, properties.sessionTtl()).toString())
                    .build();
        } catch (OAuthLoginException e) {
            return redirectWithError(e.code());
        }
    }

    @GetMapping("/errors/{code}")
    @Operation(summary = "Traduz um código de erro de login em mensagem")
    public Map<String, String> loginError(@PathVariable String code) {
        String detail = LOGIN_ERRORS.get(code);
        if (detail == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Não foi possível concluir o login.");
        }
        return Map.of("detail", detail);
    }

    private ResponseEntity<Void> redirectWithError(String code) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(googleProperties.siteUrl() + "/auth?erro=" + code))
                .build();
    }

    private static String stateCookie(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (STATE_COOKIE.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    private static boolean sameState(String received, String expected) {
        return received != null && expected != null && MessageDigest.isEqual(
                received.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/register")
    @Operation(summary = "Cria uma conta e já inicia a sessão")
    @ApiResponse(responseCode = "201", description = "Conta criada")
    @ApiResponse(responseCode = "400", description = "Dados inválidos")
    @ApiResponse(responseCode = "409", description = "E-mail ou nome de usuário em uso")
    @ApiResponse(responseCode = "429", description = "Muitas tentativas")
    public ResponseEntity<UserResponses.Me> register(
            @Valid @RequestBody AuthRequests.Register body, HttpServletRequest request) {
        AuthService.AuthResult result = auth.register(body, request.getRemoteAddr(), request.getHeader("User-Agent"));
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE, sessionCookie(result.token(), properties.sessionTtl()).toString())
                .body(UserResponses.Me.from(result.user()));
    }

    @PostMapping("/login")
    @Operation(summary = "Entra com e-mail e senha")
    @ApiResponse(responseCode = "200", description = "Sessão iniciada")
    @ApiResponse(responseCode = "401", description = "E-mail ou senha incorretos")
    @ApiResponse(responseCode = "429", description = "Muitas tentativas")
    public ResponseEntity<UserResponses.Me> login(
            @Valid @RequestBody AuthRequests.Login body, HttpServletRequest request) {
        AuthService.AuthResult result = auth.login(body, request.getRemoteAddr(), request.getHeader("User-Agent"));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookie(result.token(), properties.sessionTtl()).toString())
                .body(UserResponses.Me.from(result.user()));
    }

    @PostMapping("/logout")
    @Operation(summary = "Encerra a sessão atual", security = @SecurityRequirement(name = "session"))
    @ApiResponse(responseCode = "204", description = "Sessão encerrada")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal CurrentUser current) {
        auth.logout(current.sessionId());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, sessionCookie("", Duration.ZERO).toString())
                .build();
    }

    @GetMapping("/me")
    @Operation(summary = "Devolve a conta da sessão atual", security = @SecurityRequirement(name = "session"))
    @ApiResponse(responseCode = "401", description = "Sem sessão válida")
    public UserResponses.Me me(@AuthenticationPrincipal CurrentUser current) {
        return UserResponses.Me.from(auth.currentUser(current.id()));
    }

    private ResponseCookie sessionCookie(String value, Duration maxAge) {
        return ResponseCookie.from(OpenApiConfig.SESSION_COOKIE, value)
                .httpOnly(true)
                .secure(properties.secureCookie())
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
    }
}
