package com.example.Digital.Banking.System.Service;

import com.example.Digital.Banking.System.DTO.*;
import com.example.Digital.Banking.System.Entity.*;
import com.example.Digital.Banking.System.Enum.*;
import com.example.Digital.Banking.System.Exception.*;
import com.example.Digital.Banking.System.Repository.*;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(TransferIdempotencyTest.Config.class)
class TransactionHistoryServiceTest {
    @Autowired TransactionHistoryService history;
    @Autowired TransactionService execution;
    @Autowired AccountRepository accounts;
    @Autowired UserRepository users;
    @Autowired TransactionRepository transactions;
    @Autowired IdempotencyRecordRepository idempotency;
    @Autowired PlatformTransactionManager manager;
    @Autowired DataSource dataSource;
    @Autowired EntityManagerFactory emf;
    Long alice, bob, outsider;
    Long source, destination, ownOther;
    static final String SOURCE = "ALICE00001111";
    static final String DESTINATION = "BOB00002222";
    static final String OWN_OTHER = "ALICE00003333";

    @BeforeEach void setup() {
        tx(ignored -> {
            idempotency.deleteAllInBatch();
            transactions.deleteAllInBatch();
            accounts.deleteAllInBatch();
            users.deleteAllInBatch();
            alice = user("alice").getId();
            bob = user("bob").getId();
            outsider = user("outsider").getId();
            source = account(SOURCE, alice).getId();
            destination = account(DESTINATION, bob).getId();
            ownOther = account(OWN_OTHER, alice).getId();
        });
        emf.unwrap(SessionFactory.class).getStatistics().clear();
    }

    void tx(Consumer<org.springframework.transaction.TransactionStatus> work) {
        new TransactionTemplate(manager).executeWithoutResult(work);
    }
    User user(String name) {
        var user = new User();
        user.setName(name);
        user.setEmail(name + "@example.com");
        user.setPassword("not-a-real-credential");
        user.setPhone("123");
        return users.saveAndFlush(user);
    }
    Account account(String number, Long owner) {
        var account = new Account();
        account.setAccountNumber(number);
        account.setUser(users.getReferenceById(owner));
        account.setBalance(new BigDecimal("1000"));
        account.setCurrency(com.example.Digital.Banking.System.Enum.Currency.EGP);
        account.setStatus(AccountStatus.ACTIVE);
        account.setAccountType(AccountType.CURRENT);
        return accounts.saveAndFlush(account);
    }

    String operation(TransactionType type, TransactionStatus status, Long from, Long to, Long initiator,
                     String amount, LocalDateTime createdAt) {
        return new TransactionTemplate(manager).execute(ignored -> {
            var transaction = new com.example.Digital.Banking.System.Entity.Transaction();
            transaction.setType(type);
            transaction.setStatus(status);
            transaction.setAmount(new BigDecimal(amount));
            transaction.setSourceAccount(from == null ? null : accounts.getReferenceById(from));
            transaction.setDestinationAccount(to == null ? null : accounts.getReferenceById(to));
            transaction.setInitiatedBy(initiator == null ? null : users.getReferenceById(initiator));
            if (status == TransactionStatus.FAILED) {
                transaction.setFailureReason(FailureReason.INSUFFICIENT_FUNDS);
                transaction.setFailureMessage("SQLState=123 secret internal stack trace");
            }
            if (status == TransactionStatus.COMPLETED) {
                transaction.setCompletedAt(Instant.parse("2026-05-12T12:00:00Z"));
            }
            transactions.saveAndFlush(transaction);
            new JdbcTemplate(dataSource).update("UPDATE transactions SET createdAt=? WHERE id=?",
                    createdAt, transaction.getId());
            return transaction.getReference();
        });
    }
    String transfer(TransactionStatus status) {
        return operation(TransactionType.TRANSFER, status, source, destination, alice, "10",
                LocalDateTime.of(2026, 5, 12, 12, 0));
    }
    Page<TransactionResponseDTO> all(Long user) { return history.getUserTransactions(user, null); }
    Set<String> references(Page<TransactionResponseDTO> page) {
        return page.stream().map(TransactionResponseDTO::reference).collect(Collectors.toSet());
    }
    void status(Long accountId, AccountStatus value) {
        tx(ignored -> accounts.findById(accountId).orElseThrow().setStatus(value));
    }
    TransactionFilterDTO filter(TransactionType type, TransactionStatus status, LocalDate from,
                                LocalDate to, String min, String max) {
        return new TransactionFilterDTO(type, status, from, to,
                min == null ? null : new BigDecimal(min), max == null ? null : new BigDecimal(max));
    }

