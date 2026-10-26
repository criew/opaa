package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.common.DatabaseClock;
import io.opaa.common.FeatureFlags;
import io.opaa.instance.InvalidationSubscriber;
import io.opaa.instance.PostgresInvalidationBus;
import io.opaa.llm.RerankClient.RerankUnavailableException;
import io.opaa.llm.RerankClient.ScoredCandidate;
import io.opaa.security.SettingsEncryptor;
import io.opaa.test.OpaaIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

/**
 * The state of the probed model roles with two instances against one database (ADR-0053,
 * Entscheidung 12): instance A records what it observes at the endpoint through this context's
 * {@link JdbcModelRoleObservations}, instance B holds its own copy behind a second invalidation
 * bus. Whatever A observes - a failed rerank call, a probe that finds the endpoint back - B decides
 * on as well, so the same question is reranked on both or on neither.
 */
@OpaaIntegrationTest
class ModelRoleTwoInstanceIntegrationTest {

  private static final Duration DELIVERY = Duration.ofSeconds(10);
  private static final String ENDPOINT = "http://rerank.two-instances.invalid/v1";
  private static final String TRANSCRIPTION_ENDPOINT = "http://whisper.two-instances.invalid/v1";

  @Autowired private JdbcModelRoleObservations observationsOfA;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcConnectionDetails connectionDetails;
  @Autowired private DatabaseClock databaseClock;
  @Autowired private Clock clock;

  @Autowired private RerankModelSettingsRepository rerankRows;
  @Autowired private TranscriptionModelSettingsRepository transcriptionRows;
  @Autowired private SettingsEncryptor settingsEncryptor;
  @Autowired private ModelRoleEndpoints endpointsOfA;

  private PostgresInvalidationBus busOfB;
  private JdbcModelRoleObservations observationsOfB;
  private ModelRoleEndpoints endpointsOfB;

  @BeforeEach
  void startInstanceB() {
    deleteRows();
    StaticListableBeanFactory beansOfB = new StaticListableBeanFactory();
    busOfB =
        new PostgresInvalidationBus(
            jdbc,
            dataSource,
            PostgresInvalidationBus.opener(
                connectionDetails.getJdbcUrl(),
                connectionDetails.getUsername(),
                connectionDetails.getPassword(),
                "opaa-test-instance-b"),
            beansOfB.getBeanProvider(InvalidationSubscriber.class),
            Duration.ofMillis(200));
    observationsOfB = new JdbcModelRoleObservations(jdbc, busOfB, databaseClock);
    beansOfB.addBean("observations", observationsOfB);
    endpointsOfB = new ModelRoleEndpoints(rerankRows, transcriptionRows, settingsEncryptor, busOfB);
    beansOfB.addBean("endpoints", endpointsOfB);
    busOfB.start();
    assertThat(busOfB.isListening()).isTrue();
  }

  @AfterEach
  void stopInstanceB() {
    busOfB.stop();
    deleteRows();
    observationsOfA.invalidateAll();
  }

  /** regression guard: B decided on its own last call, unaware of what A had observed. */
  @Test
  void aRerankFailureObservedOnAStopsTheRerankingOnBToo() {
    RerankClient clientOfA = mock(RerankClient.class);
    RerankClient clientOfB = mock(RerankClient.class);
    when(clientOfA.probe(any(), any())).thenReturn(null);
    when(clientOfB.rerank(any(), any(), anyString(), any()))
        .thenReturn(List.of(new ScoredCandidate(0, 1.0)));
    RerankModelRole onA = rerankRole(clientOfA, observationsOfA);
    RerankModelRole onB = rerankRole(clientOfB, observationsOfB);
    onA.refresh();
    assertThat(onB.rerank("Frage", List.of("a"))).hasSize(1);
    assertThat(onB.usable()).isTrue();

    when(clientOfA.rerank(any(), any(), anyString(), any()))
        .thenThrow(new RerankUnavailableException("connection refused"));
    assertThat(onA.rerank("Frage", List.of("a"))).isEmpty();

    assertThat(onA.usable()).isFalse();
    await().atMost(DELIVERY).untilAsserted(() -> assertThat(onB.usable()).isFalse());
    assertThat(onB.currentStatus()).isEqualTo(onA.currentStatus());
  }

