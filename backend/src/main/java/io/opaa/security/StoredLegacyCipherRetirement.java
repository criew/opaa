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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@link LegacyCipherRetirement} from {@code cipher_legacy_retirements} (ADR-0053: "Datenbank"): a
 * purpose not yet retired is asked again at most once per heartbeat interval, so a retirement
 * recorded by another instance counts here within that interval; a retired one is latched for the
 * process. {@link CipherFormatProperties#keyed()} {@code false} refuses nothing, whatever is
 * stored.
 *
 * <p>The {@link JdbcTemplate} is looked up on first use: the encryptors are built together with the
 * JPA converters, before the data source is ready.
 */
@Component
class StoredLegacyCipherRetirement implements LegacyCipherRetirement {

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
    Boolean stored =
        jdbc.get()
            .queryForObject(
                "SELECT EXISTS (SELECT 1 FROM cipher_legacy_retirements WHERE purpose = ?)",
                Boolean.class,
                purpose.name());
    askedAt.put(purpose, now);
    if (Boolean.TRUE.equals(stored)) {
      retired.add(purpose);
      return true;
    }
    return false;
  }

  @Override
  public void retire(EncryptionPurpose purpose) {
    int recorded =
        jdbc.get()
            .update(
                "INSERT INTO cipher_legacy_retirements (purpose, retired_at) VALUES (?, now())"
                    + " ON CONFLICT (purpose) DO NOTHING",
                purpose.name());
    retired.add(purpose);
    if (recorded > 0) {
      log.info(
          "No stored {} secret is in the format enc:v1 or in cleartext any more; such values are"
              + " refused from now on",
          purpose);
    }
  }
}
