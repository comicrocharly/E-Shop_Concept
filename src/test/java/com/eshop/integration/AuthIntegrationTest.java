package com.eshop.integration;

import com.eshop.entity.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S4 — Integrazione full-stack: flusso Auth (register/login/refresh) + verifica
 * end-to-end dei due path di autenticazione (Bearer JWT e {@code ?testUser=}).
 *
 * <p>Stati canonici (coerenti con S3, handler reale {@code GlobalExceptionHandler}):
 * <ul>
 *   <li>register duplicato → 400 (IllegalArgumentException senza "non trovato");</li>
 *   <li>login credenziali errate → 400 (stesso meccanismo);</li>
 *   <li>refresh token assente/invalido → 401 (esplicito nel controller);</li>
 *   <li>refresh utente orfano → 500 (RuntimeException → handler generico).</li>
 * </ul>
 */
class AuthIntegrationTest extends IntegrationTestSupport {

    // ==================== REGISTER ====================

    @Test
    void register_success_201_persistedWithBcryptAndCart() throws Exception {
        String username = unique("reg");
        String email = username + "@auth.test";

        MvcResult res = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "username", username,
                                "password", "secret123",
                                "email", email))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.cart.id").isNumber()) // cart 1:1 auto-creata (JPA @PrePersist)
                .andExpect(jsonPath("$.phoneNumbers").isArray())
                .andExpect(jsonPath("$.addresses").isArray())
                .andReturn();

        User inDb = userRepository.findByUsername(username).orElseThrow();
        assertThat(inDb.getPassword()).startsWith("$2"); // BCrypt
        assertThat(inDb.getPassword()).isNotEqualTo("secret123");
        assertThat(inDb.getRole()).isEqualTo("USER");
        assertThat(inDb.getEmail()).isEqualTo(email);
    }

    @Test
    void register_missingFields_400_fieldErrors() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.username").exists())
                .andExpect(jsonPath("$.password").exists())
                .andExpect(jsonPath("$.email").exists());
    }

    @Test
    void register_shortPassword_400() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "username", unique("u"),
                                "password", "12345",
                                "email", "shortpw@auth.test"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.password").exists());
    }

    @Test
    void register_invalidEmail_400() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "username", unique("u"),
                                "password", "secret123",
                                "email", "not-an-email"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.email").exists());
    }

    @Test
    void register_duplicateUsername_400() throws Exception {
        String username = register("secret123");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "username", username,
                                "password", "other123",
                                "email", unique("dup") + "@auth.test"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Username già in uso: " + username));
    }

    @Test
    void register_duplicateEmail_400() throws Exception {
        String username = register("secret123");
        String email = userRepository.findByUsername(username).orElseThrow().getEmail();
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "username", unique("dupmail"),
                                "password", "secret123",
                                "email", email))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Email già registrata: " + email));
    }

    // ==================== LOGIN ====================

    @Test
    void login_success_200_tokensWithValidClaims() throws Exception {
        String username = register("secret123");

        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "username", username,
                                "password", "secret123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.user.username").value(username))
                .andExpect(jsonPath("$.user.role").value("USER"))
                .andReturn();

        JsonNode body = readJson(res.getResponse().getContentAsString());
        String access = body.path("accessToken").asText();
        String refresh = body.path("refreshToken").asText();
        assertThat(jwtTokenProvider.validateToken(access)).isTrue();
        assertThat(jwtTokenProvider.validateToken(refresh)).isTrue();
        assertThat(jwtTokenProvider.getUsernameFromToken(access)).isEqualTo(username);
        assertThat(jwtTokenProvider.getRoleFromToken(access)).isEqualTo("USER");
        assertThat(jwtTokenProvider.getUsernameFromToken(refresh)).isEqualTo(username);
    }

    @Test
    void login_wrongPassword_400() throws Exception {
        String username = register("secret123");
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "username", username,
                                "password", "wrong-password"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Credenziali non valide"));
    }

    @Test
    void login_unknownUser_400() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "username", unique("ghost"),
                                "password", "secret123"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Credenziali non valide"));
    }

    @Test
    void login_missingFields_400() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.username").exists())
                .andExpect(jsonPath("$.password").exists());
    }

    @Test
    void login_admin_200_roleClaimAdmin() throws Exception {
        Auth a = admin();
        assertThat(a.role()).isEqualTo("ADMIN");
        assertThat(jwtTokenProvider.getRoleFromToken(a.accessToken())).isEqualTo("ADMIN");
        assertThat(jwtTokenProvider.validateToken(a.refreshToken())).isTrue();
    }

    // ==================== REFRESH ====================

    @Test
    void refresh_validToken_200_newPair() throws Exception {
        String username = register("secret123");
        Auth a = login(username, "secret123");

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", a.refreshToken()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.user.username").value(username));
    }

    @Test
    void refresh_missingToken_401() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_invalidToken_401() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", "not-a-jwt"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_orphanUser_500_currentBehavior() throws Exception {
        // ⚠ Comportamento attuale documentato: token valido ma utente inesistente
        // → RuntimeException("User not found") → handler generico → 500.
        String ghostToken = jwtTokenProvider.createRefreshToken(unique("ghost"));
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", ghostToken))))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("User not found"));
    }

    // ==================== AUTHE E2E (probe A/B/C formalizzati) ====================

    @Test
    void e2e_bearerJwt_usersMe_200() throws Exception {
        // PROVA-B (formalizzata): JwtAuthenticationFilter attivo nel contesto test,
        // il Bearer JWT autentica end-to-end.
        String username = register("secret123");
        Auth a = login(username, "secret123");
        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + a.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.id").value(a.id()));
    }

    @Test
    void e2e_testUserParam_usersMe_200() throws Exception {
        // PROVA-A (formalizzata): SecurityTestConfig.TestAuthFilter (param ?testUser=).
        String username = register("secret123");
        mockMvc.perform(get("/api/users/me").param("testUser", username))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username));
    }

    @Test
    void e2e_anonymousAdminEndpoint_403() throws Exception {
        // PROVA-C (formalizzata): @PreAuthorize enforced nel contesto test-profile.
        // Qui: endpoint admin SENZA autenticazione → AccessDeniedException → 403
        // (handler GlobalExceptionHandler, non il 500 del context slice S3).
        mockMvc.perform(post("/api/articles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"anon-art\",\"description\":\"x\",\"price\":10.00,\"stock\":5}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied"));
    }

    @Test
    void e2e_userBearerOnAdminEndpoint_403() throws Exception {
        Auth a = login(register("secret123"), "secret123");
        mockMvc.perform(post("/api/articles")
                        .header("Authorization", "Bearer " + a.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"user-art\",\"description\":\"x\",\"price\":10.00,\"stock\":5}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied"));
    }
}
