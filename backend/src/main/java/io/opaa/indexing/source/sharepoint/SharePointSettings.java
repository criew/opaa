package io.opaa.indexing.source.sharepoint;

import io.opaa.common.ValidationException;
import io.opaa.indexing.source.ConnectorData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The connector settings of a SharePoint library in {@code source_settings} (ADR-0040, Nachtrag
 * „SharePoint“): one to fifty document {@code libraries} and the optional own full-sync rhythm
 * {@code fullSyncIntervalDays}. No secret.
 */
record SharePointSettings(List<SharePointLibrary> libraries, Integer fullSyncIntervalDays) {

  static final Set<String> KEYS = Set.of("libraries", "fullSyncIntervalDays");
  static final int MAX_LIBRARIES = 50;
  static final int MAX_FULL_SYNC_INTERVAL_DAYS = 365;

  SharePointSettings {
    libraries = List.copyOf(libraries);
  }

  /**
   * Reads and checks {@code data}.
   *
   * @throws ValidationException (German 400) for anything the connector does not accept
   */
  static SharePointSettings read(ConnectorData data) {
    data.requireOnly(KEYS);
    if (!(data.get("libraries") instanceof List<?> list) || list.isEmpty()) {
      throw new ValidationException(
          "sourceSettings.libraries: mindestens eine Dokumentbibliothek ist erforderlich");
    }
    if (list.size() > MAX_LIBRARIES) {
      throw new ValidationException(
          "sourceSettings.libraries: höchstens " + MAX_LIBRARIES + " Bibliotheken sind möglich");
    }
    Set<String> drives = new LinkedHashSet<>();
    List<SharePointLibrary> libraries = new ArrayList<>();
    for (Object item : list) {
      SharePointLibrary library = SharePointLibrary.fromJson(item);
      if (!drives.add(library.driveId())) {
        throw new ValidationException(
            "sourceSettings.libraries: Jede Dokumentbibliothek darf nur einmal vorkommen; ihre"
                + " Ordner stehen gemeinsam in einem Eintrag");
      }
      libraries.add(library);
    }
    return new SharePointSettings(libraries, interval(data.get("fullSyncIntervalDays")));
  }

  /** The settings stored on a library, {@code null} for none. */
  static SharePointSettings stored(ConnectorData data) {
    return data == null ? null : read(data);
  }

  ConnectorData toData() {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("libraries", libraries.stream().map(SharePointLibrary::toJson).toList());
    if (fullSyncIntervalDays != null) {
      json.put("fullSyncIntervalDays", fullSyncIntervalDays);
    }
    return ConnectorData.of(json);
  }

  /** The configured library of {@code driveId}, {@code null} when the settings name none. */
  SharePointLibrary library(String driveId) {
    return libraries.stream()
        .filter(library -> library.driveId().equals(driveId))
        .findFirst()
        .orElse(null);
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
