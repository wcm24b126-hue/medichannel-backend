package com.medichannel.dto.admin;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * An administrator creates a MANAGER account. DOCTOR accounts carry a
 * clinical profile and are therefore created through the doctor endpoint.
 */
public record CreateManagerRequest(

        @NotBlank(message = "Email is required.")
        @Email(message = "Please provide a valid email address.")
        @Size(max = 254)
        String email,

        @NotBlank(message = "Password is required.")
        @Size(min = 8, max = 72)
        @Pattern(
                regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{8,72}$",
                message = "Password must contain at least one letter, one number and one symbol."
        )
        String password,

        @NotBlank(message = "Password confirmation is required.")
        String confirmPassword
) {
}
