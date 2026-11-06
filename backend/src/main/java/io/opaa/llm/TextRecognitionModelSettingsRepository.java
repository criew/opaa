package io.opaa.llm;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for the single {@link TextRecognitionModelSettings} row. */
@Repository
public interface TextRecognitionModelSettingsRepository
    extends JpaRepository<TextRecognitionModelSettings, Short> {

  default Optional<TextRecognitionModelSettings> findStored() {
    return findById(TextRecognitionModelSettings.SINGLETON_ID);
  }
}
