package io.opaa.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import org.hibernate.TransactionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

class DatabaseConnectionLossTest {

  @ParameterizedTest
  @ValueSource(strings = {"08006", "08003", "08001", "08S01", "57P01", "57P02", "57P03"})
  void aLostConnectionCountsHoweverSpringWrapsIt(String sqlState) {
    SQLException lost = new SQLException("An I/O error occurred while sending to the backend.");
    SQLException driver = new SQLException("server side", sqlState, lost);

    assertThat(
            DatabaseConnectionLoss.isConnectionLoss(
                new JpaSystemException(new RuntimeException("wrapped", driver))))
        .isTrue();
    assertThat(
            DatabaseConnectionLoss.isConnectionLoss(
                new DataAccessResourceFailureException("resource", driver)))
        .isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"23505", "23503", "23514", "40001", "40P01", "57014", "22001", "42P01"})
  void aFailureOfTheStatementItselfDoesNotCount(String sqlState) {
    SQLException driver = new SQLException("statement", sqlState);

    assertThat(
            DatabaseConnectionLoss.isConnectionLoss(
                new DataIntegrityViolationException("constraint", driver)))
        .isFalse();
    assertThat(DatabaseConnectionLoss.isConnectionLoss(new TransactionException("commit", driver)))
        .as("a commit refused for a deferred constraint")
        .isFalse();
  }

  // regression guard for #3916: the pool closes the connection after the statement failed with
  // 57P01, the rollback then fails without SQLState and Spring keeps only the rollback's failure
  @Test
  void aRollbackThatFailedOnAConnectionThePoolAlreadyClosedCounts() {
    JpaSystemException rollbackFailed =
        new JpaSystemException(
            new TransactionException(
                "Unable to rollback against JDBC Connection",
                new SQLException("Connection is closed")));

    assertThat(DatabaseConnectionLoss.isConnectionLoss(rollbackFailed)).isTrue();
    assertThat(
            DatabaseConnectionLoss.isConnectionLoss(
                new TransactionSystemException(
                    "Could not roll back JDBC transaction",
                    new SQLException("Connection is closed"))))
        .isTrue();
  }

  @Test
  void aPoolThatHandsOutNoConnectionCounts() {
    SQLTransientConnectionException timedOut =
        new SQLTransientConnectionException("Connection is not available, request timed out");

    assertThat(
            DatabaseConnectionLoss.isConnectionLoss(
                new CannotCreateTransactionException("Could not open JPA EntityManager", timedOut)))
        .isTrue();
  }

  @Test
  void suppressedNextAndHiddenApplicationFailuresAreRead() {
    RuntimeException withSuppressed = new RuntimeException("outer");
    withSuppressed.addSuppressed(new SQLException("lost", "08006"));
    SQLException batch = new SQLException("batch entry", "23505");
    batch.setNextException(new SQLException("lost", "08006"));
    TransactionSystemException rollback = new TransactionSystemException("rollback");
    rollback.initApplicationException(new SQLException("lost", "57P01"));

    assertThat(DatabaseConnectionLoss.isConnectionLoss(withSuppressed)).isTrue();
    assertThat(DatabaseConnectionLoss.isConnectionLoss(batch)).isTrue();
    assertThat(DatabaseConnectionLoss.isConnectionLoss(rollback)).isTrue();
  }

  @Test
  void failuresWithoutADatabaseConnectionDoNotCount() {
    assertThat(DatabaseConnectionLoss.isConnectionLoss(null)).isFalse();
    assertThat(DatabaseConnectionLoss.isConnectionLoss(new IOException("unreadable file")))
        .isFalse();
    assertThat(DatabaseConnectionLoss.isConnectionLoss(new IllegalStateException("parser")))
        .isFalse();
    assertThat(DatabaseConnectionLoss.isConnectionLoss(new SQLException("no state"))).isFalse();
    assertThat(
            DatabaseConnectionLoss.isConnectionLoss(
                new TransactionException("marked for rollback only")))
        .isFalse();
  }

  @Test
  void aCyclicChainEnds() {
    RuntimeException first = new RuntimeException("first");
    RuntimeException second = new RuntimeException("second", first);
    first.addSuppressed(second);

    assertThat(DatabaseConnectionLoss.isConnectionLoss(second)).isFalse();
  }
}
