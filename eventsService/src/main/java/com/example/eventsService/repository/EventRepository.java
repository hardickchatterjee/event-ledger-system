package com.example.eventsService.repository;

import com.example.eventsService.model.EventRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface EventRepository extends JpaRepository<EventRecord, String> {
    List<EventRecord> findByAccountIdOrderByEventTimestampAsc(String accountId);
}
