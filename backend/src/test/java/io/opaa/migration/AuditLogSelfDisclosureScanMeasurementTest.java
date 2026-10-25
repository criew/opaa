package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.audit.AuditLogSelfDisclosureExporter;
import io.opaa.audit.AuditLogSelfDisclosureExporterFactory;
import io.opaa.compliance.SelfDisclosureSink;
import io.opaa.compliance.SelfDisclosureSubject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Measures the audit-log category of the self-disclosure without an index on {@code subject_ref}
 * (docs/features/security-and-compliance.md, "Protokoll der Selbstauskunft", threshold 30 s): on 10
 * million entries over 36 monthly partitions, and for a person with 50,000 entries in one month of
 * a partition of the same size. Run by hand only, it needs minutes and several GB of Docker disk:
 * {@code ./gradlew test --tests '*AuditLogSelfDisclosureScanMeasurementTest'
 * -Dopaa.selfdisclosure.measureAuditScan=true}. Prints data volume, run times and query plan.
 */
@EnabledIfSystemProperty(named = "opaa.selfdisclosure.measureAuditScan", matches = "true")
class AuditLogSelfDisclosureScanMeasurementTest extends AbstractMigrationTest {

  private static final long ROWS = 10_000_000L;
  private static final int MONTHS = 36;
  private static final String ORG = "00000000-0000-0000-0000-000000000001";

  @Override
  protected List<String> baseFixtureMigrations() {
    return Migrations.all();
  }

