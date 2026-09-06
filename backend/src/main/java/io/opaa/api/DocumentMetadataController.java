package io.opaa.api;

import io.opaa.api.dto.BulkMetadataValueRequest;
import io.opaa.api.dto.BulkMetadataValueResponse;
import io.opaa.api.dto.DocumentMetadataFieldResponse;
import io.opaa.api.dto.DocumentMetadataResponse;
import io.opaa.api.dto.DocumentTypeVocabularyResponse;
import io.opaa.api.dto.LibraryMetadataExtractionSettingsRequest;
import io.opaa.api.dto.LibraryMetadataExtractionSettingsResponse;
import io.opaa.api.dto.LibraryMetadataMaintenanceResponse;
import io.opaa.api.dto.LibraryMetadataQualityResponse;
import io.opaa.api.dto.LibraryMetadataSampleResponse;
import io.opaa.api.dto.MetadataValueRequest;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.indexing.metadata.DocumentMetadataCorrectionService;
import io.opaa.indexing.metadata.LibraryMetadataExtractionService;
import io.opaa.indexing.metadata.LibraryMetadataMaintenanceService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual correction of a document's core metadata (#1068): read, set, delete and the
 * Sammelzuweisung, plus the Dokumentart vocabulary a client offers as choice list, and the
 * Pflege-Anker of a library (#1069). Rights, validation and audit live in {@link
 * DocumentMetadataCorrectionService} and {@link LibraryMetadataMaintenanceService}.
 */
@RestController
@RequestMapping("/api/v1")
public class DocumentMetadataController {

  private final DocumentMetadataCorrectionService correctionService;
  private final LibraryMetadataMaintenanceService maintenanceService;
  private final LibraryMetadataExtractionService extractionService;

  public DocumentMetadataController(
      DocumentMetadataCorrectionService correctionService,
      LibraryMetadataMaintenanceService maintenanceService,
      LibraryMetadataExtractionService extractionService) {
    this.correctionService = correctionService;
    this.maintenanceService = maintenanceService;
    this.extractionService = extractionService;
  }

  @GetMapping("/libraries/{libraryId}/metadata/extraction-settings")
  public LibraryMetadataExtractionSettingsResponse getLibraryMetadataExtractionSettings(
      @PathVariable UUID libraryId, @Caller CurrentUser caller) {
    return MetadataExtractionResponseMapper.toSettingsResponse(
        extractionService.settingsOf(libraryId, caller));
  }

  @PutMapping("/libraries/{libraryId}/metadata/extraction-settings")
  public LibraryMetadataExtractionSettingsResponse updateLibraryMetadataExtractionSettings(
      @PathVariable UUID libraryId,
      @Valid @RequestBody LibraryMetadataExtractionSettingsRequest request,
      @Caller CurrentUser caller) {
    return MetadataExtractionResponseMapper.toSettingsResponse(
        extractionService.updateSettings(
            libraryId,
            Boolean.TRUE.equals(request.getModelExtractionEnabled()),
            Boolean.TRUE.equals(request.getKeywordsEnabled()),
            caller));
  }

  @GetMapping("/libraries/{libraryId}/metadata/quality")
  public LibraryMetadataQualityResponse getLibraryMetadataQuality(
      @PathVariable UUID libraryId, @Caller CurrentUser caller) {
    return MetadataExtractionResponseMapper.toQualityResponse(
        extractionService.qualityOf(libraryId, caller));
  }

  @GetMapping("/libraries/{libraryId}/metadata/sample")
  public LibraryMetadataSampleResponse getLibraryMetadataSample(
      @PathVariable UUID libraryId,
      @RequestParam(name = "size", defaultValue = "100") int size,
      @Caller CurrentUser caller) {
    return MetadataExtractionResponseMapper.toSampleResponse(
        extractionService.sampleOf(libraryId, size, caller));
  }

  @GetMapping("/libraries/{libraryId}/metadata/maintenance")
  public LibraryMetadataMaintenanceResponse getLibraryMetadataMaintenance(
      @PathVariable UUID libraryId, @Caller CurrentUser caller) {
    return DocumentMetadataResponseMapper.toMaintenanceResponse(
        maintenanceService.maintenanceOf(libraryId, caller));
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
    return DocumentMetadataResponseMapper.toVocabularyResponse(correctionService.vocabulary());
  }
}