  @Test
  void aProbeOnTheInstanceHoldingTheLockBringsTheRoleBackOnBoth() {
    RerankClient clientOfA = mock(RerankClient.class);
    RerankClient clientOfB = mock(RerankClient.class);
    when(clientOfA.probe(any(), any()))
        .thenReturn(new RerankClient.ProbeFailure("connection refused", false))
        .thenReturn(null);
    RerankModelRole onA = rerankRole(clientOfA, observationsOfA);
    RerankModelRole onB = rerankRole(clientOfB, observationsOfB);
    onA.refresh();
    await().atMost(DELIVERY).untilAsserted(() -> assertThat(onB.usable()).isFalse());

    onA.refresh();

    await().atMost(DELIVERY).untilAsserted(() -> assertThat(onB.usable()).isTrue());
    verify(clientOfB, never()).probe(any(), any());
  }

  /** A change in the model administration on A reaches the roles of B without a restart. */
  @Test
  void aStoredRoleChangedOnAIsInForceOnB() {
    assertThat(endpointsOfB.rerank()).isEqualTo(RerankEndpoint.FACTORY_DEFAULT);
    assertThat(endpointsOfB.transcription().language()).isEqualTo("de");

    jdbc.update(
        "INSERT INTO rerank_model (id, enabled, base_url, model_identifier, updated_at)"
            + " VALUES (1, true, ?, 'rerank-test', now())",
        ENDPOINT);
    jdbc.update(
        "INSERT INTO transcription_model (id, enabled, base_url, model_identifier, updated_at)"
            + " VALUES (1, true, ?, 'whisper-test', now())",
        TRANSCRIPTION_ENDPOINT);

    await()
        .atMost(DELIVERY)
        .untilAsserted(
            () -> {
              assertThat(endpointsOfB.rerank())
                  .isEqualTo(new RerankEndpoint(true, ENDPOINT, "rerank-test", ""));
              assertThat(endpointsOfB.transcription().language()).isEmpty();
              assertThat(endpointsOfB.transcription().enabled()).isTrue();
            });
  }

  @Test
  void theMicrophoneFollowsTheTranscriptionStateObservedOnA() {
    TranscriptionClient clientOfA = mock(TranscriptionClient.class);
    when(clientOfA.probe(any(), any())).thenReturn(null);
    TranscriptionModelRole onA = transcriptionRole(clientOfA, observationsOfA);
    TranscriptionModelRole onB =
        transcriptionRole(mock(TranscriptionClient.class), observationsOfB);
    assertThat(onB.usable()).isFalse();

    onA.refresh();

    await().atMost(DELIVERY).untilAsserted(() -> assertThat(onB.usable()).isTrue());
  }

  private RerankModelRole rerankRole(RerankClient client, ModelRoleObservations observations) {
    return new RerankModelRole(
        new RerankProperties(Duration.ofSeconds(5), null),
        () -> new RerankEndpoint(true, ENDPOINT, "rerank-test", ""),
        client,
        clock,
        observations);
  }

  private TranscriptionModelRole transcriptionRole(
      TranscriptionClient client, ModelRoleObservations observations) {
    return new TranscriptionModelRole(
        new TranscriptionProperties(1024, Duration.ofSeconds(5), 1),
        () -> new TranscriptionEndpoint(true, TRANSCRIPTION_ENDPOINT, "whisper-test", "", "de"),
        client,
        new FeatureFlags(
            new MockEnvironment().withProperty("opaa.features.speech-input.enabled", "true")),
        observations);
  }

  private void deleteRows() {
    jdbc.update("DELETE FROM rerank_model");
    jdbc.update("DELETE FROM transcription_model");
    endpointsOfA.invalidateAll();
    jdbc.update(
        "DELETE FROM model_role_observations WHERE base_url IN (?, ?)",
        ENDPOINT,
        TRANSCRIPTION_ENDPOINT);
  }
}