  @Test
  void theAuditLogCategoryOfOnePersonOnTenMillionEntries() throws Exception {
    LocalDate firstMonth = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).minusMonths(MONTHS - 1);
    measure(
        firstMonth,
        MONTHS,
        sql -> {
          // of every 15,000 entries one each names the person as actor, subject and object, and
          // one as owner in the payload where it is an owner event
          fill(sql, firstMonth, "now() - interval '1 minute'", ROWS, 15_000);
        });
  }

  @Test
  void aPersonWithFiftyThousandEntriesInOneMonth() throws Exception {
    LocalDate month = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).minusMonths(2);
    String end = "timestamptz '" + month.plusMonths(1) + "' - interval '1 hour'";
    measure(
        month,
        1,
        sql -> {
          // one partition as large as one of the 36 above, plus the person's 50,000 entries
          fill(sql, month, end, ROWS / MONTHS, 0);
          fill(sql, month, end, 50_000, 3);
        });
  }

  @FunctionalInterface
  private interface Filling {
    void fill(Statement sql) throws SQLException;
  }

  private String pseudonym;

  private void measure(LocalDate firstMonth, int months, Filling filling) throws Exception {
    UUID person;
    pseudonym = UUID.randomUUID().toString();
    long expected;
    try (Connection connection = connect();
        Statement sql = connection.createStatement()) {
      for (int i = 0; i < months + 1; i++) {
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
      List<String> indexes = new ArrayList<>();
      List<String> names = new ArrayList<>();
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
      sql.execute(
          "CREATE TEMP TABLE others AS SELECT ARRAY(SELECT gen_random_uuid()::text"
              + " FROM generate_series(1, 5000)) AS refs");
      long start = System.nanoTime();
      filling.fill(sql);
      for (String definition : indexes) {
        sql.execute(definition);
      }
      sql.execute("ANALYZE audit_log");
      System.out.printf(
          "[measure] filled %d month(s) in %s%n",
          months, Duration.ofNanos(System.nanoTime() - start));
      String asOwner =
          "event_type IN ('ASSET_OWNER_CHANGED', 'SPACE_CREATED') AND (before LIKE '%\"ownerId\":\""
              + pseudonym
              + "\"%' OR after LIKE '%\"ownerId\":\""
              + pseudonym
              + "\"%')";
      try (ResultSet rs =
          sql.executeQuery(
              "SELECT count(*) FILTER (WHERE actor_ref = '"
                  + pseudonym
                  + "' OR subject_ref = '"
                  + pseudonym
                  + "' OR object_id = '"
                  + pseudonym
                  + "' OR ("
                  + asOwner
                  + ")), count(*) FILTER (WHERE "
                  + asOwner
                  + "), count(*) FILTER (WHERE event_type IN ('ASSET_OWNER_CHANGED',"
                  + " 'SPACE_CREATED')), count(*) FROM audit_log")) {
        rs.next();
        expected = rs.getLong(1);
        System.out.printf(
            "[measure] %,d entries in audit_log, %,d owner entries, %,d of them about the person"
                + " (%,d as owner in the payload)%n",
            rs.getLong(4), rs.getLong(3), expected, rs.getLong(2));
      }
      try (ResultSet rs =
          sql.executeQuery(
              "SELECT pg_size_pretty(sum(pg_total_relation_size(inhrelid))), count(*)"
                  + " FROM pg_inherits WHERE inhparent = 'audit_log'::regclass")) {
        rs.next();
        System.out.printf(
            "[measure] audit_log: %s in %d partitions (with empty future ones)%n",
            rs.getString(1), rs.getLong(2));
      }
      LocalDate month = firstMonth.plusMonths(months / 2);
      try (PreparedStatement explain =
          connection.prepareStatement(
              "EXPLAIN (ANALYZE, BUFFERS) " + AuditLogSelfDisclosureExporterFactory.keysQuery())) {
        Object[] arguments =
            AuditLogSelfDisclosureExporterFactory.keysArguments(
                UUID.fromString(ORG),
                UUID.fromString(pseudonym),
                person,
                month.atStartOfDay(ZoneOffset.UTC).toInstant(),
                month.plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        for (int i = 0; i < arguments.length; i++) {
          explain.setObject(i + 1, arguments[i]);
        }
        try (ResultSet rs = explain.executeQuery()) {
          System.out.println("[measure] plan of the id scan of one month window:");
          while (rs.next()) {
            System.out.println("[measure]   " + rs.getString(1));
          }
        }
      }
    }

    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            currentDatabaseUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    AuditLogSelfDisclosureExporter exporter =
        AuditLogSelfDisclosureExporterFactory.create(
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

  /**
   * Inserts {@code rows} entries spread evenly from {@code firstMonth} to {@code end}, by 5,000
   * other people; of every {@code personEvery} entries one each names the person as actor, subject
   * and account object, and one as owner in the payload where it is an owner event (none for 0). 40
   * % of the others carry a subject; two of seven are owner events with an owner in the payload,
   * one of seven carries another payload.
   */
  private void fill(Statement sql, LocalDate firstMonth, String end, long rows, long personEvery)
      throws SQLException {
    long every = personEvery == 0 ? Long.MAX_VALUE : personEvery;
    long asSubject = every / 3;
    long asObject = 2 * every / 3;
    long asOwner = every / 6;
    String owner =
        "CASE WHEN i % "
            + every
            + " = "
            + asOwner
            + " THEN '"
            + pseudonym
            + "' ELSE refs[(1 + (i * 6007) % 5000)::int] END";
    sql.execute(
        "INSERT INTO audit_log (event_id, recorded_at, organization_id, actor_kind, actor_ref,"
            + " event_type, object_type, object_id, object_label, subject_kind, subject_ref,"
            + " before, after, outcome, reason, correlation_ref)"
            + " SELECT gen_random_uuid(),"
            + " timestamptz '"
            + firstMonth
            + "' + (i::float8 / "
            + rows
            + ") * ("
            + end
            + " - timestamptz '"
            + firstMonth
            + "'),"
            + " '"
            + ORG
            + "', 'USER',"
            + " CASE WHEN i % "
            + every
            + " = 0 THEN '"
            + pseudonym
            + "' ELSE refs[(1 + (i * 7919) % 5000)::int] END,"
            + " (ARRAY['SPACE_MEMBER_ADDED','GROUP_MEMBER_ADDED','ASSET_GRANT_GRANTED',"
            + "'LIBRARY_CHANGED','LOCAL_SESSION_REVOKED','ASSET_OWNER_CHANGED',"
            + "'SPACE_CREATED'])[1 + i % 7],"
            + " CASE WHEN i % "
            + every
            + " = "
            + asObject
            + " THEN 'USER_ACCOUNT' ELSE 'SPACE' END,"
            + " CASE WHEN i % "
            + every
            + " = "
            + asObject
            + " THEN '"
            + pseudonym
            + "' ELSE md5(i::text) END,"
            + " 'Bezeichnung ' || (i % 1000),"
            + " CASE WHEN i % "
            + every
            + " = "
            + asSubject
            + " OR i % 5 < 2 THEN 'USER' END,"
            + " CASE WHEN i % "
            + every
            + " = "
            + asSubject
            + " THEN '"
            + pseudonym
            + "' WHEN i % 5 < 2 THEN refs[(1 + (i * 104729) % 5000)::int] END,"
            + " CASE WHEN i % 7 = 5 THEN '{\"ownerId\":\"' || "
            + owner
            + " || '\"}' END,"
            + " CASE i % 7 WHEN 0 THEN '{\"role\":\"MEMBER\"}'"
            + " WHEN 5 THEN '{\"ownerId\":\"' || refs[(1 + (i * 3001) % 5000)::int] || '\"}'"
            + " WHEN 6 THEN '{\"name\":\"Raum\",\"ownerId\":\"' || "
            + owner
            + " || '\",\"chatAutoCleanup\":false}' END,"
            + " 'SUCCESS', NULL, NULL"
            + " FROM others, generate_series(1::bigint, "
            + rows
            + ") i");
  }
}
