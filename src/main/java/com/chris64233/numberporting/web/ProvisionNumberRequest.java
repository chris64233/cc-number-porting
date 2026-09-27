package com.chris64233.numberporting.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ProvisionNumberRequest(
        @NotBlank @Pattern(regexp = "\\d{6,20}", message = "number must be 6-20 digits")
        String number,
        @NotBlank String carrier) {
}
