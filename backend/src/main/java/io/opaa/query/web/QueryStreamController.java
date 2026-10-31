package io.opaa.query.web;

import io.opaa.api.GlobalExceptionHandler;
import io.opaa.api.dto.QueryRequest;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.metadata.web.MetadataFilterMapper;
import io.opaa.query.QueryResult;
import io.opaa.query.QueryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * The chat's streamed turn, on the request thread like the non-streamed one: a stream lives within
 * this request and holds no state beyond it, so any instance can answer it. Until the model begins
 * to answer, a failure leaves the method and gets its status as in {@code POST /api/v1/query};
 * afterwards it becomes the closing {@code error} event. A reader that went away ends the stream
 * without a word, and the turn, unfinished, is not persisted.
 */
@RestController
@RequestMapping("/api/v1")
public class QueryStreamController {

  private static final Logger log = LoggerFactory.getLogger(QueryStreamController.class);

  private final QueryService queryService;
  private final JsonMapper jsonMapper;
  private final GlobalExceptionHandler exceptionHandler;

  public QueryStreamController(
      QueryService queryService, JsonMapper jsonMapper, GlobalExceptionHandler exceptionHandler) {
    this.queryService = queryService;
    this.jsonMapper = jsonMapper;
    this.exceptionHandler = exceptionHandler;
  }

  @PostMapping("/query/stream")
  public void submitQueryStream(
      @Valid @RequestBody QueryRequest request,
      @Caller CurrentUser caller,
      HttpServletRequest httpRequest,
      HttpServletResponse httpResponse)
      throws IOException {
    boolean useKnowledge = request.getUseKnowledge() == null || request.getUseKnowledge();
    QueryEventStream events = new QueryEventStream(httpResponse, jsonMapper);
    QueryResult result;
    try {
      result =
          queryService.query(
              request.getQuestion(),
              request.getChatId(),
              caller,
              useKnowledge,
              request.getLibraryIds(),
              MetadataFilterMapper.toDomain(request.getMetadataFilter()),
              request.getUsedPromptId(),
              events);
    } catch (UncheckedIOException readerGone) {
      log.debug("Answer stream ended, its reader went away: {}", readerGone.getMessage());
      return;
    } catch (RuntimeException failure) {
      if (!events.committed()) {
        throw failure;
      }
      events.fail(exceptionHandler.describe(failure, httpRequest));
      return;
    }
    events.complete(QueryResponseMapper.toResponse(result));
  }
}
