package com.eshop.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PhoneNumberResponse {
    private Long id;
    private String countryPrefix;
    private String number;
    private String phoneType;
}
