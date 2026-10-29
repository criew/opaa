package io.opaa.indexing.source.sharepoint;

import io.opaa.common.ValidationException;
import io.opaa.indexing.spi.ConnectorData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The connector settings of a SharePoint library in {@code source_settings} (ADR-0047): one to
 * fifty entries under {@code libraries} and the optional own full-sync rhythm {@code
 * fullSyncIntervalDays}. No secret. An entry is a document library, or {@code {"site": ...}}
 * without a drive for every document library the site has when a run starts ({@link #sites}); a run
 * puts those it found among the {@link #libraries} ({@link #resolvedTo}). Stored entries are either
 * all whole sites or all single libraries, never a mix.
 */
record SharePointSettings(
    List<SharePointLibrary> libraries, Integer fullSyncIntervalDays, List<SharePointSite> sites) {

  static final Set<String> KEYS = Set.of("libraries", "fullSyncIntervalDays");
  static final int MAX_LIBRARIES = 50;
  static final int MAX_FULL_SYNC_INTERVAL_DAYS = 365;

  SharePointSettings {
    libraries = List.copyOf(libraries);
    sites = List.copyOf(sites);
  }

  SharePointSettings(List<SharePointLibrary> libraries, Integer fullSyncIntervalDays) {
    this(libraries, fullSyncIntervalDays, List.of());
  }

  /** Whether an entry stands for all document libraries of a site. */
  boolean hasWholeSites() {
    return !sites.isEmpty();
  }

  /**
   * These settings with {@code found}, the document libraries of {@link #sites} as one run listed
   * them, beside the chosen ones; a library chosen on its own keeps its folder filter.
   */
  SharePointSettings resolvedTo(List<SharePointLibrary> found) {
    List<SharePointLibrary> all = new ArrayList<>(libraries);
    for (SharePointLibrary library : found) {
      if (library(library.driveId()) == null) {
        all.add(library);
      }
    }
    return new SharePointSettings(all, fullSyncIntervalDays, sites);
  }

  static final String MIXED_SELECTION =
      "sourceSettings.libraries: Entweder ganze Sites mit allen Dokumentbibliotheken oder einzeln"
          + " gewählte Dokumentbibliotheken, nicht beides in einer Bibliothek";

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
    Map<String, SharePointSite> sites = new LinkedHashMap<>();
    for (Object item : list) {
      if (item instanceof Map<?, ?> map && !map.containsKey("driveId") && map.containsKey("site")) {
        if (map.size() != 1) {
          throw new ValidationException(
              "sourceSettings.libraries: Ein Eintrag für alle Dokumentbibliotheken einer Site ist"
                  + " {\"site\": {\"id\": \"<ID>\", \"name\": \"…\"}} ohne weitere Felder");
        }
        SharePointSite site = SharePointSite.fromJson(map.get("site"), "sourceSettings.libraries");
        if (sites.putIfAbsent(site.id(), site) != null) {
          throw new ValidationException(
              "sourceSettings.libraries: Jede Site darf nur einmal mit allen Dokumentbibliotheken"
                  + " vorkommen");
        }
        continue;
      }
      SharePointLibrary library = SharePointLibrary.fromJson(item);
      if (!drives.add(library.driveId())) {
        throw new ValidationException(
            "sourceSettings.libraries: Jede Dokumentbibliothek darf nur einmal vorkommen; ihre"
                + " Ordner stehen gemeinsam in einem Eintrag");
      }
      libraries.add(library);
    }
    if (!sites.isEmpty() && !libraries.isEmpty()) {
      throw new ValidationException(MIXED_SELECTION);
    }
    return new SharePointSettings(
        libraries, interval(data.get("fullSyncIntervalDays")), List.copyOf(sites.values()));
  }

  /** The settings stored on a library, {@code null} for none. */
  static SharePointSettings stored(ConnectorData data) {
    return data == null ? null : read(data);
  }

  ConnectorData toData() {
    Map<String, Object> json = new LinkedHashMap<>();
    List<Object> entries = new ArrayList<>();
    libraries.forEach(library -> entries.add(library.toJson()));
    sites.forEach(site -> entries.add(Map.of("site", site.toJson())));
    json.put("libraries", entries);
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
  static Integer interval(Object value) {
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
