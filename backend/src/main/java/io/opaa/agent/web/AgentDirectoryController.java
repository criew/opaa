package io.opaa.agent.web;

import io.opaa.agent.AgentDirectoryService;
import io.opaa.agent.AgentDirectoryService.DirectoryFile;
import io.opaa.api.dto.AgentDirectoryResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The agent directory and its CSV export. No {@code produces=} on the export, so a refusal still
 * answers with the JSON error envelope; the file's type is set on the response itself.
 */
@RestController
@RequestMapping("/api/v1/agent-directory")
public class AgentDirectoryController {

  private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

  private final AgentDirectoryService directoryService;

  public AgentDirectoryController(AgentDirectoryService directoryService) {
    this.directoryService = directoryService;
  }

  @GetMapping
  public AgentDirectoryResponse getAgentDirectory(@Caller CurrentUser caller) {
    return AgentDirectoryResponseMapper.toResponse(directoryService.read(caller));
  }

  @GetMapping("/export")
  public ResponseEntity<byte[]> exportAgentDirectory(@Caller CurrentUser caller) {
    DirectoryFile file = directoryService.exportCsv(caller);
    return ResponseEntity.ok()
        .contentType(CSV)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(file.fileName(), StandardCharsets.UTF_8)
                .build()
                .toString())
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(file.content());
  }
}
