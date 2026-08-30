package com.eshop.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CartResponse {
    private Long id;
    private List<CartItemResponse> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CartItemResponse {
        private Long id;
        private ArticleShort article;
        private Integer quantity;
        private BigDecimal unitPrice;

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class ArticleShort {
            private Long id;
            private String name;
            private String previewImage;
        }
    }
}
