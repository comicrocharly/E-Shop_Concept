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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Filter per rate limiting su endpoint di autenticazione.
 *
 * Traccia le richieste per IP con una fixed window temporale.
 *
 * Header response:
 * - X-RateLimit-Limit: massimo richieste consentite
 * - X-RateLimit-Remaining: richieste rimanenti nella finestra
 * - Retry-After: secondi prima di riprovare (solo se bloccato)
 */
@Component
@Order(1) // Esegue PRIMA di tutti gli altri filter
public class RateLimitFilter extends OncePerRequestFilter {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    /** Sopraggiungendo questa dimensione si puliscono le finestre scadute. */
    private static final int STALE_SWEEP_THRESHOLD = 10_000;

    private final RateLimitProperties properties;

    /**
     * chiave = IP::path, valore = Window con count e timestamp.
     * ConcurrentHashMap + Window sincronizzata per la thread-safety.
     */
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicInteger sweepCounter = new AtomicInteger(0);

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

        int limit = getLimitForPath(path);
        long windowNanos = properties.getWindowSeconds() * NANOS_PER_SECOND;

        // chiave composta (IP + path): finestre separate per endpoint
        String windowKey = clientIp + "::" + path;
        Window window = windows.computeIfAbsent(windowKey,
            k -> new Window(System.nanoTime(), limit, windowNanos));

        // Evita la crescita illimitata della mappa (una entry per ogni IP sconosciuto):
        // ogni ~1024 richieste si controlla se serve la pulizia.
        if (sweepCounter.incrementAndGet() >= 1024) {
            sweepCounter.set(0);
            if (windows.size() > STALE_SWEEP_THRESHOLD) {
                windows.entrySet().removeIf(e -> e.getValue().isStale());
            }
        }

        long remaining = window.tryConsume();

        if (remaining < 0) {
            // Troppo molte richieste → 429
            long retryAfterSeconds = Math.max(1,
                    (windowNanos - (System.nanoTime() - window.getWindowStartNanos())) / NANOS_PER_SECOND);
            response.setStatus(429);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            response.getWriter().write("{\"error\":\"Troppe richieste. Riprova tra %d secondi\"}".formatted(
                retryAfterSeconds
            ));
            return;
        }

        // Header di rate limit
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
     * Finestra temporale per un singolo IP.
     * Thread-safe per accessi concorrenti allo stesso IP.
     */
    private static class Window {
        private final long windowNanos;
        private final int limit;
        private volatile long windowStartNanos;
        private long requestCount;

        Window(long windowStartNanos, int limit, long windowNanos) {
            this.windowStartNanos = windowStartNanos;
            this.limit = limit;
            this.windowNanos = windowNanos;
            this.requestCount = 0;
        }

        /**
         * Tenta di consumare una richiesta.
         * @return numero di richieste rimanenti, o -1 se bloccato
         */
        synchronized long tryConsume() {
            // Finestra scaduta → reset
            long now = System.nanoTime();
            if (now - windowStartNanos > windowNanos) {
                this.windowStartNanos = now;
                requestCount = 0;
            }

            if (requestCount >= limit) {
                return -1;
            }

            requestCount++;
            return limit - requestCount;
        }

        synchronized long getWindowStartNanos() {
            return windowStartNanos;
        }

        /** Finestra non più attiva (usata solo dalla pulizia, nessuna azione interna). */
        boolean isStale() {
            return System.nanoTime() - windowStartNanos > windowNanos;
        }
    }
}
