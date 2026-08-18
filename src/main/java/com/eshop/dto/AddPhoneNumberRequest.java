package com.eshop.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddPhoneNumberRequest {

    @NotBlank(message = "Il prefisso è obbligatorio")
    @Pattern(regexp = "^[+]?[0-9]{1,4}$", message = "Prefisso non valido (es. +39)")
    private String countryPrefix;

    @NotBlank(message = "Il numero è obbligatorio")
    private String number;

    @NotBlank(message = "Il tipo è obbligatorio")
    private String phoneType;
}
