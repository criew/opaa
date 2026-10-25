package io.opaa.settings.web;

import io.opaa.api.dto.SettingsChange;
import io.opaa.api.dto.SettingsDocument;
import io.opaa.api.dto.SettingsImportResult;
import java.util.LinkedHashMap;
import java.util.Map;

/** Maps between the settings document and its domain record, and the result of an import. */
final class SettingsTransferResponseMapper {

  private SettingsTransferResponseMapper() {}

  static SettingsDocument toResponse(io.opaa.settings.SettingsDocument document) {
    SettingsDocument response = new SettingsDocument();
    response.setFormat(document.format());
    response.setFormatVersion(document.formatVersion());
    response.setExportedAt(document.exportedAt());
    response.setSections(new LinkedHashMap<>(document.sections()));
    return response;
  }

  static io.opaa.settings.SettingsDocument toDomain(SettingsDocument request) {
    Map<String, Map<String, Object>> sections =
        request.getSections() == null ? null : new LinkedHashMap<>(request.getSections());
    return new io.opaa.settings.SettingsDocument(
        request.getFormat(), request.getFormatVersion(), request.getExportedAt(), sections);
  }

  static SettingsImportResult toResponse(io.opaa.settings.SettingsImportResult result) {
    SettingsImportResult response = new SettingsImportResult();
    response.setApplied(result.applied());
    for (io.opaa.settings.SettingsImportResult.Change change : result.changes()) {
      SettingsChange item =
          new SettingsChange(change.section(), change.sectionLabel(), change.key());
      item.setBefore(change.before());
      item.setAfter(change.after());
      response.addChangesItem(item);
    }
    return response;
  }
}
