package com.eshop.dto;

import com.eshop.enums.PaymentMethod;

import java.math.BigDecimal;
import java.util.List;

public record PrepareCheckoutResponse(
        Long orderId,
        BigDecimal total,
        List<CartItemDto> items,
        PaymentMethod paymentMethod
) {
    public record CartItemDto(Long articleId, String name, int quantity, BigDecimal unitPrice, BigDecimal subtotal) {}
}