    @Test void completedTransferVisibleToBothOwnersButNotOutsider() {
        var reference = transfer(TransactionStatus.COMPLETED);
        assertEquals(Set.of(reference), references(all(alice)));
        assertEquals(Set.of(reference), references(all(bob)));
        assertTrue(all(outsider).isEmpty());
        assertEquals(TransactionDirection.OUTGOING, history.getTransactionByReference(alice, reference).direction());
        assertEquals(TransactionDirection.INCOMING, history.getTransactionByReference(bob, reference).direction());
    }
    @Test void failedTransferOnlyVisibleToInitiatorIncludingReferenceAndAccountScope() {
        var reference = transfer(TransactionStatus.FAILED);
        assertEquals(Set.of(reference), references(all(alice)));
        assertTrue(all(bob).isEmpty());
        assertTrue(history.getAccountTransactions(bob, DESTINATION, null).isEmpty());
        assertThrows(NotFoundException.class, () -> history.getTransactionByReference(bob, reference));
        assertThrows(NotFoundException.class, () -> history.getTransactionByReference(outsider, reference));
        assertEquals(TransactionDirection.OUTGOING, history.getTransactionByReference(alice, reference).direction());
        assertNull(history.getTransactionByReference(alice, reference).completedAt());
    }
    @Test void pendingTransferOnlyVisibleToInitiatorAndNeverAMovement() {
        var reference = transfer(TransactionStatus.PENDING);
        assertEquals(Set.of(reference), references(all(alice)));
        assertTrue(all(bob).isEmpty());
        assertThrows(NotFoundException.class, () -> history.getTransactionByReference(bob, reference));
        var response = history.getTransactionByReference(alice, reference);
        assertEquals(TransactionDirection.OUTGOING, response.direction());
        assertNull(response.completedAt());
        assertNull(response.failureReason());
        assertNull(response.failureMessage());
    }
    @Test void successfulAndFailedDepositsAndWithdrawalsArePrivateAndDirectional() {
        var expected = new HashSet<String>();
        for (var type : List.of(TransactionType.DEPOSIT, TransactionType.WITHDRAW)) {
            for (var state : List.of(TransactionStatus.COMPLETED, TransactionStatus.FAILED)) {
                String ref = operation(type, state, type == TransactionType.WITHDRAW ? source : null,
                        type == TransactionType.DEPOSIT ? source : null, alice, "20", LocalDateTime.now());
                expected.add(ref);
                assertEquals(type == TransactionType.DEPOSIT ? TransactionDirection.INCOMING : TransactionDirection.OUTGOING,
                        history.getTransactionByReference(alice, ref).direction());
                assertThrows(NotFoundException.class, () -> history.getTransactionByReference(bob, ref));
            }
        }
        assertEquals(expected, references(all(alice)));
        assertTrue(all(bob).isEmpty());
    }
    @Test void suspendedAccountsRemainVisibleInAllEntryPoints() {
        String reference = transfer(TransactionStatus.COMPLETED);
        status(source, AccountStatus.SUSPENDED);
        assertEquals(Set.of(reference), references(all(alice)));
        assertEquals(Set.of(reference), references(history.getAccountTransactions(alice, SOURCE, null)));
        assertEquals(reference, history.getTransactionByReference(alice, reference).reference());
    }
    @Test void closedSourceDoesNotHideCompletedTransferFromRecipient() {
        String reference = transfer(TransactionStatus.COMPLETED);
        status(source, AccountStatus.CLOSED);
        assertTrue(all(alice).isEmpty());
        assertThrows(AccountClosedException.class, () -> history.getAccountTransactions(alice, SOURCE, null));
        assertThrows(NotFoundException.class, () -> history.getTransactionByReference(alice, reference));
        assertEquals(Set.of(reference), references(all(bob)));
        assertEquals(reference, history.getTransactionByReference(bob, reference).reference());
        assertEquals(1, transactions.count());
    }
    @Test void closedDestinationDoesNotHideCompletedTransferFromSender() {
        String reference = transfer(TransactionStatus.COMPLETED);
        status(destination, AccountStatus.CLOSED);
        assertEquals(Set.of(reference), references(all(alice)));
        assertEquals(reference, history.getTransactionByReference(alice, reference).reference());
        assertTrue(all(bob).isEmpty());
        assertThrows(NotFoundException.class, () -> history.getTransactionByReference(bob, reference));
    }
    @Test void failedAndPendingTransfersThroughOwnedClosedSourceAreHidden() {
        String failed = transfer(TransactionStatus.FAILED);
        String pending = transfer(TransactionStatus.PENDING);
        status(source, AccountStatus.CLOSED);
        assertTrue(all(alice).isEmpty());
        assertThrows(NotFoundException.class, () -> history.getTransactionByReference(alice, failed));
        assertThrows(NotFoundException.class, () -> history.getTransactionByReference(alice, pending));
        assertTrue(all(bob).isEmpty());
    }
    @Test void closedDepositAndWithdrawalAccountsAreHiddenRegardlessOfStatus() {
        for (var type : List.of(TransactionType.DEPOSIT, TransactionType.WITHDRAW)) {
            for (var state : List.of(TransactionStatus.COMPLETED, TransactionStatus.FAILED)) {
                operation(type, state, type == TransactionType.WITHDRAW ? source : null,
                        type == TransactionType.DEPOSIT ? source : null, alice, "20", LocalDateTime.now());
            }
        }
        status(source, AccountStatus.CLOSED);
        assertTrue(all(alice).isEmpty());
        for (var transaction : transactions.findAll()) {
            assertThrows(NotFoundException.class,
                    () -> history.getTransactionByReference(alice, transaction.getReference()));
        }
    }
    @Test void internalTransferIsReturnedOnceWithDirectionsRelativeToAccount() {
        var reference = operation(TransactionType.TRANSFER, TransactionStatus.COMPLETED,
                source, ownOther, alice, "10", LocalDateTime.now());
        assertEquals(1, all(alice).getTotalElements());
        assertEquals(TransactionDirection.INTERNAL, history.getTransactionByReference(alice, reference).direction());
        assertEquals(TransactionDirection.OUTGOING,
                history.getAccountTransactions(alice, SOURCE, null).getContent().getFirst().direction());
        assertEquals(TransactionDirection.INCOMING,
                history.getAccountTransactions(alice, OWN_OTHER, null).getContent().getFirst().direction());
        status(source, AccountStatus.CLOSED);
        assertEquals(Set.of(reference), references(all(alice)));
        assertEquals(TransactionDirection.INTERNAL, history.getTransactionByReference(alice, reference).direction());
        assertEquals(Set.of(reference), references(history.getAccountTransactions(alice, OWN_OTHER, null)));
    }
    @Test void failedInternalTransferCannotAppearAsIncomingInDestinationAccount() {
        var reference = operation(TransactionType.TRANSFER, TransactionStatus.FAILED,
                source, ownOther, alice, "10", LocalDateTime.now());
        assertEquals(TransactionDirection.OUTGOING, history.getTransactionByReference(alice, reference).direction());
        assertTrue(history.getAccountTransactions(alice, OWN_OTHER, null).isEmpty());
        assertEquals(Set.of(reference), references(history.getAccountTransactions(alice, SOURCE, null)));
        status(source, AccountStatus.CLOSED);
        assertTrue(all(alice).isEmpty());
    }
    @Test void unauthorizedOrMissingAccountsAndReferencesUseNotFound() {
        transfer(TransactionStatus.COMPLETED);
        for (String number : List.of(SOURCE, DESTINATION, "missing")) {
            assertThrows(NotFoundException.class, () -> history.getAccountTransactions(outsider, number, null));
        }
        assertThrows(NotFoundException.class, () -> history.getTransactionByReference(alice, "missing"));
        assertThrows(NotFoundException.class, () -> history.getTransactionByReference(alice, " "));
        assertThrows(NotFoundException.class, () -> history.getAccountTransactions(alice, null, null));
        status(source, AccountStatus.CLOSED);
        assertThrows(NotFoundException.class, () -> history.getAccountTransactions(bob, SOURCE, null));
    }
    @Test void nullSourceFailureIsInitiatorOnlyAndIndependentOfIdempotency() {
        var reference = operation(TransactionType.TRANSFER, TransactionStatus.FAILED,
                null, destination, alice, "10", LocalDateTime.now());
        assertEquals(0, idempotency.count());
        var response = history.getTransactionByReference(alice, reference);
        assertEquals(TransactionDirection.OUTGOING, response.direction());
        assertNull(response.sourceAccountNumber());
        assertNull(response.destinationAccountNumber());
        assertNull(response.currency());
        assertTrue(all(bob).isEmpty());
        assertTrue(history.getAccountTransactions(bob, DESTINATION, null).isEmpty());
        assertTrue(history.getAccountTransactions(alice, SOURCE, null).isEmpty());
        assertThrows(NotFoundException.class, () -> history.getTransactionByReference(bob, reference));
    }
    @Test void foreignSourceFailureDoesNotExposeUnauthorizedAccountsOrAccountHistory() {
        var reference = operation(TransactionType.TRANSFER, TransactionStatus.FAILED,
                destination, ownOther, alice, "10", LocalDateTime.now());
        var response = history.getTransactionByReference(alice, reference);
        assertNull(response.sourceAccountNumber());
        assertNull(response.destinationAccountNumber());
        assertNull(response.currency());
        assertTrue(all(bob).isEmpty());
        assertTrue(history.getAccountTransactions(alice, OWN_OTHER, null).isEmpty());
    }
    @Test void historicalUnknownInitiatorDoesNotGrantFailedTransferAccess() {
        var failed = operation(TransactionType.TRANSFER, TransactionStatus.FAILED,
                source, destination, null, "10", LocalDateTime.now());
        assertTrue(all(alice).isEmpty());
        assertTrue(all(bob).isEmpty());
        assertThrows(NotFoundException.class, () -> history.getTransactionByReference(alice, failed));
        var complete = operation(TransactionType.TRANSFER, TransactionStatus.COMPLETED,
                source, destination, null, "10", LocalDateTime.now());
        assertEquals(Set.of(complete), references(all(alice)));
        assertEquals(Set.of(complete), references(all(bob)));
    }
    @Test void counterpartyNumbersAreMaskedConsistentlyAndRawFailureTextNeverLeaks() {
        var reference = transfer(TransactionStatus.COMPLETED);
        var senderView = history.getTransactionByReference(alice, reference);
        var recipientView = history.getTransactionByReference(bob, reference);
        assertEquals(SOURCE, senderView.sourceAccountNumber());
        assertEquals("****2222", senderView.destinationAccountNumber());
        assertEquals("****1111", recipientView.sourceAccountNumber());
        assertEquals(DESTINATION, recipientView.destinationAccountNumber());
        var failed = history.getTransactionByReference(alice, transfer(TransactionStatus.FAILED));
        assertEquals("Insufficient funds", failed.failureMessage());
        assertFalse(failed.toString().contains("SQLState"));
        assertEquals("****2222", failed.destinationAccountNumber());
    }
    @Test void missingAccountsAndFailureDetailsAreHandledSafely() {
        var reference = operation(TransactionType.TRANSFER, TransactionStatus.FAILED,
                null, null, alice, "10", LocalDateTime.now());
        new JdbcTemplate(dataSource).update(
                "UPDATE transactions SET failureReason=NULL, completed_at=? WHERE reference=?",
                Instant.now(), reference);
        var response = history.getTransactionByReference(alice, reference);
        assertNotNull(response.createdAt());
        assertNull(response.completedAt());
        assertNull(response.currency());
        assertEquals("Transfer could not be completed", response.failureMessage());
    }
    void filterFixtures() {
        operation(TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, source, alice,
                "10", LocalDateTime.of(2026, 5, 10, 23, 59, 59));
        operation(TransactionType.WITHDRAW, TransactionStatus.FAILED, source, null, alice,
                "20", LocalDateTime.of(2026, 5, 11, 0, 0));
        operation(TransactionType.TRANSFER, TransactionStatus.COMPLETED, source, destination, alice,
                "30", LocalDateTime.of(2026, 5, 12, 23, 59, 59, 999_000_000));
        operation(TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, source, alice,
                "40", LocalDateTime.of(2026, 5, 13, 0, 0));
        operation(TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, destination, bob,
                "20", LocalDateTime.of(2026, 5, 11, 12, 0));
    }

