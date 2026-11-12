package com.example.Digital.Banking.System.DTO;

import com.example.Digital.Banking.System.Enum.Role;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ChangeRoleRequestDTO {

    @NotNull
    private Role role;
}