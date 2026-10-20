package io.opaa.common;

import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Component;

/**
 * Reads {@code clock_timestamp()} on the connection of the surrounding transaction, if there is
 * one, otherwise on a connection of its own.
 */
@Component
public class JdbcDatabaseClock implements DatabaseClock {

  private final JdbcOperations jdbc;

  public JdbcDatabaseClock(JdbcOperations jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Instant now() {
    OffsetDateTime now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
    if (now == null) {
      throw new IllegalStateException("the database returned no clock_timestamp()");
    }
    return now.toInstant();
  }
}
