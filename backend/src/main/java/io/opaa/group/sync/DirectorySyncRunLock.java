package io.opaa.group.sync;

import io.opaa.common.ConflictException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;

/**
 * Serializes {@link DirectorySyncService}'s runs per identity provider: at most one run of one
 * provider is in flight at a time, a second caller is rejected with a {@link ConflictException}
 * rather than queued behind the first, and runs of different providers never wait for each other.
 * The lock sits on a connection of its own so that it can cover the directory fetch, which the
 * caller deliberately performs outside any transaction.
 *
 * <p><b>Operating precondition:</b> that connection sits idle in transaction for the whole run.
 * Anything that ends the session early - {@code idle_in_transaction_session_timeout}, a transaction
 * pooler between application and database, an operator terminating idle backends - drops the lock
 * silently, and a second run gets through.
 */
@Component
class DirectorySyncRunLock {

  private static final Logger log = LoggerFactory.getLogger(DirectorySyncRunLock.class);

  /**
   * Namespace of this class's advisory locks - see {@code
   * io.opaa.permission.AssetGrantRepository#ASSET_GRANT_MUTATION_LOCK_NAMESPACE} for the list every
   * namespace is registered in.
   */
  static final int DIRECTORY_SYNC_RUN_LOCK_NAMESPACE = 205;

  static final String ALREADY_RUNNING_CODE = "DIRECTORY_SYNC_ALREADY_RUNNING";

  static final String ALREADY_RUNNING_MESSAGE = "Für diesen Anbieter läuft bereits ein Abgleich.";

  private static final String TRY_LOCK_SQL =
      "SELECT pg_try_advisory_xact_lock("
          + DIRECTORY_SYNC_RUN_LOCK_NAMESPACE
          + ", hashtext(CAST(? AS text)))";

  private final DataSource dataSource;

  DirectorySyncRunLock(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /**
   * Runs {@code body} while holding the provider's run lock.
   *
   * @throws ConflictException if another run of the same provider is already in flight; {@code
   *     body} is then not called at all
   */
  <T> T runExclusively(UUID providerId, Supplier<T> body) {
    try (Connection connection = dataSource.getConnection()) {
      boolean autoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try {
        if (!tryLock(connection, providerId)) {
          throw new ConflictException(ALREADY_RUNNING_MESSAGE, ALREADY_RUNNING_CODE);
        }
        return body.get();
      } finally {
        releaseQuietly(connection, autoCommit, providerId);
      }
    } catch (SQLException e) {
      throw new DataAccessResourceFailureException(
          "Directory sync run lock failed for provider " + providerId, e);
    }
  }

  /**
   * Releasing the lock must neither turn an already completed run into an error response nor
   * replace the cause of a failed one: by this point the run's changes are committed on other
   * connections, and the lock is gone either way once try-with-resources hands this connection back
   * and the pool rolls it back.
   */
  private void releaseQuietly(Connection connection, boolean autoCommit, UUID providerId) {
    try {
      connection.rollback();
      connection.setAutoCommit(autoCommit);
    } catch (SQLException e) {
      log.warn(
          "Directory sync: failed to release the run lock for provider {} - the run's own"
              + " outcome is unaffected, and the lock goes with the connection",
          providerId,
          e);
    }
  }

  private boolean tryLock(Connection connection, UUID providerId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(TRY_LOCK_SQL)) {
      statement.setString(1, providerId.toString());
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() && resultSet.getBoolean(1);
      }
    }
  }
}
