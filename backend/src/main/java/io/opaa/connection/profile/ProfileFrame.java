package io.opaa.connection.profile;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.common.ValidationException;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.ProfileDefaults;
import io.opaa.indexing.source.SourceSettings;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What a profile sets for every library on it: the transport to its server address and the
 * connector settings its declaration binds ({@link ProfileDefaults#boundKeys}). They override the
 * library's own values; a library may repeat a bound value but never differ, and it stores only
 * what the frame leaves to it - a secret only where the profile signs in with a personal secret.
 */
final class ProfileFrame {

  private final ConnectionProfile profile;
  private final Map<String, Object> defaults;
  private final Set<String> bound;

  private ProfileFrame(ConnectionProfile profile, ProfileDefaults declared) {
    this.profile = profile;
    ConnectorData data = ConnectorData.fromJson(profile.getConnectorSettings());
    this.defaults = data == null ? Map.of() : data.asMap();
    this.bound = declared.boundKeys(data);
  }

  static ProfileFrame of(ConnectionProfile profile, ProfileDefaults declared) {
    return new ProfileFrame(profile, declared);
  }

  ConnectionProfile profile() {
    return profile;
  }

  /** Proxy and certificate check for the profile's server address, and for no other target. */
  TransportRules serverTransport() {
    return new TransportRules(profile.getSourceProxy(), profile.isSourceInsecureSsl());
  }

  /** Whether a library on the profile holds its own personal secret. */
  boolean takesSecret() {
    return profile.getAuthMethod() == ConnectionAuthMethod.PERSONAL_SECRET;
  }

  /**
   * Refuses {@code requested} where it differs from the frame: a proxy, a skipped certificate check
   * or a bound connector setting other than the profile's. An absent value always fits.
   *
   * @throws ValidationException (German 400) naming the field
   */
  void requireFits(SourceSettings requested) {
    String proxy = blankToNull(requested.sourceProxy());
    TransportRules transport = serverTransport();
    if (proxy != null && !proxy.equals(transport.proxy())) {
      throw new ValidationException(
          "sourceProxy gibt der Zugang „"
              + profile.getName()
              + "“ vor; die Bibliothek setzt ihn nicht selbst");
    }
    if (requested.sourceInsecureSsl() && !transport.insecureSsl()) {
      throw new ValidationException(
          "Die Zertifikatsprüfung gibt der Zugang „"
              + profile.getName()
              + "“ vor; die Bibliothek setzt sie nicht selbst aus");
    }
    ConnectorData own = requested.connectorSettings();
    if (own == null) {
      return;
    }
    for (String key : bound) {
      if (own.has(key) && !Objects.equals(own.get(key), defaults.get(key))) {
        throw new ValidationException(
            "sourceSettings."
                + key
                + " gibt der Zugang „"
                + profile.getName()
                + "“"
                + (defaults.containsKey(key) ? " mit " + defaults.get(key) : "")
                + " vor; ein anderer Wert ist nicht zulässig");
      }
    }
  }

  /**
   * Refuses a secret sent for a library on a profile that does not sign in with a personal secret.
   *
   * @throws ValidationException (German 400)
   */
  void requireNoForeignSecret(SourceSettings requested) {
    if (requested.sourceCredentials() != null && !takesSecret()) {
      throw new ValidationException(
          "Der Zugang „"
              + profile.getName()
              + "“ meldet sich nicht mit Zugangsdaten der Bibliothek an; sourceCredentials"
              + " entfallen");
    }
  }

  /** {@code own} with every bound key replaced by the profile's value; {@code null} for none. */
  ConnectorData over(ConnectorData own) {
    if (bound.isEmpty()) {
      return own;
    }
    Map<String, Object> values = new LinkedHashMap<>();
    if (own != null) {
      values.putAll(own.asMap());
    }
    values.keySet().removeAll(bound);
    for (String key : bound) {
      if (defaults.containsKey(key)) {
        values.put(key, defaults.get(key));
      }
    }
    return values.isEmpty() ? null : ConnectorData.of(values);
  }

  /**
   * What of {@code validated} the library stores itself: no transport of its own, its connector
   * settings without the bound keys, and a secret only where the profile takes one.
   */
  SourceSettings ownPart(SourceSettings validated) {
    return new SourceSettings(
        validated.sourcePath(),
        validated.sourceUrl(),
        null,
        takesSecret() ? validated.sourceCredentials() : null,
        false,
        ownSettings(validated.connectorSettings()),
        takesSecret() ? validated.credentialsKind() : null);
  }

  /** {@code settings} without the bound keys; {@code null} when nothing is left. */
  ConnectorData ownSettings(ConnectorData settings) {
    if (settings == null || bound.isEmpty()) {
      return settings;
    }
    Map<String, Object> own = new LinkedHashMap<>(settings.asMap());
    own.keySet().removeAll(bound);
    return own.isEmpty() ? null : ConnectorData.of(own);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
