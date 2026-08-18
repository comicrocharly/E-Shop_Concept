package com.eshop.service;

import com.eshop.dto.AddToCartRequest;
import com.eshop.entity.Articles;
import com.eshop.entity.Cart;
import com.eshop.entity.User;
import com.eshop.repository.CartRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CartService {

    private final CartRepository cartRepository;
    private final ArticlesService articlesService;
    private final UserService userService;

    @Transactional(readOnly = true)
    public Cart getCartByUserId(Long userId) {
        User user = userService.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Utente non trovato: " + userId));
        return cartRepository.findByUser(user)
                .orElseThrow(() -> new IllegalStateException("Carrello non trovato per utente: " + userId));
    }

    @Transactional
    public Cart addToCart(Long userId, AddToCartRequest request) {
        User user = userService.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Utente non trovato: " + userId));
        Cart cart = cartRepository.findByUser(user)
                .orElseGet(() -> {
                    Cart newCart = Cart.builder().user(user).build();
                    return cartRepository.save(newCart);
                });

        Articles articles = articlesService.findById(request.articleId());

        if (articles.getStock() < request.quantity()) {
            throw new IllegalStateException(
                    "Stock insufficiente per '" + articles.getName() +
                            "'. Disponibile: " + articles.getStock() +
                            ", Richiesto: " + request.quantity());
        }

        cart.addItem(articles, request.quantity());
        cart = cartRepository.save(cart);

        return cart;
    }

    @Transactional
    public Cart removeFromCart(Long userId, Long articleId) {
        User user = userService.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Utente non trovato: " + userId));
        Cart cart = cartRepository.findByUser(user)
                .orElseThrow(() -> new IllegalStateException("Carrello non trovato per utente: " + userId));

        cart.removeItem(articleId);
        cart = cartRepository.save(cart);

        return cart;
    }

    @Transactional
    public Cart clearCart(Long userId) {
        User user = userService.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Utente non trovato: " + userId));
        Cart cart = cartRepository.findByUser(user)
                .orElseThrow(() -> new IllegalStateException("Carrello non trovato per utente: " + userId));

        cart.clear();
        cart = cartRepository.save(cart);

        return cart;
    }

    @Transactional(readOnly = true)
    public BigDecimal calculateTotal(Cart cart) {
        return cart.getItems().stream()
                .map(item -> item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Transactional(readOnly = true)
    public List<Cart> getAllCarts() {
        return cartRepository.findAll();
    }
}
