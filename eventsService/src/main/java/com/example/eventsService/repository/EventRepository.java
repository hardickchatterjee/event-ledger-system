package com.example.eventsService.repository;

import com.example.eventsService.model.EventRecord;
import com.example.eventsService.model.EventStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface EventRepository extends JpaRepository<EventRecord, String> {
    List<EventRecord> findByAccountIdOrderByEventTimestampAsc(String accountId);

    @Query("SELECT e FROM EventRecord e WHERE e.status = :status AND e.retryCount < :maxRetries ORDER BY e.lastRetryTime ASC NULLS FIRST")
    List<EventRecord> findRetryableEvents(@Param("status") EventStatus status, @Param("maxRetries") Integer maxRetries);
}