    @Test void eachOptionalFilterWorksIndividually() {
        filterFixtures();
        var cases = List.of(
                filter(TransactionType.DEPOSIT, null, null, null, null, null),
                filter(null, TransactionStatus.FAILED, null, null, null, null),
                filter(null, null, LocalDate.of(2026, 5, 12), null, null, null),
                filter(null, null, null, LocalDate.of(2026, 5, 11), null, null),
                filter(null, null, null, null, "20", null),
                filter(null, null, null, null, null, "30"));
        var counts = List.of(2L, 1L, 2L, 2L, 3L, 3L);
        for (int i = 0; i < cases.size(); i++) {
            assertEquals(counts.get(i).longValue(), history.getUserTransactions(alice, cases.get(i)).getTotalElements());
        }
    }

    @Test void allFiltersCombineWithAndIncludingAccountScope() {
        filterFixtures();
        var filter = filter(TransactionType.TRANSFER, TransactionStatus.COMPLETED,
                LocalDate.of(2026, 5, 12), LocalDate.of(2026, 5, 12), "30", "30");
        assertEquals(1, history.getUserTransactions(alice, filter).getTotalElements());
        assertEquals(1, history.getAccountTransactions(alice, SOURCE, filter).getTotalElements());
        assertTrue(history.getAccountTransactions(alice, OWN_OTHER, filter).isEmpty());
    }

