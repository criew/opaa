package io.opaa.instance;

import io.opaa.compliance.FingerprintKeys;
import io.opaa.compliance.FingerprintKeys.FingerprintKey;
import io.opaa.compliance.PropertyValues;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.env.Environment;

/**
 * Reads the values of the configuration fingerprint from this instance (ADR-0053, Entscheidung 4):
 * the keys of {@link FingerprintKeys}, plus two values no key carries - the authentication profile
 * among the active profiles, and the time zone the {@link Clock} bean and every cron of this
 * process run in.
 */
class ConfigurationFingerprintReader {

  /** The pseudo key of the authentication profile. */
  static final String AUTH_PROFILE_KEY = "spring.profiles.active";

  /** The pseudo key of the time zone, named after the JVM property that sets it. */
  static final String TIME_ZONE_KEY = "user.timezone";

  /** The profiles that choose the authentication mode; other profiles may differ. */
  private static final Set<String> AUTH_PROFILES = Set.of("oidc", "dev");

  private final Environment environment;
  private final Clock clock;
  private final List<FingerprintKey> keys;

  ConfigurationFingerprintReader(Environment environment, Clock clock) {
    this(environment, clock, FingerprintKeys.delivered());
  }

  ConfigurationFingerprintReader(Environment environment, Clock clock, List<FingerprintKey> keys) {
    this.environment = environment;
    this.clock = clock;
    this.keys = List.copyOf(keys);
  }

  /** Every value of the fingerprint, sorted by key. */
  List<FingerprintValue> read() {
    List<FingerprintValue> values = new ArrayList<>();
    for (FingerprintKey key : keys) {
      values.add(
          new FingerprintValue(
              key.name(), PropertyValues.read(environment, key.name()), key.secret()));
    }
    values.add(new FingerprintValue(AUTH_PROFILE_KEY, authProfile(), false));
    values.add(new FingerprintValue(TIME_ZONE_KEY, clock.getZone().getId(), false));
    values.sort((a, b) -> a.key().compareTo(b.key()));
    return List.copyOf(values);
  }

  private String authProfile() {
    return Arrays.stream(environment.getActiveProfiles())
        .filter(AUTH_PROFILES::contains)
        .sorted()
        .collect(Collectors.joining(","));
  }
}
