package io.opaa.llm;

import org.springframework.stereotype.Repository;

/** Persistence for the single {@link RerankModelSettings} row. */
@Repository
public interface RerankModelSettingsRepository
    extends OptionalRoleSettingsRepository<RerankModelSettings> {}
