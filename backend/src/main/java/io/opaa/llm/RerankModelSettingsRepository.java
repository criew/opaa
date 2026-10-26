package io.opaa.llm;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for the single {@link RerankModelSettings} row. */
@Repository
public interface RerankModelSettingsRepository extends JpaRepository<RerankModelSettings, Short> {

  default Optional<RerankModelSettings> findStored() {
    return findById(RerankModelSettings.SINGLETON_ID);
  }
}
