package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.audit.AuditLogSelfDisclosureExporter;
import io.opaa.compliance.SelfDisclosureSink;
import io.opaa.compliance.SelfDisclosureSubject;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Measures the audit-log category of the self-disclosure without an index on {@code subject_ref} on
 * 10 million entries over 36 monthly partitions (docs/features/security-and-compliance.md,
 * "Protokoll der Selbstauskunft", threshold 30 s). Run by hand only, it needs minutes and several
 * GB of Docker disk: {@code ./gradlew test --tests '*AuditLogSelfDisclosureScanMeasurementTest'
 * -Dopaa.selfdisclosure.measureAuditScan=true}. Prints data volume, run times and query plan.
 */
@EnabledIfSystemProperty(named = "opaa.selfdisclosure.measureAuditScan", matches = "true")
class AuditLogSelfDisclosureScanMeasurementTest extends AbstractMigrationTest {

  private static final long ROWS = 10_000_000L;
  private static final int MONTHS = 36;
  private static final String ORG = "00000000-0000-0000-0000-000000000001";

  @Override
  protected List<String> baseFixtureChangelogs() {
    return List.of("db/changelog/test-master-through-baseline.yaml");
  }

  @Test
  void theAuditLogCategoryOfOnePersonOnTenMillionEntries() throws Exception {
    UUID person;
    String pseudonym = UUID.randomUUID().toString();
    LocalDate firstMonth = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).minusMonths(MONTHS - 1);
    long expected;
    try (Connection connection = connect();
        Statement sql = connection.createStatement()) {
      for (int i = 0; i < MONTHS + 1; i++) {
        LocalDate from = firstMonth.plusMonths(i);
        sql.execute(
            String.format(
                "CREATE TABLE IF NOT EXISTS audit_log_%s PARTITION OF audit_log"
                    + " FOR VALUES FROM ('%s') TO ('%s')",
                from.toString().substring(0, 7).replace("-", "_"), from, from.plusMonths(1)));
      }
      try (ResultSet rs =
          sql.executeQuery(
              "INSERT INTO users (subject, issuer, email, display_name, organization_id) VALUES"
                  + " ('measure', 'measure', 'm@example', 'Messperson', '"
                  + ORG
                  + "') RETURNING id")) {
        rs.next();
        person = rs.getObject(1, UUID.class);
      }
      sql.execute(
          "INSERT INTO audit_actor_pseudonyms (pseudonym_id, user_id, organization_id) VALUES ('"
              + pseudonym
              + "', '"
              + person
              + "', '"
              + ORG
              + "')");
      // the secondary indexes are built after the bulk insert, which takes minutes instead of an
      // hour; the final state is the same as written row by row
      List<String> indexes = new java.util.ArrayList<>();
      List<String> names = new java.util.ArrayList<>();
      try (ResultSet rs =
          sql.executeQuery(
              "SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'audit_log'"
                  + " AND indexname <> 'audit_log_pkey'")) {
        while (rs.next()) {
          names.add(rs.getString(1));
          indexes.add(rs.getString(2).replace(" ON ONLY ", " ON "));
        }
      }
      for (String name : names) {
        sql.execute("DROP INDEX " + name);
      }
      long start = System.nanoTime();
      // 5,000 other people; every 5,000th entry is about the person, a third each as actor,
      // subject and account object; 40 % of all entries carry a subject, 20 % a payload
      sql.execute(
          "CREATE TEMP TABLE others AS SELECT ARRAY(SELECT gen_random_uuid()::text"
              + " FROM generate_series(1, 5000)) AS refs");
      sql.execute(
          "INSERT INTO audit_log (event_id, recorded_at, organization_id, actor_kind, actor_ref,"
              + " event_type, object_type, object_id, object_label, subject_kind, subject_ref,"
              + " before, after, outcome, reason, correlation_ref)"
              + " SELECT gen_random_uuid(),"
              + " timestamptz '"
              + firstMonth
              + "' + (i::float8 / "
              + ROWS
              + ") * (now() - interval '1 minute' - timestamptz '"
              + firstMonth
              + "'),"
              + " '"
              + ORG
              + "', 'USER',"
              + " CASE WHEN i % 15000 = 0 THEN '"
              + pseudonym
              + "' ELSE refs[(1 + (i * 7919) % 5000)::int] END,"
              + " (ARRAY['SPACE_MEMBER_ADDED','GROUP_MEMBER_ADDED','ASSET_GRANT_GRANTED',"
              + "'LIBRARY_CHANGED','LOCAL_SESSION_REVOKED'])[1 + i % 5],"
              + " CASE WHEN i % 15000 = 10000 THEN 'USER_ACCOUNT' ELSE 'SPACE' END,"
              + " CASE WHEN i % 15000 = 10000 THEN '"
              + pseudonym
              + "' ELSE md5(i::text) END,"
              + " 'Bezeichnung ' || (i % 1000),"
              + " CASE WHEN i % 15000 = 5000 OR i % 5 < 2 THEN 'USER' END,"
              + " CASE WHEN i % 15000 = 5000 THEN '"
              + pseudonym
              + "' WHEN i % 5 < 2 THEN refs[(1 + (i * 104729) % 5000)::int]"
              + " END,"
              + " NULL,"
              + " CASE WHEN i % 5 = 0 THEN '{\"role\":\"MEMBER\"}' END,"
              + " 'SUCCESS', NULL, NULL"
              + " FROM others, generate_series(1::bigint, "
              + ROWS
              + ") i");
      for (String definition : indexes) {
        sql.execute(definition);
      }
      sql.execute("ANALYZE audit_log");
      System.out.printf(
          "[measure] %,d rows over %d partitions inserted in %s%n",
          ROWS, MONTHS, Duration.ofNanos(System.nanoTime() - start));
      try (ResultSet rs =
          sql.executeQuery(
              "SELECT count(*) FROM audit_log WHERE actor_ref = '"
                  + pseudonym
                  + "' OR subject_ref = '"
                  + pseudonym
                  + "' OR object_id = '"
                  + pseudonym
                  + "'")) {
        rs.next();
        expected = rs.getLong(1);
      }
      try (ResultSet rs =
          sql.executeQuery(
              "SELECT pg_size_pretty(sum(pg_total_relation_size(inhrelid))), count(*)"
                  + " FROM pg_inherits WHERE inhparent = 'audit_log'::regclass")) {
        rs.next();
        System.out.printf(
            "[measure] audit_log: %s in %d partitions (with empty future ones), %d entries about"
                + " the person%n",
            rs.getString(1), rs.getLong(2), expected);
      }
      LocalDate month = firstMonth.plusMonths(MONTHS / 2);
      try (ResultSet rs =
          sql.executeQuery(
              "EXPLAIN (ANALYZE, BUFFERS) SELECT event_id, recorded_at FROM audit_log"
                  + " WHERE organization_id = '"
                  + ORG
                  + "' AND recorded_at >= '"
                  + month
                  + "' AND recorded_at < '"
                  + month.plusMonths(1)
                  + "' AND ((actor_kind = 'USER' AND actor_ref = '"
                  + pseudonym
                  + "') OR (subject_kind = 'USER' AND subject_ref = '"
                  + pseudonym
                  + "') OR (object_type = 'USER_ACCOUNT' AND object_id = '"
                  + pseudonym
                  + "')) AND (event_type <> 'AUDIT_LOG_ACCESSED' OR actor_ref = '"
                  + pseudonym
                  + "') AND (recorded_at, event_id) > ('1970-01-01', '"
                  + new UUID(0, 0)
                  + "') ORDER BY recorded_at, event_id LIMIT 500")) {
        System.out.println("[measure] plan of one month window:");
        while (rs.next()) {
          System.out.println("[measure]   " + rs.getString(1));
        }
      }
    }

    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            currentDatabaseUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    AuditLogSelfDisclosureExporter exporter =
        new AuditLogSelfDisclosureExporter(
            new JdbcTemplate(dataSource), new DataSourceTransactionManager(dataSource));
    for (int run = 1; run <= 3; run++) {
      AtomicLong records = new AtomicLong();
      SelfDisclosureSink sink = key -> record -> records.incrementAndGet();
      long start = System.nanoTime();
      exporter.export(new SelfDisclosureSubject(person, UUID.fromString(ORG)), sink);
      Duration took = Duration.ofNanos(System.nanoTime() - start);
      System.out.printf("[measure] export run %d: %d records in %s%n", run, records.get(), took);
      assertThat(records.get()).isEqualTo(expected);
    }
  }
}
