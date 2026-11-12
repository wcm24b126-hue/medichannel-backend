package com.medichannel.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(

        @NotBlank(message = "Email is required.")
        @Email(message = "Please provide a valid email address.")
        @Size(max = 254, message = "Email must not exceed 254 characters.")
        String email,

        @NotBlank(message = "Password is required.")
        @Size(
                min = 12,
                max = 72,
                message = "Password must contain between 12 and 72 characters."
        )
        @Pattern(
                regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z\\d]).*$",
                message = "Password must include at least one letter, one number and one symbol."
        )
        String password,

        @NotBlank(message = "Password confirmation is required.")
        String confirmPassword

) {
}
