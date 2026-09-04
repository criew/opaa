package io.opaa.api;

import io.opaa.api.dto.BulkMetadataValueRequest;
import io.opaa.api.dto.BulkMetadataValueResponse;
import io.opaa.api.dto.DocumentMetadataFieldResponse;
import io.opaa.api.dto.DocumentMetadataResponse;
import io.opaa.api.dto.DocumentTypeVocabularyResponse;
import io.opaa.api.dto.MetadataValueRequest;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.indexing.metadata.DocumentMetadataCorrectionService;
import io.opaa.indexing.metadata.DocumentTypeVocabularyRepository;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual correction of a document's core metadata (#1068): read, set, delete and the
 * Sammelzuweisung, plus the Dokumentart vocabulary a client offers as choice list. Rights,
 * validation and audit live in {@link DocumentMetadataCorrectionService}.
 */
@RestController
@RequestMapping("/api/v1")
public class DocumentMetadataController {

  private final DocumentMetadataCorrectionService correctionService;
  private final DocumentTypeVocabularyRepository vocabularyRepository;

  public DocumentMetadataController(
      DocumentMetadataCorrectionService correctionService,
      DocumentTypeVocabularyRepository vocabularyRepository) {
    this.correctionService = correctionService;
    this.vocabularyRepository = vocabularyRepository;
  }

  @GetMapping("/libraries/{libraryId}/documents/{documentId}/metadata")
  public DocumentMetadataResponse getDocumentMetadata(
      @PathVariable UUID libraryId, @PathVariable UUID documentId, @Caller CurrentUser caller) {
    return DocumentMetadataResponseMapper.toResponse(
        documentId, correctionService.fieldsOf(libraryId, documentId, caller));
  }

  @PutMapping("/libraries/{libraryId}/documents/{documentId}/metadata/{fieldKey}")
  public DocumentMetadataFieldResponse setDocumentMetadataValue(
      @PathVariable UUID libraryId,
      @PathVariable UUID documentId,
      @PathVariable String fieldKey,
      @Valid @RequestBody MetadataValueRequest request,
      @Caller CurrentUser caller) {
    return DocumentMetadataResponseMapper.toFieldResponse(
        correctionService.setValue(
            libraryId,
            documentId,
            fieldKey,
            DocumentMetadataResponseMapper.toInput(request),
            caller));
  }

  @DeleteMapping("/libraries/{libraryId}/documents/{documentId}/metadata/{fieldKey}")
  public ResponseEntity<Void> deleteDocumentMetadataValue(
      @PathVariable UUID libraryId,
      @PathVariable UUID documentId,
      @PathVariable String fieldKey,
      @Caller CurrentUser caller) {
    correctionService.deleteValue(libraryId, documentId, fieldKey, caller);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/libraries/{libraryId}/documents/metadata/bulk")
  public BulkMetadataValueResponse bulkSetDocumentMetadata(
      @PathVariable UUID libraryId,
      @Valid @RequestBody BulkMetadataValueRequest request,
      @Caller CurrentUser caller) {
    return DocumentMetadataResponseMapper.toBulkResponse(
        correctionService.bulkSetValue(
            libraryId,
            request.getFieldKey(),
            DocumentMetadataResponseMapper.toInput(request.getValue()),
            request.getDocumentIds(),
            caller));
  }

  @GetMapping("/metadata/document-types")
  public DocumentTypeVocabularyResponse listDocumentTypes() {
    return DocumentMetadataResponseMapper.toVocabularyResponse(
        vocabularyRepository.findAllByOrderBySortOrderAsc());
  }
}
