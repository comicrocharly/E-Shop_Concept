package com.eshop.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AddToCartRequest(
        @NotNull Long articleId,
        @Positive Integer quantity
) {}
