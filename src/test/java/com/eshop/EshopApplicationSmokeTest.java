package com.eshop;

import com.eshop.entity.User;
import com.eshop.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S0 — Smoke test: full context boots (Test profile, Testcontainers PostgreSQL)
 * and a basic JPA round-trip works.
 */
class EshopApplicationSmokeTest extends AbstractIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("Spring context boots with test profile and Testcontainers DB")
    void contextLoads() {
        assertThat(userRepository).isNotNull();
    }

    @Test
    @DisplayName("JPA round-trip: save and find a user")
    void databaseRoundTrip() {
        String suffix = String.valueOf(System.nanoTime());
        User user = User.builder()
                .username("smoke_" + suffix)
                .email("smoke_" + suffix + "@test.local")
                .password("plain-password")
                .build();

        User saved = userRepository.save(user);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getRole()).isEqualTo("USER");
        assertThat(userRepository.findByUsername(saved.getUsername())).isPresent();
        assertThat(userRepository.count()).isGreaterThanOrEqualTo(1);
    }
}
