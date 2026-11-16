package com.gearhub.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limite geral de requisições por IP, antes de qualquer outra coisa: protege a API de robôs que disparam
 * milhares de chamadas. Os limites mais apertados de login, cadastro e senha continuam valendo por cima.
 * O IP vem do proxy (o site) só quando ele está na rede interna: ver server.forward-headers-strategy.
 */
public class RequestRateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;
    private final int requestsPerMinute;

    public RequestRateLimitFilter(RateLimiter rateLimiter, int requestsPerMinute) {
        this.rateLimiter = rateLimiter;
        this.requestsPerMinute = requestsPerMinute;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // O monitoramento consulta a saúde da API o tempo todo
        return "/actuator/health".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!rateLimiter.tryConsume("requests:" + request.getRemoteAddr(), requestsPerMinute)) {
            response.setHeader("Retry-After", "60");
            ProblemResponse.write(response, 429, "Muitas requisições. Aguarde um pouco e tente de novo.");
            return;
        }
        chain.doFilter(request, response);
    }
}
