package com.eshop.controller;

import com.eshop.config.CurrentUser;
import com.eshop.dto.AddToCartRequest;
import com.eshop.entity.Cart;
import com.eshop.service.CartService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;
    private final CurrentUser currentUser;

    @GetMapping("/me")
    public ResponseEntity<Cart> getCart(
            @RequestParam(required = false) Long testUserId) {
        Long userId = (testUserId != null) ? testUserId : currentUser.getCurrentUserId();
        return ResponseEntity.ok(cartService.getCartByUserId(userId));
    }

    @PostMapping("/items")
    public ResponseEntity<Cart> addToCart(
            @RequestParam(required = false) Long testUserId,
            @Valid @RequestBody AddToCartRequest request) {
        Long userId = (testUserId != null) ? testUserId : currentUser.getCurrentUserId();
        Cart cart = cartService.addToCart(userId, request);
        return ResponseEntity.ok(cart);
    }

    @DeleteMapping("/items/{articleId}")
    public ResponseEntity<Cart> removeFromCart(
            @PathVariable Long articleId,
            @RequestParam(required = false) Long testUserId) {
        Long userId = (testUserId != null) ? testUserId : currentUser.getCurrentUserId();
        Cart cart = cartService.removeFromCart(userId, articleId);
        return ResponseEntity.ok(cart);
    }

    @DeleteMapping("/clear")
    public ResponseEntity<Cart> clearCart(
            @RequestParam(required = false) Long testUserId) {
        Long userId = (testUserId != null) ? testUserId : currentUser.getCurrentUserId();
        Cart cart = cartService.clearCart(userId);
        return ResponseEntity.ok(cart);
    }

    @GetMapping("/total")
    public ResponseEntity<BigDecimal> calculateTotal(
            @RequestParam(required = false) Long testUserId) {
        Long userId = (testUserId != null) ? testUserId : currentUser.getCurrentUserId();
        Cart cart = cartService.getCartByUserId(userId);
        BigDecimal total = cartService.calculateTotal(cart);
        return ResponseEntity.ok(total);
    }
}
