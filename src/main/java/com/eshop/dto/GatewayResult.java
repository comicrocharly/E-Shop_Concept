package com.eshop.dto;

public record GatewayResult(
        boolean success,
        String status,
        String transactionId
) {}
