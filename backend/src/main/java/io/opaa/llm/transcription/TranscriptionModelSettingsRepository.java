package io.opaa.llm.transcription;

import io.opaa.llm.OptionalRoleSettingsRepository;
import org.springframework.stereotype.Repository;

/** Persistence for the single {@link TranscriptionModelSettings} row. */
@Repository
public interface TranscriptionModelSettingsRepository
    extends OptionalRoleSettingsRepository<TranscriptionModelSettings> {}
