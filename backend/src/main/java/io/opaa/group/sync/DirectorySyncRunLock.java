package io.opaa.group.sync;

import io.opaa.common.ConflictException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;

/**
 * Serializes {@link DirectorySyncService}'s runs per organization: at most one run of one
 * organization is ever in flight, while runs of different organizations never wait for each other.
 *
 * <p><b>Rejects instead of queueing.</b> {@code pg_try_advisory_xact_lock}, not {@code
 * pg_advisory_xact_lock}: a run reads a whole directory and can take minutes, so a waiting second
 * caller would hold an HTTP request open for that long with no way to tell it apart from a hang.
 * The second caller gets a {@link ConflictException} instead - the same answer a second indexing
 * run of one library gets from {@code uk_indexing_jobs_library_running}.
 *
 * <p><b>Its own connection, not the caller's transaction.</b> The lock must cover the directory
 * fetch, and {@link DirectorySyncService} deliberately runs that fetch outside any transaction (see
 * its Javadoc). The lock therefore lives on a connection checked out here for the duration of the
 * run, untouched by - and invisible to - the transactions {@link DirectorySyncPlanExecutor} opens
 * inside the body. The transaction-scoped variant is what makes that safe: the lock is released by
 * the {@code rollback} below, and by the backend dying if the process does, so it cannot be leaked
 * the way a {@code pg_advisory_lock}/{@code pg_advisory_unlock} pair on a pooled connection could.
 * The price is one pooled connection per in-flight run, held across the fetch; the try-lock bounds
 * that to one per organization.
 */
@Component
class DirectorySyncRunLock {

  /**
   * Namespace of this class's advisory locks - see {@code
   * io.opaa.library.AssetGrantRepository#ASSET_GRANT_MUTATION_LOCK_NAMESPACE} for the list every
   * namespace is registered in.
   */
  static final int DIRECTORY_SYNC_RUN_LOCK_NAMESPACE = 205;

  static final String ALREADY_RUNNING_CODE = "DIRECTORY_SYNC_ALREADY_RUNNING";

  private static final String TRY_LOCK_SQL =
      "SELECT pg_try_advisory_xact_lock("
          + DIRECTORY_SYNC_RUN_LOCK_NAMESPACE
          + ", hashtext(CAST(? AS text)))";

  private final DataSource dataSource;

  DirectorySyncRunLock(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /**
   * Runs {@code body} while holding the organization's run lock.
   *
   * @throws ConflictException if another run of the same organization is already in flight; {@code
   *     body} is then not called at all
   */
  <T> T runExclusively(UUID organizationId, Supplier<T> body) {
    try (Connection connection = dataSource.getConnection()) {
      boolean autoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try {
        if (!tryLock(connection, organizationId)) {
          throw new ConflictException(
              "Für diese Organisation läuft bereits ein Abgleich.", ALREADY_RUNNING_CODE);
        }
        return body.get();
      } finally {
        connection.rollback();
        connection.setAutoCommit(autoCommit);
      }
    } catch (SQLException e) {
      throw new DataAccessResourceFailureException(
          "Failed to acquire the directory sync run lock for organization " + organizationId, e);
    }
  }

  private boolean tryLock(Connection connection, UUID organizationId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(TRY_LOCK_SQL)) {
      statement.setString(1, organizationId.toString());
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() && resultSet.getBoolean(1);
      }
    }
  }
}
