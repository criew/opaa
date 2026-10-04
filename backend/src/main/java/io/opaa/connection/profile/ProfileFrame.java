package io.opaa.connection.profile;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.common.ValidationException;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.SourceSettings;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What a profile sets for every library on it: proxy, TLS switch and the connector defaults it
 * names. They override the library's own values; a library may repeat them but never differ, and it
 * stores only what the frame leaves to it.
 */
final class ProfileFrame {

  private final ConnectionProfile profile;
  private final Map<String, Object> defaults;

  private ProfileFrame(ConnectionProfile profile) {
    this.profile = profile;
    ConnectorData data = ConnectorData.fromJson(profile.getConnectorSettings());
    this.defaults = data == null ? Map.of() : data.asMap();
  }

  static ProfileFrame of(ConnectionProfile profile) {
    return new ProfileFrame(profile);
  }

  ConnectionProfile profile() {
    return profile;
  }

  String proxy() {
    return profile.getSourceProxy();
  }

  boolean insecureSsl() {
    return profile.isSourceInsecureSsl();
  }

  /**
   * Refuses {@code requested} where it differs from the frame: a proxy, a skipped certificate check
   * or a connector setting other than the profile's. An absent value always fits.
   *
   * @throws ValidationException (German 400) naming the field
   */
  void requireFits(SourceSettings requested) {
    String proxy = blankToNull(requested.sourceProxy());
    if (proxy != null && !proxy.equals(proxy())) {
      throw new ValidationException(
          "sourceProxy gibt der Zugang „"
              + profile.getName()
              + "“ vor; die Bibliothek setzt ihn"
              + " nicht selbst");
    }
    if (requested.sourceInsecureSsl() && !insecureSsl()) {
      throw new ValidationException(
          "Die Zertifikatsprüfung gibt der Zugang „"
              + profile.getName()
              + "“ vor; die Bibliothek setzt sie nicht selbst aus");
    }
    ConnectorData own = requested.connectorSettings();
    if (own == null) {
      return;
    }
    for (Map.Entry<String, Object> entry : defaults.entrySet()) {
      if (own.has(entry.getKey()) && !Objects.equals(own.get(entry.getKey()), entry.getValue())) {
        throw new ValidationException(
            "sourceSettings."
                + entry.getKey()
                + " gibt der Zugang „"
                + profile.getName()
                + "“ mit "
                + entry.getValue()
                + " vor; ein anderer Wert ist nicht zulässig");
      }
    }
  }

  /** {@code own} with every key the profile sets replaced by its value; {@code null} for none. */
  ConnectorData over(ConnectorData own) {
    if (defaults.isEmpty()) {
      return own;
    }
    Map<String, Object> values = new LinkedHashMap<>();
    if (own != null) {
      values.putAll(own.asMap());
    }
    values.putAll(defaults);
    return ConnectorData.of(values);
  }

  /** {@code settings} with proxy, TLS switch and connector settings of the frame applied. */
  SourceSettings over(SourceSettings settings) {
    return new SourceSettings(
        settings.sourcePath(),
        settings.sourceUrl(),
        proxy(),
        settings.sourceCredentials(),
        insecureSsl(),
        settings.connectorSettings() == null ? null : over(settings.connectorSettings()),
        settings.credentialsKind());
  }

  /**
   * What of {@code validated} the library stores itself: no proxy, no skipped check, its connector
   * settings without the keys the profile sets, and no secret on a profile without sign-in.
   */
  SourceSettings ownPart(SourceSettings validated) {
    ConnectorData settings = validated.connectorSettings();
    if (settings != null && !defaults.isEmpty()) {
      Map<String, Object> own = new LinkedHashMap<>(settings.asMap());
      own.keySet().removeAll(defaults.keySet());
      settings = own.isEmpty() ? null : ConnectorData.of(own);
    }
    boolean signsIn = profile.getAuthMethod() != ConnectionAuthMethod.NONE;
    return new SourceSettings(
        validated.sourcePath(),
        validated.sourceUrl(),
        null,
        signsIn ? validated.sourceCredentials() : null,
        false,
        settings,
        signsIn ? validated.credentialsKind() : null);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
