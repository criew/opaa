package io.opaa.security;

import io.opaa.instance.InstanceProperties;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@link LegacyCipherRetirement} from {@code cipher_legacy_retirements} (ADR-0053: "Datenbank"): a
 * purpose not yet retired is asked again at most once per heartbeat interval, so a retirement
 * recorded by another instance counts here within that interval; a retired one is latched for the
 * process. With {@link CipherFormatProperties#keyed()} {@code false} nothing is refused, and the
 * start of such an instance clears every retirement: it writes {@code enc:v1:} again, which the
 * re-encryption must still be able to read once the keyed format is opened again.
 *
 * <p>The {@link JdbcTemplate} is looked up on first use: the encryptors are built together with the
 * JPA converters, before the data source is ready.
 */
@Component
class StoredLegacyCipherRetirement implements LegacyCipherRetirement, ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(StoredLegacyCipherRetirement.class);

  private final boolean keyed;
  private final Supplier<JdbcTemplate> jdbc;
  private final long cacheNanos;
  private final LongSupplier nanoTime;

  private final Set<EncryptionPurpose> retired = ConcurrentHashMap.newKeySet();
  private final Map<EncryptionPurpose, Long> askedAt = new ConcurrentHashMap<>();

  @Autowired
  StoredLegacyCipherRetirement(
      ObjectProvider<JdbcTemplate> jdbc,
      InstanceProperties instance,
      CipherFormatProperties format) {
    this(format.keyed(), jdbc::getObject, instance.heartbeatInterval(), System::nanoTime);
  }

  StoredLegacyCipherRetirement(
      boolean keyed, Supplier<JdbcTemplate> jdbc, Duration cacheFor, LongSupplier nanoTime) {
    this.keyed = keyed;
    this.jdbc = jdbc;
    this.cacheNanos = cacheFor.toNanos();
    this.nanoTime = nanoTime;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!keyed) {
      reopen();
    }
  }

  /** Clears every retirement and every pending measurement; see the class Javadoc. */
  void reopen() {
    int cleared = jdbc.get().update("DELETE FROM cipher_legacy_retirements");
    retired.clear();
    askedAt.clear();
    if (cleared > 0) {
      log.info(
          "{}=false: enc:v1 and cleartext are read again and stay so until the re-encryption finds"
              + " none left with the keyed format open",
          CipherFormatProperties.KEYED_VARIABLE);
    }
  }

  @Override
  public boolean refusesLegacy(EncryptionPurpose purpose) {
    if (!keyed) {
      return false;
    }
    if (retired.contains(purpose)) {
      return true;
    }
    long now = nanoTime.getAsLong();
    Long at = askedAt.get(purpose);
    if (at != null && now - at < cacheNanos) {
      return false;
    }
    boolean stored;
    try {
      stored =
          Boolean.TRUE.equals(
              jdbc.get()
                  .queryForObject(
                      "SELECT EXISTS (SELECT 1 FROM cipher_legacy_retirements"
                          + " WHERE purpose = ? AND retired_at IS NOT NULL)",
                      Boolean.class,
                      purpose.name()));
    } catch (DataAccessException e) {
      log.warn("Whether {} secrets refuse enc:v1 and cleartext could not be read", purpose, e);
      return false;
    }
    askedAt.put(purpose, now);
    if (stored) {
      retired.add(purpose);
    }
    return stored;
  }

  @Override
  public void measured(EncryptionPurpose purpose, int unbound) {
    if (!keyed) {
      return;
    }
    if (unbound > 0) {
      jdbc.get()
          .update(
              "DELETE FROM cipher_legacy_retirements WHERE purpose = ? AND retired_at IS NULL",
              purpose.name());
      return;
    }
    jdbc.get()
        .update(
            "INSERT INTO cipher_legacy_retirements (purpose, measured_since) VALUES (?, now())"
                + " ON CONFLICT (purpose) DO NOTHING",
            purpose.name());
    int recorded =
        jdbc.get()
            .update(
                "UPDATE cipher_legacy_retirements SET retired_at = now() WHERE purpose = ?"
                    + " AND retired_at IS NULL AND measured_since <= now() - ?::interval",
                purpose.name(),
                CONFIRMATION.toSeconds() + " seconds");
    if (recorded > 0) {
      retired.add(purpose);
      log.info(
          "No stored {} secret is in the format enc:v1 or in cleartext any more; such values are"
              + " refused from now on",
          purpose);
    }
  }
}
