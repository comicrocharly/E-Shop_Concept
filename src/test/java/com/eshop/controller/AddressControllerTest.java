package com.eshop.controller;

import com.eshop.EshopApplication;
import com.eshop.config.CurrentUser;
import com.eshop.config.JwtAuthenticationFilter;
import com.eshop.config.RateLimitFilter;
import com.eshop.controller.ControllerTestSupport.MethodSecurityConfig;
import com.eshop.controller.ControllerTestSupport.TestFixtures;
import com.eshop.dto.AddAddressRequest;
import com.eshop.service.AddressService;
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
 * S3 — unit test {@code @WebMvcTest} di {@link AddressController} (service mockato).
 *
 * <p>Nota: il path è {@code /api/users/{userId}/address/...} ma il {@code {userId}} non è mai
 * letto dal controller: l'utente effettivo viene da {@code testUserId} oppure da
 * {@code currentUser.getCurrentUserId()}.</p>
 */
@WebMvcTest(AddressController.class)
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = EshopApplication.class)
@Import(MethodSecurityConfig.class)
class AddressControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AddressService addressService;

    @MockBean
    private CurrentUser currentUser;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockBean
    private RateLimitFilter rateLimitFilter;

    @Test
    void findByUser_success_returns200() throws Exception {
        when(currentUser.getCurrentUserId()).thenReturn(1L);
        when(addressService.findByUserId(1L))
                .thenReturn(List.of(TestFixtures.addressResponse(1L)));

        mockMvc.perform(get("/api/users/1/address/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].street").value("Via Roma"))
                .andExpect(jsonPath("$[0].city").value("Roma"));
    }

    @Test
    void findByUser_empty_returnsEmptyArray() throws Exception {
        when(currentUser.getCurrentUserId()).thenReturn(1L);
        when(addressService.findByUserId(1L)).thenReturn(List.of());

        mockMvc.perform(get("/api/users/1/address/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void add_success_returns200() throws Exception {
        when(currentUser.getCurrentUserId()).thenReturn(1L);
        when(addressService.add(eq(1L), any(AddAddressRequest.class)))
                .thenReturn(TestFixtures.addressResponse(1L));

        mockMvc.perform(post("/api/users/1/address/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"street\":\"Via Roma\",\"streetNumber\":1,\"postalCode\":\"00100\"," +
                                "\"city\":\"Roma\",\"country\":\"IT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.street").value("Via Roma"));
    }

    @Test
    void add_missingFields_returns400() throws Exception {
        mockMvc.perform(post("/api/users/1/address/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"streetNumber\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.street").exists())
                .andExpect(jsonPath("$.postalCode").exists())
                .andExpect(jsonPath("$.city").exists())
                .andExpect(jsonPath("$.country").exists());

        verify(addressService, never()).add(anyLong(), any(AddAddressRequest.class));
    }

    @Test
    void add_streetNumberBelowMin_returns400() throws Exception {
        mockMvc.perform(post("/api/users/1/address/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"street\":\"Via Roma\",\"streetNumber\":0,\"postalCode\":\"00100\"," +
                                "\"city\":\"Roma\",\"country\":\"IT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.streetNumber").value("Il numero civico deve essere ≥ 1"));
    }

    @Test
    void delete_success_returns204() throws Exception {
        when(currentUser.getCurrentUserId()).thenReturn(1L);

        mockMvc.perform(delete("/api/users/1/address/me/9"))
                .andExpect(status().isNoContent());

        verify(addressService).delete(1L, 9L);
    }

    @Test
    void delete_notFound_returns500() throws Exception {
        // ⚠ Comportamento attuale documentato: EntityNotFoundException → handler generico → 500
        when(currentUser.getCurrentUserId()).thenReturn(1L);
        doThrow(new EntityNotFoundException("Address not found or owned by user"))
                .when(addressService).delete(1L, 99L);

        mockMvc.perform(delete("/api/users/1/address/me/99"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Address not found or owned by user"));
    }
}
