package com.example.Digital.Banking.System.DTO;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@NoArgsConstructor
public class LoginRequestDTO {



        @NotBlank
        @Email
        private String email;

        @NotBlank
        @ToString.Exclude   // عشان الباسورد ميظهرش في الـ logs
        private String password;


}
