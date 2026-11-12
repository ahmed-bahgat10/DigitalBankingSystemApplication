package com.example.Digital.Banking.System.Service;

import com.example.Digital.Banking.System.DTO.AccountResponseDTO;
import com.example.Digital.Banking.System.DTO.CreateAccountRequestDTO;
import com.example.Digital.Banking.System.Entity.Account;
import com.example.Digital.Banking.System.Entity.ScheduledTransfer;
import com.example.Digital.Banking.System.Entity.User;
import com.example.Digital.Banking.System.Enum.AccountStatus;
import com.example.Digital.Banking.System.Enum.CancellationReason;
import com.example.Digital.Banking.System.Enum.KycStatus;
import com.example.Digital.Banking.System.Enum.ScheduledTransferStatus;
import com.example.Digital.Banking.System.Exception.BusinessException;
import com.example.Digital.Banking.System.Exception.NotFoundException;
import com.example.Digital.Banking.System.Repository.AccountRepository;
import com.example.Digital.Banking.System.Repository.ScheduledTransferRepository;
import com.example.Digital.Banking.System.Repository.UserRepository;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final ScheduledTransferRepository scheduledTransferRepository;

    private String generateAccountNumber() {
        return UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 16);
    }

    @Transactional
    public AccountResponseDTO createAccount(
            Long userId,
            CreateAccountRequestDTO request
    ) {

        // 1. Fetch User
        User user = userRepository.findById(userId)
                .orElseThrow(() ->
                        new NotFoundException("User not found")
                );

        // 2. Check KYC Status
        if (user.getKycStatus() != KycStatus.VERIFIED) {
            throw new BusinessException(
                    "User must complete KYC verification first"
            );
        }

        // 3. Create Account
        Account account = new Account();

        account.setAccountNumber(generateAccountNumber());
        account.setAccountType(request.getAccountType());
        account.setCurrency(request.getCurrency());
        account.setBalance(BigDecimal.ZERO);
        account.setStatus(AccountStatus.ACTIVE);
        account.setPrimary(false);
        account.setUser(user);

        // 4. Save Account
        Account savedAccount = accountRepository.save(account);


        return new AccountResponseDTO(
                savedAccount.getId(),
                savedAccount.getAccountNumber(),
                savedAccount.getAccountType(),
                savedAccount.getCurrency(),
                savedAccount.getBalance(),
                savedAccount.getStatus()
        );

    }
    public AccountResponseDTO getAccountByAccountNumber(
            Long userId,
            String accountNumber) {

        Account account = accountRepository
                .findByUserIdAndAccountNumber(userId, accountNumber)
                .orElseThrow(() ->
                        new NotFoundException("Account not found")
                );

        return new AccountResponseDTO(
                account.getId(),
                account.getAccountNumber(),
                account.getAccountType(),
                account.getCurrency(),
                account.getBalance(),
                account.getStatus()
        );
    }

    public List<AccountResponseDTO> getUserAccounts(Long userId) {

        return accountRepository.findAllByUserId(userId)
                .stream()
                .map(account -> new AccountResponseDTO(
                        account.getId(),
                        account.getAccountNumber(),
                        account.getAccountType(),
                        account.getCurrency(),
                        account.getBalance(),
                        account.getStatus()
                ))
                .toList();
    }

    @Transactional
    public void suspendAccount(Long userId, String accountNumber) {

        Account account = accountRepository
                .findByUserIdAndAccountNumber(userId, accountNumber)
                .orElseThrow(() ->
                        new NotFoundException("Account not found")
                );

        if (account.getStatus() == AccountStatus.SUSPENDED) {
            throw new BusinessException("Account is already suspended");
        }

        if (account.getStatus() == AccountStatus.CLOSED) {
            throw new BusinessException("Cannot suspend a closed account");
        }

        account.setStatus(AccountStatus.SUSPENDED);
    }

    @Transactional
    public void activateAccount(Long userId, String accountNumber) {

        Account account = accountRepository
                .findByUserIdAndAccountNumber(userId, accountNumber)
                .orElseThrow(() ->
                        new NotFoundException("Account not found")
                );

        if (account.getStatus() == AccountStatus.ACTIVE) {
            throw new BusinessException("Account is already active");
        }

        if (account.getStatus() == AccountStatus.CLOSED) {
            throw new BusinessException("Cannot activate a closed account");
        }

        account.setStatus(AccountStatus.ACTIVE);
    }
    @Transactional
    public void closeAccount(Long userId, String accountNumber) {

        // 1. Find account and verify ownership
        Account account = accountRepository
                .findByUserIdAndAccountNumberForUpdate(userId, accountNumber)
                .orElseThrow(() ->
                        new NotFoundException("Account not found")
                );

        // 2. Check if account is already closed
        if (account.getStatus() == AccountStatus.CLOSED) {
            throw new BusinessException("Account is already closed");
        }

        // 3. Find pending and processing transfers
        List<ScheduledTransfer> transfers =
                scheduledTransferRepository.findByAccountIdAndStatuses(
                        account.getId(),
                        List.of(
                                ScheduledTransferStatus.PENDING,
                                ScheduledTransferStatus.PROCESSING
                        )
                );

        // 4. Prevent closing while transfers are processing
        boolean hasProcessingTransfer = transfers.stream()
                .anyMatch(transfer ->
                        transfer.getStatus() == ScheduledTransferStatus.PROCESSING
                );

        if (hasProcessingTransfer) {
            throw new BusinessException(
                    "Cannot close account while transfers are processing"
            );
        }

        // 5. Check account balance
        if (account.getBalance().compareTo(BigDecimal.ZERO) != 0) {
            throw new BusinessException(
                    "Cannot close account with non-zero balance"
            );
        }

        // 6. Cancel pending transfers
        transfers.stream()
                .filter(transfer ->
                        transfer.getStatus() == ScheduledTransferStatus.PENDING
                )
                .forEach(transfer -> {

                    transfer.setStatus(ScheduledTransferStatus.CANCELLED);
                    transfer.setCancelledAt(LocalDateTime.now());

                    if (transfer.getSourceAccount().getId().equals(account.getId())) {
                        transfer.setCancellationReason(
                                CancellationReason.SOURCE_ACCOUNT_CLOSED
                        );
                    } else {
                        transfer.setCancellationReason(
                                CancellationReason.DESTINATION_ACCOUNT_CLOSED
                        );
                    }
                });
        // 7. Close account
        account.setStatus(AccountStatus.CLOSED);
    }
    public BigDecimal getAccountBalance(Long userId ,String accountNumber) {

        Account account = accountRepository
                .findByUserIdAndAccountNumber(userId, accountNumber)
                .orElseThrow(() ->
                        new BusinessException("Account not found")
                );

        if (account.getStatus() == AccountStatus.CLOSED) {
            throw new BusinessException("Account is closed");
        }

        return account.getBalance();
    }

}
