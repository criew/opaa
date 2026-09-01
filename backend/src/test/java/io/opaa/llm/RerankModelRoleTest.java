package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.llm.RerankClient.RerankUnavailableException;
import io.opaa.llm.RerankClient.ScoredCandidate;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The four states of the rerank role and the two guarantees around them: a contradiction between
 * switch and configuration is visible rather than silent, and no state ever carries the access key.
 */
class RerankModelRoleTest {

  private static final String KEY = "s3cret-key";

  private static RerankProperties properties(boolean enabled, String baseUrl, String model) {
    return new RerankProperties(enabled, baseUrl, model, KEY, Duration.ofSeconds(5));
  }

  @Test
  void aSwitchedOffRoleIsDisabledAndNeverProbed() {
    RerankClient client = mock(RerankClient.class);
    RerankModelRole role =
        new RerankModelRole(properties(false, "http://localhost:9/v1", "m"), client);

    RerankRoleStatus status = role.status();

    assertThat(status.state()).isEqualTo(RerankRoleState.DISABLED);
    assertThat(status.state().contradictsIntent()).isFalse();
    assertThat(status.checkedAt()).isNull();
    assertThat(role.usable()).isFalse();
  }

  @Test
  void aSwitchedOnButUnboundRoleContradictsTheOperatorsIntent() {
    RerankModelRole role = new RerankModelRole(properties(true, "", ""), mock(RerankClient.class));

    RerankRoleStatus status = role.status();

    assertThat(status.state()).isEqualTo(RerankRoleState.UNCONFIGURED);
    assertThat(status.state().contradictsIntent()).isTrue();
    assertThat(status.enabled()).isTrue();
    assertThat(role.usable()).isFalse();
  }

  /** Half a configuration is no configuration: an endpoint without a model cannot be called. */
  @Test
  void aRoleWithAnEndpointButNoModelIsUnconfigured() {
    RerankModelRole role =
        new RerankModelRole(properties(true, "http://localhost:9/v1", ""), mock(RerankClient.class));

    assertThat(role.status().state()).isEqualTo(RerankRoleState.UNCONFIGURED);
  }

  @Test
  void aBoundRoleWhoseEndpointAnswersIsActive() {
    RerankClient client = mock(RerankClient.class);
    when(client.probeFailureMessage(any())).thenReturn(null);
    RerankModelRole role =
        new RerankModelRole(properties(true, "http://localhost:9/v1", "m"), client);

    assertThat(role.status().state()).isEqualTo(RerankRoleState.ACTIVE);
    assertThat(role.usable()).isTrue();
    assertThat(role.status().checkedAt()).isNotNull();
  }

  @Test
  void aBoundRoleWhoseEndpointStaysSilentIsUnreachable() {
    RerankClient client = mock(RerankClient.class);
    when(client.probeFailureMessage(any())).thenReturn("Die Verbindung wurde abgelehnt.");
    RerankModelRole role =
        new RerankModelRole(properties(true, "http://localhost:9/v1", "m"), client);

    RerankRoleStatus status = role.status();

    assertThat(status.state()).isEqualTo(RerankRoleState.UNREACHABLE);
    assertThat(status.state().contradictsIntent()).isTrue();
    assertThat(status.message()).contains("Die Verbindung wurde abgelehnt.");
  }

  /** A failed call is a state change, not an exception the query has to survive. */
  @Test
  void aFailedCallYieldsNoScoresAndMovesTheRoleToUnreachable() {
    RerankClient client = mock(RerankClient.class);
    when(client.probeFailureMessage(any())).thenReturn(null);
    when(client.rerank(any(), anyString(), any()))
        .thenThrow(new RerankUnavailableException("Der Rerank-Endpunkt ist nicht erreichbar."));
    RerankModelRole role =
        new RerankModelRole(properties(true, "http://localhost:9/v1", "m"), client);

    assertThat(role.rerank("Frage", List.of("a"))).isEmpty();
    assertThat(role.status().state()).isEqualTo(RerankRoleState.UNREACHABLE);
  }

  @Test
  void aSuccessfulCallReturnsTheScoresAndKeepsTheRoleActive() {
    RerankClient client = mock(RerankClient.class);
    when(client.probeFailureMessage(any())).thenReturn(null);
    when(client.rerank(any(), anyString(), any()))
        .thenReturn(List.of(new ScoredCandidate(0, 1.0)));
    RerankModelRole role =
        new RerankModelRole(properties(true, "http://localhost:9/v1", "m"), client);

    assertThat(role.rerank("Frage", List.of("a"))).containsExactly(new ScoredCandidate(0, 1.0));
    assertThat(role.status().state()).isEqualTo(RerankRoleState.ACTIVE);
  }

  /** A switched-off role never reaches the client, whatever a caller asks it to do. */
  @Test
  void aSwitchedOffRoleReturnsNoScores() {
    RerankClient client = mock(RerankClient.class);
    RerankModelRole role =
        new RerankModelRole(properties(false, "http://localhost:9/v1", "m"), client);

    assertThat(role.rerank("Frage", List.of("a"))).isEmpty();
  }

  @Test
  void noStateEverCarriesTheAccessKey() {
    RerankClient client = mock(RerankClient.class);
    when(client.probeFailureMessage(any())).thenReturn("Der Rerank-Endpunkt ist nicht erreichbar.");
    RerankProperties properties = properties(true, "http://localhost:9/v1", "m");

    for (RerankRoleStatus status :
        List.of(
            new RerankModelRole(properties, client).status(),
            new RerankModelRole(properties(true, "", ""), client).status(),
            new RerankModelRole(properties(false, "http://localhost:9/v1", "m"), client).status())) {
      assertThat(status.toString()).doesNotContain(KEY);
      assertThat(status.message()).doesNotContain(KEY);
      assertThat(status.baseUrl()).doesNotContain(KEY);
    }
    assertThat(properties.describeWithoutSecrets()).doesNotContain(KEY);
  }
}
