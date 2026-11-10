package io.opaa.query.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.opaa.api.dto.AgentRunEndState;
import io.opaa.api.dto.AgentRunStepStatus;
import io.opaa.api.dto.AgentRunStreamStep;
import io.opaa.api.dto.ErrorResponse;
import io.opaa.api.dto.QueryResponse;
import io.opaa.api.dto.QueryStreamEvent;
import io.opaa.chat.AgentRunRecord;
import io.opaa.query.answer.AnswerStreamListener;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * The body of {@code POST /api/v1/query/stream}: server-sent events, one {@link QueryStreamEvent}
 * per data line, written and flushed one by one. The response is committed with the first event, so
 * that a failure before it still gets its own status from {@code GlobalExceptionHandler}. An agent
 * run pings it from a second thread while the request thread works, so every write is synchronized.
 */
final class QueryEventStream implements AnswerStreamListener {

  private static final byte[] PING = ": ping\n\n".getBytes(StandardCharsets.UTF_8);

  private final HttpServletResponse response;
  private final JsonMapper jsonMapper;
  private OutputStream out;

  /**
   * The application's mapper with the agent-only fields of an event left out while unset: an
   * ordinary turn's frames stay byte for byte those of a stream without agents.
   */
  static JsonMapper mapper(JsonMapper jsonMapper) {
    return jsonMapper.rebuild().addMixIn(QueryStreamEvent.class, AgentFieldsWhenSet.class).build();
  }

  private abstract static class AgentFieldsWhenSet {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    abstract AgentRunStreamStep getStep();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    abstract AgentRunEndState getEndState();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    abstract UUID getRunId();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    abstract String getCancelToken();
  }

  QueryEventStream(HttpServletResponse response, JsonMapper jsonMapper) {
    this.response = response;
    this.jsonMapper = jsonMapper;
  }

  synchronized boolean committed() {
    return out != null;
  }

  @Override
  public synchronized void started() throws IOException {
    if (committed()) {
      return;
    }
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType("text/event-stream");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setHeader("Cache-Control", "no-cache, no-transform");
    response.setHeader("X-Accel-Buffering", "no");
    out = response.getOutputStream();
    send(new QueryStreamEvent(QueryStreamEvent.TypeEnum.STARTED));
  }

  @Override
  public synchronized void provisional(String text, boolean replace) throws IOException {
    send(
        new QueryStreamEvent(
                replace ? QueryStreamEvent.TypeEnum.RESET : QueryStreamEvent.TypeEnum.DELTA)
            .text(text));
  }

  @Override
  public synchronized void idle() throws IOException {
    out.write(PING);
    out.flush();
  }

  @Override
  public synchronized void beforeKeeping() throws IOException {
    started();
    idle();
  }

  @Override
  public synchronized void runStarted(UUID runId, String cancelToken) throws IOException {
    send(
        new QueryStreamEvent(QueryStreamEvent.TypeEnum.RUN_STARTED)
            .runId(runId)
            .cancelToken(cancelToken));
  }

  @Override
  public synchronized void stepStarted(int index, String tool, String detail) throws IOException {
    send(
        new QueryStreamEvent(QueryStreamEvent.TypeEnum.STEP_STARTED)
            .step(new AgentRunStreamStep(index, tool).detail(detail)));
  }

  @Override
  public synchronized void stepFinished(int index, AgentRunRecord.Step step) throws IOException {
    send(
        new QueryStreamEvent(QueryStreamEvent.TypeEnum.STEP_FINISHED)
            .step(
                new AgentRunStreamStep(index, step.tool())
                    .detail(step.detail())
                    .hits(step.hits())
                    .status(AgentRunStepStatus.valueOf(step.status().name()))));
  }

  @Override
  public synchronized void limitReached(AgentRunRecord.EndState limit) throws IOException {
    send(
        new QueryStreamEvent(QueryStreamEvent.TypeEnum.LIMIT_REACHED)
            .endState(AgentRunEndState.valueOf(limit.name())));
  }

  @Override
  public synchronized void runFinished(AgentRunRecord.EndState endState) throws IOException {
    send(
        new QueryStreamEvent(QueryStreamEvent.TypeEnum.RUN_FINISHED)
            .endState(AgentRunEndState.valueOf(endState.name())));
  }

  /** The closing event of a complete turn; commits first if nothing was streamed. */
  synchronized void complete(QueryResponse queryResponse) throws IOException {
    started();
    send(new QueryStreamEvent(QueryStreamEvent.TypeEnum.FINAL).response(queryResponse));
  }

  /** The closing event of a turn that ended without an answer. */
  synchronized void fail(ErrorResponse error) throws IOException {
    send(new QueryStreamEvent(QueryStreamEvent.TypeEnum.ERROR).error(error));
  }

  private void send(QueryStreamEvent event) throws IOException {
    String frame =
        "event: "
            + event.getType().getValue()
            + "\ndata: "
            + jsonMapper.writeValueAsString(event)
            + "\n\n";
    out.write(frame.getBytes(StandardCharsets.UTF_8));
    out.flush();
  }
}
