package io.opaa.common;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;
import org.hibernate.TransactionException;
import org.springframework.transaction.TransactionSystemException;

/**
 * Whether a failure says that the database connection it ran on is gone, not that its statement was
 * wrong: SQLState class {@code 08} (connection exception, also the driver's I/O error and a
 * connection the pool already closed), {@code 57P01} to {@code 57P03} (the server shut the session
 * down or does not accept connections yet), a pool that could hand out no connection in time, or a
 * rollback or commit that failed with an SQLException without SQLState - the connection pool
 * already closed the connection after its statement failed, and PostgreSQL never refuses a rollback
 * on a live one. Spring and Hibernate wrap such a failure ({@code JpaSystemException}, {@code
 * TransactionSystemException}, {@code CannotCreateTransactionException}, {@code
 * DataAccessResourceFailureException}), so the whole cause chain is read, with suppressed failures,
 * chained {@link SQLException#getNextException() next exceptions} and the application exception a
 * failed rollback hides. A constraint, a parser, a model or a validation failure never qualifies.
 */
public final class DatabaseConnectionLoss {

  /** {@code admin_shutdown}, {@code crash_shutdown}, {@code cannot_connect_now}. */
  private static final Set<String> SESSION_ENDED = Set.of("57P01", "57P02", "57P03");

  private static final String CONNECTION_EXCEPTION_CLASS = "08";

  private DatabaseConnectionLoss() {}

  public static boolean isConnectionLoss(Throwable failure) {
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    Deque<Throwable> pending = new ArrayDeque<>();
    if (failure != null) {
      pending.push(failure);
    }
    while (!pending.isEmpty()) {
      Throwable current = pending.pop();
      if (!seen.add(current)) {
        continue;
      }
      if (endsTheConnection(current)) {
        return true;
      }
      related(current, pending);
    }
    return false;
  }

  private static boolean endsTheConnection(Throwable failure) {
    if (failure instanceof SQLTransientConnectionException) {
      return true;
    }
    if (endsATransaction(failure)
        && failure.getCause() instanceof SQLException cause
        && cause.getSQLState() == null) {
      return true;
    }
    if (!(failure instanceof SQLException sql) || sql.getSQLState() == null) {
      return false;
    }
    String state = sql.getSQLState();
    return state.startsWith(CONNECTION_EXCEPTION_CLASS) || SESSION_ENDED.contains(state);
  }

  private static boolean endsATransaction(Throwable failure) {
    return failure instanceof TransactionSystemException || failure instanceof TransactionException;
  }

  private static void related(Throwable failure, Deque<Throwable> pending) {
    if (failure.getCause() != null) {
      pending.push(failure.getCause());
    }
    for (Throwable suppressed : failure.getSuppressed()) {
      pending.push(suppressed);
    }
    if (failure instanceof SQLException sql && sql.getNextException() != null) {
      pending.push(sql.getNextException());
    }
    if (failure instanceof TransactionSystemException rollback
        && rollback.getApplicationException() != null) {
      pending.push(rollback.getApplicationException());
    }
  }
}
