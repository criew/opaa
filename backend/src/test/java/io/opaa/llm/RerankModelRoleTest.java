package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.llm.RerankClient.RerankUnavailableException;
import io.opaa.llm.RerankClient.ScoredCandidate;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The four states of the rerank role and the three guarantees around them: a contradiction between
 * switch and configuration is visible rather than silent, no state ever carries the access key, and
 * neither {@code currentStatus()} nor {@code usable()} pays for a network round trip.
 */
class RerankModelRoleTest {

  private static final String KEY = "s3cret-key";
  private static final String ENDPOINT = "http://localhost:8081/v1";
  private static final String SECRET_IN_BASE_URL = "benutzer:geheim";

  private static RerankProperties properties(boolean enabled, String baseUrl, String model) {
    return new RerankProperties(enabled, baseUrl, model, KEY, Duration.ofSeconds(5));
  }

  private static RerankModelRole role(RerankClient client, boolean enabled, String url, String m) {
    return new RerankModelRole(properties(enabled, url, m), client);
  }

  @Test
  void aSwitchedOffRoleIsDisabledAndNeverProbed() {
    RerankClient client = mock(RerankClient.class);
    RerankModelRole role = role(client, false, ENDPOINT, "m");

    RerankRoleStatus status = role.currentStatus();

    assertThat(status.state()).isEqualTo(RerankRoleState.DISABLED);
    assertThat(status.diagnostic()).isNull();
    assertThat(role.usable()).isFalse();
    verify(client, never()).probe(any());
  }

  @Test
  void aSwitchedOnButUnboundRoleContradictsTheOperatorsIntent() {
    RerankClient client = mock(RerankClient.class);
    RerankModelRole role = role(client, true, "", "");

    RerankRoleStatus status = role.currentStatus();

    assertThat(status.state()).isEqualTo(RerankRoleState.UNCONFIGURED);
    assertThat(status.baseUrl()).isNull();
    assertThat(status.modelIdentifier()).isNull();
    assertThat(status.diagnostic()).contains("opaa.rerank.base-url");
    assertThat(role.usable()).isFalse();
    verify(client, never()).probe(any());
  }

  /**
   * A base address carrying userinfo must never reach the status, a log line or the endpoint. The
   * role treats it as no configuration at all: the operator's intent (switch on) is contradicted
   * either way, and the address itself may not be echoed anywhere.
   */
  @Test
  void aBaseUrlWithCredentialsIsRejectedAndNeverAppearsInTheStatus() {
    RerankClient client = mock(RerankClient.class);
    RerankModelRole role =
        role(client, true, "https://" + SECRET_IN_BASE_URL + "@reranker.example.internal/v1", "m");

    RerankRoleStatus status = role.currentStatus();

    assertThat(status.state()).isEqualTo(RerankRoleState.UNCONFIGURED);
    assertThat(status.baseUrl()).isNull();
    assertThat(status.diagnostic()).doesNotContain(SECRET_IN_BASE_URL);
    assertThat(role.usable()).isFalse();
    assertThat(role.refresh().baseUrl()).isNull();
    assertThat(role.rerank("q", List.of("a"))).isEmpty();
    verify(client, never()).probe(any());
    verify(client, never()).rerank(any(), anyString(), any());
  }

  /** Half a configuration is no configuration: an endpoint without a model cannot be called. */
  @Test
  void aRoleWithAnEndpointButNoModelIsUnconfigured() {
    assertThat(role(mock(RerankClient.class), true, ENDPOINT, "").currentStatus().state())
        .isEqualTo(RerankRoleState.UNCONFIGURED);
  }

  /**
   * Before the first probe the role is not usable. The startup check probes on {@code
   * ApplicationReadyEvent}, so this state is not reachable by a request - but "not probed yet" must
   * never read as "ready".
   */
  @Test
  void aBoundRoleIsNotUsableUntilItHasBeenProbed() {
    RerankModelRole role = role(mock(RerankClient.class), true, ENDPOINT, "m");

    assertThat(role.currentStatus().state()).isEqualTo(RerankRoleState.UNREACHABLE);
    assertThat(role.currentStatus().diagnostic()).contains("not probed yet");
    assertThat(role.usable()).isFalse();
  }

  @Test
  void aBoundRoleWhoseEndpointAnswersIsReadyAfterTheProbe() {
    RerankClient client = mock(RerankClient.class);
    when(client.probe(any())).thenReturn(null);
    RerankModelRole role = role(client, true, ENDPOINT, "m");

    assertThat(role.refresh().state()).isEqualTo(RerankRoleState.READY);
    assertThat(role.currentStatus().baseUrl()).isEqualTo(ENDPOINT);
    assertThat(role.currentStatus().diagnostic()).isNull();
    assertThat(role.usable()).isTrue();
  }

  @Test
  void aBoundRoleWhoseEndpointStaysSilentIsUnreachable() {
    RerankClient client = mock(RerankClient.class);
    when(client.probe(any()))
        .thenReturn(new RerankClient.ProbeFailure("connection refused", false));
    RerankModelRole role = role(client, true, ENDPOINT, "m");

    RerankRoleStatus status = role.refresh();

    assertThat(status.state()).isEqualTo(RerankRoleState.UNREACHABLE);
    assertThat(status.diagnostic()).isEqualTo("connection refused");
    assertThat(role.usable()).isFalse();
  }

  /** The two hot paths must answer from the last known state, never from a fresh call. */
  @Test
  void neitherTheStatusNorTheUsabilityCheckProbes() {
    RerankClient client = mock(RerankClient.class);
    when(client.probe(any())).thenReturn(null);
    RerankModelRole role = role(client, true, ENDPOINT, "m");
    role.refresh();

    for (int i = 0; i < 5; i++) {
      role.currentStatus();
      role.usable();
    }

    verify(client, org.mockito.Mockito.times(1)).probe(any());
  }

