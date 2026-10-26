package io.opaa.llm;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for the single {@link TranscriptionModelSettings} row. */
@Repository
public interface TranscriptionModelSettingsRepository
    extends JpaRepository<TranscriptionModelSettings, Short> {

  default Optional<TranscriptionModelSettings> findStored() {
    return findById(TranscriptionModelSettings.SINGLETON_ID);
  }
}
