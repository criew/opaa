package io.opaa.connection.web;

import io.opaa.api.dto.ConnectionLogEntryResponse;
import io.opaa.api.dto.ConnectionLogPage;
import io.opaa.api.dto.ConnectionLogRetentionResponse;
import io.opaa.connection.log.ConnectionLogEntry;
import io.opaa.connection.log.ConnectionLogRetentionSettings;
import org.springframework.data.domain.Page;

/** Maps connection log entries and the retention row onto their responses, field for field. */
final class ConnectionLogResponseMapper {

  private ConnectionLogResponseMapper() {}

  static ConnectionLogPage toPage(Page<ConnectionLogEntry> page) {
    return new ConnectionLogPage(
        page.getContent().stream().map(ConnectionLogResponseMapper::toResponse).toList(),
        page.getNumber(),
        page.getSize(),
        page.hasNext());
  }

  static ConnectionLogEntryResponse toResponse(ConnectionLogEntry entry) {
    return new ConnectionLogEntryResponse(
            entry.getEventId(),
            entry.getRecordedAt(),
            entry.getOrganizationId(),
            entry.getEventType(),
            entry.getActorRef(),
            entry.getOwnerKind(),
            entry.getProfileId(),
            entry.getProfileName())
        .personRef(entry.getPersonRef())
        .libraryId(entry.getLibraryId())
        .accountLabel(entry.getAccountLabel())
        .cause(entry.getCause());
  }

  static ConnectionLogRetentionResponse toRetentionResponse(
      ConnectionLogRetentionSettings settings) {
    return new ConnectionLogRetentionResponse(
            settings.getRetentionMonths(), settings.getUpdatedAt())
        .lastCutoff(settings.getLastCutoff());
  }
}