    @Test void filtersCannotBypassAuthorizationOrMakeFailedRecipientVisible() {
        transfer(TransactionStatus.FAILED);
        var filter = filter(TransactionType.TRANSFER, TransactionStatus.FAILED, null, null, "0", "100");
        assertTrue(history.getUserTransactions(bob, filter).isEmpty());
        assertTrue(history.getUserTransactions(outsider, filter).isEmpty());
        assertTrue(history.getAccountTransactions(bob, DESTINATION, filter).isEmpty());
        assertEquals(1, history.getUserTransactions(alice, filter).getTotalElements());
    }

    @Test void dateStartInclusiveAndNextDayExclusive() {
        filterFixtures();
        var filter = filter(null, null, LocalDate.of(2026, 5, 11), LocalDate.of(2026, 5, 12), null, null);
        var result = history.getUserTransactions(alice, filter);
        assertEquals(2, result.getTotalElements());
        assertEquals(Set.of(new BigDecimal("20.00"), new BigDecimal("30.00")),
                result.stream().map(TransactionResponseDTO::amount).collect(Collectors.toSet()));
    }

    @Test void amountBoundsAreInclusive() {
        filterFixtures();
        assertEquals(2, history.getUserTransactions(alice,
                filter(null, null, null, null, "20", "30")).getTotalElements());
        assertEquals(1, history.getUserTransactions(alice,
                filter(null, null, null, null, "20", "20")).getTotalElements());
    }

