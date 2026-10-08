package io.opaa.config;

import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Refuses to start on a pgvector extension older than 0.8.0: every vector search runs as an
 * iterative index scan ({@code hnsw.iterative_scan}), which older versions do not have - the search
 * would fail, or silently return fewer candidates than requested. A missing extension or a version
 * string this guard cannot read is left to the schema initialization and the search itself.
 */
@Component
public class PgVectorVersionGuard implements ApplicationRunner {

  static final int MINIMUM_MAJOR = 0;
  static final int MINIMUM_MINOR = 8;

  private final JdbcTemplate jdbcTemplate;

  public PgVectorVersionGuard(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void run(ApplicationArguments args) {
    List<String> versions =
        jdbcTemplate.queryForList(
            "SELECT extversion FROM pg_extension WHERE extname = 'vector'", String.class);
    if (versions.isEmpty() || isAtLeastMinimum(versions.get(0))) {
      return;
    }
    throw new IllegalStateException(
        ("The installed pgvector extension has version %s, but OPAA needs %d.%d.0 or later for its"
                + " vector search (hnsw.iterative_scan). Install a newer pgvector and run ALTER"
                + " EXTENSION vector UPDATE.")
            .formatted(versions.get(0), MINIMUM_MAJOR, MINIMUM_MINOR));
  }

  static boolean isAtLeastMinimum(String version) {
    String[] parts = version.split("\\.");
    if (parts.length < 2) {
      return true;
    }
    try {
      int major = Integer.parseInt(parts[0]);
      int minor = Integer.parseInt(parts[1]);
      return major > MINIMUM_MAJOR || (major == MINIMUM_MAJOR && minor >= MINIMUM_MINOR);
    } catch (NumberFormatException e) {
      return true;
    }
  }
}
