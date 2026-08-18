package com.eshop.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddAddressRequest {

    @NotBlank(message = "La via è obbligatoria")
    private String street;

    @Min(value = 1, message = "Il numero civico deve essere ≥ 1")
    private Integer streetNumber;

    @NotBlank(message = "Il CAP è obbligatorio")
    private String postalCode;

    @NotBlank(message = "Il comune è obbligatorio")
    private String city;

    @NotBlank(message = "La nazione è obbligatoria")
    private String country;
}
