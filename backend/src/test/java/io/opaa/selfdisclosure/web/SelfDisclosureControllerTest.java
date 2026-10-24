package io.opaa.selfdisclosure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.common.ServiceUnavailableException;
import io.opaa.selfdisclosure.SelfDisclosureService;
import io.opaa.selfdisclosure.SelfDisclosureService.Outcome;
import io.opaa.selfdisclosure.SelfDisclosureService.Run;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.apache.coyote.CloseNowException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** How the download ends on the connection for each outcome of the stream. */
class SelfDisclosureControllerTest {

  private final SelfDisclosureService service = mock(SelfDisclosureService.class);
  private final SelfDisclosureController controller = new SelfDisclosureController(service);
  private final MockHttpServletResponse response = new MockHttpServletResponse();

  @BeforeEach
  void setUp() {
    Run run =
        new Run(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            LocalDate.of(2026, 10, 24),
            Instant.parse("2026-10-24T10:00:00Z"),
            0L);
    when(service.start("ticket")).thenReturn(run);
    when(service.installationZone()).thenReturn(ZoneOffset.UTC);
  }

  // regression guard for #3024: nobody listens any more, so nothing is thrown and nothing logged
  @Test
  void aClientThatWentAwayEndsTheDownloadWithoutAFailure() throws Exception {
    when(service.stream(any(), any())).thenReturn(Outcome.CLIENT_DISCONNECTED);
    response.setCommitted(true);

    controller.downloadSelfDisclosure("ticket", response);

    assertThat(response.getStatus()).isEqualTo(200);
  }

  /**
   * Deadline and shutdown are expected: the container closes the connection on its own signal and
   * logs it at DEBUG only, the service has already logged the abort in one line.
   */
  @Test
  void anExpectedEndByTheServerAfterTheStartClosesTheConnectionQuietly() {
    when(service.stream(any(), any())).thenReturn(Outcome.ENDED_BY_SERVER);
    response.setCommitted(true);

    assertThatThrownBy(() -> controller.downloadSelfDisclosure("ticket", response))
        .isInstanceOf(CloseNowException.class);
  }

  @Test
  void aFailureAfterTheStartBreaksTheConnectionVisibly() {
    when(service.stream(any(), any())).thenReturn(Outcome.FAILED);
    response.setCommitted(true);

    assertThatThrownBy(() -> controller.downloadSelfDisclosure("ticket", response))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void anExportEndedBeforeTheStartAnswers503() {
    for (Outcome outcome : new Outcome[] {Outcome.ENDED_BY_SERVER, Outcome.FAILED}) {
      MockHttpServletResponse uncommitted = new MockHttpServletResponse();
      when(service.stream(any(), any())).thenReturn(outcome);

      assertThatThrownBy(() -> controller.downloadSelfDisclosure("ticket", uncommitted))
          .isInstanceOf(ServiceUnavailableException.class);
      assertThat(uncommitted.getHeader("Content-Disposition")).isNull();
    }
  }
}
