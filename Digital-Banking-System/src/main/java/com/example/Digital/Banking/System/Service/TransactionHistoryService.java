package com.example.Digital.Banking.System.Service;

import com.example.Digital.Banking.System.DTO.TransactionFilterDTO;
import com.example.Digital.Banking.System.DTO.TransactionResponseDTO;
import com.example.Digital.Banking.System.Entity.Transaction;
import com.example.Digital.Banking.System.Enum.AccountStatus;
import com.example.Digital.Banking.System.Exception.AccountClosedException;
import com.example.Digital.Banking.System.Exception.BusinessException;
import com.example.Digital.Banking.System.Exception.NotFoundException;
import com.example.Digital.Banking.System.Mapper.TransactionMapper;
import com.example.Digital.Banking.System.Repository.AccountRepository;
import com.example.Digital.Banking.System.Repository.TransactionRepository;
import com.example.Digital.Banking.System.Specification.TransactionSpecification;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;

/**
 * userId is trusted service input, never an API parameter. A future controller must
 * obtain it from AuthenticatedUser. This service does not expose HTTP endpoints.
 *
 * History date filters use UTC calendar boundaries on the existing LocalDateTime
 * createdAt column (the JDBC URL already specifies serverTimezone=UTC). Legacy
 * timezone-less timestamps are compared as stored; no global timezone, timestamp
 * generation, or existing LocalDate.now() daily-limit behavior is changed.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TransactionHistoryService {
    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final TransactionMapper mapper;

    public Page<TransactionResponseDTO> getUserTransactions(Long userId, TransactionFilterDTO filter) {
        return getUserTransactions(userId, filter, 0, 20, "createdAt", "DESC");
    }

    public Page<TransactionResponseDTO> getUserTransactions(
            Long userId, TransactionFilterDTO filter, int page, int size, String sortBy, String direction) {
        validateUser(userId);
        TransactionFilterDTO validated = validateFilter(filter);
        return transactionRepository.findAll(authorized(userId, null, validated),
                        pageable(page, size, sortBy, direction))
                .map(transaction -> mapper.toResponse(transaction, userId, null));
    }

    public Page<TransactionResponseDTO> getAccountTransactions(
            Long userId, String accountNumber, TransactionFilterDTO filter) {
        return getAccountTransactions(userId, accountNumber, filter, 0, 20, "createdAt", "DESC");
    }

    public Page<TransactionResponseDTO> getAccountTransactions(
            Long userId, String accountNumber, TransactionFilterDTO filter,
            int page, int size, String sortBy, String direction) {
        validateUser(userId);
        TransactionFilterDTO validated = validateFilter(filter);
        Pageable pageable = pageable(page, size, sortBy, direction);
        if (accountNumber == null || accountNumber.isBlank()) {
            throw new NotFoundException("Account not found");
        }
        var account = accountRepository.findByUserIdAndAccountNumber(userId, accountNumber.trim())
                .orElseThrow(() -> new NotFoundException("Account not found"));
        if (account.getStatus() == AccountStatus.CLOSED) {
            throw new AccountClosedException("Account is closed");
        }
        return transactionRepository.findAll(authorized(userId, account.getId(), validated), pageable)
                .map(transaction -> mapper.toResponse(transaction, userId, account.getId()));
    }

    public TransactionResponseDTO getTransactionByReference(Long userId, String reference) {
        validateUser(userId);
        if (reference == null || reference.isBlank()) {
            throw new NotFoundException("Transaction not found");
        }
        // Reference and authorization are conditions of the same database query.
        var specification = TransactionSpecification.visibleTo(userId, null)
                .and(TransactionSpecification.withReference(reference.trim()));
        Transaction transaction = transactionRepository.findOne(specification)
                .orElseThrow(() -> new NotFoundException("Transaction not found"));
        return mapper.toResponse(transaction, userId, null);
    }

    private Specification<Transaction> authorized(Long userId, Long accountId, TransactionFilterDTO filter) {
        return TransactionSpecification.visibleTo(userId, accountId)
                .and(TransactionSpecification.matching(filter));
    }

    private void validateUser(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException("Authenticated user is required");
        }
    }

    private TransactionFilterDTO validateFilter(TransactionFilterDTO filter) {
        if (filter == null) {
            return TransactionFilterDTO.empty();
        }
        if (filter.fromDate() != null && filter.toDate() != null
                && filter.fromDate().isAfter(filter.toDate())) {
            throw new BusinessException("fromDate must not be after toDate");
        }
        if (LocalDate.MAX.equals(filter.toDate())) {
            throw new BusinessException("toDate must allow an exclusive next-day boundary");
        }
        if ((filter.minAmount() != null && filter.minAmount().signum() < 0)
                || (filter.maxAmount() != null && filter.maxAmount().signum() < 0)) {
            throw new BusinessException("Amount filters must not be negative");
        }
        if (filter.minAmount() != null && filter.maxAmount() != null
                && filter.minAmount().compareTo(filter.maxAmount()) > 0) {
            throw new BusinessException("minAmount must not exceed maxAmount");
        }
        return filter;
    }

    private Pageable pageable(int page, int size, String sortBy, String direction) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException("Page must be non-negative and size must be between 1 and 100");
        }
        String property = sortBy == null ? "createdAt" : sortBy;
        if (!property.equals("createdAt") && !property.equals("amount")) {
            throw new BusinessException("Sorting is allowed only by createdAt or amount");
        }
        String requestedDirection = direction == null ? "DESC" : direction;
        if (!requestedDirection.equalsIgnoreCase("ASC") && !requestedDirection.equalsIgnoreCase("DESC")) {
            throw new BusinessException("Sort direction must be ASC or DESC");
        }
        Sort.Direction order = Sort.Direction.fromString(requestedDirection);
        return PageRequest.of(page, size, Sort.by(new Sort.Order(order, property), new Sort.Order(order, "id")));
    }
}
