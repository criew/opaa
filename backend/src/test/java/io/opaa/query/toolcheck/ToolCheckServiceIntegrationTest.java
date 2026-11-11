package io.opaa.query.toolcheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opaa.api.types.ToolCheckStatus;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.test.OpaaIntegrationTest;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A tool check against a fake OpenAI-compatible endpoint that answers every request with plain text
 * and never calls a tool: the check runs on its pool, completes all cases, fails the floor and
 * keeps the report, while the agent switch and the stored combination decide what is offered.
 */
@OpaaIntegrationTest
class ToolCheckServiceIntegrationTest {

  @Autowired private ToolCheckService service;
  @Autowired private ToolCheckGate gate;
  @Autowired private ToolCheckRepository repository;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ThreadPoolTaskExecutor toolCheckExecutor;
  @Autowired private MockMvc mockMvc;

  private HttpServer server;

  /** Whether the fake endpoint answers anything but the readiness call with a server error. */
  private volatile boolean failCases;

  /** Released once the first case reached the fake endpoint. */
  private volatile CountDownLatch firstCase = new CountDownLatch(1);

  private volatile CountDownLatch hold = new CountDownLatch(0);
  private UUID modelId;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", this::answer);
    server.start();
    modelId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO llm_models (id, display_name, base_url, model_identifier, max_tokens)"
            + " VALUES (?, 'Prüfmodell', ?, 'fake-model', 500)",
        modelId,
        baseUrl());
    doReturn(true).when(gate).open();
  }

  @AfterEach
  void tearDown() {
    hold.countDown();
    await().atMost(Duration.ofSeconds(60)).until(() -> !running() && !workerBusy());
    jdbc.update("DELETE FROM llm_tool_checks WHERE model_id = ?", modelId);
    jdbc.update("DELETE FROM llm_models WHERE id = ?", modelId);
    server.stop(0);
  }

  @Test
  void aCheckRunsEveryCaseAndKeepsTheReportWithoutTouchingTheCapability() {
    ToolCheckView started = service.start(modelId, 1);

    assertThat(started.check().getStatus()).isEqualTo(ToolCheckStatus.RUNNING);
    assertThat(started.check().getTotalRuns()).isEqualTo(ToolCallingCaseBase.load().cases().size());
    ToolCheckView done = awaitEnd();

    assertThat(done.check().getStatus()).isEqualTo(ToolCheckStatus.COMPLETED);
    assertThat(done.check().getCompletedRuns()).isEqualTo(done.check().getTotalRuns());
    assertThat(done.report().capabilityFloor().met()).isFalse();
    assertThat(done.report().byCategory()).containsKey(ToolCallingCase.CALLS_WHEN_NEEDED);
    assertThat(done.report().byCategory().get(ToolCallingCase.ABSTAINS_WHEN_NOT_NEEDED).passed())
        .isEqualTo(4);
    assertThat(done.report().caseBase().toolDefinition()).isEqualTo("PRODUCTION");
    assertThat(done.currentCombination()).isTrue();
    assertThat(done.measurementRun()).startsWith(done.check().getRunId()).contains("fake-model");
    assertThat(service.reportMarkdown(modelId, done.check().getId()))
        .contains("search_knowledge der Agentenschleife");
    assertThat(
            jdbc.queryForObject(
                "SELECT tool_capability FROM llm_models WHERE id = ?", String.class, modelId))
        .isEqualTo("UNVERIFIED");
  }

  @Test
  void aSecondCheckWaitsUntilTheFirstHasEnded() {
    hold = new CountDownLatch(1);
    service.start(modelId, 1);

    assertThatThrownBy(() -> service.start(modelId, 1)).isInstanceOf(ConflictException.class);
    hold.countDown();
    awaitEnd();
    assertThat(service.start(modelId, 1).check().getStatus()).isEqualTo(ToolCheckStatus.RUNNING);
  }

  @Test
  void aCheckOfAnotherAddressIsMarkedAsNotCurrent() {
    service.start(modelId, 1);
    awaitEnd();

    jdbc.update("UPDATE llm_models SET base_url = 'http://127.0.0.1:1/v1' WHERE id = ?", modelId);

    assertThat(service.latest(modelId).currentCombination()).isFalse();
  }

  @Test
  void anUnreachableModelFailsWithAGermanReason() {
    jdbc.update("UPDATE llm_models SET base_url = 'http://127.0.0.1:1/v1' WHERE id = ?", modelId);

    service.start(modelId, 1);
    ToolCheckView done = awaitEnd();

    assertThat(done.check().getStatus()).isEqualTo(ToolCheckStatus.FAILED);
    assertThat(done.check().getFailure()).contains("nicht erreichbar");
    assertThatThrownBy(() -> service.reportMarkdown(modelId, done.check().getId()))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void aStaleCheckIsGivenUpAsAborted() {
    hold = new CountDownLatch(1);
    UUID checkId = service.start(modelId, 1).check().getId();
    jdbc.update(
        "UPDATE llm_tool_checks SET heartbeat_at = clock_timestamp() - interval '1 day'"
            + " WHERE id = ?",
        checkId);

    assertThat(service.latest(modelId).check().getStatus()).isEqualTo(ToolCheckStatus.ABORTED);
    hold.countDown();
    await().atMost(Duration.ofSeconds(60)).until(() -> !workerBusy());
    assertThat(repository.findById(checkId).orElseThrow().getStatus())
        .as("an aborted check is never completed afterwards")
        .isEqualTo(ToolCheckStatus.ABORTED);
  }

  @Test
  void withAgentsSwitchedOffNothingIsOffered() {
    doReturn(false).when(gate).open();

    assertThatThrownBy(() -> service.start(modelId, 1)).isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> service.latest(modelId)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void errorsOfTheEndpointFailTheCheckInsteadOfMeasuringIt() {
    failCases = true;

    service.start(modelId, 1);
    ToolCheckView done = awaitEnd();

    assertThat(done.check().getStatus()).isEqualTo(ToolCheckStatus.FAILED);
    assertThat(done.check().getFailure()).contains("Fehler des Endpunkts");
    assertThat(done.measurementRun()).isNull();
  }

  @Test
  void switchingAgentsOffStopsARunningCheck() throws InterruptedException {
    hold = new CountDownLatch(1);
    service.start(modelId, 1);
    assertThat(firstCase.await(30, TimeUnit.SECONDS)).isTrue();

    doReturn(false).when(gate).open();
    hold.countDown();
    await().atMost(Duration.ofSeconds(60)).until(() -> !running() && !workerBusy());

    ToolCheck check = repository.findFirstByModelIdOrderByStartedAtDesc(modelId).orElseThrow();
    assertThat(check.getStatus()).isEqualTo(ToolCheckStatus.FAILED);
    assertThat(check.getFailure()).contains("Agenten");
    assertThat(check.getCompletedRuns()).isLessThan(check.getTotalRuns());
  }

  @Test
  void onlyTheNewestChecksOfAModelAreKept() {
    for (int i = 0; i < ToolCheckService.KEPT_PER_MODEL + 1; i++) {
      jdbc.update(
          "INSERT INTO llm_tool_checks (id, model_id, base_url, model_identifier, status,"
              + " repetitions, total_runs, started_at, heartbeat_at, finished_at)"
              + " VALUES (?, ?, 'x', 'fake-model', 'FAILED', 1, 28,"
              + " clock_timestamp() - make_interval(hours => ?), clock_timestamp(),"
              + " clock_timestamp())",
          UUID.randomUUID(),
          modelId,
          i + 1);
    }

    service.start(modelId, 1);
    awaitEnd();

    assertThat(repository.findByModelIdOrderByStartedAtDesc(modelId))
        .hasSize(ToolCheckService.KEPT_PER_MODEL);
  }

  @Test
  void theApiStartsAndReadsAChecksAndHandsOutItsReport() throws Exception {
    String path = "/api/v1/admin/models/" + modelId + "/tool-checks";
    mockMvc
        .perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"repetitions\":4}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"repetitions\":1}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("RUNNING"))
        .andExpect(jsonPath("$.modelIdentifier").value("fake-model"))
        .andExpect(jsonPath("$.repetitions").value(1))
        .andExpect(jsonPath("$.totalRuns").value(ToolCallingCaseBase.load().cases().size()));
    ToolCheckView done = awaitEnd();

    mockMvc
        .perform(get(path + "/latest"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(done.check().getId().toString()))
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.runId").value(done.check().getRunId()))
        .andExpect(jsonPath("$.currentCombination").value(true))
        .andExpect(jsonPath("$.floorMet").value(false))
        .andExpect(jsonPath("$.categories.length()").value(7))
        .andExpect(jsonPath("$.categories[0].category").exists())
        .andExpect(jsonPath("$.categories[0].summary").exists())
        .andExpect(jsonPath("$.measurementRun").value(done.measurementRun()));
    mockMvc
        .perform(get(path + "/" + done.check().getId() + "/report"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("text/markdown"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(
            header().string("Content-Security-Policy", org.hamcrest.Matchers.startsWith("sandbox")))
        .andExpect(
            header().string("Content-Disposition", org.hamcrest.Matchers.containsString(".md")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString(done.check().getRunId())));
    mockMvc
        .perform(
            get(
                "/api/v1/admin/models/"
                    + UUID.randomUUID()
                    + "/tool-checks/"
                    + done.check().getId()
                    + "/report"))
        .andExpect(status().isNotFound());
  }

  private ToolCheckView awaitEnd() {
    await().atMost(Duration.ofSeconds(60)).until(() -> !running() && !workerBusy());
    return service.latest(modelId);
  }

  private boolean running() {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM llm_tool_checks WHERE model_id = ? AND status = 'RUNNING'",
            Integer.class,
            modelId);
    return count != null && count > 0;
  }

  private boolean workerBusy() {
    return toolCheckExecutor.getActiveCount() > 0;
  }

  private String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
  }

  private void answer(HttpExchange exchange) throws IOException {
    String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    boolean readiness = request.contains("Antworte mit dem Wort: bereit");
    if (!readiness) {
      firstCase.countDown();
      try {
        hold.await(30, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    if (failCases && !readiness) {
      exchange.sendResponseHeaders(500, -1);
      exchange.close();
      return;
    }
    byte[] body =
        ("{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"fake-model\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"content\":\"Gern geschehen.\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}")
            .getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }
}
