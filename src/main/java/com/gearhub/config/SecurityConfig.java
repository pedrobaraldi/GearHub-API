package com.gearhub.config;

import com.gearhub.auth.AuthProperties;
import com.gearhub.auth.OriginCheckFilter;
import com.gearhub.auth.ProblemResponse;
import com.gearhub.auth.SessionAuthenticationFilter;
import com.gearhub.auth.SessionRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Tudo começa fechado: uma rota só responde se estiver liberada aqui.
 */
@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        // Argon2id: 16 bytes de salt, 32 de hash, 1 thread, 19 MiB e 2 iterações
        return new Argon2PasswordEncoder(16, 32, 1, 19_456, 2);
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, SessionRepository sessions, AuthProperties properties)
            throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> ProblemResponse.write(
                                response, 401, "Entre na sua conta para continuar.")))
                .addFilterBefore(new OriginCheckFilter(properties), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new SessionAuthenticationFilter(sessions), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // Sem conta, só o que leva a ter uma: entrar (com o código de 2FA), cadastrar e recuperar a senha
                        .requestMatchers("/actuator/health", "/error").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login",
                                "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password", "/api/v1/auth/2fa/verify").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/google/**", "/api/v1/auth/discord/**", "/api/v1/auth/errors/*").permitAll()
                        // Todo o conteúdo exige conta, como X e Instagram: feed, perfis, produtos e até as fotos
                        .requestMatchers(HttpMethod.GET, "/api/v1/categories/**", "/api/v1/feed", "/api/v1/products", "/api/v1/post-topics",
                                "/api/v1/products/*", "/api/v1/hall", "/api/v1/search", "/api/v1/games", "/api/v1/games/*", "/api/v1/gear-options", "/api/v1/users/*/setup", "/api/v1/users/*/setup/photos", "/api/v1/users/*/progress", "/api/v1/users/*/games", "/api/v1/badges", "/api/v1/market/**", "/api/v1/catalog/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/uploads/avatars/**", "/uploads/content/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/uploads/images").authenticated()
                        .requestMatchers("/api/v1/posts/**", "/api/v1/reviews/**", "/api/v1/comments/**", "/api/v1/me/**").authenticated()
                        .requestMatchers("/api/v1/users/*/follow", "/api/v1/users/*/badges/*").authenticated()
                        // Denúncias; o painel de admin confere ainda o papel e a verificação em duas etapas
                        .requestMatchers("/api/v1/reports", "/api/v1/report-reasons", "/api/v1/admin/**").authenticated()
                        .requestMatchers("/api/v1/users/me/avatar", "/api/v1/users/me/banner").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/me").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/users/me").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/*").authenticated()
                        .anyRequest().denyAll())
                .build();
    }
}
