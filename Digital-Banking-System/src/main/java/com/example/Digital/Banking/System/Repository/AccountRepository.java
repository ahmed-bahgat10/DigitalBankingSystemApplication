package com.example.Digital.Banking.System.Repository;

import com.example.Digital.Banking.System.Entity.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account,Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT a FROM Account a
        WHERE a.user.id = :userId
        AND a.accountNumber = :accountNumber
        """)
    Optional<Account> findByUserIdAndAccountNumberForUpdate(
            @Param("userId") Long userId,
            @Param("accountNumber") String accountNumber
    );
    Account findByAccountNumber(String accountNumber);
    Account findByUserId(long id);
    Optional<Account> findByUserIdAndAccountNumber(Long userId, String accountNumber);
    List<Account> findAllByUserId(Long userId);
}
