package io.opaa.query.toolcheck;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The tool checks. Every write after the insert is a conditional update on {@code status =
 * 'RUNNING'}: a check another instance already gave up as aborted is never completed afterwards.
 * Points in time come from the database clock (ADR-0053, Entscheidung 9).
 */
public interface ToolCheckRepository extends JpaRepository<ToolCheck, UUID> {

  Optional<ToolCheck> findFirstByModelIdOrderByStartedAtDesc(UUID modelId);

  List<ToolCheck> findByModelIdOrderByStartedAtDesc(UUID modelId);

  /** Gives up every running check whose heartbeat is older than {@code staleSeconds}. */
  @Modifying
  @Query(
      value =
          "UPDATE llm_tool_checks SET status = 'ABORTED', finished_at = clock_timestamp()"
              + " WHERE status = 'RUNNING'"
              + " AND heartbeat_at < clock_timestamp() - make_interval(secs => :staleSeconds)",
      nativeQuery = true)
  int abortStale(@Param("staleSeconds") long staleSeconds);

  @Modifying
  @Query(
      value =
          "UPDATE llm_tool_checks SET heartbeat_at = clock_timestamp()"
              + " WHERE id = :id AND status = 'RUNNING'",
      nativeQuery = true)
  int beat(@Param("id") UUID id);

  @Modifying
  @Query(
      value =
          "UPDATE llm_tool_checks SET completed_runs = :completedRuns,"
              + " heartbeat_at = clock_timestamp() WHERE id = :id AND status = 'RUNNING'",
      nativeQuery = true)
  int recordProgress(@Param("id") UUID id, @Param("completedRuns") int completedRuns);

  @Modifying
  @Query(
      value =
          "UPDATE llm_tool_checks SET status = 'COMPLETED', finished_at = clock_timestamp(),"
              + " heartbeat_at = clock_timestamp(), completed_runs = total_runs, run_id = :runId,"
              + " report = CAST(:report AS jsonb), report_markdown = :markdown"
              + " WHERE id = :id AND status = 'RUNNING'",
      nativeQuery = true)
  int complete(
      @Param("id") UUID id,
      @Param("runId") String runId,
      @Param("report") String report,
      @Param("markdown") String markdown);

  @Modifying
  @Query(
      value =
          "UPDATE llm_tool_checks SET status = 'FAILED', finished_at = clock_timestamp(),"
              + " heartbeat_at = clock_timestamp(), failure = :failure"
              + " WHERE id = :id AND status = 'RUNNING'",
      nativeQuery = true)
  int fail(@Param("id") UUID id, @Param("failure") String failure);
}
