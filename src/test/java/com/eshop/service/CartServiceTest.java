package com.eshop.service;

import com.eshop.dto.AddToCartRequest;
import com.eshop.entity.Articles;
import com.eshop.entity.Cart;
import com.eshop.entity.CartItem;
import com.eshop.entity.User;
import com.eshop.repository.CartRepository;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S2 — Unit tests for {@link CartService} (mocked repositories, real Cart/CartItem entities).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CartServiceTest {

    @Mock
    private CartRepository cartRepository;

    @Mock
    private ArticlesService articlesService;

    @Mock
    private UserService userService;

    @InjectMocks
    private CartService cartService;

    private User user;
    private Cart cart;

    private Articles article(int stock, String price) {
        return Articles.builder()
                .id(10L)
                .name("Caffettiera")
                .price(new BigDecimal(price))
                .stock(stock)
                .build();
    }

    @BeforeEach
    void setUp() {
        user = User.builder()
                .id(1L).username("carlo").email("carlo@test.local")
                .password("h").role("USER").build();
        cart = Cart.builder().id(100L).user(user).build();
    }

    // ==================== GET CART ====================

    @Nested
    @DisplayName("getCartByUserId")
    class GetCart {

        @Test
        @DisplayName("returns the cart of the user")
        void getCartSuccess() {
            when(userService.findById(1L)).thenReturn(Optional.of(user));
            when(cartRepository.findByUser(user)).thenReturn(Optional.of(cart));

            assertThat(cartService.getCartByUserId(1L)).isSameAs(cart);
        }

        @Test
        @DisplayName("unknown user -> IllegalArgumentException")
        void getCartUnknownUser() {
            when(userService.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> cartService.getCartByUserId(404L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Utente non trovato");
        }

        @Test
        @DisplayName("user without cart -> IllegalStateException")
        void getCartMissingCart() {
            when(userService.findById(1L)).thenReturn(Optional.of(user));
            when(cartRepository.findByUser(user)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> cartService.getCartByUserId(1L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Carrello non trovato");
        }
    }

    // ==================== ADD TO CART ====================

    @Nested
    @DisplayName("addToCart")
    class AddToCart {

        @Test
        @DisplayName("adds new item to existing cart")
        void addToCartNewItem() {
            when(userService.findById(1L)).thenReturn(Optional.of(user));
            when(cartRepository.findByUser(user)).thenReturn(Optional.of(cart));
            when(articlesService.findById(10L)).thenReturn(article(10, "19.90"));
            when(cartRepository.save(cart)).thenReturn(cart);

            Cart result = cartService.addToCart(1L, new AddToCartRequest(10L, 2));

            assertThat(result).isSameAs(cart);
            assertThat(cart.getItems()).hasSize(1);
            assertThat(cart.getItems().get(0).getArticles().getId()).isEqualTo(10L);
            assertThat(cart.getItems().get(0).getQuantity()).isEqualTo(2);
        }

        @Test
        @DisplayName("adds to existing line item (quantity merged)")
        void addToCartMergesQuantity() {
            CartItem existing = CartItem.builder()
                    .cart(cart)
                    .articles(article(10, "19.90"))
                    .quantity(3)
                    .unitPrice(new BigDecimal("19.90"))
                    .build();
            cart.getItems().add(existing);

            when(userService.findById(1L)).thenReturn(Optional.of(user));
            when(cartRepository.findByUser(user)).thenReturn(Optional.of(cart));
            when(articlesService.findById(10L)).thenReturn(article(10, "19.90"));
            when(cartRepository.save(cart)).thenReturn(cart);

            cartService.addToCart(1L, new AddToCartRequest(10L, 2));

            assertThat(cart.getItems()).hasSize(1);
            assertThat(cart.getItems().get(0).getQuantity()).isEqualTo(5);
        }

        @Test
        @DisplayName("creates a new cart when the user has none (saved twice - see comment)")
        void addToCartCreatesCart() {
            when(userService.findById(1L)).thenReturn(Optional.of(user));
            when(cartRepository.findByUser(user)).thenReturn(Optional.empty());
            when(articlesService.findById(10L)).thenReturn(article(10, "19.90"));
            when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

            Cart result = cartService.addToCart(1L, new AddToCartRequest(10L, 1));

            assertThat(result.getItems()).hasSize(1);
            // Documents current behavior: the same cart instance is saved twice -
            // once in orElseGet (creation) and once after addItem(). Redundant in a
            // single @Transactional context, but harmless (same persistence context).
            verify(cartRepository, times(2)).save(result);
        }

        @Test
        @DisplayName("insufficient stock -> IllegalStateException, cart unchanged")
        void addToCartInsufficientStock() {
            when(userService.findById(1L)).thenReturn(Optional.of(user));
            when(cartRepository.findByUser(user)).thenReturn(Optional.of(cart));
            when(articlesService.findById(10L)).thenReturn(article(1, "19.90"));

            assertThatThrownBy(() -> cartService.addToCart(1L, new AddToCartRequest(10L, 2)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Stock insufficiente");
            assertThat(cart.getItems()).isEmpty();
            verify(cartRepository, never()).save(cart);
        }

        @Test
        @DisplayName("unknown user -> IllegalArgumentException")
        void addToCartUnknownUser() {
            when(userService.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> cartService.addToCart(404L, new AddToCartRequest(10L, 1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Utente non trovato");
        }
    }

    // ==================== REMOVE / CLEAR ====================

    @Nested
    @DisplayName("removeFromCart / clearCart")
    class RemoveClear {

        @Test
        @DisplayName("removeFromCart removes the line for the article")
        void removeFromCartSuccess() {
            CartItem item = CartItem.builder()
                    .cart(cart).articles(article(10, "19.90"))
                    .quantity(1).unitPrice(new BigDecimal("19.90")).build();
            cart.getItems().add(item);

            when(userService.findById(1L)).thenReturn(Optional.of(user));
            when(cartRepository.findByUser(user)).thenReturn(Optional.of(cart));
            when(cartRepository.save(cart)).thenReturn(cart);

            cartService.removeFromCart(1L, 10L);

            assertThat(cart.getItems()).isEmpty();
            verify(cartRepository).save(cart);
        }

        @Test
        @DisplayName("removeFromCart with missing cart -> IllegalStateException")
        void removeFromCartMissingCart() {
            when(userService.findById(1L)).thenReturn(Optional.of(user));
            when(cartRepository.findByUser(user)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> cartService.removeFromCart(1L, 10L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Carrello non trovato");
        }

        @Test
        @DisplayName("clearCart empties all items")
        void clearCartSuccess() {
            cart.getItems().add(CartItem.builder()
                    .cart(cart).articles(article(10, "19.90"))
                    .quantity(1).unitPrice(new BigDecimal("19.90")).build());

            when(userService.findById(1L)).thenReturn(Optional.of(user));
            when(cartRepository.findByUser(user)).thenReturn(Optional.of(cart));
            when(cartRepository.save(cart)).thenReturn(cart);

            cartService.clearCart(1L);

            assertThat(cart.getItems()).isEmpty();
            verify(cartRepository).save(cart);
        }
    }

    // ==================== TOTAL / LIST ====================

    @Nested
    @DisplayName("calculateTotal / getAllCarts")
    class Total {

        @Test
        @DisplayName("calculateTotal sums quantity x unitPrice")
        void calculateTotal() {
            cart.getItems().add(CartItem.builder()
                    .cart(cart).articles(article(10, "10.00"))
                    .quantity(2).unitPrice(new BigDecimal("10.00")).build());
            cart.getItems().add(CartItem.builder()
                    .cart(cart).articles(Articles.builder()
                            .id(11L).name("Tazza").price(new BigDecimal("5.50")).stock(5).build())
                    .quantity(3).unitPrice(new BigDecimal("5.50")).build());

            BigDecimal total = cartService.calculateTotal(cart);

            assertThat(total).isEqualByComparingTo("36.50"); // 2*10.00 + 3*5.50
        }

        @Test
        @DisplayName("calculateTotal of empty cart is ZERO")
        void calculateTotalEmpty() {
            assertThat(cartService.calculateTotal(cart)).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("getAllCarts delegates to repository")
        void getAllCarts() {
            when(cartRepository.findAll()).thenReturn(List.of(cart));

            assertThat(cartService.getAllCarts()).containsExactly(cart);
        }
    }
}
