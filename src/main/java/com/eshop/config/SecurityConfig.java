package com.eshop.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@Profile("!test")
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // Pattern (non origini letterali): il browser invia sempre l'header
        // Origin sui POST anche same-origin; il CorsFilter di Spring Security
        // li valida e risponde 403 se l'origin non è consentita.
        // "http://localhost:*" / "http://127.0.0.1:*" coprono ogni porta
        // locale (8080 nginx, 8081 backend diretto, 30080 NodePort).
        config.setAllowedOriginPatterns(List.of(
            "http://localhost:*",
            "http://127.0.0.1:*"
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, CorsConfigurationSource corsConfigurationSource) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Auth - pubblici (protetti da rate limiting)
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/", "/index.html", "/favicon.ico").permitAll()

                        // Kubernetes probes (liveness/readiness) — pubbliche
                        .requestMatchers("/actuator/health/**").permitAll()

                        // Static resources - pubblici
                        .requestMatchers("/css/**", "/js/**", "/images/**", "/**/*.png", "/**/*.ico",
                                "/**/*.jpg", "/**/*.jpeg", "/**/*.gif", "/**/*.svg").permitAll()

                        // Catalogo articoli - GET pubblico
                        .requestMatchers("/api/articles").permitAll()
                        .requestMatchers("/api/articles/**").permitAll()

                        // Categorie - pubblico
                        .requestMatchers("/api/categories").permitAll()

                        // Cart - autenticati
                        .requestMatchers("/api/cart/**").hasAnyRole("USER", "ADMIN")

                        // Orders - autenticati
                        .requestMatchers("/api/orders/**").hasAnyRole("USER", "ADMIN")

                        // Admin - solo ADMIN
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")

                        // Default: autenticati
                        .anyRequest().authenticated()
                )
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
