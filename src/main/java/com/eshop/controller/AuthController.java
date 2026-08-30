package com.eshop.controller;

import com.eshop.config.JwtTokenProvider;
import com.eshop.dto.*;
import com.eshop.dto.CartResponse.CartItemResponse;
import com.eshop.dto.CartResponse.CartItemResponse.ArticleShort;
import com.eshop.entity.User;

import com.eshop.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final JwtTokenProvider jwtTokenProvider;

    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(@RequestBody Map<String, String> body) {
        String refreshToken = body.get("refreshToken");
        if (refreshToken == null || refreshToken.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String username = jwtTokenProvider.getUsernameFromToken(refreshToken);
        User user = userService.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        String newAccessToken = jwtTokenProvider.createAccessToken(
                user.getUsername(), user.isAdmin() ? "ADMIN" : "USER");
        String newRefreshToken = jwtTokenProvider.createRefreshToken(user.getUsername());

        return ResponseEntity.ok(LoginResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .user(toUserResponse(user))
                .build());
    }

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        User user = userService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(toUserResponse(user));
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        User user = userService.authenticate(request);
        
        String accessToken = jwtTokenProvider.createAccessToken(user.getUsername(), user.isAdmin() ? "ADMIN" : "USER");
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getUsername());

        LoginResponse response = LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .user(toUserResponse(user))
                .build();

        return ResponseEntity.ok(response);
    }

    private UserResponse toUserResponse(User user) {
        CartResponse cart = user.getCart() != null
                ? CartResponse.builder()
                        .id(user.getCart().getId())
                        .items(user.getCart().getItems().stream().map(item -> CartItemResponse.builder()
                                .id(item.getId())
                                .article(ArticleShort.builder()
                                        .id(item.getArticles().getId())
                                        .name(item.getArticles().getName())
                                        .previewImage(item.getArticles().getPreviewImage())
                                        .build())
                                .quantity(item.getQuantity())
                                .unitPrice(item.getUnitPrice())
                                .build()).collect(Collectors.toList()))
                        .build()
                : null;

        return UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .role(user.isAdmin() ? "ADMIN" : "USER")
                .createdAt(user.getCreatedAt() != null ? user.getCreatedAt().toString() : null)
                .cart(cart)
                .phoneNumbers(user.getPhoneNumbers() != null ? user.getPhoneNumbers().stream()
                        .map(phone -> PhoneNumberResponse.builder()
                                .id(phone.getId())
                                .countryPrefix(phone.getCountryPrefix())
                                .number(phone.getNumber())
                                .phoneType(phone.getPhoneType().name())
                                .build()).collect(Collectors.toList())
                        : Collections.emptyList())
                .addresses(user.getAddresses() != null ? user.getAddresses().stream()
                        .map(addr -> AddressResponse.builder()
                                .id(addr.getId())
                                .street(addr.getStreet())
                                .streetNumber(addr.getStreetNumber())
                                .postalCode(addr.getPostalCode())
                                .city(addr.getCity())
                                .country(addr.getCountry())
                                .build()).collect(Collectors.toList())
                        : Collections.emptyList())
                .build();
    }
}