    @Test void emptyFiltersReturnDefaultPageAndNoMatchesReturnEmptyPage() {
        assertTrue(all(alice).isEmpty());
        var defaultPage = all(alice);
        assertEquals(0, defaultPage.getNumber());
        assertEquals(20, defaultPage.getSize());
        filterFixtures();
        assertTrue(history.getUserTransactions(alice,
                filter(null, null, null, null, "999", null)).isEmpty());
    }

    @Test void paginationAndAllowedSizeBoundariesWorkInDatabase() {
        filterFixtures();
        var first = history.getUserTransactions(alice, null, 0, 2, "createdAt", "DESC");
        var second = history.getUserTransactions(alice, null, 1, 2, "createdAt", "DESC");
        assertEquals(4, first.getTotalElements());
        assertEquals(2, first.getNumberOfElements());
        assertEquals(2, second.getNumberOfElements());
        assertTrue(Collections.disjoint(references(first), references(second)));
        assertEquals(4, history.getUserTransactions(alice, null, 0, 100, "createdAt", "DESC").getNumberOfElements());
        assertTrue(history.getUserTransactions(alice, null, 10, 1, "createdAt", "DESC").isEmpty());
        assertEquals(1, history.getAccountTransactions(alice, SOURCE, null, 0, 1, "amount", "ASC").getNumberOfElements());
    }

    @Test void invalidPageSizesAndNumbersAreRejected() {
        for (int size : List.of(-1, 0, 101, Integer.MAX_VALUE)) {
            assertThrows(BusinessException.class,
                    () -> history.getUserTransactions(alice, null, 0, size, null, null));
            assertThrows(BusinessException.class,
                    () -> history.getAccountTransactions(alice, SOURCE, null, 0, size, null, null));
        }
        assertThrows(BusinessException.class,
                () -> history.getUserTransactions(alice, null, -1, 20, null, null));
    }

