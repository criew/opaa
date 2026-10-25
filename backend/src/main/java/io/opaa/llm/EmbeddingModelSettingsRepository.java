package io.opaa.llm;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for the single {@link EmbeddingModelSettings} row. */
@Repository
public interface EmbeddingModelSettingsRepository
    extends JpaRepository<EmbeddingModelSettings, Short> {

  default Optional<EmbeddingModelSettings> findInstalled() {
    return findById(EmbeddingModelSettings.SINGLETON_ID);
  }
}
