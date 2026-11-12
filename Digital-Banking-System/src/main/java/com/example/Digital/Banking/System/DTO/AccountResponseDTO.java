package com.example.Digital.Banking.System.DTO;

import com.example.Digital.Banking.System.Enum.AccountStatus;
import com.example.Digital.Banking.System.Enum.AccountType;
import com.example.Digital.Banking.System.Enum.Currency;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class AccountResponseDTO {
    private long id;
    private String accountNumber;
    private AccountType accountType;
    private Currency currency;
    private BigDecimal balance;
    private AccountStatus accountStatus;
}