    @Test void ascendingDescendingAndDeterministicTieBreakers() {
        LocalDateTime sameTime = LocalDateTime.of(2026, 5, 12, 12, 0);
        var first = operation(TransactionType.DEPOSIT, TransactionStatus.COMPLETED,
                null, source, alice, "20", sameTime);
        var second = operation(TransactionType.DEPOSIT, TransactionStatus.COMPLETED,
                null, source, alice, "20", sameTime);
        for (String sortBy : List.of("createdAt", "amount")) {
            var ascending = history.getUserTransactions(alice, null, 0, 20, sortBy, "ASC");
            var descending = history.getUserTransactions(alice, null, 0, 20, sortBy, "DESC");
            assertEquals(List.of(first, second), ascending.stream().map(TransactionResponseDTO::reference).toList());
            assertEquals(List.of(second, first), descending.stream().map(TransactionResponseDTO::reference).toList());
            assertEquals(second, history.getUserTransactions(alice, null, 0, 1, sortBy, "DESC")
                    .getContent().getFirst().reference());
            assertEquals(first, history.getUserTransactions(alice, null, 1, 1, sortBy, "DESC")
                    .getContent().getFirst().reference());
        }
        assertEquals(second, all(alice).getContent().getFirst().reference());
    }

    @Test void amountAndTimestampSortingUseRequestedPrimaryField() {
        filterFixtures();
        var ascending = history.getUserTransactions(alice, null, 0, 20, "amount", "asc");
        assertEquals(List.of("10.00", "20.00", "30.00", "40.00"),
                ascending.stream().map(t -> t.amount().toPlainString()).toList());
        var descending = history.getUserTransactions(alice, null, 0, 20, "createdAt", "DESC");
        assertEquals(List.of("40.00", "30.00", "20.00", "10.00"),
                descending.stream().map(t -> t.amount().toPlainString()).toList());
    }

    @Test void arbitrarySortFieldsAndDirectionsAreRejected() {
        for (String field : List.of("id", "sourceAccount.user.email", "reference", "createdAt DESC", "")) {
            assertThrows(BusinessException.class,
                    () -> history.getUserTransactions(alice, null, 0, 20, field, "DESC"));
        }
        for (String direction : List.of("", "sideways", "DESC, id")) {
            assertThrows(BusinessException.class,
                    () -> history.getUserTransactions(alice, null, 0, 20, "createdAt", direction));
        }
    }

    @Test void invalidDateAndAmountFiltersAreRejected() {
        var invalid = List.of(
                filter(null, null, LocalDate.of(2026, 5, 12), LocalDate.of(2026, 5, 11), null, null),
                filter(null, null, null, LocalDate.MAX, null, null),
                filter(null, null, null, null, "-1", null),
                filter(null, null, null, null, null, "-1"),
                filter(null, null, null, null, "20", "10"));
        for (var filter : invalid) {
            assertThrows(BusinessException.class, () -> history.getUserTransactions(alice, filter));
            assertThrows(BusinessException.class, () -> history.getAccountTransactions(alice, SOURCE, filter));
        }
        assertThrows(BusinessException.class, () -> history.getUserTransactions(null, null));
        assertThrows(BusinessException.class, () -> history.getTransactionByReference(0L, "ref"));
    }

    @Test void pageMappingHasBoundedQueryCountAndNoCollectionFetchPagination() {
        for (int i = 0; i < 8; i++) {
            transfer(TransactionStatus.COMPLETED);
        }
        var statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        var page = history.getUserTransactions(alice, null, 0, 5, "createdAt", "DESC");
        assertEquals(5, page.getNumberOfElements());
        assertEquals(8, page.getTotalElements());
        assertTrue(statistics.getPrepareStatementCount() <= 2,
                "Expected content and count queries, got " + statistics.getPrepareStatementCount());
        statistics.clear();
        history.getTransactionByReference(bob, page.getContent().getFirst().reference());
        assertEquals(1, statistics.getPrepareStatementCount());
        statistics.clear();
        var accountPage = history.getAccountTransactions(alice, SOURCE, null, 0, 5, "createdAt", "DESC");
        assertEquals(8, accountPage.getTotalElements());
        assertTrue(statistics.getPrepareStatementCount() <= 3,
                "Expected ownership lookup, content and count queries");
    }

