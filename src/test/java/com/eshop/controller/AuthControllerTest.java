package com.eshop.controller;

import com.eshop.EshopApplication;
import com.eshop.config.JwtAuthenticationFilter;
import com.eshop.config.JwtTokenProvider;
import com.eshop.config.RateLimitFilter;
import com.eshop.controller.ControllerTestSupport.MethodSecurityConfig;
import com.eshop.controller.ControllerTestSupport.TestFixtures;
import com.eshop.entity.User;
import com.eshop.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S3 — unit test {@code @WebMvcTest} di {@link AuthController} (service mockati).
 */
@WebMvcTest(value = AuthController.class, properties = "app.security.allow-test-userid=true")
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = EshopApplication.class)
@Import(MethodSecurityConfig.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    // La slice ritiene i Filter @Component: le loro dipendenze (JwtTokenProvider,
    // RateLimitProperties, UserDetailsService) non sono nella slice → mock li blocca (REBUILD_PLAN §2.6).
    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockBean
    private RateLimitFilter rateLimitFilter;

    // ==================== REGISTER ====================

    @Test
    void register_success_returns201() throws Exception {
        User user = TestFixtures.user(1L, "alice", false);
        when(userService.register(any())).thenReturn(user);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"secret123\",\"email\":\"alice@example.com\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void register_missingFields_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"123\",\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.username").exists())
                .andExpect(jsonPath("$.password").exists())
                .andExpect(jsonPath("$.email").exists());

        // La validazione @Valid fallisce PRIMA della chiamata al service
        verify(userService, org.mockito.Mockito.never()).register(any());
    }

    @Test
    void register_duplicateUsername_returns400() throws Exception {
        when(userService.register(any()))
                .thenThrow(new IllegalArgumentException("Username già in uso: alice"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"secret123\",\"email\":\"alice@example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Username già in uso: alice"));
    }

    // ==================== LOGIN ====================

    @Test
    void login_success_returns200WithTokens() throws Exception {
        User user = TestFixtures.user(1L, "alice", false);
        when(userService.authenticate(any())).thenReturn(user);
        when(jwtTokenProvider.createAccessToken("alice", "USER")).thenReturn("access-token");
        when(jwtTokenProvider.createRefreshToken("alice")).thenReturn("refresh-token");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"secret123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-token"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-token"))
                .andExpect(jsonPath("$.user.username").value("alice"))
                .andExpect(jsonPath("$.user.role").value("USER"));
    }

    @Test
    void login_wrongCredentials_returns400() throws Exception {
        when(userService.authenticate(any()))
                .thenThrow(new IllegalArgumentException("Credenziali non valide"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"wrong\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Credenziali non valide"));
    }

    @Test
    void login_missingFields_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.username").exists())
                .andExpect(jsonPath("$.password").exists());
    }

    // ==================== REFRESH ====================

    @Test
    void refresh_missingToken_returns401() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_invalidToken_returns401() throws Exception {
        when(jwtTokenProvider.validateToken("bad-token")).thenReturn(false);

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"bad-token\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_success_returns200WithNewTokens() throws Exception {
        User user = TestFixtures.user(1L, "alice", false);
        when(jwtTokenProvider.validateToken("valid-refresh")).thenReturn(true);
        when(jwtTokenProvider.getUsernameFromToken("valid-refresh")).thenReturn("alice");
        when(userService.findByUsername("alice")).thenReturn(Optional.of(user));
        when(jwtTokenProvider.createAccessToken("alice", "USER")).thenReturn("new-access");
        when(jwtTokenProvider.createRefreshToken("alice")).thenReturn("new-refresh");

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"valid-refresh\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-access"))
                .andExpect(jsonPath("$.refreshToken").value("new-refresh"))
                .andExpect(jsonPath("$.user.username").value("alice"));
    }

    @Test
    void refresh_userNotFound_returns500() throws Exception {
        // ⚠ Comportamento attuale documentato: RuntimeException → handler generico → 500
        when(jwtTokenProvider.validateToken("orphan-token")).thenReturn(true);
        when(jwtTokenProvider.getUsernameFromToken("orphan-token")).thenReturn("ghost");
        when(userService.findByUsername("ghost")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"orphan-token\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("User not found"));
    }
}
