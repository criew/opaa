package io.opaa.query.web;

import io.opaa.api.dto.LlmModelToolCheckRequest;
import io.opaa.api.dto.LlmModelToolCheckResponse;
import io.opaa.query.toolcheck.ToolCheckService;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tool checks of chat model entries, {@code SYSTEM_ADMIN} only. A sub-resource of the model
 * administration with a controller of its own here, because the check needs the agent loop's tool
 * definition and the agent switch, both above the model module.
 */
@RestController
@RequestMapping("/api/v1/admin/models/{modelId}/tool-checks")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class ToolCheckController {

  private static final MediaType MARKDOWN = new MediaType("text", "markdown");

  private final ToolCheckService service;

  public ToolCheckController(ToolCheckService service) {
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<LlmModelToolCheckResponse> startLlmModelToolCheck(
      @PathVariable UUID modelId, @Valid @RequestBody LlmModelToolCheckRequest request) {
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(ToolCheckResponseMapper.toResponse(service.start(modelId, request.getRepetitions())));
  }

  @GetMapping("/latest")
  public LlmModelToolCheckResponse getLatestLlmModelToolCheck(@PathVariable UUID modelId) {
    return ToolCheckResponseMapper.toResponse(service.latest(modelId));
  }

  /**
   * The report as a download. It quotes model answers verbatim, so it goes out as bytes with {@code
   * nosniff} and a sandboxing policy: a browser never renders it as a page of this origin.
   */
  @GetMapping("/{checkId}/report")
  public ResponseEntity<byte[]> getLlmModelToolCheckReport(
      @PathVariable UUID modelId, @PathVariable UUID checkId) {
    return ResponseEntity.ok()
        .contentType(new MediaType(MARKDOWN, StandardCharsets.UTF_8))
        .header(
            "Content-Disposition", "attachment; filename=\"werkzeugpruefung-" + checkId + ".md\"")
        .header("X-Content-Type-Options", "nosniff")
        .header("Content-Security-Policy", "sandbox; default-src 'none'")
        .body(service.reportMarkdown(modelId, checkId).getBytes(StandardCharsets.UTF_8));
  }
}
