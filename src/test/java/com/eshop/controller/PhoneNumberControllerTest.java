package com.eshop.controller;

import com.eshop.EshopApplication;
import com.eshop.config.CurrentUser;
import com.eshop.config.JwtAuthenticationFilter;
import com.eshop.config.RateLimitFilter;
import com.eshop.controller.ControllerTestSupport.MethodSecurityConfig;
import com.eshop.controller.ControllerTestSupport.TestFixtures;
import com.eshop.dto.AddPhoneNumberRequest;
import com.eshop.service.PhoneNumberService;
import jakarta.persistence.EntityNotFoundException;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S3 — unit test {@code @WebMvcTest} di {@link PhoneNumberController} (service mockato).
 */
@WebMvcTest(value = PhoneNumberController.class, properties = "app.security.allow-test-userid=true")
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = EshopApplication.class)
@Import(MethodSecurityConfig.class)
class PhoneNumberControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PhoneNumberService phoneNumberService;

    @MockBean
    private CurrentUser currentUser;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockBean
    private RateLimitFilter rateLimitFilter;

    @Test
    void findByUser_success_returns200() throws Exception {
        when(currentUser.getCurrentUserId()).thenReturn(1L);
        when(phoneNumberService.findByUserId(1L))
                .thenReturn(List.of(TestFixtures.phoneNumberResponse(1L)));

        mockMvc.perform(get("/api/users/1/phone/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].countryPrefix").value("+39"))
                .andExpect(jsonPath("$[0].phoneType").value("MOBILE"));
    }

    @Test
    void findByUser_empty_returnsEmptyArray() throws Exception {
        when(currentUser.getCurrentUserId()).thenReturn(1L);
        when(phoneNumberService.findByUserId(1L)).thenReturn(List.of());

        mockMvc.perform(get("/api/users/1/phone/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void add_success_returns200() throws Exception {
        when(currentUser.getCurrentUserId()).thenReturn(1L);
        when(phoneNumberService.add(eq(1L), any(AddPhoneNumberRequest.class)))
                .thenReturn(TestFixtures.phoneNumberResponse(1L));

        mockMvc.perform(post("/api/users/1/phone/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"countryPrefix\":\"+39\",\"number\":\"3331234567\",\"phoneType\":\"MOBILE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countryPrefix").value("+39"));
    }

    @Test
    void add_missingNumber_returns400() throws Exception {
        mockMvc.perform(post("/api/users/1/phone/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"countryPrefix\":\"+39\",\"phoneType\":\"MOBILE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.number").exists());

        verify(phoneNumberService, never()).add(anyLong(), any(AddPhoneNumberRequest.class));
    }

    @Test
    void add_invalidPrefix_returns400() throws Exception {
        mockMvc.perform(post("/api/users/1/phone/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"countryPrefix\":\"abc\",\"number\":\"3331234567\",\"phoneType\":\"MOBILE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.countryPrefix").value("Prefisso non valido (es. +39)"));
    }

    @Test
    void add_missingPhoneType_returns400() throws Exception {
        mockMvc.perform(post("/api/users/1/phone/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"countryPrefix\":\"+39\",\"number\":\"3331234567\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.phoneType").exists());
    }

    @Test
    void add_invalidPhoneTypeValue_returns400() throws Exception {
        // phoneType "INVALID" passa la validazione DTO (solo @NotBlank) e fallisce
        // nello service (PhoneType.valueOf) → 400 via handler
        when(currentUser.getCurrentUserId()).thenReturn(1L);
        when(phoneNumberService.add(eq(1L), any(AddPhoneNumberRequest.class)))
                .thenThrow(new IllegalArgumentException(
                        "INVALID is not a valid PhoneType"));

        mockMvc.perform(post("/api/users/1/phone/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"countryPrefix\":\"+39\",\"number\":\"3331234567\",\"phoneType\":\"INVALID\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID is not a valid PhoneType"));
    }

    @Test
    void delete_success_returns204() throws Exception {
        when(currentUser.getCurrentUserId()).thenReturn(1L);

        mockMvc.perform(delete("/api/users/1/phone/me/9"))
                .andExpect(status().isNoContent());

        verify(phoneNumberService).delete(1L, 9L);
    }

    @Test
    void delete_notFound_returns500() throws Exception {
        // ⚠ Comportamento attuale documentato: EntityNotFoundException → handler generico → 500
        when(currentUser.getCurrentUserId()).thenReturn(1L);
        doThrow(new EntityNotFoundException("Phone not found or owned by user"))
                .when(phoneNumberService).delete(1L, 99L);

        mockMvc.perform(delete("/api/users/1/phone/me/99"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Phone not found or owned by user"));
    }
}
