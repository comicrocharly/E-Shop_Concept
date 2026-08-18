package com.eshop.service;

import com.eshop.dto.*;
import com.eshop.entity.Cart;
import com.eshop.entity.User;

import com.eshop.repository.CartRepository;
import com.eshop.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService implements org.springframework.security.core.userdetails.UserDetailsService {

    private final UserRepository userRepository;
    private final CartRepository cartRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public User register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new IllegalArgumentException("Username già in uso: " + request.username());
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new IllegalArgumentException("Email già registrata: " + request.email());
        }

        User user = User.builder()
                .username(request.username())
                .password(passwordEncoder.encode(request.password()))
                .email(request.email())
                .role("USER")
                .build();

        User savedUser = userRepository.save(user);
        return savedUser;
    }

    @Transactional(readOnly = true)
    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    @Override
    @Transactional(readOnly = true)
    public org.springframework.security.core.userdetails.UserDetails loadUserByUsername(String username) throws org.springframework.security.core.userdetails.UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new org.springframework.security.core.userdetails.UsernameNotFoundException("User not found: " + username));

        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getUsername())
                .password(user.getPassword())
                .roles(user.isAdmin() ? "ADMIN" : "USER")
                .build();
    }

    @Transactional
    public User findOrCreateUser(String username) {
        return userRepository.findByUsername(username)
                .orElseGet(() -> {
                    User newUser = User.builder()
                            .username(username)
                            .password("N/A")
                            .email(username + "@eshop.local")
                            .role("USER")
                            .build();
                    return userRepository.save(newUser);
                });
    }

    @Transactional(readOnly = true)
    public User authenticate(LoginRequest request) {
        return userRepository.findByUsername(request.username())
                .filter(user -> passwordEncoder.matches(request.password(), user.getPassword()))
                .orElseThrow(() -> new IllegalArgumentException("Credenziali non valide"));
    }

    @Transactional(readOnly = true)
    public Optional<User> findById(Long id) {
        return userRepository.findById(id);
    }

    @Transactional
    public User updateProfile(Long userId, Map<String, String> updates) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + userId));

        // Update email
        String newEmail = updates.get("email");
        if (newEmail != null && !newEmail.equals(user.getEmail())) {
            if (userRepository.existsByEmail(newEmail)) {
                throw new IllegalArgumentException("Email già in uso: " + newEmail);
            }
            user.setEmail(newEmail);
        }

        // Update password — requires current password verification
        String newPassword = updates.get("password");
        if (newPassword != null && !newPassword.isEmpty()) {
            String currentPassword = updates.get("currentPassword");
            if (currentPassword == null || currentPassword.isEmpty()) {
                throw new IllegalArgumentException("È richiesta la password corrente per cambiare la password");
            }
            if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
                throw new IllegalArgumentException("La password corrente non è corretta");
            }
            if (newPassword.length() < 6) {
                throw new IllegalArgumentException("La password deve avere almeno 6 caratteri");
            }
            user.setPassword(passwordEncoder.encode(newPassword));
        }

        return userRepository.save(user);
    }

    public UserResponse toUserResponse(User user) {
        CartResponse cart = user.getCart() != null
                ? CartResponse.builder()
                        .id(user.getCart().getId())
                        .items(user.getCart().getItems().stream().map(item -> CartResponse.CartItemResponse.builder()
                                .id(item.getId())
                                .article(CartResponse.CartItemResponse.ArticleShort.builder()
                                        .id(item.getArticles().getId())
                                        .name(item.getArticles().getName())
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
                        : List.of())
                .addresses(user.getAddresses() != null ? user.getAddresses().stream()
                        .map(addr -> AddressResponse.builder()
                                .id(addr.getId())
                                .street(addr.getStreet())
                                .streetNumber(addr.getStreetNumber())
                                .postalCode(addr.getPostalCode())
                                .city(addr.getCity())
                                .country(addr.getCountry())
                                .build()).collect(Collectors.toList())
                        : List.of())
                .build();
    }
}
