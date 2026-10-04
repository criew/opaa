package io.opaa.connection.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.dto.ConnectionLogEntryResponse;
import io.opaa.api.types.ConnectionEndCause;
import io.opaa.api.types.ConnectionLogEventType;
import io.opaa.connection.log.ConnectionLogEntry;
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
    Instant recordedAt = Instant.parse("2026-10-04T08:00:00Z");
    when(entry.getEventId()).thenReturn(eventId);
    when(entry.getRecordedAt()).thenReturn(recordedAt);
    when(entry.getOrganizationId()).thenReturn(organizationId);
    when(entry.getEventType()).thenReturn(ConnectionLogEventType.EMERGENCY_DISCONNECTED);
    when(entry.getActorRef()).thenReturn("actor");
    when(entry.getPersonRef()).thenReturn("person");
    when(entry.getProfileId()).thenReturn(profileId);
    when(entry.getProfileName()).thenReturn("Nextcloud Rathaus");
    when(entry.getCause()).thenReturn(ConnectionEndCause.EMERGENCY);

    ConnectionLogEntryResponse response = ConnectionLogResponseMapper.toResponse(entry);

    assertThat(response.getEventId()).isEqualTo(eventId);
    assertThat(response.getRecordedAt()).isEqualTo(recordedAt);
    assertThat(response.getOrganizationId()).isEqualTo(organizationId);
    assertThat(response.getEventType()).isEqualTo(ConnectionLogEventType.EMERGENCY_DISCONNECTED);
    assertThat(response.getActorRef()).isEqualTo("actor");
    assertThat(response.getPersonRef()).isEqualTo("person");
    assertThat(response.getProfileId()).isEqualTo(profileId);
    assertThat(response.getProfileName()).isEqualTo("Nextcloud Rathaus");
    assertThat(response.getCause()).isEqualTo(ConnectionEndCause.EMERGENCY);
  }
}
