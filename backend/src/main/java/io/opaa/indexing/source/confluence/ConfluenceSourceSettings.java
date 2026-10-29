package io.opaa.indexing.source.confluence;

import io.opaa.common.ValidationException;
import io.opaa.indexing.spi.ConnectorData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The connector settings of a {@code CONFLUENCE} library (ADR-0023, ADR-0038): the edition, the
 * space selection or {@code allSpaces} for every space the access sees (ADR-0023, Entscheidung 1),
 * and the library's own full-sync rhythm in days, stored as {@code {"edition", "spaces": [{"key",
 * "name"}], "allSpaces", "fullSyncIntervalDays"}} with {@code allSpaces} only when true. In a
 * request every part may be absent ({@code null}) - on a change that leaves the stored part alone;
 * an element of {@code spaces} without a key reads as {@code null} and is refused by the
 * connector's validation.
 */
public record ConfluenceSourceSettings(
    ConfluenceEdition edition,
    List<ConfluenceSpaceSelection> spaces,
    Integer fullSyncIntervalDays,
    Boolean allSpaces) {

  static final String EDITION = "edition";
  static final String SPACES = "spaces";
  static final String ALL_SPACES = "allSpaces";
  static final String FULL_SYNC_INTERVAL_DAYS = "fullSyncIntervalDays";
  static final java.util.Set<String> KEYS =
      java.util.Set.of(EDITION, SPACES, ALL_SPACES, FULL_SYNC_INTERVAL_DAYS);

  private static final ConfluenceSourceSettings NONE =
      new ConfluenceSourceSettings(null, List.of(), null);

  /** Settings with an explicit selection, or none given. */
  public ConfluenceSourceSettings(
      ConfluenceEdition edition,
      List<ConfluenceSpaceSelection> spaces,
      Integer fullSyncIntervalDays) {
    this(edition, spaces, fullSyncIntervalDays, null);
  }

  /** Whether the scope is every space the access sees rather than a selection. */
  public boolean all() {
    return Boolean.TRUE.equals(allSpaces);
  }

  /** The settings a library carries; no edition and no spaces for {@code null}. */
  public static ConfluenceSourceSettings stored(ConnectorData stored) {
    return stored == null ? NONE : read(stored);
  }

  /**
   * Reads {@code data} part by part; an absent part stays {@code null}.
   *
   * @throws ValidationException for a part of the wrong kind
   */
  public static ConfluenceSourceSettings read(ConnectorData data) {
    if (data == null) {
      return new ConfluenceSourceSettings(null, null, null);
    }
    return new ConfluenceSourceSettings(
        edition(data.get(EDITION)),
        spaces(data.get(SPACES)),
        days(data.get(FULL_SYNC_INTERVAL_DAYS)),
        all(data.get(ALL_SPACES)));
  }

  /** Every part that is present, the spaces in the order given. */
  public ConnectorData toData() {
    Map<String, Object> json = new LinkedHashMap<>();
    if (edition != null) {
      json.put(EDITION, edition.name());
    }
    if (spaces != null) {
      List<Map<String, Object>> entries = new ArrayList<>();
      for (ConfluenceSpaceSelection space : spaces) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("key", space.getSpaceKey());
        entry.put("name", space.getSpaceName());
        entries.add(entry);
      }
      json.put(SPACES, entries);
    }
    if (allSpaces != null) {
      json.put(ALL_SPACES, allSpaces);
    }
    if (fullSyncIntervalDays != null) {
      json.put(FULL_SYNC_INTERVAL_DAYS, fullSyncIntervalDays);
    }
    return ConnectorData.of(json);
  }

  /** The stored form: the spaces ordered by key, the order a run lists them in. */
  ConfluenceSourceSettings sortedByKey() {
    return new ConfluenceSourceSettings(
        edition,
        spaces == null
            ? null
            : spaces.stream()
                .sorted(Comparator.comparing(ConfluenceSpaceSelection::getSpaceKey))
                .toList(),
        fullSyncIntervalDays,
        all() ? Boolean.TRUE : null);
  }

  /** The selection, empty when none is stored. */
  public List<ConfluenceSpaceSelection> spaceSelection() {
    return spaces == null ? List.of() : spaces;
  }

  private static ConfluenceEdition edition(Object value) {
    if (value == null) {
      return null;
    }
    try {
      return ConfluenceEdition.valueOf(value.toString());
    } catch (IllegalArgumentException e) {
      throw new ValidationException("sourceSettings.edition ist keine bekannte Edition");
    }
  }

  private static List<ConfluenceSpaceSelection> spaces(Object value) {
    if (value == null) {
      return null;
    }
    if (!(value instanceof List<?> list)) {
      throw new ValidationException("sourceSettings.spaces muss eine Liste sein");
    }
    List<ConfluenceSpaceSelection> spaces = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof Map<?, ?> entry && entry.get("key") != null) {
        Object name = entry.get("name");
        spaces.add(
            new ConfluenceSpaceSelection(
                entry.get("key").toString(), name == null ? null : name.toString()));
      } else {
        spaces.add(null);
      }
    }
    return spaces;
  }

  private static Boolean all(Object value) {
    if (value == null) {
      return null;
    }
    if (!(value instanceof Boolean flag)) {
      throw new ValidationException("sourceSettings.allSpaces muss true oder false sein");
    }
    return flag;
  }

  private static Integer days(Object value) {
    if (value == null) {
      return null;
    }
    if (!(value instanceof Number number) || number.doubleValue() != number.intValue()) {
      throw new ValidationException(
          "sourceSettings.fullSyncIntervalDays muss eine ganze Zahl sein");
    }
    return number.intValue();
  }
}
