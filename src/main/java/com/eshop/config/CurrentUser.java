package com.eshop.config;

import com.eshop.entity.User;
import com.eshop.service.UserService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentUser {

    private final UserService userService;

    public CurrentUser(UserService userService) {
        this.userService = userService;
    }

    public User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new RuntimeException("Utente non autenticato");
        }

        // Caso normale (JwtAuthenticationFilter): l'entity User è già il principal
        // → nessuna query al database.
        Object principal = authentication.getPrincipal();
        if (principal instanceof User user) {
            return user;
        }

        // Fallback (es. auth di test che mette un nome in chiaro nel context).
        return userService.findByUsername(principal.toString())
                .orElseThrow(() -> new RuntimeException("User not found: " + principal));
    }

    public Long getCurrentUserId() {
        return getCurrentUser().getId();
    }

    public String getCurrentUsername() {
        return getCurrentUser().getUsername();
    }
}
