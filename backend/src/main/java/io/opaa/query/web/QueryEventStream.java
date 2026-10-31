package io.opaa.query.web;

import io.opaa.api.dto.ErrorResponse;
import io.opaa.api.dto.QueryResponse;
import io.opaa.api.dto.QueryStreamEvent;
import io.opaa.query.answer.AnswerStreamListener;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.json.JsonMapper;

/**
 * The body of {@code POST /api/v1/query/stream}: server-sent events, one {@link QueryStreamEvent}
 * per data line, written and flushed one by one. The response is committed with the first event, so
 * that a failure before it still gets its own status from {@code GlobalExceptionHandler}.
 */
final class QueryEventStream implements AnswerStreamListener {

  private static final byte[] PING = ": ping\n\n".getBytes(StandardCharsets.UTF_8);

  private final HttpServletResponse response;
  private final JsonMapper jsonMapper;
  private OutputStream out;

  QueryEventStream(HttpServletResponse response, JsonMapper jsonMapper) {
    this.response = response;
    this.jsonMapper = jsonMapper;
  }

  boolean committed() {
    return out != null;
  }

  @Override
  public void started() throws IOException {
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
  public void provisional(String text, boolean replace) throws IOException {
    send(
        new QueryStreamEvent(
                replace ? QueryStreamEvent.TypeEnum.RESET : QueryStreamEvent.TypeEnum.DELTA)
            .text(text));
  }

  @Override
  public void idle() throws IOException {
    out.write(PING);
    out.flush();
  }

  @Override
  public void beforeKeeping() throws IOException {
    started();
    idle();
  }

  /** The closing event of a complete turn; commits first if nothing was streamed. */
  void complete(QueryResponse queryResponse) throws IOException {
    started();
    send(new QueryStreamEvent(QueryStreamEvent.TypeEnum.FINAL).response(queryResponse));
  }

  /** The closing event of a turn that ended without an answer. */
  void fail(ErrorResponse error) throws IOException {
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
