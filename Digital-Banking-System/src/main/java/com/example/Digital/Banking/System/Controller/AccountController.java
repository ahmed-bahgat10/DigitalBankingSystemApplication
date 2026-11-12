package com.example.Digital.Banking.System.Controller;

import com.example.Digital.Banking.System.DTO.AccountResponseDTO;
import com.example.Digital.Banking.System.Service.AccountService;
import com.example.Digital.Banking.System.Service.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
@RestController
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @GetMapping("/{accountNumber}")
    public ResponseEntity<AccountResponseDTO> getAccount(
            @PathVariable String accountNumber,
            @AuthenticationPrincipal AuthenticatedUser user) {

        return ResponseEntity.ok(
                accountService.getAccountByAccountNumber(
                        user.id(), accountNumber
                )
        );
    }

    @GetMapping
    public ResponseEntity<List<AccountResponseDTO>> getUserAccounts(
            @AuthenticationPrincipal AuthenticatedUser user) {

        return ResponseEntity.ok(
                accountService.getUserAccounts(user.id())
        );
    }
}