    @Test void allExecutionPathsPersistTrustedInitiator() {
        execution.deposit(SOURCE, new BigDecimal("10"), alice);
        execution.withdraw(SOURCE, new BigDecimal("10"), alice);
        execution.transfer(SOURCE, DESTINATION, new BigDecimal("10"), alice, "success");
        assertThrows(BusinessException.class, () ->
                execution.transfer("missing", DESTINATION, new BigDecimal("10"), alice, "missing-source"));
        status(source, AccountStatus.SUSPENDED);
        assertThrows(AccountSuspendedException.class, () -> execution.deposit(SOURCE, new BigDecimal("10"), alice));
        assertThrows(AccountSuspendedException.class, () -> execution.withdraw(SOURCE, new BigDecimal("10"), alice));
        assertThrows(AccountSuspendedException.class, () ->
                execution.transfer(SOURCE, DESTINATION, new BigDecimal("10"), alice, "suspended"));
        tx(ignored -> {
            assertEquals(7, transactions.count());
            for (var transaction : transactions.findAll()) {
                assertEquals(alice, transaction.getInitiatedBy().getId());
            }
        });
        assertEquals(7, all(alice).getTotalElements());
        assertEquals(1, all(bob).getTotalElements());
    }

    @Test void unauthorizedSourceExecutionDoesNotLeakAccountsAndInitiatorSurvivesCleanup() {
        assertThrows(BusinessException.class, () ->
                execution.transfer(DESTINATION, SOURCE, new BigDecimal("10"), alice, "unauthorized"));
        tx(ignored -> idempotency.deleteAllInBatch());
        var response = all(alice).getContent().getFirst();
        assertNull(response.sourceAccountNumber());
        assertNull(response.destinationAccountNumber());
        assertTrue(all(bob).isEmpty());
        assertTrue(history.getAccountTransactions(alice, SOURCE, null).isEmpty());
        assertEquals(1, transactions.count());
    }

    @Test void suspendedDepositAndWithdrawalHistoryAndPendingAccountScope() {
        for (var type : List.of(TransactionType.DEPOSIT, TransactionType.WITHDRAW)) {
            for (var state : List.of(TransactionStatus.COMPLETED, TransactionStatus.FAILED)) {
                operation(type, state, type == TransactionType.WITHDRAW ? source : null,
                        type == TransactionType.DEPOSIT ? source : null, alice, "20", LocalDateTime.now());
            }
        }
        status(source, AccountStatus.SUSPENDED);
        assertEquals(4, history.getAccountTransactions(alice, SOURCE, null).getTotalElements());
        assertEquals(4, all(alice).getTotalElements());
        String pending = transfer(TransactionStatus.PENDING);
        assertTrue(history.getAccountTransactions(bob, DESTINATION, null).isEmpty());
        assertTrue(references(history.getAccountTransactions(alice, SOURCE, null)).contains(pending));
    }

    @Test void untrustedAttemptDoesNotExposeForeignAccountFailureDetails() {
        String reference = operation(TransactionType.TRANSFER, TransactionStatus.FAILED,
                destination, source, alice, "10", LocalDateTime.now());
        var response = history.getTransactionByReference(alice, reference);
        assertNull(response.failureReason());
        assertEquals("Transfer could not be completed", response.failureMessage());
    }

    @Test void reliableMigrationBackfillIsIdempotentAndNeverGuessesAccountOwnership() throws Exception {
        var linked = execution.transfer(SOURCE, DESTINATION, new BigDecimal("10"), alice, "evidence");
        var unknownTransfer = operation(TransactionType.TRANSFER, TransactionStatus.FAILED,
                source, destination, null, "10", LocalDateTime.now());
        var unknownDeposit = operation(TransactionType.DEPOSIT, TransactionStatus.COMPLETED,
                null, source, null, "10", LocalDateTime.now());
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.update("UPDATE transactions SET initiated_by_id=NULL WHERE reference=?", linked.getReference());
        String migration = java.nio.file.Files.readString(java.nio.file.Path.of("database/migrate-transaction-history.sql"));
        String sql = migration.substring(migration.indexOf("-- BEGIN RELIABLE BACKFILL")
                        + "-- BEGIN RELIABLE BACKFILL".length(), migration.indexOf("-- END RELIABLE BACKFILL")).trim();
        jdbc.execute(sql);
        jdbc.execute(sql);
        assertEquals(alice, jdbc.queryForObject(
                "SELECT initiated_by_id FROM transactions WHERE reference=?", Long.class, linked.getReference()));
        assertNull(jdbc.queryForObject(
                "SELECT initiated_by_id FROM transactions WHERE reference=?", Long.class, unknownTransfer));
        assertNull(jdbc.queryForObject(
                "SELECT initiated_by_id FROM transactions WHERE reference=?", Long.class, unknownDeposit));
        // Even a conflicting pre-existing attribution is not overwritten automatically.
        jdbc.update("UPDATE transactions SET initiated_by_id=? WHERE reference=?", bob, linked.getReference());
        jdbc.execute(sql);
        assertEquals(bob, jdbc.queryForObject(
                "SELECT initiated_by_id FROM transactions WHERE reference=?", Long.class, linked.getReference()));
    }

