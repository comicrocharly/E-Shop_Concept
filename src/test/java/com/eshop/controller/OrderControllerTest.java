package com.eshop.controller;

import com.eshop.EshopApplication;
import com.eshop.config.CurrentUser;
import com.eshop.config.JwtAuthenticationFilter;
import com.eshop.config.RateLimitFilter;
import com.eshop.controller.ControllerTestSupport.MethodSecurityConfig;
import com.eshop.controller.ControllerTestSupport.TestFixtures;
import com.eshop.entity.User;
import com.eshop.enums.OrderStatus;
import com.eshop.enums.PaymentMethod;
import com.eshop.enums.PaymentStatus;
import com.eshop.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S3 — unit test {@code @WebMvcTest} di {@link OrderController} (service mockato).
 */
@WebMvcTest(value = OrderController.class, properties = "app.security.allow-test-userid=true")
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = EshopApplication.class)
@Import(MethodSecurityConfig.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrderService orderService;

    @MockBean
    private CurrentUser currentUser;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockBean
    private RateLimitFilter rateLimitFilter;

    private static final User OWNER = TestFixtures.user(1L, "alice", false);
    private static final User OTHER = TestFixtures.user(7L, "mallory", false);

    // ==================== PREPARE CHECKOUT ====================

    @WithMockUser(username = "admin", roles = "ADMIN")
    @Test
    void prepareCheckoutSuccessReturns200() throws Exception {
        when(currentUser.getCurrentUserId()).thenReturn(1L);
        when(orderService.prepareCheckout(eq(1L), eq(PaymentMethod.CREDIT_CARD)))
                .thenReturn(TestFixtures.prepareCheckoutResponse(7L, new BigDecimal("100.00")));

        mockMvc.perform(post("/api/orders/checkout/prepare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"CREDIT_CARD\",\"details\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(7))
                .andExpect(jsonPath("$.total").value(100.00))
                .andExpect(jsonPath("$.paymentMethod").value("CREDIT_CARD"));
    }

    @WithMockUser(username = "bob", roles = "USER")
    @Test
    void prepareCheckoutAsNonAdminNoRoleCheckCurrentBehavior() throws Exception {
        // ⚠ Comportamento attuale documentato: /checkout/prepare NON ha @PreAuthorize —
        // un utente non-admin passa (il test senza @WithMockUser darebbe 200 body vuoto
        // solo se il service fosse stubbato con userId corretto; qui documentiamo il 200).
        when(currentUser.getCurrentUserId()).thenReturn(1L);
        when(orderService.prepareCheckout(eq(1L), eq(PaymentMethod.CREDIT_CARD)))
                .thenReturn(TestFixtures.prepareCheckoutResponse(7L, new BigDecimal("100.00")));

        mockMvc.perform(post("/api/orders/checkout/prepare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"CREDIT_CARD\",\"details\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(7));
    }

    @Test
    void prepareCheckoutEmptyCartReturns409() throws Exception {
        when(currentUser.getCurrentUserId()).thenReturn(1L);
        when(orderService.prepareCheckout(eq(1L), eq(PaymentMethod.CREDIT_CARD)))
                .thenThrow(new IllegalStateException("Il carrello è vuoto"));

        mockMvc.perform(post("/api/orders/checkout/prepare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"CREDIT_CARD\",\"details\":{}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Il carrello è vuoto"));
    }

    // ==================== PAY ====================
    // NOTA: l'endpoint è POST /api/orders/{id}/pay (non PUT).

    @Test
    void paySuccessReturns200() throws Exception {
        when(currentUser.getCurrentUserId()).thenReturn(1L);
        when(orderService.findById(5L))
                .thenReturn(TestFixtures.order(5L, OWNER, OrderStatus.PENDING, new BigDecimal("100.00")));
        when(orderService.completePayment(eq(5L), eq(PaymentMethod.CREDIT_CARD), anyMap(), any()))
                .thenReturn(TestFixtures.payOrderResponse(5L, PaymentStatus.CAPTURED, "MOCK-1",
                        new BigDecimal("100.00")));

        mockMvc.perform(post("/api/orders/5/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"CREDIT_CARD\",\"details\":{\"number\":\"4242\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(5))
                .andExpect(jsonPath("$.paymentStatus").value("CAPTURED"))
                .andExpect(jsonPath("$.transactionId").value("MOCK-1"));
    }

    @Test
    void payNotOwnerReturns403() throws Exception {
        when(currentUser.getCurrentUserId()).thenReturn(1L);
        when(orderService.findById(5L))
                .thenReturn(TestFixtures.order(5L, OTHER, OrderStatus.PENDING, new BigDecimal("100.00")));

        mockMvc.perform(post("/api/orders/5/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"CREDIT_CARD\",\"details\":{}}"))
                .andExpect(status().isForbidden());

        verify(orderService, never()).completePayment(anyLong(), any(), anyMap(), any());
    }

    @Test
    void payMissingMethodNoValidAnnotationCurrentBehavior() throws Exception {
        // ⚠ Comportamento attuale documentato: il @RequestBody NON ha @Valid, quindi
        // body {} NON viene rifiutato con 400: method=null arriva fino al service.
        when(currentUser.getCurrentUserId()).thenReturn(1L);
        when(orderService.findById(5L))
                .thenReturn(TestFixtures.order(5L, OWNER, OrderStatus.PENDING, new BigDecimal("100.00")));
        when(orderService.completePayment(eq(5L), eq(null), eq(null), eq(null)))
                .thenReturn(TestFixtures.payOrderResponse(5L, PaymentStatus.FAILED, null, null));

        mockMvc.perform(post("/api/orders/5/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("FAILED"));

        verify(orderService).completePayment(eq(5L), eq(null), eq(null), eq(null));
    }

    // ==================== LEGACY CHECKOUT ====================

    @Test
    void legacyCheckoutSuccessReturns200() throws Exception {
        when(currentUser.getCurrentUserId()).thenReturn(1L);
        when(orderService.checkout(1L))
                .thenReturn(TestFixtures.order(6L, OWNER, OrderStatus.PENDING, new BigDecimal("50.00")));

        mockMvc.perform(post("/api/orders/checkout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(6))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    // ==================== ADMIN GET ALL ====================

    @WithMockUser(username = "admin", roles = "ADMIN")
    @Test
    void getAllOrdersAsAdminReturns200() throws Exception {
        when(orderService.getAllOrders())
                .thenReturn(List.of(TestFixtures.order(1L, OWNER, OrderStatus.PENDING, new BigDecimal("10.00"))));

        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(1));
    }

    @WithMockUser(username = "bob", roles = "USER")
    @Test
    void getAllOrdersAsNonAdminReturns403() throws Exception {
        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isForbidden());

        verify(orderService, never()).getAllOrders();
    }

    @Test
    void findByIdOwnOrderReturns200() throws Exception {
        when(currentUser.getCurrentUserId()).thenReturn(1L);
        when(orderService.findById(5L))
                .thenReturn(TestFixtures.order(5L, OWNER, OrderStatus.SHIPPED, new BigDecimal("10.00")));

        mockMvc.perform(get("/api/orders/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.status").value("SHIPPED"));
    }

    @Test
    void findByIdForeignOrderReturns403() throws Exception {
        when(orderService.findById(5L))
                .thenReturn(TestFixtures.order(5L, OTHER, OrderStatus.SHIPPED, new BigDecimal("10.00")));

        mockMvc.perform(get("/api/orders/5").param("testUserId", "9"))
                .andExpect(status().isForbidden());
    }

    // ==================== MY ORDERS ====================

    @Test
    void getMyOrdersSuccessReturns200() throws Exception {
        when(currentUser.getCurrentUserId()).thenReturn(1L);
        when(orderService.getOrdersByUserIdPaginated(eq(1L), any(Pageable.class)))
                .thenReturn(TestFixtures.page(
                        TestFixtures.order(5L, OWNER, OrderStatus.PENDING, new BigDecimal("10.00"))));

        mockMvc.perform(get("/api/orders/my"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(5));
    }

    @Test
    void getMyOrdersWithStatusUsesStatusFilter() throws Exception {
        when(currentUser.getCurrentUserId()).thenReturn(1L);
        when(orderService.getOrdersByUserIdAndStatusPaginated(eq(1L), eq(OrderStatus.PENDING), any(Pageable.class)))
                .thenReturn(TestFixtures.page(
                        TestFixtures.order(5L, OWNER, OrderStatus.PENDING, new BigDecimal("10.00"))));

        mockMvc.perform(get("/api/orders/my").param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("PENDING"));

        verify(orderService).getOrdersByUserIdAndStatusPaginated(eq(1L), eq(OrderStatus.PENDING), any(Pageable.class));
    }

    @WithMockUser(username = "admin", roles = "ADMIN")
    @Test
    void adminGetAllWithStatusAndSearchReturnsAdminDtos() throws Exception {
        when(orderService.getOrdersByStatusAndSearch(eq(OrderStatus.PENDING), eq("ps4"), any(Pageable.class)))
                .thenReturn(TestFixtures.page(TestFixtures.order(5L, OWNER, OrderStatus.PENDING,
                        new BigDecimal("10.00"),
                        TestFixtures.orderItem(1L, 5L, 2, new BigDecimal("10.00")))));

        mockMvc.perform(get("/api/orders/admin").param("status", "PENDING").param("search", "ps4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(5))
                .andExpect(jsonPath("$.content[0].username").value("alice"))
                .andExpect(jsonPath("$.content[0].items[0].articleName").value("art-5"))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"));
    }

    @WithMockUser(username = "bob", roles = "USER")
    @Test
    void adminGetAllAsNonAdminReturns403() throws Exception {
        mockMvc.perform(get("/api/orders/admin"))
                .andExpect(status().isForbidden());

        verify(orderService, never()).getOrdersByStatusAndSearch(any(), any(), any(Pageable.class));
    }

    // ==================== UPDATE STATUS / CANCEL / COMPLETE ====================

    @WithMockUser(username = "admin", roles = "ADMIN")
    @Test
    void updateStatusAsAdminReturns200() throws Exception {
        when(orderService.updateOrderStatus(5L, OrderStatus.SHIPPED))
                .thenReturn(TestFixtures.order(5L, OWNER, OrderStatus.SHIPPED, new BigDecimal("10.00")));

        mockMvc.perform(put("/api/orders/5/status").param("status", "SHIPPED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"));
    }

    @WithMockUser(username = "bob", roles = "USER")
    @Test
    void updateStatusAsNonAdminReturns403() throws Exception {
        mockMvc.perform(put("/api/orders/5/status").param("status", "SHIPPED"))
                .andExpect(status().isForbidden());

        verify(orderService, never()).updateOrderStatus(anyLong(), any());
    }

    @Test
    void cancelSuccessReturns200() throws Exception {
        when(currentUser.getCurrentUserId()).thenReturn(1L);
        when(orderService.findById(5L))
                .thenReturn(TestFixtures.order(5L, OWNER, OrderStatus.PENDING, new BigDecimal("10.00")));

        mockMvc.perform(post("/api/orders/5/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5));

        verify(orderService).cancelOrder(5L);
    }

    @Test
    void cancelForeignOrderReturns403() throws Exception {
        when(orderService.findById(5L))
                .thenReturn(TestFixtures.order(5L, OTHER, OrderStatus.PENDING, new BigDecimal("10.00")));

        mockMvc.perform(post("/api/orders/5/cancel").param("testUserId", "9"))
                .andExpect(status().isForbidden());
    }

    @Test
    void completeSuccessReturns200() throws Exception {
        when(currentUser.getCurrentUserId()).thenReturn(1L);
        when(orderService.markAsCompleted(5L, 1L))
                .thenReturn(TestFixtures.order(5L, OWNER, OrderStatus.COMPLETED, new BigDecimal("10.00")));

        mockMvc.perform(put("/api/orders/5/complete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5));
    }

    @Test
    void completeNotAuthorizedReturns400() throws Exception {
        when(currentUser.getCurrentUserId()).thenReturn(1L);
        when(orderService.markAsCompleted(5L, 1L))
                .thenThrow(new IllegalArgumentException("Non sei autorizzato a completare questo ordine"));

        mockMvc.perform(put("/api/orders/5/complete"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Non sei autorizzato a completare questo ordine"));
    }
}
