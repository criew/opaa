package io.opaa.indexing.source.googledrive;

import io.opaa.common.ValidationException;
import io.opaa.indexing.source.ConnectorData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The connector settings of a Google Drive library in {@code source_settings} (ADR-0040,
 * Entscheidungen 4 and 5): one to fifty {@code scopes}, the optional imitated account {@code
 * subject} and the optional own full-sync rhythm {@code fullSyncIntervalDays}. No secret.
 */
record GoogleDriveSettings(
    List<GoogleDriveScope> scopes, String subject, Integer fullSyncIntervalDays) {

  static final Set<String> KEYS = Set.of("scopes", "subject", "fullSyncIntervalDays");
  static final int MAX_SCOPES = 50;
  static final int MAX_FULL_SYNC_INTERVAL_DAYS = 365;

  private static final Pattern ACCOUNT = Pattern.compile("[^@\\s]{1,64}@[^@\\s]{1,255}");

  GoogleDriveSettings {
    scopes = List.copyOf(scopes);
  }

  /**
   * Reads and checks {@code data}.
   *
   * @throws ValidationException (German 400) for anything the connector does not accept
   */
  static GoogleDriveSettings read(ConnectorData data) {
    data.requireOnly(KEYS);
    if (!(data.get("scopes") instanceof List<?> list) || list.isEmpty()) {
      throw new ValidationException(
          "sourceSettings.scopes: mindestens ein Bereich ist erforderlich");
    }
    if (list.size() > MAX_SCOPES) {
      throw new ValidationException(
          "sourceSettings.scopes: höchstens " + MAX_SCOPES + " Bereiche sind möglich");
    }
    Set<String> keys = new LinkedHashSet<>();
    List<GoogleDriveScope> scopes = new ArrayList<>();
    for (Object item : list) {
      GoogleDriveScope scope = GoogleDriveScope.fromJson(item);
      if (keys.add(scope.key())) {
        scopes.add(scope);
      }
    }
    String subject = subject(data.get("subject"));
    if (subject == null
        && scopes.stream().anyMatch(s -> s.kind() == GoogleDriveScope.Kind.MY_DRIVE)) {
      throw new ValidationException(
          "sourceSettings.scopes: „Meine Ablage“ setzt ein imitiertes Konto (subject) voraus; ein"
              + " Dienstkonto selbst besitzt keine Dateien");
    }
    return new GoogleDriveSettings(scopes, subject, interval(data.get("fullSyncIntervalDays")));
  }

  /** The settings stored on a library, {@code null} for none; a stored document is trusted. */
  static GoogleDriveSettings stored(ConnectorData data) {
    return data == null ? null : read(data);
  }

  /** The imitated account of {@code data}, {@code null} for none or unreadable settings. */
  static String subjectOf(ConnectorData data) {
    if (data == null || !(data.get("subject") instanceof String subject) || subject.isBlank()) {
      return null;
    }
    return subject.trim();
  }

  ConnectorData toData() {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("scopes", scopes.stream().map(GoogleDriveScope::toJson).toList());
    if (subject != null) {
      json.put("subject", subject);
    }
    if (fullSyncIntervalDays != null) {
      json.put("fullSyncIntervalDays", fullSyncIntervalDays);
    }
    return ConnectorData.of(json);
  }

  private static String subject(Object value) {
    if (value == null || (value instanceof String text && text.isBlank())) {
      return null;
    }
    if (!(value instanceof String text) || !ACCOUNT.matcher(text.trim()).matches()) {
      throw new ValidationException(
          "sourceSettings.subject: Das imitierte Konto ist eine E-Mail-Adresse der Domäne");
    }
    return text.trim();
  }

  /** {@code 0} returns to the instance's rhythm, like an absent value. */
  private static Integer interval(Object value) {
    if (value == null || (value instanceof Number zero && zero.doubleValue() == 0)) {
      return null;
    }
    if (!(value instanceof Number number)
        || number.doubleValue() != number.intValue()
        || number.intValue() < 1
        || number.intValue() > MAX_FULL_SYNC_INTERVAL_DAYS) {
      throw new ValidationException(
          "sourceSettings.fullSyncIntervalDays: eine ganze Zahl von 1 bis "
              + MAX_FULL_SYNC_INTERVAL_DAYS);
    }
    return number.intValue();
  }
}
