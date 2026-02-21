package io.opaa.indexing;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IndexingJobRepository extends JpaRepository<IndexingJob, UUID> {

  Optional<IndexingJob> findTopByOrderByStartedAtDesc();
}
