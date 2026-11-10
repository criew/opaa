package io.opaa.library;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.opaa.knowledge.PersonalStorageQuota;
import io.opaa.knowledge.SharedLibraryQuota;
import io.opaa.observability.PolledGauges;
import io.opaa.permission.PersonThreshold;
import java.time.Duration;
import java.util.function.DoubleSupplier;
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

  private static final String OF_KIND = " JOIN assets a ON a.id = l.id WHERE a.owner_only = ?";

  static final String LIBRARIES_SQL = "SELECT count(*) FROM knowledge_libraries l" + OF_KIND;

  private static final String DOCUMENTS_OF_KIND =
      " FROM documents d JOIN knowledge_libraries l ON l.id = d.library_id" + OF_KIND;

  static final String DOCUMENTS_SQL = "SELECT count(*)" + DOCUMENTS_OF_KIND;

  static final String CHUNKS_SQL = "SELECT coalesce(sum(d.chunk_count), 0)" + DOCUMENTS_OF_KIND;

  static final String STORAGE_SQL =
      "SELECT coalesce(sum(coalesce(d.file_size, 0)), 0)" + DOCUMENTS_OF_KIND;

  static final String PRIVATE_OWNERS_SQL =
      "SELECT count(DISTINCT a.owner_user_id) FROM knowledge_libraries l" + OF_KIND;

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
      gauges
          .gauge(
              "opaa.inventory.libraries",
              kind,
              null,
              "Knowledge libraries",
              masked(jdbc, threshold, ownerOnly, PolledGauges.sql(jdbc, LIBRARIES_SQL, ownerOnly)))
          .gauge(
              "opaa.inventory.documents",
              kind,
              null,
              "Documents in knowledge libraries",
              masked(jdbc, threshold, ownerOnly, PolledGauges.sql(jdbc, DOCUMENTS_SQL, ownerOnly)))
          .gauge(
              "opaa.inventory.chunks",
              kind,
              null,
              "Indexed chunks of the documents",
              masked(jdbc, threshold, ownerOnly, PolledGauges.sql(jdbc, CHUNKS_SQL, ownerOnly)))
          .gauge(
              "opaa.inventory.storage.used",
              kind,
              "bytes",
              "Storage the documents occupy, counted like the storage limit",
              masked(jdbc, threshold, ownerOnly, PolledGauges.sql(jdbc, STORAGE_SQL, ownerOnly)));
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

  /** {@code read} for shared libraries; for private ones only from the minimum group size on. */
  private static DoubleSupplier masked(
      JdbcTemplate jdbc, PersonThreshold threshold, boolean ownerOnly, DoubleSupplier read) {
    if (!ownerOnly) {
      return read;
    }
    DoubleSupplier owners = PolledGauges.sql(jdbc, PRIVATE_OWNERS_SQL, true);
    return () -> threshold.discloses((long) owners.getAsDouble()) ? read.getAsDouble() : Double.NaN;
  }
}
