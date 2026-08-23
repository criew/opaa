package io.opaa.llm;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for the singleton {@link LlmModelSeedMarker} row (#756). */
@Repository
public interface LlmModelSeedMarkerRepository extends JpaRepository<LlmModelSeedMarker, Integer> {

  /** Whether {@link LlmModelSeeder} has already attempted the one-time takeover. */
  default boolean seedAlreadyAttempted() {
    return existsById(LlmModelSeedMarker.SINGLETON_ID);
  }
}
