package io.opaa.search.web;

import io.opaa.api.dto.DocumentTextResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.search.DocumentTextService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Textfassung of a document, a subresource of {@code /api/v1/documents} that reads the vector
 * store and therefore lives with the reading path rather than with the original's download.
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentTextController {

  private final DocumentTextService documentTextService;

  public DocumentTextController(DocumentTextService documentTextService) {
    this.documentTextService = documentTextService;
  }

  @GetMapping("/{documentId}/text")
  public DocumentTextResponse getDocumentText(
      @PathVariable UUID documentId,
      @RequestParam(required = false) Integer from,
      @RequestParam(required = false) Integer to,
      @RequestParam(required = false, defaultValue = "false") boolean sections,
      @Caller CurrentUser caller) {
    return SearchResponseMapper.toResponse(
        documentTextService.read(caller, documentId, from, to, sections));
  }
}
