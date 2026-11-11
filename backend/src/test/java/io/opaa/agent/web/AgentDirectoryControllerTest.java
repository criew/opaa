package io.opaa.agent.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.agent.AgentDirectoryService;
import io.opaa.agent.AgentDirectoryService.DirectoryFile;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/** The export answers as a UTF-8 CSV attachment that no cache keeps. */
class AgentDirectoryControllerTest {

  @Test
  void theExportIsAnUncachedCsvAttachment() {
    AgentDirectoryService service = mock(AgentDirectoryService.class);
    CurrentUser caller = CurrentUser.of(UUID.randomUUID(), UUID.randomUUID(), SystemRole.USER, "X");
    byte[] content = "\"Art\"".getBytes(StandardCharsets.UTF_8);
    when(service.exportCsv(caller))
        .thenReturn(new DirectoryFile("agentenverzeichnis-2026-11-11.csv", content));

    ResponseEntity<byte[]> response =
        new AgentDirectoryController(service).exportAgentDirectory(caller);

    HttpHeaders headers = response.getHeaders();
    assertThat(headers.getContentType()).hasToString("text/csv;charset=UTF-8");
    assertThat(headers.getContentDisposition().isAttachment()).isTrue();
    assertThat(headers.getContentDisposition().getFilename())
        .isEqualTo("agentenverzeichnis-2026-11-11.csv");
    assertThat(headers.getCacheControl()).isEqualTo("no-store");
    assertThat(response.getBody()).isEqualTo(content);
  }
}
