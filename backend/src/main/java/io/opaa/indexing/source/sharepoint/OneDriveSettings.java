package io.opaa.indexing.source.sharepoint;

import io.opaa.common.ValidationException;
import io.opaa.indexing.spi.ConnectorData;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The connector settings of a OneDrive library in {@code source_settings} (ADR-0058): the one
 * {@code drive} - {@code {driveId, folders?, name?}} as a SharePoint library entry - and the
 * optional own full-sync rhythm {@code fullSyncIntervalDays}. No secret, no address.
 */
record OneDriveSettings(SharePointLibrary drive, Integer fullSyncIntervalDays) {

  static final Set<String> KEYS = Set.of("drive", "fullSyncIntervalDays");

  /**
   * Reads and checks {@code data}.
   *
   * @throws ValidationException (German 400) for anything the connector does not accept
   */
  static OneDriveSettings read(ConnectorData data) {
    data.requireOnly(KEYS);
    if (data.get("drive") == null) {
      throw new ValidationException("sourceSettings.drive: das OneDrive ist erforderlich");
    }
    SharePointLibrary drive =
        SharePointLibrary.fromJson(data.get("drive"), SharePointLibrary.Terms.DRIVE);
    return new OneDriveSettings(
        drive, SharePointSettings.interval(data.get("fullSyncIntervalDays")));
  }

  /** The settings stored on a library, {@code null} for none. */
  static OneDriveSettings stored(ConnectorData data) {
    return data == null ? null : read(data);
  }

  ConnectorData toData() {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("drive", drive.toJson());
    if (fullSyncIntervalDays != null) {
      json.put("fullSyncIntervalDays", fullSyncIntervalDays);
    }
    return ConnectorData.of(json);
  }
}
