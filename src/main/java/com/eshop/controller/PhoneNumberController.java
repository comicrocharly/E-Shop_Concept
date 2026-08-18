package com.eshop.controller;

import com.eshop.config.CurrentUser;
import com.eshop.dto.AddPhoneNumberRequest;
import com.eshop.dto.PhoneNumberResponse;
import com.eshop.service.PhoneNumberService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users/{userId}/phone")
@RequiredArgsConstructor
public class PhoneNumberController {

    private final PhoneNumberService phoneService;
    private final CurrentUser currentUser;

    @GetMapping("/me")
    public ResponseEntity<List<PhoneNumberResponse>> findByUser(
            @RequestParam(required = false) Long testUserId) {
        Long userId = (testUserId != null) ? testUserId : currentUser.getCurrentUserId();
        return ResponseEntity.ok(phoneService.findByUserId(userId));
    }

    @PostMapping("/me")
    public ResponseEntity<PhoneNumberResponse> add(
            @RequestParam(required = false) Long testUserId,
            @Valid @RequestBody AddPhoneNumberRequest request) {
        Long userId = (testUserId != null) ? testUserId : currentUser.getCurrentUserId();
        return ResponseEntity.ok(phoneService.add(userId, request));
    }

    @DeleteMapping("/me/{phoneId}")
    public ResponseEntity<Void> delete(
            @PathVariable Long phoneId,
            @RequestParam(required = false) Long testUserId) {
        Long userId = (testUserId != null) ? testUserId : currentUser.getCurrentUserId();
        phoneService.delete(userId, phoneId);
        return ResponseEntity.noContent().build();
    }
}
