package com.example.Digital.Banking.System.DTO;

import com.example.Digital.Banking.System.Enum.AccountStatus;
import com.example.Digital.Banking.System.Enum.AccountType;
import com.example.Digital.Banking.System.Enum.Currency;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class CreateAccountRequestDTO {
    @NotNull
    private Currency currency;
    @NotNull
    private AccountType accountType;

}
