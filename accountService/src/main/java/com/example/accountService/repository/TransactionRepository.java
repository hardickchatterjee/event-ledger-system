package com.example.accountService.repository;

import com.example.accountService.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    boolean existsByEventId(String eventId);

    Optional<Transaction> findByEventId(String eventId);

    List<Transaction> findByAccountIdOrderByAppliedAtDesc(String accountId);

    long countByAccountId(String accountId);

    @Query("SELECT COALESCE(SUM(CASE WHEN t.type = 'CREDIT' THEN t.amount ELSE -t.amount END), 0) " +
           "FROM Transaction t WHERE t.accountId = :accountId")
    BigDecimal computeBalance(@Param("accountId") String accountId);
}
