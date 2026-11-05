package com.gearhub.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Proteção contra CSRF: como a sessão é um cookie, alterações só são aceitas
 * quando vêm de uma origem conhecida. Navegadores sempre enviam Origin nesses métodos.
 */
public class OriginCheckFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final Set<String> allowedOrigins;

    public OriginCheckFilter(AuthProperties properties) {
        this.allowedOrigins = Set.copyOf(properties.allowedOrigins());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        if (!SAFE_METHODS.contains(request.getMethod()) && origin != null && !allowedOrigins.contains(origin)) {
            ProblemResponse.write(response, HttpServletResponse.SC_FORBIDDEN,
                    "Esta ação não é permitida a partir deste endereço.");
            return;
        }
        chain.doFilter(request, response);
    }
}
