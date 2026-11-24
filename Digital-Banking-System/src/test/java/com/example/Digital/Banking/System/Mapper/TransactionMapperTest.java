package com.example.Digital.Banking.System.Mapper;

import com.example.Digital.Banking.System.Entity.*;
import com.example.Digital.Banking.System.Enum.*;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class TransactionMapperTest {
    final TransactionMapper mapper = new TransactionMapper();

    Account account(Long id, Long ownerId, String number) {
        var owner = new User();
        owner.setId(ownerId);
        var account = new Account();
        account.setId(id);
        account.setUser(owner);
        account.setAccountNumber(number);
        account.setCurrency(com.example.Digital.Banking.System.Enum.Currency.EGP);
        return account;
    }

    @Test void shortForeignAccountNumbersAreFullyMasked() {
        var transaction = new Transaction();
        transaction.setType(TransactionType.TRANSFER);
        transaction.setStatus(TransactionStatus.COMPLETED);
        transaction.setSourceAccount(account(1L, 1L, "1234"));
        transaction.setDestinationAccount(account(2L, 2L, "9"));
        var result = mapper.toResponse(transaction, 1L, null);
        assertEquals("1234", result.sourceAccountNumber());
        assertEquals("****", result.destinationAccountNumber());
    }

    @Test void incompatibleOrAbsentCurrencyIsNotInvented() {
        var transaction = new Transaction();
        transaction.setType(TransactionType.TRANSFER);
        transaction.setStatus(TransactionStatus.COMPLETED);
        transaction.setSourceAccount(account(1L, 1L, "11111"));
        var destination = account(2L, 2L, "22222");
        destination.setCurrency(com.example.Digital.Banking.System.Enum.Currency.USD);
        transaction.setDestinationAccount(destination);
        assertNull(mapper.toResponse(transaction, 1L, null).currency());
        transaction.setSourceAccount(null);
        transaction.setDestinationAccount(null);
        assertNull(mapper.toResponse(transaction, 1L, null).currency());
    }

    @Test void nullableRelationshipsAndUnknownOwnerNeverExposeFullNumbers() {
        var transaction = new Transaction();
        transaction.setType(TransactionType.DEPOSIT);
        transaction.setStatus(TransactionStatus.FAILED);
        var account = account(1L, 1L, "123456789");
        account.setUser(null);
        transaction.setDestinationAccount(account);
        var response = mapper.toResponse(transaction, 1L, null);
        assertNull(response.createdAt());
        assertNull(response.sourceAccountNumber());
        assertEquals("****6789", response.destinationAccountNumber());
        assertNull(response.completedAt());
        assertEquals("Transaction could not be completed", response.failureMessage());
    }

    @Test void allFailureReasonsUseSafeCanonicalMessages() {
        var transaction = new Transaction();
        transaction.setType(TransactionType.TRANSFER);
        transaction.setStatus(TransactionStatus.FAILED);
        transaction.setFailureMessage("SQLException password hash=secret");
        transaction.setSourceAccount(account(1L, 1L, "11111"));
        for (var reason : FailureReason.values()) {
            transaction.setFailureReason(reason);
            var response = mapper.toResponse(transaction, 1L, null);
            assertNotNull(response.failureMessage());
            assertFalse(response.toString().contains("secret"));
            assertEquals(reason, response.failureReason());
        }
    }

    @Test void responseContainsOnlyApprovedFieldsAndNoJpaEntities() {
        var fields = Arrays.stream(com.example.Digital.Banking.System.DTO.TransactionResponseDTO.class
                        .getRecordComponents()).map(java.lang.reflect.RecordComponent::getName)
                .collect(Collectors.toSet());
        assertEquals(Set.of("reference", "type", "direction", "amount", "currency", "status",
                "sourceAccountNumber", "destinationAccountNumber", "createdAt", "completedAt",
                "failureReason", "failureMessage"), fields);
    }
}