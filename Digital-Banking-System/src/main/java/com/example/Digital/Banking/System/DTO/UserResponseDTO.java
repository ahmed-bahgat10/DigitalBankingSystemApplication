package com.example.Digital.Banking.System.DTO;

import com.example.Digital.Banking.System.Enum.KycStatus;
import com.example.Digital.Banking.System.Enum.Role;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class UserResponseDTO {
    private Long id;
    private String name;
    private String email;
    private Role role;
    private KycStatus kycStatus;
}
