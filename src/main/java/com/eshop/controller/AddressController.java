package com.eshop.controller;

import com.eshop.config.CurrentUser;
import com.eshop.dto.AddAddressRequest;
import com.eshop.dto.AddressResponse;
import com.eshop.service.AddressService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users/{userId}/address")
@RequiredArgsConstructor
public class AddressController {

    private final AddressService addressService;
    private final CurrentUser currentUser;

    @GetMapping("/me")
    public ResponseEntity<List<AddressResponse>> findByUser(
            @RequestParam(required = false) Long testUserId) {
        Long userId = (testUserId != null) ? testUserId : currentUser.getCurrentUserId();
        return ResponseEntity.ok(addressService.findByUserId(userId));
    }

    @PostMapping("/me")
    public ResponseEntity<AddressResponse> add(
            @RequestParam(required = false) Long testUserId,
            @Valid @RequestBody AddAddressRequest request) {
        Long userId = (testUserId != null) ? testUserId : currentUser.getCurrentUserId();
        return ResponseEntity.ok(addressService.add(userId, request));
    }

    @DeleteMapping("/me/{addressId}")
    public ResponseEntity<Void> delete(
            @PathVariable Long addressId,
            @RequestParam(required = false) Long testUserId) {
        Long userId = (testUserId != null) ? testUserId : currentUser.getCurrentUserId();
        addressService.delete(userId, addressId);
        return ResponseEntity.noContent().build();
    }
}
