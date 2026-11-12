package com.example.Digital.Banking.System.DTO;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RegisterRequestDTO {

            @NotBlank private String name;
            @Email
            @NotBlank private String email;
            @NotBlank @Size(min = 8) private String password;
            @NotBlank private String phone;

}
//public record RegisterRequest(
//        @NotBlank String name,
//        @Email @NotBlank String email,
//        @NotBlank @Size(min = 8) String password,
//        @NotBlank String phone
//) {}