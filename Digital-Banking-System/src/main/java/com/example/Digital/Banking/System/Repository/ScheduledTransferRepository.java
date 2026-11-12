package com.example.Digital.Banking.System.Repository;

import com.example.Digital.Banking.System.Entity.ScheduledTransfer;
import com.example.Digital.Banking.System.Enum.ScheduledTransferStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ScheduledTransferRepository extends JpaRepository<ScheduledTransfer,Long> {
    @Query("""
    SELECT st FROM ScheduledTransfer st
    WHERE (st.sourceAccount.id = :accountId
        OR st.destinationAccount.id = :accountId)
    AND st.status IN :statuses
""")
    List<ScheduledTransfer> findByAccountIdAndStatuses(
            @Param("accountId") Long accountId,
            @Param("statuses") Collection<ScheduledTransferStatus> statuses
    );
}
