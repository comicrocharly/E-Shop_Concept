package com.eshop.controller;

import com.eshop.EshopApplication;
import com.eshop.config.CurrentUser;
import com.eshop.config.JwtAuthenticationFilter;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S3 — unit test {@code @WebMvcTest} di {@link UserController} (service mockato).
 */
@WebMvcTest(value = UserController.class, properties = "app.security.allow-test-userid=true")
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = EshopApplication.class)
@Import(MethodSecurityConfig.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

    @MockBean
    private CurrentUser currentUser;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockBean
    private RateLimitFilter rateLimitFilter;

    private static final User ALICE = TestFixtures.user(1L, "alice", false);

    @Test
    void getCurrentUser_success_returns200() throws Exception {
        when(currentUser.getCurrentUserId()).thenReturn(1L);
        when(userService.findById(1L)).thenReturn(Optional.of(ALICE));
        when(userService.toUserResponse(ALICE))
                .thenReturn(TestFixtures.userResponse(ALICE));

        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.email").value("alice@example.com"));
    }

    @Test
    void getCurrentUser_withTestUserIdParam_usesParam() throws Exception {
        User bob = TestFixtures.user(7L, "bob", false);
        when(userService.findById(7L)).thenReturn(Optional.of(bob));
        when(userService.toUserResponse(bob)).thenReturn(TestFixtures.userResponse(bob));

        mockMvc.perform(get("/api/users/me").param("testUserId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("bob"));

        verify(userService).findById(7L);
    }

    @Test
    void getCurrentUser_notFound_returns500() throws Exception {
        // ⚠ Comportamento attuale documentato: RuntimeException → handler generico → 500
        when(currentUser.getCurrentUserId()).thenReturn(1L);
        when(userService.findById(1L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void updateProfile_success_returns200() throws Exception {
        User updated = TestFixtures.user(1L, "alice", false);
        updated.setEmail("new@example.com");
        when(currentUser.getCurrentUserId()).thenReturn(1L);
        when(userService.updateProfile(eq(1L), anyMap())).thenReturn(updated);
        when(userService.toUserResponse(updated))
                .thenReturn(TestFixtures.userResponse(updated));

        mockMvc.perform(put("/api/users/me/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("new@example.com"));
    }

    @Test
    void updateProfile_changingPasswordWithoutCurrent_returns400() throws Exception {
        when(currentUser.getCurrentUserId()).thenReturn(1L);
        when(userService.updateProfile(eq(1L), anyMap()))
                .thenThrow(new IllegalArgumentException(
                        "È richiesta la password corrente per cambiare la password"));

        mockMvc.perform(put("/api/users/me/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"newpassword1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("È richiesta la password corrente per cambiare la password"));
    }

    @Test
    void updateProfile_wrongCurrentPassword_returns400() throws Exception {
        when(currentUser.getCurrentUserId()).thenReturn(1L);
        when(userService.updateProfile(eq(1L), anyMap()))
                .thenThrow(new IllegalArgumentException("La password corrente non è corretta"));

        mockMvc.perform(put("/api/users/me/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"newpassword1\",\"currentPassword\":\"wrong\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("La password corrente non è corretta"));
    }
}
