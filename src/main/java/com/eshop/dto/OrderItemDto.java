package com.eshop.dto;

import java.math.BigDecimal;

/**
 * DTO per un elemento ordine nell'admin view.
 */
public record OrderItemDto(
        Long id,
        String articleName,
        Integer quantity,
        BigDecimal unitPrice
) {}
