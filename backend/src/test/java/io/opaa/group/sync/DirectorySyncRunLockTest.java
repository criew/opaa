package io.opaa.group.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.common.ConflictException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * The failure handling around {@link DirectorySyncRunLock}, which its integration test cannot
 * reach: the lock connection sits idle in transaction for the whole run, so the session can already
 * be gone by the time the lock is released - through {@code idle_in_transaction_session_timeout}, a
 * pooler, or a terminated backend. Releasing the lock must then neither fail an already committed
 * run nor hide why a failed one failed.
 *
 * <p>Mocking the connection is the point here, not a shortcut: under test is the Java-level error
 * handling, while the locking semantics themselves are covered against a real Postgres by {@link
 * DirectorySyncConcurrencyIntegrationTest}.
 */
class DirectorySyncRunLockTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();
  private static final SQLException SESSION_GONE =
      new SQLException("terminating connection due to idle-in-transaction timeout");

  private final DataSource dataSource = mock(DataSource.class);
  private final Connection connection = mock(Connection.class);
  private final DirectorySyncRunLock runLock = new DirectorySyncRunLock(dataSource);

  @BeforeEach
  void setUp() throws SQLException {
    when(dataSource.getConnection()).thenReturn(connection);
    grantLock(true);
  }

  @Test
  void aCompletedRunSurvivesAFailureToReleaseTheLock() throws SQLException {
    doThrow(SESSION_GONE).when(connection).rollback();

    assertThat(runLock.runExclusively(ORGANIZATION_ID, () -> "report")).isEqualTo("report");
  }

  @Test
  void aFailedRunKeepsItsOwnCauseWhenReleasingTheLockAlsoFails() throws SQLException {
    doThrow(SESSION_GONE).when(connection).rollback();

    assertThatThrownBy(
            () ->
                runLock.runExclusively(
                    ORGANIZATION_ID,
                    () -> {
                      throw new IllegalStateException("the run itself failed");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("the run itself failed");
  }

  @Test
  void aSecondRunIsRejectedWithTheStableConflictCodeAndNeverEntersTheBody() throws SQLException {
    grantLock(false);

    Throwable thrown =
        catchThrowable(
            () ->
                runLock.runExclusively(
                    ORGANIZATION_ID,
                    () -> {
                      throw new AssertionError("the body must not run without the lock");
                    }));

    assertThat(thrown)
        .isInstanceOf(ConflictException.class)
        .hasMessage(DirectorySyncRunLock.ALREADY_RUNNING_MESSAGE);
    assertThat(((ConflictException) thrown).getCode())
        .isEqualTo(DirectorySyncRunLock.ALREADY_RUNNING_CODE);
  }

  @Test
  void aConnectionThatCannotBeObtainedSurfacesAsADataAccessFailure() throws SQLException {
    when(dataSource.getConnection()).thenThrow(new SQLException("pool exhausted"));

    assertThatThrownBy(() -> runLock.runExclusively(ORGANIZATION_ID, () -> "report"))
        .isInstanceOf(DataAccessResourceFailureException.class)
        .hasMessageContaining(ORGANIZATION_ID.toString());
  }

  @Test
  void theLockIsReleasedAndTheAutoCommitModeRestoredAfterASuccessfulRun() throws SQLException {
    when(connection.getAutoCommit()).thenReturn(true);

    runLock.runExclusively(ORGANIZATION_ID, () -> "report");

    verify(connection).setAutoCommit(false);
    verify(connection).rollback();
    verify(connection).setAutoCommit(true);
    verify(connection).close();
  }

  private void grantLock(boolean granted) throws SQLException {
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet resultSet = mock(ResultSet.class);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(resultSet);
    when(resultSet.next()).thenReturn(true);
    when(resultSet.getBoolean(1)).thenReturn(granted);
  }
}
