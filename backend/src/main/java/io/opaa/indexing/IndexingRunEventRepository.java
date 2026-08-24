package io.opaa.indexing;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IndexingRunEventRepository extends JpaRepository<IndexingRunEvent, UUID> {

  /** A single run's protocol, oldest first - the order events actually happened in. */
  List<IndexingRunEvent> findByJobIdOrderByCreatedAtAsc(UUID jobId);
}
