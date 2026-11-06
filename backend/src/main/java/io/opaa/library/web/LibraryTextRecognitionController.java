package io.opaa.library.web;

import io.opaa.api.dto.TextRecognitionBlock;
import io.opaa.api.dto.TextRecognitionBlockListResponse;
import io.opaa.api.types.TextRecognitionConnectionType;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.indexing.document.DocumentTextRecognitionStore;
import io.opaa.indexing.document.TextRecognitionBlocks;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The locked scans of a library's text recognition and the release of one lock (ADR-0069); access
 * is checked by {@link TextRecognitionBlocks}.
 */
@RestController
@RequestMapping("/api/v1/libraries/{libraryId}/text-recognition/blocks")
public class LibraryTextRecognitionController {

  private final TextRecognitionBlocks blocks;

  public LibraryTextRecognitionController(TextRecognitionBlocks blocks) {
    this.blocks = blocks;
  }

  @GetMapping
  public TextRecognitionBlockListResponse listTextRecognitionBlocks(
      @PathVariable UUID libraryId, @Caller CurrentUser caller) {
    List<TextRecognitionBlock> items =
        blocks.list(libraryId, caller).stream()
            .map(LibraryTextRecognitionController::toResponse)
            .toList();
    return new TextRecognitionBlockListResponse(items).pending(blocks.counts(libraryId).pending());
  }

  @DeleteMapping("/{documentId}")
  public ResponseEntity<Void> releaseTextRecognitionBlock(
      @PathVariable UUID libraryId, @PathVariable UUID documentId, @Caller CurrentUser caller) {
    blocks.release(libraryId, documentId, caller);
    return ResponseEntity.noContent().build();
  }

  static TextRecognitionBlock toResponse(DocumentTextRecognitionStore.BlockedDocument blocked) {
    return new TextRecognitionBlock(
        blocked.documentId(),
        blocked.fileName(),
        blocked.reason(),
        TextRecognitionConnectionType.valueOf(blocked.connectionType()),
        blocked.blockedAt());
  }
}
