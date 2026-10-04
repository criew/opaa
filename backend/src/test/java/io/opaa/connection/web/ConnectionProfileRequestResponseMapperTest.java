package io.opaa.connection.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.dto.ConnectionProfileRequestResponse;
import io.opaa.common.ValidationException;
import io.opaa.connection.request.ConnectionProfileRequest;
import io.opaa.connection.request.ConnectionProfileRequestService.RequestPage;
import io.opaa.connection.request.ConnectionProfileRequestService.RequestView;
import io.opaa.connection.request.ProfileRequestState;
import io.opaa.knowledge.SourceType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ConnectionProfileRequestResponseMapperTest {

  @Test
  void everyFieldOfAResolvedRequestIsMapped() {
    UUID id = UUID.randomUUID();
    UUID profile = UUID.randomUUID();
    Instant created = Instant.parse("2026-10-04T08:00:00Z");
    Instant resolved = Instant.parse("2026-10-04T09:00:00Z");
    ConnectionProfileRequest request = Mockito.mock(ConnectionProfileRequest.class);
    Mockito.when(request.getId()).thenReturn(id);
    Mockito.when(request.getSourceType()).thenReturn(SourceType.of("NEXTCLOUD"));
    Mockito.when(request.getServerUrl()).thenReturn("https://cloud.example.org");
    Mockito.when(request.getReason()).thenReturn("<b>Grund</b>");
    Mockito.when(request.getState()).thenReturn(ProfileRequestState.DONE);
    Mockito.when(request.getCreatedAt()).thenReturn(created);
    Mockito.when(request.getResolvedAt()).thenReturn(resolved);
    Mockito.when(request.getProfileId()).thenReturn(profile);
    Mockito.when(request.getAnswer()).thenReturn("Angelegt");

    ConnectionProfileRequestResponse response =
        ConnectionProfileRequestResponseMapper.toResponse(
            new RequestView(request, "Dev User", "Zugang Nextcloud"));

    assertThat(response.getId()).isEqualTo(id);
    assertThat(response.getSourceType()).isEqualTo("NEXTCLOUD");
    assertThat(response.getServerUrl()).isEqualTo("https://cloud.example.org");
    assertThat(response.getReason()).isEqualTo("<b>Grund</b>");
    assertThat(response.getState().name()).isEqualTo("DONE");
    assertThat(response.getRequestedByName()).isEqualTo("Dev User");
    assertThat(response.getCreatedAt()).isEqualTo(created);
    assertThat(response.getResolvedAt()).isEqualTo(resolved);
    assertThat(response.getProfile().getId()).isEqualTo(profile);
    assertThat(response.getProfile().getName()).isEqualTo("Zugang Nextcloud");
    assertThat(response.getAnswer()).isEqualTo("Angelegt");

    assertThat(
            ConnectionProfileRequestResponseMapper.toResponse(
                    new RequestPage(List.of(new RequestView(request, "Dev User", null)), 7, 1, 5))
                .getItems())
        .singleElement()
        .satisfies(item -> assertThat(item.getProfile()).isNull());
  }

  @Test
  void anUnknownStateParameterIsA400AndAnAbsentOneMeansEveryState() {
    assertThat(ConnectionProfileRequestResponseMapper.toState((String) null)).isNull();
    assertThat(ConnectionProfileRequestResponseMapper.toState("OPEN"))
        .isEqualTo(ProfileRequestState.OPEN);
    assertThatThrownBy(() -> ConnectionProfileRequestResponseMapper.toState("open"))
        .isInstanceOf(ValidationException.class);
  }
}
