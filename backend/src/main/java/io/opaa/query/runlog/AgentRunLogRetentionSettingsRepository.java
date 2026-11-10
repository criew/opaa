package io.opaa.query.runlog;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The singleton retention row plus the entry into {@code opaa_agent_run_logs_maintain()}, the only
 * call that can remove anything from the step log or the sandbox log - parameterless, so no caller
 * can narrow or widen what a run removes.
 */
interface AgentRunLogRetentionSettingsRepository
    extends JpaRepository<AgentRunLogRetentionSettings, Integer> {

  default Optional<AgentRunLogRetentionSettings> findSingleton() {
    return findById(AgentRunLogRetentionSettings.SINGLETON_ID);
  }

  /** Touches only the columns the application account may update. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          "UPDATE agent_run_log_retention_settings SET step_log_retention_days = :stepDays,"
              + " sandbox_log_retention_days = :sandboxDays, updated_at = now() WHERE id = 1",
      nativeQuery = true)
  int updateRetentionDays(@Param("stepDays") int stepDays, @Param("sandboxDays") int sandboxDays);

  /** Creates the coming day partitions, drops the expired ones and returns their names. */
  @Query(value = "SELECT * FROM opaa_agent_run_logs_maintain()", nativeQuery = true)
  List<String> maintainPartitions();
}
