package com.eshop.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.eshop.enums.OrderStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO per la visualizzazione ordini nell'interfaccia admin.
 * Include il nome utente (l'Order entity ha @JsonIgnore su user).
 */
@JsonPropertyOrder({"id", "orderDate", "status", "total", "user", "items"})
public record AdminOrderDto(
        Long id,
        LocalDateTime orderDate,
        OrderStatus status,
        BigDecimal total,
        String username,
        List<OrderItemDto> items
) {
    public AdminOrderDto {
        if (items == null) items = List.of();
    }
}
