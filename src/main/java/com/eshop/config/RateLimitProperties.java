package com.eshop.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configurazione rate limiting per gli endpoint di autenticazione.
 */
@Component
@ConfigurationProperties(prefix = "app.rate-limit")
@Getter
@Setter
public class RateLimitProperties {

    /** Limite richieste per login (per IP, per finestra) */
    private int login = 30;

    /** Limite richieste per registration (per IP, per finestra) */
    private int register = 10;

    /** Limite di default per altri endpoint protetti */
    private int defaultLimit = 30;

    /** Finestra temporale in secondi (default 60s = 1 minuto) */
    private int windowSeconds = 60;

    /** Path da proteggere */
    private RateLimitPath loginPath = new RateLimitPath("/api/auth/login", 30);
    private RateLimitPath registerPath = new RateLimitPath("/api/auth/register", 10);

    @Getter
    @Setter
    public static class RateLimitPath {
        public String path;
        public int limit;

        public RateLimitPath() {}
        public RateLimitPath(String path, int limit) {
            this.path = path;
            this.limit = limit;
        }
    }
}