    @Test void withdrawalInsufficientFundsFailureIsAttributedAndDoesNotMoveMoney() {
        tx(ignored -> accounts.findById(source).orElseThrow().setBalance(new BigDecimal("5")));
        assertThrows(InsufficientFundsException.class,
                () -> execution.withdraw(SOURCE, new BigDecimal("10"), alice));
        var response = all(alice).getContent().getFirst();
        assertEquals(TransactionType.WITHDRAW, response.type());
        assertEquals(TransactionStatus.FAILED, response.status());
        assertEquals(FailureReason.INSUFFICIENT_FUNDS, response.failureReason());
        assertEquals(0, accounts.findById(source).orElseThrow().getBalance().compareTo(new BigDecimal("5")));
        tx(ignored -> assertEquals(alice, transactions.findAll().getFirst().getInitiatedBy().getId()));
    }

    @Test void withdrawalDailyLimitStillRecordsFailureWithoutAdditionalDebit() {
        execution.withdraw(SOURCE, new BigDecimal("300"), alice);
        assertThrows(DailyWithdrawalLimitExceededException.class,
                () -> execution.withdraw(SOURCE, new BigDecimal("300"), alice));
        assertEquals(0, accounts.findById(source).orElseThrow().getBalance().compareTo(new BigDecimal("700")));
        assertEquals(2, all(alice).getTotalElements());
        assertEquals(1, all(alice).stream()
                .filter(t -> t.failureReason() == FailureReason.DAILY_LIMIT_EXCEEDED).count());
    }

    @Test void closedAccountExecutionFailuresRemainStoredButInvisible() {
        status(source, AccountStatus.CLOSED);
        assertThrows(AccountClosedException.class, () -> execution.deposit(SOURCE, new BigDecimal("10"), alice));
        assertThrows(AccountClosedException.class, () -> execution.withdraw(SOURCE, new BigDecimal("10"), alice));
        assertEquals(2, transactions.count());
        assertTrue(all(alice).isEmpty());
        assertTrue(all(bob).isEmpty());
        tx(ignored -> transactions.findAll().forEach(t -> assertEquals(alice, t.getInitiatedBy().getId())));
        assertThrows(AccountClosedException.class, () -> history.getAccountTransactions(alice, SOURCE, null));
    }

    @Test void unauthorizedDepositAndWithdrawalDoNotCreateTransactionsOrExposeHistory() {
        assertThrows(NotFoundException.class, () -> execution.deposit(SOURCE, new BigDecimal("10"), bob));
        assertThrows(NotFoundException.class, () -> execution.withdraw(SOURCE, new BigDecimal("10"), bob));
        assertEquals(0, transactions.count());
        assertEquals(0, accounts.findById(source).orElseThrow().getBalance().compareTo(new BigDecimal("1000")));
        assertTrue(all(bob).isEmpty());
    }

    @Test void invalidDepositAndWithdrawalAmountsPreserveOriginalValidation() {
        for (var amount : List.of(new BigDecimal("-1"), new BigDecimal("0.001"), new BigDecimal("10001"))) {
            assertThrows(InvalidAmountException.class, () -> execution.deposit(SOURCE, amount, alice));
            assertThrows(BusinessException.class, () -> execution.withdraw(SOURCE, amount, alice));
        }
        assertEquals(0, transactions.count());
        assertEquals(0, accounts.findById(source).orElseThrow().getBalance().compareTo(new BigDecimal("1000")));
    }

    @Test void internalTransferWithBothAccountsClosedIsHiddenEverywhere() {
        String reference = operation(TransactionType.TRANSFER, TransactionStatus.COMPLETED,
                source, ownOther, alice, "10", LocalDateTime.now());
        status(source, AccountStatus.CLOSED);
        status(ownOther, AccountStatus.CLOSED);
        assertTrue(all(alice).isEmpty());
        assertThrows(NotFoundException.class, () -> history.getTransactionByReference(alice, reference));
        assertEquals(1, transactions.count());
    }
}