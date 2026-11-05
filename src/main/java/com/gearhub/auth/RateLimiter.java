package com.gearhub.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Limite de tentativas em memória. Com mais de uma instância da API, precisa ir para um armazenamento comum. */
@Component
public class RateLimiter {

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(10))
            .maximumSize(100_000)
            .build();

    public void check(String key, int limitPerMinute) {
        Bucket bucket = buckets.get(key, ignored -> Bucket.builder()
                .addLimit(limit -> limit.capacity(limitPerMinute).refillGreedy(limitPerMinute, Duration.ofMinutes(1)))
                .build());
        if (!bucket.tryConsume(1)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Muitas tentativas. Aguarde um minuto e tente novamente.");
        }
    }
}
