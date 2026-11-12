package com.example.Digital.Banking.System.DTO;

import com.example.Digital.Banking.System.Enum.KycStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ChangeKycStatusRequestDTO {

    @NotNull
    private KycStatus kycStatus;
}