  /** A failed call is a state change, not an exception the query has to survive. */
  @Test
  void aFailedCallYieldsNoScoresAndMovesTheRoleToUnreachable() {
    RerankClient client = mock(RerankClient.class);
    when(client.probe(any())).thenReturn(null);
    when(client.rerank(any(), anyString(), any()))
        .thenThrow(new RerankUnavailableException("endpoint not reachable"));
    RerankModelRole role = role(client, true, ENDPOINT, "m");
    role.refresh();

    assertThat(role.rerank("Frage", List.of("a"))).isEmpty();
    assertThat(role.currentStatus().state()).isEqualTo(RerankRoleState.UNREACHABLE);
    assertThat(role.currentStatus().diagnostic()).isEqualTo("endpoint not reachable");
    assertThat(role.currentStatus().timedOut()).isFalse();
  }

  /**
   * The finding #1154 exists for: a call that ran into the configured timeout still moves the role
   * to {@link RerankRoleState#UNREACHABLE} - a run must fall back to the fused order either way -
   * but the status keeps that the endpoint was merely slow, not unreachable.
   */
  @Test
  void aTimedOutCallMovesTheRoleToUnreachableButMarksItAsTimedOut() {
    RerankClient client = mock(RerankClient.class);
    when(client.probe(any())).thenReturn(null);
    when(client.rerank(any(), anyString(), any()))
        .thenThrow(new RerankClient.RerankTimeoutException("request timed out", null));
    RerankModelRole role = role(client, true, ENDPOINT, "m");
    role.refresh();

    assertThat(role.rerank("Frage", List.of("a"))).isEmpty();
    assertThat(role.currentStatus().state()).isEqualTo(RerankRoleState.UNREACHABLE);
    assertThat(role.currentStatus().diagnostic()).isEqualTo("request timed out");
    assertThat(role.currentStatus().timedOut()).isTrue();
  }

  /** The same distinction holds for the startup/scheduled probe, not only for a live call. */
  @Test
  void aProbeThatTimesOutMarksTheStatusAsTimedOut() {
    RerankClient client = mock(RerankClient.class);
    when(client.probe(any())).thenReturn(new RerankClient.ProbeFailure("request timed out", true));
    RerankModelRole role = role(client, true, ENDPOINT, "m");

    RerankRoleStatus status = role.refresh();

    assertThat(status.state()).isEqualTo(RerankRoleState.UNREACHABLE);
    assertThat(status.timedOut()).isTrue();
  }

  @Test
  void aSuccessfulCallReturnsTheScoresAndKeepsTheRoleReady() {
    RerankClient client = mock(RerankClient.class);
    when(client.rerank(any(), anyString(), any())).thenReturn(List.of(new ScoredCandidate(0, 1.0)));
    RerankModelRole role = role(client, true, ENDPOINT, "m");

    assertThat(role.rerank("Frage", List.of("a"))).containsExactly(new ScoredCandidate(0, 1.0));
    assertThat(role.currentStatus().state()).isEqualTo(RerankRoleState.READY);
  }

  /** A switched-off role never reaches the client, whatever a caller asks it to do. */
  @Test
  void aSwitchedOffRoleReturnsNoScores() {
    RerankClient client = mock(RerankClient.class);

    assertThat(role(client, false, ENDPOINT, "m").rerank("Frage", List.of("a"))).isEmpty();
    verify(client, never()).rerank(any(), anyString(), any());
  }

  /**
   * The reading a measurement compares before and after a run: the last known state alone cannot
   * answer "did reranking hold throughout?", because a role that fails and recovers reads READY
   * again afterwards.
   */
  @Test
  void aFailedCallIsCountedAndStaysCountedAfterTheRoleRecovers() {
    RerankClient client = mock(RerankClient.class);
    RerankModelRole role = role(client, true, ENDPOINT, "m");
    when(client.rerank(any(), anyString(), any()))
        .thenThrow(new RerankUnavailableException("connection refused"))
        .thenReturn(List.of(new ScoredCandidate(0, 1.0)));

    assertThat(role.rerank("Frage", List.of("a"))).isEmpty();
    assertThat(role.degradedCallCount()).isEqualTo(1);

    assertThat(role.rerank("Frage", List.of("a"))).hasSize(1);
    assertThat(role.usable()).isTrue();
    assertThat(role.degradedCallCount()).isEqualTo(1);
  }

  /**
   * An endpoint that answers with an empty ranking leaves the caller with the order it already had,
   * exactly as a failure does - so it counts the same, even though the role stays reachable.
   */
  @Test
  void anEmptyRankingForANonEmptyRequestCountsAsDegradedToo() {
    RerankClient client = mock(RerankClient.class);
    RerankModelRole role = role(client, true, ENDPOINT, "m");
    when(client.rerank(any(), anyString(), any())).thenReturn(List.of());

    assertThat(role.rerank("Frage", List.of("a"))).isEmpty();

    assertThat(role.degradedCallCount()).isEqualTo(1);
    assertThat(role.usable()).isTrue();
  }

  @Test
  void noStateEverCarriesTheAccessKey() {
    RerankClient client = mock(RerankClient.class);
    when(client.probe(any()))
        .thenReturn(new RerankClient.ProbeFailure("connection refused", false));
    RerankModelRole unreachable = role(client, true, ENDPOINT, "m");
    unreachable.refresh();

    for (RerankRoleStatus status :
        List.of(
            unreachable.currentStatus(),
            role(client, true, "", "").currentStatus(),
            role(client, false, ENDPOINT, "m").currentStatus())) {
      assertThat(status.toString()).doesNotContain(KEY);
    }
  }
}
