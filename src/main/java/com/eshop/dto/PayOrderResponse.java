package com.eshop.dto;

import com.eshop.enums.PaymentMethod;
import com.eshop.enums.PaymentStatus;

import java.math.BigDecimal;

public record PayOrderResponse(
        Long orderId,
        PaymentStatus paymentStatus,
        String transactionId,
        BigDecimal amount,
        PaymentMethod method
) {}
