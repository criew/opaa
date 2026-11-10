package io.opaa.agent;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** The single row of {@code agent_settings}. */
interface AgentSettingsRepository extends JpaRepository<AgentSettings, Integer> {

  default Optional<AgentSettings> findSingleton() {
    return findById(AgentSettings.SINGLETON_ID);
  }
}
