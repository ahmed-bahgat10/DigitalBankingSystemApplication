package com.example.Digital.Banking.System.DTO;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
    @Setter
    @NoArgsConstructor
    public class UpdateUserRequestDTO {

        @Size(min = 2, max = 100)
        private String name;

        @Pattern(regexp = "^\\+?[0-9]{10,15}$", message = "Invalid phone number")
        private String phone;
    }

