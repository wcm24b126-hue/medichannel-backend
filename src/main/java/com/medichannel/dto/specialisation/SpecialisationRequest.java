package com.medichannel.dto.specialisation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SpecialisationRequest(

        @NotBlank
        @Size(max = 100)
        String name,

        @Size(max = 500)
        String description

) {
}
