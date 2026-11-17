package com.gearhub.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Token do painel de admin (Authorization: Bearer). Só vale nas rotas de admin e no logout do painel: um token
 * vazado não abre o resto do site. Token inválido ou vencido responde 401 na hora, para o programa pedir login.
 */
public class AdminTokenAuthenticationFilter extends OncePerRequestFilter {

    /** Atributo da requisição com o aparelho, para a auditoria registrar de onde veio cada ação. */
    public static final String DEVICE_ATTRIBUTE = "gearhub.adminDevice";

    private final AdminAppTokenService tokens;

    public AdminTokenAuthenticationFilter(AdminAppTokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        String header = request.getHeader("Authorization");
        boolean adminPath = path.startsWith("/api/v1/admin/") || path.equals("/api/v1/auth/admin/logout");
        return !adminPath || header == null || !header.startsWith("Bearer ");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getHeader("Authorization").substring("Bearer ".length()).strip();
        Optional<AdminAppTokenService.Holder> holder = token.isEmpty() ? Optional.empty() : tokens.authenticate(token);
        if (holder.isEmpty()) {
            ProblemResponse.write(response, HttpServletResponse.SC_UNAUTHORIZED, "Sua sessão no painel expirou. Entre de novo.");
            return;
        }
        AdminAppTokenService.Holder found = holder.get();
        // O token vale sozinho: um cookie de sessão junto não muda quem está agindo
        CurrentUser principal = new CurrentUser(found.userId(), found.tokenId(), found.role());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + found.role().toUpperCase()))));
        request.setAttribute(DEVICE_ATTRIBUTE, found.deviceName());
        chain.doFilter(request, response);
    }
}
