package io.opaa.api;

import io.opaa.api.dto.CoreContextPrefixRequest;
import io.opaa.api.dto.CoreContextPrefixResponse;
import io.opaa.api.dto.CreateLibraryMetadataFieldRequest;
import io.opaa.api.dto.LibraryMetadataFieldResponse;
import io.opaa.api.dto.LibraryMetadataFieldValueLabelRequest;
import io.opaa.api.dto.LibraryMetadataFieldValueRequest;
import io.opaa.api.dto.LibraryMetadataFieldsResponse;
import io.opaa.api.dto.MetadataChangeImpactResponse;
import io.opaa.api.dto.MetadataFieldUsageResponse;
import io.opaa.api.dto.RemapLibraryMetadataFieldValueRequest;
import io.opaa.api.dto.RemapLibraryMetadataFieldValueResponse;
import io.opaa.api.dto.UpdateLibraryMetadataFieldRequest;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ValidationException;
import io.opaa.indexing.metadata.LibraryMetadataFieldService;
import io.opaa.indexing.metadata.MetadataChangeKind;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The schema configuration of a library's own metadata fields: fields, their Wirkstellen and their
 * controlled value lists, including the confirmed mapping a removed value needs. Rights (management
 * right for every change, read right for the configured lists), the Aufnahmeregel and the
 * Abbildungsregel live in {@link LibraryMetadataFieldService}.
 */
@RestController
@RequestMapping("/api/v1/libraries/{libraryId}/metadata-fields")
public class LibraryMetadataFieldController {

  private final LibraryMetadataFieldService fieldService;

  public LibraryMetadataFieldController(LibraryMetadataFieldService fieldService) {
    this.fieldService = fieldService;
  }

  @GetMapping
  public LibraryMetadataFieldsResponse listLibraryMetadataFields(
      @PathVariable UUID libraryId, @Caller CurrentUser caller) {
    return LibraryMetadataFieldResponseMapper.toResponse(
        fieldService.overviewOf(libraryId, caller));
  }

  /**
   * The Folgekosten of a planned schema change, asked before it is saved (#1072). A literal path
   * segment beside {@code /{fieldKey}} - no field key can look like it, its pattern forbids the
   * hyphen.
   */
  @GetMapping("/change-impact")
  public MetadataChangeImpactResponse getMetadataChangeImpact(
      @PathVariable UUID libraryId,
      @RequestParam String fieldKey,
      @RequestParam String change,
      @RequestParam(required = false) String valueCode,
      @Caller CurrentUser caller) {
    MetadataChangeKind kind =
        MetadataChangeKind.fromName(change)
            .orElseThrow(() -> new ValidationException("Unbekannte Änderungsart: " + change));
    if (kind == MetadataChangeKind.VALUE_REMOVED && valueCode != null) {
      return LibraryMetadataFieldResponseMapper.toImpactResponse(
          fieldService.valueChangeImpact(libraryId, fieldKey, valueCode, caller));
    }
    return LibraryMetadataFieldResponseMapper.toImpactResponse(
        fieldService.changeImpact(libraryId, fieldKey, kind, caller));
  }

  /** Switches the Kontextpräfix-Wirkstelle of the two switchable core fields (#1072). */
  @PutMapping("/core-context-prefix")
  public CoreContextPrefixResponse updateCoreContextPrefix(
      @PathVariable UUID libraryId,
      @Valid @RequestBody CoreContextPrefixRequest request,
      @Caller CurrentUser caller) {
    return LibraryMetadataFieldResponseMapper.toCoreContextPrefixResponse(
        fieldService.updateCoreContextPrefix(
            libraryId,
            Boolean.TRUE.equals(request.getDocumentType()),
            Boolean.TRUE.equals(request.getDocumentDate()),
            caller));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public LibraryMetadataFieldResponse createLibraryMetadataField(
      @PathVariable UUID libraryId,
      @Valid @RequestBody CreateLibraryMetadataFieldRequest request,
      @Caller CurrentUser caller) {
    return LibraryMetadataFieldResponseMapper.toFieldResponse(
        fieldService.createField(
            libraryId, LibraryMetadataFieldResponseMapper.toInput(request), caller));
  }

  @PutMapping("/{fieldKey}")
  public LibraryMetadataFieldResponse updateLibraryMetadataField(
      @PathVariable UUID libraryId,
      @PathVariable String fieldKey,
      @Valid @RequestBody UpdateLibraryMetadataFieldRequest request,
      @Caller CurrentUser caller) {
    return LibraryMetadataFieldResponseMapper.toFieldResponse(
        fieldService.updateField(
            libraryId,
            fieldKey,
            request.getLabel(),
            Boolean.TRUE.equals(request.getFilter()),
            Boolean.TRUE.equals(request.getContextPrefix()),
            request.getCitationPosition(),
            caller));
  }

  @DeleteMapping("/{fieldKey}")
  public ResponseEntity<Void> deleteLibraryMetadataField(
      @PathVariable UUID libraryId, @PathVariable String fieldKey, @Caller CurrentUser caller) {
    fieldService.deleteField(libraryId, fieldKey, caller);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{fieldKey}/usage")
  public MetadataFieldUsageResponse getLibraryMetadataFieldUsage(
      @PathVariable UUID libraryId, @PathVariable String fieldKey, @Caller CurrentUser caller) {
    return LibraryMetadataFieldResponseMapper.toUsageResponse(
        fieldService.fieldUsage(libraryId, fieldKey, caller));
  }

  @PostMapping("/{fieldKey}/values")
  public LibraryMetadataFieldResponse addLibraryMetadataFieldValue(
      @PathVariable UUID libraryId,
      @PathVariable String fieldKey,
      @Valid @RequestBody LibraryMetadataFieldValueRequest request,
      @Caller CurrentUser caller) {
    return LibraryMetadataFieldResponseMapper.toFieldResponse(
        fieldService.addValue(libraryId, fieldKey, request.getCode(), request.getLabel(), caller));
  }

  @PatchMapping("/{fieldKey}/values/{code}")
  public LibraryMetadataFieldResponse relabelLibraryMetadataFieldValue(
      @PathVariable UUID libraryId,
      @PathVariable String fieldKey,
      @PathVariable String code,
      @Valid @RequestBody LibraryMetadataFieldValueLabelRequest request,
      @Caller CurrentUser caller) {
    return LibraryMetadataFieldResponseMapper.toFieldResponse(
        fieldService.relabelValue(libraryId, fieldKey, code, request.getLabel(), caller));
  }

  @GetMapping("/{fieldKey}/values/{code}/usage")
  public MetadataFieldUsageResponse getLibraryMetadataFieldValueUsage(
      @PathVariable UUID libraryId,
      @PathVariable String fieldKey,
      @PathVariable String code,
      @Caller CurrentUser caller) {
    return LibraryMetadataFieldResponseMapper.toUsageResponse(
        fieldService.valueUsage(libraryId, fieldKey, code, caller));
  }

  @PostMapping("/{fieldKey}/values/{code}/remap")
  public RemapLibraryMetadataFieldValueResponse remapLibraryMetadataFieldValue(
      @PathVariable UUID libraryId,
      @PathVariable String fieldKey,
      @PathVariable String code,
      @Valid @RequestBody RemapLibraryMetadataFieldValueRequest request,
      @Caller CurrentUser caller) {
    return LibraryMetadataFieldResponseMapper.toRemapResponse(
        fieldService.remapValue(libraryId, fieldKey, code, request.getTargetCode(), caller));
  }
}
