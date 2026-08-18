package com.eshop.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserResponse {
    private Long id;
    private String username;
    private String email;
    private String role;
    private String createdAt;
    private CartResponse cart;
    private List<PhoneNumberResponse> phoneNumbers;
    private List<AddressResponse> addresses;
}
