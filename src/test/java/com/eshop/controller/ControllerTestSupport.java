package com.eshop.controller;

import com.eshop.dto.AddressResponse;
import com.eshop.dto.PayOrderResponse;
import com.eshop.dto.PhoneNumberResponse;
import com.eshop.dto.PrepareCheckoutResponse;
import com.eshop.dto.UserResponse;
import com.eshop.entity.Articles;
import com.eshop.entity.Cart;
import com.eshop.entity.CartItem;
import com.eshop.entity.Order;
import com.eshop.entity.OrderItem;
import com.eshop.entity.User;
import com.eshop.enums.OrderStatus;
import com.eshop.enums.PaymentMethod;
import com.eshop.enums.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Supporto condiviso per la suite S3 (@WebMvcTest).
 *
 * <p>Contiene:</p>
 * <ul>
 *   <li>{@link MethodSecurityConfig}: abilita {@code @PreAuthorize}/{@code @PostAuthorize} nella slice
 *       (le slice {@code @WebMvcTest} non caricano le {@code @Configuration} dell'app, quindi i method
 *       security annotations non sarebbero attivi senza questo import).</li>
 *   <li>{@link TestFixtures}: factory statiche per entity e DTO riusate in tutti i controller test.</li>
 * </ul>
 */
public final class ControllerTestSupport {

    private ControllerTestSupport() {
    }

    /**
     * Da {@code @Import} in ogni controller test: senza di essa gli {@code @PreAuthorize}
     * sui controller restano inerti (nessun denial, nessun 403).
     */
    @Configuration
    @EnableMethodSecurity
    public static class MethodSecurityConfig {
    }

    /** Factory statiche per entity/DTO — nessun estado condiviso tra test. */
    public static final class TestFixtures {

        private TestFixtures() {
        }

        /**
         * Usa {@code new User()} + setter (NON il builder): il builder lascia
         * phoneNumbers/addresses/orders a null (manca @Builder.Default, vedi REBUILD_PLAN §2.5).
         */
        public static User user(long id, String username, boolean admin) {
            User user = new User();
            user.setId(id);
            user.setUsername(username);
            user.setPassword("hashed-password");
            user.setEmail(username + "@example.com");
            user.setRole(admin ? "ADMIN" : "USER");
            user.setCreatedAt(LocalDateTime.of(2026, 1, 1, 12, 0));
            return user;
        }

        public static Articles articles(long id, String name, BigDecimal price, int stock) {
            return Articles.builder()
                    .id(id)
                    .name(name)
                    .description("descrizione di " + name)
                    .category("TestCategory")
                    .price(price)
                    .stock(stock)
                    .build();
        }

        public static CartItem cartItem(long id, long articleId, int quantity, BigDecimal unitPrice) {
            return CartItem.builder()
                    .id(id)
                    .articles(articles(articleId, "art-" + articleId, unitPrice, quantity + 10))
                    .quantity(quantity)
                    .unitPrice(unitPrice)
                    .build();
        }

        public static Cart cart(long id, User user, CartItem... items) {
            Cart cart = Cart.builder().id(id).user(user).build();
            cart.setItems(new ArrayList<>(Arrays.asList(items)));
            return cart;
        }

        public static OrderItem orderItem(long id, long articleId, int quantity, BigDecimal unitPrice) {
            return OrderItem.builder()
                    .id(id)
                    .articles(articles(articleId, "art-" + articleId, unitPrice, quantity + 10))
                    .quantity(quantity)
                    .unitPrice(unitPrice)
                    .build();
        }

        public static Order order(long id, User user, OrderStatus status, BigDecimal total, OrderItem... items) {
            return Order.builder()
                    .id(id)
                    .user(user)
                    .orderDate(LocalDateTime.of(2026, 1, 2, 9, 0))
                    .status(status)
                    .total(total)
                    .items(new ArrayList<>(Arrays.asList(items)))
                    .build();
        }

        public static <T> Page<T> page(T... items) {
            return new PageImpl<>(Arrays.asList(items));
        }

        public static PrepareCheckoutResponse prepareCheckoutResponse(long orderId, BigDecimal total) {
            return new PrepareCheckoutResponse(orderId, total, List.of(), PaymentMethod.CREDIT_CARD);
        }

        public static PayOrderResponse payOrderResponse(long orderId, PaymentStatus status,
                                                       String transactionId, BigDecimal amount) {
            return new PayOrderResponse(orderId, status, transactionId, amount, PaymentMethod.CREDIT_CARD);
        }

        public static AddressResponse addressResponse(long id) {
            return AddressResponse.builder()
                    .id(id)
                    .street("Via Roma")
                    .streetNumber(1)
                    .postalCode("00100")
                    .city("Roma")
                    .country("IT")
                    .build();
        }

        public static PhoneNumberResponse phoneNumberResponse(long id) {
            return PhoneNumberResponse.builder()
                    .id(id)
                    .countryPrefix("+39")
                    .number("3331234567")
                    .phoneType("MOBILE")
                    .build();
        }

        public static UserResponse userResponse(User user) {
            return UserResponse.builder()
                    .id(user.getId())
                    .username(user.getUsername())
                    .email(user.getEmail())
                    .role(user.isAdmin() ? "ADMIN" : "USER")
                    .createdAt(user.getCreatedAt() != null ? user.getCreatedAt().toString() : null)
                    .cart(null)
                    .phoneNumbers(List.of())
                    .addresses(List.of())
                    .build();
        }
    }
}
