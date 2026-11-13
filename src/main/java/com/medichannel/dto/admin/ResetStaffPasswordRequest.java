package com.medichannel.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ResetStaffPasswordRequest(

        @NotBlank(message = "A temporary password is required.")
        @Size(min = 8, max = 72)
        @Pattern(
                regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{8,72}$",
                message = "Password must contain at least one letter, one number and one symbol."
        )
        String newPassword,

        @NotBlank(message = "Password confirmation is required.")
        String confirmPassword
) {
}
