package com.eshop.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Filter per rate limiting su endpoint di autenticazione.
 * 
 * Utilizza un sliding window algorithm per tracciare le richieste per IP.
 * 
 * Header response:
 * - X-RateLimit-Limit: massimo richieste consentite
 * - X-RateLimit-Remaining: richieste rimanenti nella finestra
 * - Retry-After: secondi prima di重试 (solo se bloccato)
 */
@Component
@Order(1) // Esegue PRIMA di tutti gli altri filter
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitProperties properties;

    /**
     * Sliding window: chiave = IP::path, valore = Window con count e timestamp
     * Usa ConcurrentHashMap per thread-safety senza sincronizzazione esplicita
     */
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(RateLimitProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.equals(properties.getLoginPath().getPath())
            && !path.equals(properties.getRegisterPath().getPath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, 
                                   FilterChain filterChain) throws ServletException, IOException {
        String clientIp = getClientIp(request);
        String path = request.getRequestURI();

        // Determina il limite per questo endpoint
        int limit = getLimitForPath(path);
        long windowNanos = properties.getWindowSeconds() * 1_000_000_000L; // Convert to nanoseconds (1s = 1B ns)

        // Usa chiave composta (IP + path) per tracciare finestre separate per endpoint
        String windowKey = clientIp + "::" + path;
        Window window = windows.computeIfAbsent(windowKey, 
            k -> new Window(System.nanoTime(), limit, windowNanos));

        // Pulizia e verifica
        long remaining = window.tryConsume();

        if (remaining < 0) {
            // Troppo molte richieste → 429
            response.setStatus(429);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            long retryAfter = (windowNanos / 1_000_000_000L) - 
                ((System.nanoTime() - window.getWindowStart()) / 1_000_000_000L);
            response.setHeader("Retry-After", String.valueOf(Math.max(1, retryAfter)));
            response.getWriter().write("{\"error\":\"Troppe richieste. Riprova tra %d secondi\"}".formatted(
                Math.max(1, retryAfter)
            ));
            return;
        }

        // Aggiungi header di rate limit
        response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, remaining)));

        // Prosegui con la request
        filterChain.doFilter(request, response);
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            // Prendi il primo IP (cliente originale)
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private int getLimitForPath(String path) {
        if (path.equals(properties.getLoginPath().getPath())) {
            return properties.getLoginPath().getLimit();
        }
        if (path.equals(properties.getRegisterPath().getPath())) {
            return properties.getRegisterPath().getLimit();
        }
        return properties.getDefaultLimit();
    }

    /**
     * Finestra temporale sliding per un singolo IP.
     * Thread-safe per accessi concorrenti allo stesso IP.
     */
    private static class Window {
        private long windowStart;
        private final long windowMillis;
        private final int limit;
        private long requestCount;

        Window(long windowStart, int limit, long windowMillis) {
            this.windowStart = windowStart;
            this.limit = limit;
            this.windowMillis = windowMillis;
            this.requestCount = 0;
        }

        /**
         * Tenta di consumare una richiesta.
         * @return numero di richieste rimanenti, o -1 se bloccato
         */
        synchronized long tryConsume() {
            // Controlla se la finestra è scaduta (reset)
            long now = System.nanoTime();
            if (now - windowStart > windowMillis) {
                this.windowStart = now;
                requestCount = 0;
            }

            if (requestCount >= limit) {
                return -1;
            }

            requestCount++;
            return limit - requestCount;
        }

        long getWindowStart() {
            return windowStart;
        }
    }
}
