package com.eshop.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateArticlesRequest(
        @NotBlank String name,
        @Size(max = 5000) String description,
        @NotNull BigDecimal price,
        @NotNull Integer stock
) {}
