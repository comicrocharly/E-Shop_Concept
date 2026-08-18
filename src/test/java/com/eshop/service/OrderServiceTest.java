package com.eshop.service;

import com.eshop.dto.GatewayResult;
import com.eshop.dto.PayOrderResponse;
import com.eshop.dto.PrepareCheckoutResponse;
import com.eshop.entity.Articles;
import com.eshop.entity.Cart;
import com.eshop.entity.CartItem;
import com.eshop.entity.Order;
import com.eshop.entity.OrderItem;
import com.eshop.entity.OrderPayment;
import com.eshop.entity.User;
import com.eshop.enums.OrderStatus;
import com.eshop.enums.PaymentMethod;
import com.eshop.enums.PaymentStatus;
import com.eshop.repository.ArticlesRepository;
import com.eshop.repository.CartRepository;
import com.eshop.repository.OrderPaymentRepository;
import com.eshop.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S2 — Unit tests for {@link OrderService} (mocked repositories and payment gateway).
 *
 * <p>Covers the new 2-step checkout (prepare → pay), payment failure/cancel,
 * stock reservation, order status transitions and the legacy one-step checkout.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderPaymentRepository orderPaymentRepository;
    @Mock
    private CartRepository cartRepository;
    @Mock
    private ArticlesRepository articlesRepository;
    @Mock
    private UserService userService;
    @Mock
    private PaymentGatewayService paymentGateway;

    @InjectMocks
    private OrderService orderService;

    private User user;
    private Articles articleA; // stock 10, price 10.00
    private Articles articleB; // stock 5,  price 5.00
    private Cart cart;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .id(1L).username("carlo").email("carlo@test.local")
                .password("h").role("USER").build();
        articleA = Articles.builder()
                .id(10L).name("Caffettiera").price(new BigDecimal("10.00")).stock(10).build();
        articleB = Articles.builder()
                .id(11L).name("Tazza").price(new BigDecimal("5.00")).stock(5).build();
        cart = Cart.builder().id(100L).user(user).build();
    }

    private CartItem cartItem(Articles articles, int qty, String price) {
        CartItem item = CartItem.builder()
                .cart(cart)
                .articles(articles)
                .quantity(qty)
                .unitPrice(new BigDecimal(price))
                .build();
        cart.getItems().add(item);
        return item;
    }

    private Order pendingOrderWithItems(OrderStatus status, Integer reservedStock,
                                        OrderItem... items) {
        Order order = Order.builder()
                .id(500L)
                .user(user)
                .status(status)
                .total(BigDecimal.ZERO)
                .paymentMethod(PaymentMethod.CREDIT_CARD)
                .reservedStock(reservedStock == null ? 0 : reservedStock)
                .items(new ArrayList<>(List.of(items)))
                .build();
        order.setTotal(order.getItems().stream()
                .map(OrderItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        return order;
    }

    private OrderItem orderItem(Order order, Articles articles, int qty, String price) {
        return OrderItem.builder()
                .order(order)
                .articles(articles)
                .quantity(qty)
                .unitPrice(new BigDecimal(price))
                .build();
    }

    // ==================== PREPARE CHECKOUT (STEP 1) ====================

    @Nested
    @DisplayName("prepareCheckout (step 1)")
    class PrepareCheckout {

        @Test
        @DisplayName("reserves stock, creates PENDING order, clears cart, stock untouched")
        void prepareSuccess() {
            cartItem(articleA, 2, "10.00");
            cartItem(articleB, 1, "5.00");
            when(cartRepository.findByUserIdWithItems(1L)).thenReturn(Optional.of(cart));
            when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
                Order o = inv.getArgument(0);
                o.setId(500L);
                return o;
            });
            when(cartRepository.save(cart)).thenReturn(cart);

            PrepareCheckoutResponse response =
                    orderService.prepareCheckout(1L, PaymentMethod.CREDIT_CARD);

            // response
            assertThat(response.orderId()).isEqualTo(500L);
            assertThat(response.total()).isEqualByComparingTo("25.00");
            assertThat(response.paymentMethod()).isEqualTo(PaymentMethod.CREDIT_CARD);
            assertThat(response.items()).hasSize(2);
            assertThat(response.items().get(0).articleId()).isEqualTo(10L);
            assertThat(response.items().get(0).quantity()).isEqualTo(2);
            assertThat(response.items().get(0).unitPrice()).isEqualByComparingTo("10.00");
            assertThat(response.items().get(0).subtotal()).isEqualByComparingTo("20.00");

            // cart cleared + saved
            assertThat(cart.getItems()).isEmpty();
            verify(cartRepository).save(cart);

            // stock only reserved, not decremented
            assertThat(articleA.getStock()).isEqualTo(10);
            assertThat(articleB.getStock()).isEqualTo(5);
        }

        @Test
        @DisplayName("missing cart -> IllegalStateException")
        void prepareCartNotFound() {
            when(cartRepository.findByUserIdWithItems(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> orderService.prepareCheckout(404L, PaymentMethod.COD))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Carrello non trovato");
        }

        @Test
        @DisplayName("empty cart -> IllegalStateException, no order saved")
        void prepareEmptyCart() {
            when(cartRepository.findByUserIdWithItems(1L)).thenReturn(Optional.of(cart));

            assertThatThrownBy(() -> orderService.prepareCheckout(1L, PaymentMethod.COD))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("carrello è vuoto");
            verify(orderRepository, never()).save(any(Order.class));
        }

        @Test
        @DisplayName("insufficient stock -> IllegalStateException, no order saved")
        void prepareInsufficientStock() {
            articleB.setStock(0);
            cartItem(articleA, 1, "10.00");
            cartItem(articleB, 1, "5.00");
            when(cartRepository.findByUserIdWithItems(1L)).thenReturn(Optional.of(cart));

            assertThatThrownBy(() -> orderService.prepareCheckout(1L, PaymentMethod.COD))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Stock insufficiente");
            verify(orderRepository, never()).save(any(Order.class));
        }
    }

    // ==================== COMPLETE PAYMENT (STEP 2) ====================

    @Nested
    @DisplayName("completePayment (step 2)")
    class CompletePayment {

        @Test
        @DisplayName("gateway OK: stock decremented, status PROCESSING, payment stored")
        void completePaymentSuccess() {
            Order order = pendingOrderWithItems(OrderStatus.PENDING, 3,
                    orderItem(null, articleA, 2, "10.00"),
                    orderItem(null, articleB, 1, "5.00"));
            when(orderRepository.findById(500L)).thenReturn(Optional.of(order));
            when(paymentGateway.processPayment(any(), any(), any()))
                    .thenReturn(new GatewayResult(true, "CAPTURED", "MOCK-ABC123"));
            when(orderPaymentRepository.save(any(OrderPayment.class)))
                    .thenAnswer(inv -> {
                        OrderPayment p = inv.getArgument(0);
                        p.setId(900L);
                        return p;
                    });
            when(orderRepository.save(order)).thenReturn(order);

            PayOrderResponse response = orderService.completePayment(
                    500L, PaymentMethod.CREDIT_CARD, Map.of("card", "****1234"));

            // stock decremented
            assertThat(articleA.getStock()).isEqualTo(8);
            assertThat(articleB.getStock()).isEqualTo(4);
            verify(articlesRepository).save(articleA);
            verify(articlesRepository).save(articleB);

            // order state
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PROCESSING);
            assertThat(order.getReservedStock()).isZero();
            assertThat(order.getPayment()).isNotNull();
            assertThat(order.getPayment().getStatus()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(order.getPayment().getTransactionId()).isEqualTo("MOCK-ABC123");
            assertThat(order.getPayment().getAmount()).isEqualByComparingTo("25.00");

            // response
            assertThat(response.orderId()).isEqualTo(500L);
            assertThat(response.paymentStatus()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(response.transactionId()).isEqualTo("MOCK-ABC123");
            assertThat(response.amount()).isEqualByComparingTo("25.00");
            assertThat(response.method()).isEqualTo(PaymentMethod.CREDIT_CARD);
        }

        @Test
        @DisplayName("unknown order -> IllegalArgumentException")
        void completePaymentUnknownOrder() {
            when(orderRepository.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> orderService.completePayment(404L, PaymentMethod.COD, Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Ordine non trovato");
        }

        @Test
        @DisplayName("order already processed -> IllegalStateException, gateway not called")
        void completePaymentAlreadyProcessed() {
            Order order = pendingOrderWithItems(OrderStatus.PROCESSING, 0,
                    orderItem(null, articleA, 1, "10.00"));
            when(orderRepository.findById(500L)).thenReturn(Optional.of(order));

            assertThatThrownBy(() -> orderService.completePayment(500L, PaymentMethod.COD, Map.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("già elaborato");
            verify(paymentGateway, never()).processPayment(any(), any(), any());
        }

        @Test
        @DisplayName("gateway failure -> order cancelled, exception thrown (stock inflated - known bug)")
        void completePaymentGatewayFailure() {
            Order order = pendingOrderWithItems(OrderStatus.PENDING, 2,
                    orderItem(null, articleA, 2, "10.00"));
            when(orderRepository.findById(500L)).thenReturn(Optional.of(order)); // 1x pay, 1x cancel
            when(paymentGateway.processPayment(any(), any(), any()))
                    .thenReturn(new GatewayResult(false, "Simulazione errore gateway", null));
            when(orderRepository.save(order)).thenReturn(order);

            assertThatThrownBy(() -> orderService.completePayment(
                    500L, PaymentMethod.CREDIT_CARD, Map.of("card", "x")))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Pagamento fallito");

            // cancelOrder side effects
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order.getReservedStock()).isZero();
            // ⚠ KNOWN BUG (documented in REBUILD_PLAN.md, section "Bug discovered"): the gateway
            // failed BEFORE any stock decrement, yet cancelOrder still adds the quantity back.
            // prepareCheckout only reserves (never decrements), so stock inflates 10 -> 12.
            assertThat(articleA.getStock()).isEqualTo(12);
            assertThat(order.getPayment()).isNull();
        }

        @Test
        @DisplayName("stock vanished between prepare and pay -> rollback + IllegalStateException")
        void completePaymentStockRace() {
            articleA.setStock(1); // 2 reserved but only 1 left
            Order order = pendingOrderWithItems(OrderStatus.PENDING, 2,
                    orderItem(null, articleA, 2, "10.00"));
            when(orderRepository.findById(500L)).thenReturn(Optional.of(order));
            when(paymentGateway.processPayment(any(), any(), any()))
                    .thenReturn(new GatewayResult(true, "CAPTURED", "MOCK-RACE"));

            assertThatThrownBy(() -> orderService.completePayment(
                    500L, PaymentMethod.CREDIT_CARD, Map.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Stock insufficiente");

            // rollback path saved the article back
            verify(articlesRepository).save(articleA);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING); // not advanced
        }
    }

    // ==================== CANCEL ORDER ====================

    @Nested
    @DisplayName("cancelOrder")
    class CancelOrder {

        @Test
        @DisplayName("PENDING order: status CANCELLED, reservation released (stock inflated - known bug)")
        void cancelPendingOrder() {
            Order order = pendingOrderWithItems(OrderStatus.PENDING, 2,
                    orderItem(null, articleA, 2, "10.00"));
            when(orderRepository.findById(500L)).thenReturn(Optional.of(order));
            when(orderRepository.save(order)).thenReturn(order);

            orderService.cancelOrder(500L);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order.getReservedStock()).isZero();
            // ⚠ KNOWN BUG (documented in REBUILD_PLAN.md, section "Bug discovered"): stock was
            // reserved but never decremented by prepareCheckout, yet cancelOrder adds the
            // quantity back -> stock inflates 10 -> 12. Same for admin-cancelled PENDING orders.
            assertThat(articleA.getStock()).isEqualTo(12);
            verify(orderRepository).save(order);
        }

        @Test
        @DisplayName("non-PENDING order: untouched")
        void cancelNonPendingOrder() {
            Order order = pendingOrderWithItems(OrderStatus.PROCESSING, 0,
                    orderItem(null, articleA, 1, "10.00"));
            when(orderRepository.findById(500L)).thenReturn(Optional.of(order));

            orderService.cancelOrder(500L);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.PROCESSING);
            verify(orderRepository, never()).save(order);
        }

        @Test
        @DisplayName("missing order: no-op")
        void cancelMissingOrder() {
            when(orderRepository.findById(404L)).thenReturn(Optional.empty());

            orderService.cancelOrder(404L); // must not throw

            verify(orderRepository, never()).save(any(Order.class));
        }
    }

    // ==================== STATUS TRANSITIONS ====================

    @Nested
    @DisplayName("updateOrderStatus / markAsCompleted")
    class Status {

        @Test
        @DisplayName("valid transition PROCESSING -> SHIPPED")
        void updateOrderStatusValid() {
            Order order = pendingOrderWithItems(OrderStatus.PROCESSING, 0,
                    orderItem(null, articleA, 1, "10.00"));
            when(orderRepository.findById(500L)).thenReturn(Optional.of(order));
            when(orderRepository.save(order)).thenReturn(order);

            Order updated = orderService.updateOrderStatus(500L, OrderStatus.SHIPPED);

            assertThat(updated.getStatus()).isEqualTo(OrderStatus.SHIPPED);
        }

        @Test
        @DisplayName("invalid transition PENDING -> COMPLETED -> IllegalStateException")
        void updateOrderStatusInvalid() {
            Order order = pendingOrderWithItems(OrderStatus.PENDING, 0,
                    orderItem(null, articleA, 1, "10.00"));
            when(orderRepository.findById(500L)).thenReturn(Optional.of(order));

            assertThatThrownBy(() -> orderService.updateOrderStatus(500L, OrderStatus.COMPLETED))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Transizione di stato non valida");
        }

        @Test
        @DisplayName("unknown order -> IllegalArgumentException")
        void updateOrderStatusUnknown() {
            when(orderRepository.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> orderService.updateOrderStatus(404L, OrderStatus.SHIPPED))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("markAsCompleted: DELIVERED order by owner -> COMPLETED")
        void markAsCompletedSuccess() {
            Order order = pendingOrderWithItems(OrderStatus.DELIVERED, 0,
                    orderItem(null, articleA, 1, "10.00"));
            when(orderRepository.findById(500L)).thenReturn(Optional.of(order));
            when(orderRepository.save(order)).thenReturn(order);

            Order completed = orderService.markAsCompleted(500L, 1L);

            assertThat(completed.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        }

        @Test
        @DisplayName("markAsCompleted: not the owner -> IllegalArgumentException")
        void markAsCompletedNotOwner() {
            Order order = pendingOrderWithItems(OrderStatus.DELIVERED, 0,
                    orderItem(null, articleA, 1, "10.00"));
            when(orderRepository.findById(500L)).thenReturn(Optional.of(order));

            assertThatThrownBy(() -> orderService.markAsCompleted(500L, 999L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Non sei autorizzato");
        }

        @Test
        @DisplayName("markAsCompleted: not DELIVERED -> IllegalStateException")
        void markAsCompletedWrongStatus() {
            Order order = pendingOrderWithItems(OrderStatus.SHIPPED, 0,
                    orderItem(null, articleA, 1, "10.00"));
            when(orderRepository.findById(500L)).thenReturn(Optional.of(order));

            assertThatThrownBy(() -> orderService.markAsCompleted(500L, 1L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DELIVERED");
        }
    }

    // ==================== LEGACY ONE-STEP CHECKOUT ====================

    @Nested
    @DisplayName("legacy checkout()")
    class LegacyCheckout {

        @Test
        @DisplayName("decrements stock, creates PENDING order, clears cart")
        void checkoutSuccess() {
            cartItem(articleA, 2, "10.00");
            cartItem(articleB, 1, "5.00");
            when(cartRepository.findByUserIdWithItems(1L)).thenReturn(Optional.of(cart));
            when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
                Order o = inv.getArgument(0);
                o.setId(600L);
                return o;
            });
            when(cartRepository.save(cart)).thenReturn(cart);

            Order order = orderService.checkout(1L);

            assertThat(order.getId()).isEqualTo(600L);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(order.getTotal()).isEqualByComparingTo("25.00");
            assertThat(order.getItems()).hasSize(2);
            assertThat(articleA.getStock()).isEqualTo(8);  // decremented
            assertThat(articleB.getStock()).isEqualTo(4);
            assertThat(cart.getItems()).isEmpty();
            verify(articlesRepository).save(articleA);
            verify(articlesRepository).save(articleB);
        }

        @Test
        @DisplayName("empty cart -> IllegalStateException")
        void checkoutEmptyCart() {
            when(cartRepository.findByUserIdWithItems(1L)).thenReturn(Optional.of(cart));

            assertThatThrownBy(() -> orderService.checkout(1L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("carrello è vuoto");
        }

        @Test
        @DisplayName("insufficient stock -> IllegalStateException, no order")
        void checkoutInsufficientStock() {
            articleA.setStock(1);
            cartItem(articleA, 2, "10.00");
            when(cartRepository.findByUserIdWithItems(1L)).thenReturn(Optional.of(cart));

            assertThatThrownBy(() -> orderService.checkout(1L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Stock insufficiente");
            verify(orderRepository, never()).save(any(Order.class));
            assertThat(articleA.getStock()).isEqualTo(1); // untouched
        }
    }
}
