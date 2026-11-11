package io.opaa.query.toolloop;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.query.toolloop.AgentWorkspace.Reservation;
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
 * capacity and the share of one person hold for both together, and a place whose deadline has
 * passed is free again even when its instance never released it.
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
    assertThat(instanceA.tryReserve(UUID.randomUUID(), UUID.randomUUID()))
        .isEqualTo(Reservation.RESERVED);
    List<Reservation> outcomes = new CopyOnWriteArrayList<>();

    new TwoInstanceRace(dataSource)
        .run(
            () -> outcomes.add(instanceA.tryReserve(UUID.randomUUID(), UUID.randomUUID())),
            () -> outcomes.add(instanceB.tryReserve(UUID.randomUUID(), UUID.randomUUID())),
            "SELECT id FROM agent_workspace WHERE id = ? FOR UPDATE",
            1);

    assertThat(outcomes)
        .containsExactlyInAnyOrder(Reservation.RESERVED, Reservation.WORKSPACE_FULL);
    assertThat(instanceB.occupied()).isEqualTo(CAPACITY);
  }

  @Test
  void aPersonAtTheirShareIsRefusedWhileAnotherPersonStillGetsAPlace() {
    AgentWorkspace workspace = workspace(3, 2);
    UUID person = UUID.randomUUID();
    assertThat(workspace.tryReserve(UUID.randomUUID(), person)).isEqualTo(Reservation.RESERVED);
    assertThat(workspace.tryReserve(UUID.randomUUID(), person)).isEqualTo(Reservation.RESERVED);

    assertThat(workspace.tryReserve(UUID.randomUUID(), person))
        .isEqualTo(Reservation.PERSON_LIMIT_REACHED);
    assertThat(workspace.tryReserve(UUID.randomUUID(), UUID.randomUUID()))
        .isEqualTo(Reservation.RESERVED);
    assertThat(workspace.occupied()).isEqualTo(3);
  }

  @Test
  void twoInstancesRacingForTheLastPlaceOfOnePersonTakeItOnce() throws Exception {
    AgentWorkspace instanceA = workspace(3, 2);
    AgentWorkspace instanceB = workspace(3, 2);
    UUID person = UUID.randomUUID();
    assertThat(instanceA.tryReserve(UUID.randomUUID(), person)).isEqualTo(Reservation.RESERVED);
    List<Reservation> outcomes = new CopyOnWriteArrayList<>();

    new TwoInstanceRace(dataSource)
        .run(
            () -> outcomes.add(instanceA.tryReserve(UUID.randomUUID(), person)),
            () -> outcomes.add(instanceB.tryReserve(UUID.randomUUID(), person)),
            "SELECT id FROM agent_workspace WHERE id = ? FOR UPDATE",
            1);

    assertThat(outcomes)
        .containsExactlyInAnyOrder(Reservation.RESERVED, Reservation.PERSON_LIMIT_REACHED);
    assertThat(instanceB.occupied()).isEqualTo(2);
  }

  @Test
  void anExpiredPlaceIsFreeAgainWithoutARelease() {
    AgentWorkspace workspace = workspace();
    UUID crashed = UUID.randomUUID();
    assertThat(workspace.tryReserve(crashed, UUID.randomUUID())).isEqualTo(Reservation.RESERVED);
    assertThat(workspace.tryReserve(UUID.randomUUID(), UUID.randomUUID()))
        .isEqualTo(Reservation.RESERVED);
    assertThat(workspace.tryReserve(UUID.randomUUID(), UUID.randomUUID()))
        .isEqualTo(Reservation.WORKSPACE_FULL);

    expire(crashed);

    assertThat(workspace.occupied()).isEqualTo(1);
    assertThat(workspace.tryReserve(UUID.randomUUID(), UUID.randomUUID()))
        .isEqualTo(Reservation.RESERVED);
  }

  @Test
  void anExpiredPlaceNoLongerCountsAgainstThePerson() {
    AgentWorkspace workspace = workspace(3, 1);
    UUID person = UUID.randomUUID();
    UUID crashed = UUID.randomUUID();
    assertThat(workspace.tryReserve(crashed, person)).isEqualTo(Reservation.RESERVED);
    assertThat(workspace.tryReserve(UUID.randomUUID(), person))
        .isEqualTo(Reservation.PERSON_LIMIT_REACHED);

    expire(crashed);

    assertThat(workspace.tryReserve(UUID.randomUUID(), person)).isEqualTo(Reservation.RESERVED);
  }

  @Test
  void aStopAskedOnOneInstanceIsSeenByTheInstanceRunningTheRun() {
    AgentWorkspace running = workspace();
    AgentWorkspace other = workspace();
    UUID run = UUID.randomUUID();
    UUID neighbour = UUID.randomUUID();
    UUID person = UUID.randomUUID();
    running.tryReserve(run, person);
    running.tryReserve(neighbour, UUID.randomUUID());
    running.armCancel(run, "token-of-the-run");
    running.armCancel(neighbour, "token-of-the-neighbour");
    assertThat(running.cancelRequested(run)).isFalse();

    other.cancel("unknown-token", person);
    assertThat(running.cancelRequested(run)).isFalse();
    // a token that leaked to another account stops nothing
    other.cancel("token-of-the-run", UUID.randomUUID());
    assertThat(running.cancelRequested(run)).isFalse();
    other.cancel("token-of-the-run", person);

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
    workspace.tryReserve(run, UUID.randomUUID());
    workspace.tryReserve(UUID.randomUUID(), UUID.randomUUID());

    workspace.release(run);

    assertThat(workspace.tryReserve(UUID.randomUUID(), UUID.randomUUID()))
        .isEqualTo(Reservation.RESERVED);
  }

  private void expire(UUID runId) {
    jdbcTemplate.update(
        "UPDATE agent_run_reservations SET expires_at = clock_timestamp() - interval '1 second'"
            + " WHERE run_id = ?",
        runId);
  }

  private AgentWorkspace workspace() {
    return workspace(CAPACITY, CAPACITY);
  }

  private AgentWorkspace workspace(int capacity, int perPerson) {
    return new AgentWorkspace(
        JdbcClient.create(dataSource),
        new DataSourceTransactionManager(dataSource),
        new AgentToolLoopProperties(
            5, 3, Duration.ofSeconds(120), 60_000, capacity, perPerson, Duration.ofSeconds(60)));
  }
}
