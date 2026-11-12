package com.example.Digital.Banking.System.DTO;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class LoginResponseDTO {

    private String accessToken;
    private String tokenType;      // دايماً "Bearer"
    private long expiresIn;        // مدة الصلاحية بالثواني
    private UserResponseDTO user;  // بيانات بسيطة عن اليوزر
}
