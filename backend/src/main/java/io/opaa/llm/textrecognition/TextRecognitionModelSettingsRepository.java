package io.opaa.llm.textrecognition;

import io.opaa.llm.OptionalRoleSettingsRepository;
import org.springframework.stereotype.Repository;

/** Persistence for the single {@link TextRecognitionModelSettings} row. */
@Repository
public interface TextRecognitionModelSettingsRepository
    extends OptionalRoleSettingsRepository<TextRecognitionModelSettings> {}
