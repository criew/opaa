package io.opaa.indexing.spi;

import io.opaa.knowledge.SourceType;
import java.time.Duration;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * The rhythm of a connector's full sync, one source per level: the library's own days, else the
 * days the system administration set for the source type, else the factory default the connector
 * names in its code. Read from the database on every call, without a cache, so a change holds on
 * every instance from the next decision on (ADR-0053).
 */
public class FullSyncRhythm {

  public static final int MIN_DAYS = 1;
  public static final int MAX_DAYS = 365;

  private final FullSyncRhythmSettingRepository repository;

  public FullSyncRhythm(FullSyncRhythmSettingRepository repository) {
    this.repository = repository;
  }

  /** The interval a library of {@code type} runs under; {@code ownDays} may be {@code null}. */
  public Duration interval(SourceType type, Duration factoryDefault, Integer ownDays) {
    return ownDays != null ? Duration.ofDays(ownDays) : installationDefault(type, factoryDefault);
  }

  /**
   * The interval of a library of {@code type} without a rhythm of its own; {@code null} for a type
   * whose every run is a full one ({@code factoryDefault} {@code null}).
   */
  public Duration installationDefault(SourceType type, Duration factoryDefault) {
    if (factoryDefault == null) {
      return null;
    }
    return administrationDays(type).map(Duration::ofDays).orElse(factoryDefault);
  }

  /** {@code interval} in whole days, a sub-day interval as one; {@code null} stays {@code null}. */
  public static Integer wholeDays(Duration interval) {
    return interval == null ? null : (int) Math.max(1, interval.toDays());
  }

  /** The days the administration set for {@code type}, empty while the factory default applies. */
  public Optional<Integer> administrationDays(SourceType type) {
    return repository.findDays(type.key());
  }

  /**
   * Sets the days of {@code type}; {@code null} returns to the factory default.
   *
   * @throws IllegalArgumentException for a value outside {@value #MIN_DAYS} to {@value #MAX_DAYS}
   */
  @Transactional
  public void setDays(SourceType type, Integer days) {
    if (days == null) {
      repository.clear(type.key());
      return;
    }
    if (days < MIN_DAYS || days > MAX_DAYS) {
      throw new IllegalArgumentException("full-sync rhythm out of range: " + days);
    }
    repository.upsert(type.key(), days);
  }
}
