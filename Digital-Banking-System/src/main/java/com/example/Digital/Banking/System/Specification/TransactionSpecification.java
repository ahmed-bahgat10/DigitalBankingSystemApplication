package com.example.Digital.Banking.System.Specification;

import com.example.Digital.Banking.System.DTO.TransactionFilterDTO;
import com.example.Digital.Banking.System.Entity.Account;
import com.example.Digital.Banking.System.Entity.Transaction;
import com.example.Digital.Banking.System.Entity.User;
import com.example.Digital.Banking.System.Enum.AccountStatus;
import com.example.Digital.Banking.System.Enum.TransactionStatus;
import com.example.Digital.Banking.System.Enum.TransactionType;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;

/** All history entry points must AND optional filters with visibleTo. */
public final class TransactionSpecification {
    private TransactionSpecification() {}

    public static Specification<Transaction> visibleTo(Long userId, Long accountId) {
        return (root, query, cb) -> {
            Join<Transaction, Account> source = root.join("sourceAccount", JoinType.LEFT);
            Join<Transaction, Account> destination = root.join("destinationAccount", JoinType.LEFT);
            Join<Account, User> sourceOwner = source.join("user", JoinType.LEFT);
            Join<Account, User> destinationOwner = destination.join("user", JoinType.LEFT);
            Join<Transaction, User> initiator = root.join("initiatedBy", JoinType.LEFT);

            var sourceOwned = cb.equal(sourceOwner.get("id"), userId);
            var destinationOwned = cb.equal(destinationOwner.get("id"), userId);
            var sourceEligible = cb.and(sourceOwned,
                    source.get("status").in(AccountStatus.ACTIVE, AccountStatus.SUSPENDED));
            var destinationEligible = cb.and(destinationOwned,
                    destination.get("status").in(AccountStatus.ACTIVE, AccountStatus.SUSPENDED));

            // Account scope is applied to the authorized side of each operation.
            if (accountId != null) {
                sourceEligible = cb.and(sourceEligible, cb.equal(source.get("id"), accountId));
                destinationEligible = cb.and(destinationEligible, cb.equal(destination.get("id"), accountId));
            }

            var deposit = cb.and(cb.equal(root.get("type"), TransactionType.DEPOSIT), destinationEligible);
            var withdrawal = cb.and(cb.equal(root.get("type"), TransactionType.WITHDRAW), sourceEligible);
            var completedTransfer = cb.and(
                    cb.equal(root.get("status"), TransactionStatus.COMPLETED),
                    cb.or(sourceEligible, destinationEligible));

            var attemptSourceVisible = sourceEligible;
            if (accountId == null) {
                // An unattributed or foreign source cannot grant account-scoped visibility.
                // An owned CLOSED source is deliberately excluded from this fallback.
                var noAuthorizedSource = cb.or(cb.isNull(source.get("id")),
                        cb.isNull(sourceOwner.get("id")), cb.notEqual(sourceOwner.get("id"), userId));
                attemptSourceVisible = cb.or(sourceEligible, noAuthorizedSource);
            }
            var attemptedTransfer = cb.and(
                    root.get("status").in(TransactionStatus.FAILED, TransactionStatus.PENDING),
                    cb.equal(initiator.get("id"), userId),
                    attemptSourceVisible);
            var transfer = cb.and(cb.equal(root.get("type"), TransactionType.TRANSFER),
                    cb.or(completedTransfer, attemptedTransfer));
            // All joins are to-one: no duplicate rows and no DISTINCT/collection pagination.
            return cb.or(deposit, withdrawal, transfer);
        };
    }

    public static Specification<Transaction> matching(TransactionFilterDTO filter) {
        return (root, query, cb) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            if (filter.type() != null) {
                predicates.add(cb.equal(root.get("type"), filter.type()));
            }
            if (filter.status() != null) {
                predicates.add(cb.equal(root.get("status"), filter.status()));
            }
            if (filter.fromDate() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), filter.fromDate().atStartOfDay()));
            }
            if (filter.toDate() != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), filter.toDate().plusDays(1).atStartOfDay()));
            }
            if (filter.minAmount() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("amount"), filter.minAmount()));
            }
            if (filter.maxAmount() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("amount"), filter.maxAmount()));
            }
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }

    public static Specification<Transaction> withReference(String reference) {
        return (root, query, cb) -> cb.equal(root.get("reference"), reference);
    }
}
