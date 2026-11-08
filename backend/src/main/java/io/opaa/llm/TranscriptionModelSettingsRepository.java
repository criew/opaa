package io.opaa.llm;

import org.springframework.stereotype.Repository;

/** Persistence for the single {@link TranscriptionModelSettings} row. */
@Repository
public interface TranscriptionModelSettingsRepository
    extends OptionalRoleSettingsRepository<TranscriptionModelSettings> {}
