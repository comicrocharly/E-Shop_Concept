package com.eshop.controller;

import com.eshop.config.CurrentUser;
import com.eshop.dto.UserResponse;
import com.eshop.entity.User;
import com.eshop.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final CurrentUser currentUser;

    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser(
            @RequestParam(required = false) Long testUserId) {
        Long userId = (testUserId != null) ? testUserId : currentUser.getCurrentUserId();
        User user = userService.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));
        return ResponseEntity.ok(userService.toUserResponse(user));
    }

    @PutMapping("/me/profile")
    public ResponseEntity<UserResponse> updateProfile(
            @RequestParam(required = false) Long testUserId,
            @RequestBody Map<String, String> updates) {
        Long userId = (testUserId != null) ? testUserId : currentUser.getCurrentUserId();
        User user = userService.updateProfile(userId, updates);
        return ResponseEntity.ok(userService.toUserResponse(user));
    }
}
