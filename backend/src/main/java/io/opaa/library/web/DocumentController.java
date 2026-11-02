package io.opaa.library.web;

import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.knowledge.DocumentContent;
import io.opaa.library.LibraryDocumentService;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the original file behind an indexed document. The library is resolved from the document,
 * not named in the path; access checks are those of {@link LibraryDocumentService#loadContent}.
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

  private final LibraryDocumentService documentService;

  public DocumentController(LibraryDocumentService documentService) {
    this.documentService = documentService;
  }

  /** Streams the original inline with its own content type ({@link InlineContentResponses}). */
  @GetMapping("/{documentId}/content")
  public ResponseEntity<Resource> getDocumentContent(
      @PathVariable UUID documentId, @Caller CurrentUser caller) {
    DocumentContent content = documentService.loadContent(documentId, caller);

    return InlineContentResponses.of(content);
  }
}
