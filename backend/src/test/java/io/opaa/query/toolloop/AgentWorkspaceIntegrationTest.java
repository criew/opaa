package io.opaa.query.toolloop;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.TwoInstanceRace;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * The workspace with two instances against one database (ADR-0070, Entscheidung 6; ADR-0053): the
 * capacity holds for both together, and a place whose deadline has passed is free again even when
 * its instance never released it.
 */
@OpaaIntegrationTest
class AgentWorkspaceIntegrationTest {

  private static final int CAPACITY = 2;

  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  @AfterEach
  void clear() {
    jdbcTemplate.update("DELETE FROM agent_run_reservations");
  }

  @Test
  void twoInstancesRacingForTheLastPlaceTakeItOnce() throws Exception {
    AgentWorkspace instanceA = workspace();
    AgentWorkspace instanceB = workspace();
    assertThat(instanceA.tryReserve(UUID.randomUUID())).isTrue();
    List<Boolean> outcomes = new CopyOnWriteArrayList<>();

    new TwoInstanceRace(dataSource)
        .run(
            () -> outcomes.add(instanceA.tryReserve(UUID.randomUUID())),
            () -> outcomes.add(instanceB.tryReserve(UUID.randomUUID())),
            "SELECT id FROM agent_workspace WHERE id = ? FOR UPDATE",
            1);

    assertThat(outcomes).containsExactlyInAnyOrder(true, false);
    assertThat(instanceB.occupied()).isEqualTo(CAPACITY);
  }

  @Test
  void anExpiredPlaceIsFreeAgainWithoutARelease() {
    AgentWorkspace workspace = workspace();
    UUID crashed = UUID.randomUUID();
    assertThat(workspace.tryReserve(crashed)).isTrue();
    assertThat(workspace.tryReserve(UUID.randomUUID())).isTrue();
    assertThat(workspace.tryReserve(UUID.randomUUID())).isFalse();

    jdbcTemplate.update(
        "UPDATE agent_run_reservations SET expires_at = clock_timestamp() - interval '1 second'"
            + " WHERE run_id = ?",
        crashed);

    assertThat(workspace.occupied()).isEqualTo(1);
    assertThat(workspace.tryReserve(UUID.randomUUID())).isTrue();
  }

  @Test
  void aStopAskedOnOneInstanceIsSeenByTheInstanceRunningTheRun() {
    AgentWorkspace running = workspace();
    AgentWorkspace other = workspace();
    UUID run = UUID.randomUUID();
    UUID neighbour = UUID.randomUUID();
    running.tryReserve(run);
    running.tryReserve(neighbour);
    running.armCancel(run, "token-of-the-run");
    running.armCancel(neighbour, "token-of-the-neighbour");
    assertThat(running.cancelRequested(run)).isFalse();

    other.cancel("unknown-token");
    assertThat(running.cancelRequested(run)).isFalse();
    other.cancel("token-of-the-run");

    assertThat(running.cancelRequested(run)).isTrue();
    assertThat(running.cancelRequested(neighbour)).isFalse();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM agent_run_reservations WHERE cancel_token_hash = ?",
                Integer.class,
                "token-of-the-run"))
        .isZero();
  }

  @Test
  void aReleasedPlaceIsFreeAtOnce() {
    AgentWorkspace workspace = workspace();
    UUID run = UUID.randomUUID();
    workspace.tryReserve(run);
    workspace.tryReserve(UUID.randomUUID());

    workspace.release(run);

    assertThat(workspace.tryReserve(UUID.randomUUID())).isTrue();
  }

  private AgentWorkspace workspace() {
    return new AgentWorkspace(
        JdbcClient.create(dataSource),
        new DataSourceTransactionManager(dataSource),
        new AgentToolLoopProperties(
            5, 3, Duration.ofSeconds(120), 60_000, CAPACITY, Duration.ofSeconds(60)));
  }
}
