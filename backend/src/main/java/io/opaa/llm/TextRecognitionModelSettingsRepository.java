package io.opaa.llm;

import org.springframework.stereotype.Repository;

/** Persistence for the single {@link TextRecognitionModelSettings} row. */
@Repository
public interface TextRecognitionModelSettingsRepository
    extends OptionalRoleSettingsRepository<TextRecognitionModelSettings> {}
