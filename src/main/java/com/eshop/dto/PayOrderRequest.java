package com.eshop.dto;

import com.eshop.enums.PaymentMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record PayOrderRequest(
        @NotNull PaymentMethod method,
        Map<String, String> details
) {}
