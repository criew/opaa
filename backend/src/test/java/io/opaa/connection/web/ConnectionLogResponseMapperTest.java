package io.opaa.connection.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.dto.ConnectionLogEntryResponse;
import io.opaa.api.types.ConnectionEndCause;
import io.opaa.api.types.ConnectionLogEventType;
import io.opaa.api.types.ConnectionLogOwnerKind;
import io.opaa.connection.log.ConnectionLogEntry;
import io.opaa.connection.log.ConnectionLogRetentionSettings;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConnectionLogResponseMapperTest {

  @Test
  void mapsEveryField() {
    ConnectionLogEntry entry = mock(ConnectionLogEntry.class);
    UUID eventId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();
    UUID profileId = UUID.randomUUID();
    UUID libraryId = UUID.randomUUID();
    Instant recordedAt = Instant.parse("2026-10-04T08:00:00Z");
    when(entry.getEventId()).thenReturn(eventId);
    when(entry.getRecordedAt()).thenReturn(recordedAt);
    when(entry.getOrganizationId()).thenReturn(organizationId);
    when(entry.getEventType()).thenReturn(ConnectionLogEventType.EMERGENCY_DISCONNECTED);
    when(entry.getActorRef()).thenReturn("actor");
    when(entry.getOwnerKind()).thenReturn(ConnectionLogOwnerKind.LIBRARY);
    when(entry.getPersonRef()).thenReturn("person");
    when(entry.getLibraryId()).thenReturn(libraryId);
    when(entry.getAccountLabel()).thenReturn("svc-opaa@rathaus.de");
    when(entry.getProfileId()).thenReturn(profileId);
    when(entry.getProfileName()).thenReturn("Nextcloud Rathaus");
    when(entry.getCause()).thenReturn(ConnectionEndCause.EMERGENCY);

    ConnectionLogEntryResponse response = ConnectionLogResponseMapper.toResponse(entry);

    assertThat(response.getEventId()).isEqualTo(eventId);
    assertThat(response.getRecordedAt()).isEqualTo(recordedAt);
    assertThat(response.getOrganizationId()).isEqualTo(organizationId);
    assertThat(response.getEventType()).isEqualTo(ConnectionLogEventType.EMERGENCY_DISCONNECTED);
    assertThat(response.getActorRef()).isEqualTo("actor");
    assertThat(response.getOwnerKind()).isEqualTo(ConnectionLogOwnerKind.LIBRARY);
    assertThat(response.getPersonRef()).isEqualTo("person");
    assertThat(response.getLibraryId()).isEqualTo(libraryId);
    assertThat(response.getAccountLabel()).isEqualTo("svc-opaa@rathaus.de");
    assertThat(response.getProfileId()).isEqualTo(profileId);
    assertThat(response.getProfileName()).isEqualTo("Nextcloud Rathaus");
    assertThat(response.getCause()).isEqualTo(ConnectionEndCause.EMERGENCY);
  }

  @Test
  void mapsTheRetentionRow() {
    ConnectionLogRetentionSettings settings = mock(ConnectionLogRetentionSettings.class);
    Instant cutoff = Instant.parse("2025-10-01T00:00:00Z");
    Instant updatedAt = Instant.parse("2026-10-04T08:00:00Z");
    when(settings.getRetentionMonths()).thenReturn(12);
    when(settings.getLastCutoff()).thenReturn(cutoff);
    when(settings.getUpdatedAt()).thenReturn(updatedAt);

    var response = ConnectionLogResponseMapper.toRetentionResponse(settings);

    assertThat(response.getRetentionMonths()).isEqualTo(12);
    assertThat(response.getLastCutoff()).isEqualTo(cutoff);
    assertThat(response.getUpdatedAt()).isEqualTo(updatedAt);
  }
}
