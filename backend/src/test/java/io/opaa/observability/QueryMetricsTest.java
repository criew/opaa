package io.opaa.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** What each chat question metric records and under which tag values (ADR-0071). */
class QueryMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final QueryMetrics metrics = new QueryMetrics(registry);

  @Test
  void aQuestionCountsByStatusAndModeAndTimesByMode() {
    metrics.recordQuery(QueryMetrics.Mode.RAG, true, Duration.ofSeconds(2));
    metrics.recordQuery(QueryMetrics.Mode.NO_SEARCH, false, Duration.ofSeconds(1));
    metrics.recordQuery(QueryMetrics.Mode.AGENT, true, Duration.ofSeconds(5));

    assertThat(count("opaa.query.count", "status", "success", "mode", "rag")).isEqualTo(1);
    assertThat(count("opaa.query.count", "status", "error", "mode", "no_search")).isEqualTo(1);
    assertThat(count("opaa.query.count", "status", "success", "mode", "agent")).isEqualTo(1);
    assertThat(count("opaa.query.count", "status", "error", "mode", "rag")).isZero();
    assertThat(
            registry
                .get("opaa.query.duration")
                .tag("mode", "agent")
                .timer()
                .totalTime(TimeUnit.SECONDS))
        .isEqualTo(5);
    assertThat(tagValues("opaa.query.duration", "mode"))
        .containsExactlyInAnyOrder("rag", "no_search", "agent");
  }

  @Test
  void theTimeToTheFirstTokenIsATimerPerMode() {
    metrics.recordTimeToFirstToken(QueryMetrics.Mode.RAG, Duration.ofMillis(900));

    assertThat(
            registry
                .get("opaa.query.time_to_first_token")
                .tag("mode", "rag")
                .timer()
                .totalTime(TimeUnit.MILLISECONDS))
        .isEqualTo(900);
    assertThat(tagValues("opaa.query.time_to_first_token", "mode"))
        .containsExactlyInAnyOrder("rag", "no_search", "agent");
  }

  @Test
  void everyStageHasItsOwnTimerAndANegativeRestCountsAsZero() {
    metrics.recordStage(QueryMetrics.Stage.EMBED_QUERY, Duration.ofMillis(40));
    metrics.recordStage(QueryMetrics.Stage.RETRIEVE, Duration.ofMillis(-3));

    assertThat(tagValues("opaa.query.stage.duration", "stage"))
        .containsExactlyInAnyOrder(
            "decompose", "embed_query", "retrieve", "rerank", "generate", "persist");
    assertThat(
            registry
                .get("opaa.query.stage.duration")
                .tag("stage", "embed_query")
                .timer()
                .totalTime(TimeUnit.MILLISECONDS))
        .isEqualTo(40);
    assertThat(registry.get("opaa.query.stage.duration").tag("stage", "retrieve").timer().count())
        .isEqualTo(1);
    assertThat(
            registry
                .get("opaa.query.stage.duration")
                .tag("stage", "retrieve")
                .timer()
                .totalTime(TimeUnit.NANOSECONDS))
        .isZero();
  }

  @Test
  void theRetrievalCountsItsHitsAndAnEmptySelectionApart() {
    metrics.recordRetrieval(6);
    metrics.recordRetrieval(0);

    assertThat(registry.get("opaa.retrieval.hits").summary().count()).isEqualTo(2);
    assertThat(registry.get("opaa.retrieval.hits").summary().totalAmount()).isEqualTo(6);
    assertThat(registry.get("opaa.retrieval.empty").counter().count()).isEqualTo(1);
  }

  @Test
  void theContextTokensRecordOnlyAReportedCountAndTheCutWindowCountsApart() {
    metrics.recordContextTokens(3200);
    metrics.recordContextTokens(null);
    metrics.recordContextTokens(0);
    metrics.recordContextTruncated();

    assertThat(registry.get("opaa.query.context.tokens").summary().count()).isEqualTo(1);
    assertThat(registry.get("opaa.query.context.tokens").summary().totalAmount()).isEqualTo(3200);
    assertThat(registry.get("opaa.query.context.truncated").counter().count()).isEqualTo(1);
  }

  @ParameterizedTest
  @CsvSource(
      nullValues = "null",
      value = {
        "stop, stop",
        "STOP, stop",
        "length, length",
        "MAX_TOKENS, length",
        "content_filter, content_filter",
        "tool_calls, tool",
        "TOOL_CALLS, tool",
        "toolCallLimitExceeded, other",
        "null, other"
      })
  void theFinishReasonIsReducedToTheFixedSet(String providerReason, String tag) {
    metrics.recordFinishReason(providerReason);

    assertThat(count("opaa.query.finish_reason", "reason", tag)).isEqualTo(1);
    assertThat(tagValues("opaa.query.finish_reason", "reason"))
        .containsExactlyInAnyOrder("stop", "length", "content_filter", "tool", "other");
  }

  @Test
  void anAbortCountsByCause() {
    metrics.recordAborted(QueryMetrics.AbortCause.CLIENT_CANCEL);
    metrics.recordAborted(QueryMetrics.AbortCause.TIMEOUT);
    metrics.recordAborted(QueryMetrics.AbortCause.TIMEOUT);

    assertThat(count("opaa.query.aborted", "cause", "client_cancel")).isEqualTo(1);
    assertThat(count("opaa.query.aborted", "cause", "timeout")).isEqualTo(2);
    assertThat(count("opaa.query.aborted", "cause", "error")).isZero();
  }

  @Test
  void aTimeoutAnywhereInTheCauseChainIsATimeout() {
    assertThat(
            QueryMetrics.abortCause(
                new IllegalStateException("model", new SocketTimeoutException("read timed out"))))
        .isEqualTo(QueryMetrics.AbortCause.TIMEOUT);
    assertThat(QueryMetrics.abortCause(new RuntimeException(new InterruptedIOException("timeout"))))
        .isEqualTo(QueryMetrics.AbortCause.TIMEOUT);
    assertThat(QueryMetrics.abortCause(new RuntimeException(new HttpTimeoutException("x"))))
        .isEqualTo(QueryMetrics.AbortCause.TIMEOUT);
    assertThat(QueryMetrics.abortCause(new RuntimeException(new TimeoutException())))
        .isEqualTo(QueryMetrics.AbortCause.TIMEOUT);
    assertThat(QueryMetrics.abortCause(new IllegalStateException(new IOException("refused"))))
        .isEqualTo(QueryMetrics.AbortCause.ERROR);
  }

  private double count(String name, String... tags) {
    return registry.get(name).tags(tags).counter().count();
  }

  private Set<String> tagValues(String name, String key) {
    return registry.find(name).meters().stream()
        .map(Meter::getId)
        .map(id -> id.getTag(key))
        .collect(Collectors.toSet());
  }
}
