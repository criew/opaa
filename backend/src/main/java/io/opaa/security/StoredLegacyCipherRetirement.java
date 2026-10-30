package io.opaa.security;

import io.opaa.instance.CurrentInstance;
import io.opaa.instance.InstanceProperties;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
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
 * {@link LegacyCipherRetirement} from {@code cipher_legacy_retirements} (ADR-0053: "Datenbank"):
 * each instance asks at most once per heartbeat interval per purpose, so a retirement recorded or
 * lapsed elsewhere counts here within that interval. A retirement lapses once an instance of
 * another version has sent a heartbeat after it; the next measurement then deletes it and starts
 * anew. With {@link CipherFormatProperties#keyed()} {@code false} nothing is refused, and the start
 * of such an instance clears every retirement: it writes {@code enc:v1:} again, which the
 * re-encryption must still be able to read once the keyed format is opened again.
 *
 * <p>Two measurements without unbound values are at least {@link #MIN_CONFIRMATION} and two
 * heartbeat intervals apart: longer than an instance keeps the answer of {@link CipherFormatGate}
 * or of this class, and than a transaction that wrote such a value before the first one takes to
 * commit. The {@link JdbcTemplate} is looked up on first use: the encryptors are built together
 * with the JPA converters, before the data source is ready.
 */
@Component
class StoredLegacyCipherRetirement implements LegacyCipherRetirement, ApplicationRunner {

  static final Duration MIN_CONFIRMATION = Duration.ofMinutes(5);

  private static final Logger log = LoggerFactory.getLogger(StoredLegacyCipherRetirement.class);

  private final boolean keyed;
  private final Supplier<JdbcTemplate> jdbc;
  private final Predicate<Instant> anotherVersionRanSince;
  private final long cacheNanos;
  private final Duration confirmation;
  private final LongSupplier nanoTime;

  private record Answer(long at, boolean refuses) {}

  private final Map<EncryptionPurpose, Answer> answers = new ConcurrentHashMap<>();

  @Autowired
  StoredLegacyCipherRetirement(
      ObjectProvider<JdbcTemplate> jdbc,
      ObjectProvider<CurrentInstance> currentInstance,
      InstanceProperties instance,
      CipherFormatProperties format) {
    this(
        format.keyed(),
        jdbc::getObject,
        since -> currentInstance.getObject().anotherVersionRanSince(since),
        instance.heartbeatInterval(),
        System::nanoTime);
  }

  StoredLegacyCipherRetirement(
      boolean keyed,
      Supplier<JdbcTemplate> jdbc,
      Predicate<Instant> anotherVersionRanSince,
      Duration cacheFor,
      LongSupplier nanoTime) {
    this.keyed = keyed;
    this.jdbc = jdbc;
    this.anotherVersionRanSince = anotherVersionRanSince;
    this.cacheNanos = cacheFor.toNanos();
    Duration twoBeats = cacheFor.multipliedBy(2);
    this.confirmation = twoBeats.compareTo(MIN_CONFIRMATION) > 0 ? twoBeats : MIN_CONFIRMATION;
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
    answers.clear();
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
    long now = nanoTime.getAsLong();
    Answer cached = answers.get(purpose);
    if (cached != null && now - cached.at() < cacheNanos) {
      return cached.refuses();
    }
    boolean refuses;
    try {
      Instant retiredAt = retiredAt(purpose);
      refuses = retiredAt != null && !anotherVersionRanSince.test(retiredAt);
    } catch (DataAccessException e) {
      log.warn("Whether {} secrets refuse enc:v1 and cleartext could not be read", purpose, e);
      refuses = false;
    }
    answers.put(purpose, new Answer(now, refuses));
    return refuses;
  }

  @Override
  public void measured(EncryptionPurpose purpose, int unbound) {
    if (!keyed) {
      return;
    }
    Instant retiredAt = retiredAt(purpose);
    if (retiredAt != null && anotherVersionRanSince.test(retiredAt)) {
      jdbc.get().update("DELETE FROM cipher_legacy_retirements WHERE purpose = ?", purpose.name());
      log.info(
          "An instance of another version ran since {} secrets refused enc:v1 and cleartext; they"
              + " are read again until the re-encryption finds none left",
          purpose);
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
                confirmation.toSeconds() + " seconds");
    if (recorded > 0) {
      answers.remove(purpose);
      log.info(
          "No stored {} secret is in the format enc:v1 or in cleartext any more; such values are"
              + " refused from now on",
          purpose);
    }
  }

  private Instant retiredAt(EncryptionPurpose purpose) {
    List<Timestamp> retired =
        jdbc.get()
            .queryForList(
                "SELECT retired_at FROM cipher_legacy_retirements"
                    + " WHERE purpose = ? AND retired_at IS NOT NULL",
                Timestamp.class,
                purpose.name());
    return retired.isEmpty() ? null : retired.getFirst().toInstant();
  }
}
