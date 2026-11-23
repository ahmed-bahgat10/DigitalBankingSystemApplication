package com.example.Digital.Banking.System.Service;

import com.example.Digital.Banking.System.Entity.*;
import com.example.Digital.Banking.System.Enum.*;
import com.example.Digital.Banking.System.Exception.*;
import com.example.Digital.Banking.System.Repository.*;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(TransferIdempotencyTest.Config.class)
class TransferIdempotencyTest {
    @Configuration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = AccountRepository.class)
    @Import({TransactionService.class, TransferExecutionService.class, IdempotencyService.class,
            FailedTransactionService.class, WithdrawalExecutionService.class, DepositExecutionService.class})
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource(
                    "jdbc:h2:mem:idempotency;MODE=MySQL;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
                    "sa", "");
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(ds);
            factory.setPackagesToScan(Account.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
            return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
            return new JpaTransactionManager(emf);
        }
        @Bean TransferProperties transferProperties() {
            var p = new TransferProperties();
            p.setMinAmount(new BigDecimal("1"));
            p.setMaxAmount(new BigDecimal("10000"));
            p.setDailyLimit(new BigDecimal("500"));
            return p;
        }
        @Bean DepositProperties depositProperties() { return new DepositProperties(); }
        @Bean WithdrawalProperties withdrawalProperties() { return new WithdrawalProperties(); }
        @Bean IdempotencyProperties idempotencyProperties() { return new IdempotencyProperties(); }
    }

    @Autowired TransactionService service;
    @Autowired AccountRepository accounts;
    @Autowired UserRepository users;
    @Autowired TransactionRepository transactions;
    @Autowired IdempotencyRecordRepository records;
    @Autowired PlatformTransactionManager manager;
    @Autowired DataSource dataSource;
    Long userId;
    Long sourceId;
    Long destinationId;

    @BeforeEach void setup() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            records.deleteAllInBatch();
            transactions.deleteAllInBatch();
            accounts.deleteAllInBatch();
            users.deleteAllInBatch();
            var user = new User();
            user.setName("Test");
            user.setEmail("test@example.com");
            user.setPassword("test");
            user.setPhone("123");
            users.saveAndFlush(user);
            userId = user.getId();
            sourceId = account("SOURCE", "1000", user).getId();
            destinationId = account("DEST", "0", user).getId();
        });
    }

    Account account(String number, String balance, User user) {
        var account = new Account();
        account.setAccountNumber(number);
        account.setBalance(new BigDecimal(balance));
        account.setCurrency(com.example.Digital.Banking.System.Enum.Currency.EGP);
        account.setStatus(AccountStatus.ACTIVE);
        account.setAccountType(AccountType.CURRENT);
        account.setUser(user);
        return accounts.saveAndFlush(account);
    }

    com.example.Digital.Banking.System.Entity.Transaction transfer(String key, String amount) {
        return service.transfer("SOURCE", "DEST", new BigDecimal(amount), userId, key);
    }

    void balance(Long id, String expected) {
        assertEquals(0, accounts.findById(id).orElseThrow().getBalance().compareTo(new BigDecimal(expected)));
    }

    @Test void completedRequestReplaysWithoutMovingMoneyAgain() {
        var first = transfer("key", "100");
        var replay = service.transfer(" SOURCE ", " DEST ", new BigDecimal("100.00"), userId, "key");
        assertEquals(first.getId(), replay.getId());
        assertEquals(1, transactions.count());
        assertEquals(1, records.count());
        balance(sourceId, "900");
        balance(destinationId, "100");
    }

    @Test void changedPayloadCannotReuseKey() {
        transfer("key", "100");
        assertThrows(BusinessException.class, () -> transfer("key", "101"));
        balance(sourceId, "900");
        assertEquals(1, transactions.count());
    }

    @Test void businessFailureCommitsAndReplaysAfterAccountChanges() {
        new TransactionTemplate(manager).executeWithoutResult(status ->
                accounts.findById(sourceId).orElseThrow().setBalance(new BigDecimal("50")));
        assertThrows(InsufficientFundsException.class, () -> transfer("key", "100"));
        assertEquals(TransactionStatus.FAILED, transactions.findAll().getFirst().getStatus());
        assertEquals(IdempotencyStatus.FAILED, records.findAll().getFirst().getStatus());
        new TransactionTemplate(manager).executeWithoutResult(status ->
                accounts.findById(sourceId).orElseThrow().setBalance(new BigDecimal("1000")));
        assertThrows(InsufficientFundsException.class, () -> transfer("key", "100"));
        assertEquals(1, transactions.count());
        balance(sourceId, "1000");
        balance(destinationId, "0");
    }

    @Test void invalidTransferIsSavedAndReplayedWithSameMessage() {
        var first = assertThrows(BusinessException.class, () ->
                service.transfer("SOURCE", "SOURCE", new BigDecimal("10"), userId, "bad"));
        var replay = assertThrows(BusinessException.class, () ->
                service.transfer("SOURCE", "SOURCE", new BigDecimal("10"), userId, "bad"));
        assertEquals(first.getMessage(), replay.getMessage());
        assertEquals(1, transactions.count());
        assertEquals(FailureReason.INVALID_TRANSFER, records.findAll().getFirst().getFailureReason());
        balance(sourceId, "1000");
    }

    @Test void technicalFailureRollsBackBalancesAndKeyAndAllowsRetry() {
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("ALTER TABLE transactions ADD CONSTRAINT test_reject CHECK (amount <> 100)");
        try {
            assertThrows(RuntimeException.class, () -> transfer("technical", "100"));
            assertEquals(0, records.count());
            assertEquals(0, transactions.count());
            balance(sourceId, "1000");
            balance(destinationId, "0");
        } finally {
            jdbc.execute("ALTER TABLE transactions DROP CONSTRAINT test_reject");
        }
        transfer("technical", "100");
        balance(sourceId, "900");
        assertEquals(1, transactions.count());
    }

    @Test void concurrentDuplicatesMoveMoneyOnce() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Long> request = () -> {
                start.await();
                return transfer("concurrent", "100").getId();
            };
            var first = pool.submit(request);
            var second = pool.submit(request);
            start.countDown();
            assertEquals(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        }
        balance(sourceId, "900");
        balance(destinationId, "100");
        assertEquals(1, transactions.count());
        assertEquals(1, records.count());
    }

    @Test void concurrentDistinctKeysRespectDailyLimit() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> firstRequest = () -> attempt(start, "one");
            Callable<Boolean> secondRequest = () -> attempt(start, "two");
            var first = pool.submit(firstRequest);
            var second = pool.submit(secondRequest);
            start.countDown();
            assertNotEquals(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        }
        balance(sourceId, "700");
        balance(destinationId, "300");
        assertEquals(2, records.count());
        assertEquals(1, transactions.findAll().stream()
                .filter(t -> t.getStatus() == TransactionStatus.COMPLETED).count());
    }

    boolean attempt(CountDownLatch start, String key) throws InterruptedException {
        start.await();
        try {
            transfer(key, "300");
            return true;
        } catch (DailyTransferLimitExceededException expected) {
            return false;
        }
    }

    @Test void keysAreScopedToUser() {
        transfer("shared", "100");
        Long secondUser = new TransactionTemplate(manager).execute(status -> {
            var user = new User();
            user.setName("Other");
            user.setEmail("other@example.com");
            user.setPassword("test");
            user.setPhone("456");
            users.saveAndFlush(user);
            account("OTHER", "1000", user);
            return user.getId();
        });
        service.transfer("OTHER", "DEST", new BigDecimal("100"), secondUser, "shared");
        assertEquals(2, records.count());
        balance(destinationId, "200");
    }
}