package io.opaa.library;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.opaa.knowledge.PersonalStorageQuota;
import io.opaa.knowledge.SharedLibraryQuota;
import io.opaa.observability.PolledGauges;
import io.opaa.permission.PersonThreshold;
import java.time.Duration;
import java.util.Arrays;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The installation's holdings as {@code opaa.inventory.*} (ADR-0071): libraries, documents, chunks
 * and occupied storage, each with {@code kind} {@code shared} or {@code private}, the storage limit
 * in force and the shared spaces. Read from the database every {@link #INTERVAL}, summed over every
 * organization, never per library, space or person.
 *
 * <p>A number about private libraries rests on their owners: below the minimum group size
 * (ADR-0036, {@link PersonThreshold}) it is {@code NaN}, as the administration's view masks it.
 * Personal spaces are not counted, their number would be the number of persons.
 */
@Configuration(proxyBeanMethods = false)
public class InventoryMetricsConfiguration {

  /** Counting documents is no light read; the holdings change slowly. */
  static final Duration INTERVAL = Duration.ofMinutes(15);

  /**
   * Libraries, documents, chunks, occupied storage and owners of one kind in one read; a library
   * without documents counts once, with none of them.
   */
  static final String HOLDINGS_SQL =
      "SELECT count(DISTINCT l.id), count(d.id), coalesce(sum(d.chunk_count), 0),"
          + " coalesce(sum(coalesce(d.file_size, 0)), 0), count(DISTINCT a.owner_user_id)"
          + " FROM knowledge_libraries l JOIN assets a ON a.id = l.id"
          + " LEFT JOIN documents d ON d.library_id = l.id WHERE a.owner_only = ?";

  static final String SHARED_SPACES_SQL =
      "SELECT count(*) FROM spaces WHERE NOT is_default AND NOT archived";

  @Bean
  PolledGauges inventoryGauges(
      DataSource dataSource,
      MeterRegistry registry,
      PersonThreshold threshold,
      PersonalStorageQuota personalQuota,
      SharedLibraryQuota sharedQuota) {
    JdbcTemplate jdbc = PolledGauges.reads(dataSource);
    PolledGauges gauges = new PolledGauges(registry, INTERVAL);
    for (boolean ownerOnly : new boolean[] {false, true}) {
      Tags kind = Tags.of("kind", ownerOnly ? "private" : "shared");
      Supplier<double[]> holdings = PolledGauges.sqlRow(jdbc, HOLDINGS_SQL, ownerOnly);
      gauges.gauges(
          () -> masked(holdings.get(), ownerOnly, threshold),
          new PolledGauges.Spec("opaa.inventory.libraries", kind, null, "Knowledge libraries"),
          new PolledGauges.Spec(
              "opaa.inventory.documents", kind, null, "Documents in knowledge libraries"),
          new PolledGauges.Spec(
              "opaa.inventory.chunks", kind, null, "Indexed chunks of the documents"),
          new PolledGauges.Spec(
              "opaa.inventory.storage.used",
              kind,
              "bytes",
              "Storage the documents occupy, counted like the storage limit"));
    }
    return gauges
        .gauge(
            "opaa.inventory.storage.limit",
            Tags.of("kind", "shared"),
            "bytes",
            "Storage limit in force per shared library, 0 for unlimited",
            sharedQuota::quotaBytes)
        .gauge(
            "opaa.inventory.storage.limit",
            Tags.of("kind", "private"),
            "bytes",
            "Storage limit in force per person across their private libraries, 0 for unlimited",
            personalQuota::quotaBytes)
        .gauge(
            "opaa.inventory.spaces",
            Tags.empty(),
            null,
            "Shared spaces that are not archived, personal spaces not counted",
            PolledGauges.sql(jdbc, SHARED_SPACES_SQL));
  }

  /**
   * The first four values of a {@link #HOLDINGS_SQL} row; for private libraries each is {@code NaN}
   * while their owners, the fifth value, stay below the minimum group size.
   */
  static double[] masked(double[] holdings, boolean ownerOnly, PersonThreshold threshold) {
    if (ownerOnly && !threshold.discloses((long) holdings[4])) {
      return new double[] {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
    }
    return Arrays.copyOf(holdings, 4);
  }
}
