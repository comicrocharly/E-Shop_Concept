package com.eshop.controller;

import com.eshop.EshopApplication;
import com.eshop.config.CurrentUser;
import com.eshop.config.JwtAuthenticationFilter;
import com.eshop.config.RateLimitFilter;
import com.eshop.controller.ControllerTestSupport.MethodSecurityConfig;
import com.eshop.controller.ControllerTestSupport.TestFixtures;
import com.eshop.entity.User;
import com.eshop.service.CartService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S3 — unit test {@code @WebMvcTest} di {@link CartController} (service mockato).
 */
@WebMvcTest(value = CartController.class, properties = "app.security.allow-test-userid=true")
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = EshopApplication.class)
@Import(MethodSecurityConfig.class)
class CartControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CartService cartService;

    @MockBean
    private CurrentUser currentUser;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockBean
    private RateLimitFilter rateLimitFilter;

    private static final User USER_1 = TestFixtures.user(1L, "alice", false);

    // ==================== GET /me ====================

    @Test
    void getCart_success_returns200() throws Exception {
        when(currentUser.getCurrentUserId()).thenReturn(1L);
        when(cartService.getCartByUserId(1L))
                .thenReturn(TestFixtures.cart(1L, USER_1,
                        TestFixtures.cartItem(1L, 5L, 2, new BigDecimal("10.00"))));

        mockMvc.perform(get("/api/cart/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.user.username").value("alice"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].articles.id").value(5));
    }

    @Test
    void getCart_withTestUserIdParam_usesParam() throws Exception {
        when(cartService.getCartByUserId(7L)).thenReturn(TestFixtures.cart(7L, USER_1));

        mockMvc.perform(get("/api/cart/me").param("testUserId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7));

        verify(cartService).getCartByUserId(7L);
    }

    @Test
    void getCart_userNotFound_returns404() throws Exception {
        when(currentUser.getCurrentUserId()).thenReturn(1L);
        when(cartService.getCartByUserId(1L))
                .thenThrow(new IllegalArgumentException("Utente non trovato: 1"));

        mockMvc.perform(get("/api/cart/me"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Utente non trovato: 1"));
    }

    @Test
    void getCart_cartNotFound_returns409() throws Exception {
        when(currentUser.getCurrentUserId()).thenReturn(1L);
        when(cartService.getCartByUserId(1L))
                .thenThrow(new IllegalStateException("Carrello non trovato per utente: 1"));

        mockMvc.perform(get("/api/cart/me"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Carrello non trovato per utente: 1"));
    }

    // ==================== POST /items ====================

    @Test
    void addToCart_success_returns200() throws Exception {
        when(currentUser.getCurrentUserId()).thenReturn(1L);
        when(cartService.addToCart(eq(1L), any()))
                .thenReturn(TestFixtures.cart(1L, USER_1,
                        TestFixtures.cartItem(1L, 5L, 2, new BigDecimal("10.00"))));

        mockMvc.perform(post("/api/cart/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"articleId\":5,\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(2));
    }

    @Test
    void addToCart_zeroQuantity_returns400() throws Exception {
        mockMvc.perform(post("/api/cart/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"articleId\":5,\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.quantity").exists());

        verify(cartService, org.mockito.Mockito.never()).addToCart(anyLong(), any());
    }

    @Test
    void addToCart_missingArticleId_returns400() throws Exception {
        mockMvc.perform(post("/api/cart/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.articleId").exists());
    }

    @Test
    void addToCart_insufficientStock_returns409() throws Exception {
        when(currentUser.getCurrentUserId()).thenReturn(1L);
        when(cartService.addToCart(eq(1L), any()))
                .thenThrow(new IllegalStateException(
                        "Stock insufficiente per 'PS4'. Disponibile: 0, Richiesto: 2"));

        mockMvc.perform(post("/api/cart/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"articleId\":5,\"quantity\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Stock insufficiente per 'PS4'. Disponibile: 0, Richiesto: 2"));
    }

    // ==================== DELETE ====================

    @Test
    void removeFromCart_success_returns200() throws Exception {
        when(currentUser.getCurrentUserId()).thenReturn(1L);
        when(cartService.removeFromCart(1L, 5L)).thenReturn(TestFixtures.cart(1L, USER_1));

        mockMvc.perform(delete("/api/cart/items/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));

        verify(cartService).removeFromCart(1L, 5L);
    }

    @Test
    void clearCart_success_returns200() throws Exception {
        when(currentUser.getCurrentUserId()).thenReturn(1L);
        when(cartService.clearCart(1L)).thenReturn(TestFixtures.cart(1L, USER_1));

        mockMvc.perform(delete("/api/cart/clear"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    // ==================== GET /total ====================

    @Test
    void calculateTotal_success_returns200() throws Exception {
        when(currentUser.getCurrentUserId()).thenReturn(1L);
        when(cartService.getCartByUserId(1L))
                .thenReturn(TestFixtures.cart(1L, USER_1,
                        TestFixtures.cartItem(1L, 5L, 2, new BigDecimal("10.00"))));
        when(cartService.calculateTotal(any())).thenReturn(new BigDecimal("36.50"));

        mockMvc.perform(get("/api/cart/total"))
                .andExpect(status().isOk())
                // Jackson serializza il BigDecimal preservando lo scale ("36.50", non "36.5")
                .andExpect(content().string("36.50"));
    }
}
