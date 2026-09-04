package io.opaa.api;

import io.opaa.api.dto.ChunkInspectionResponse;
import io.opaa.api.dto.DocumentChunksResponse;
import io.opaa.api.dto.SearchDiagnosisContextResponse;
import io.opaa.api.dto.SearchDiagnosisRequest;
import io.opaa.api.dto.SearchDiagnosisResponse;
import io.opaa.api.dto.SearchStatusResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.searchadmin.ChunkInspectionService;
import io.opaa.searchadmin.DiagnosisContextType;
import io.opaa.searchadmin.DiagnosisQuery;
import io.opaa.searchadmin.SearchDiagnosisService;
import io.opaa.searchadmin.SearchStatusService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The administration page "Suche &amp; Indexierung" (#1053), {@code SYSTEM_ADMIN} only - the same
 * access bar {@link LlmModelController} and {@link IndexingAdminController} establish, and scoped
 * to the caller's own organization, never to a request parameter.
 *
 * <p><b>Read-only by design, in both senses.</b> There is no endpoint here that changes a setting:
 * the page shows the active configuration, it is not a Reglerwand. And the diagnosis, though it is
 * a {@code POST}, writes nothing either - it is a {@code POST} because a test question does not
 * belong in a URL, not because it has an effect. The one write a person-context run does cause is
 * its protocol entry, which {@code ForeignDiagnosticContextService} owns and this controller cannot
 * skip: nothing here resolves a person's search scope on its own (#1150). The chunk endpoints
 * (#1230) show what the index actually stores - text and metadata, never the embedding - and answer
 * a foreign or unknown id with 404 rather than 403, so they never confirm an id outside the
 * caller's organization.
 */
@RestController
@RequestMapping("/api/v1/admin/search")
public class SearchAdminController {

  private final SearchStatusService searchStatusService;
  private final SearchDiagnosisService searchDiagnosisService;
  private final ChunkInspectionService chunkInspectionService;

  public SearchAdminController(
      SearchStatusService searchStatusService,
      SearchDiagnosisService searchDiagnosisService,
      ChunkInspectionService chunkInspectionService) {
    this.searchStatusService = searchStatusService;
    this.searchDiagnosisService = searchDiagnosisService;
    this.chunkInspectionService = chunkInspectionService;
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping("/status")
  public SearchStatusResponse getSearchStatus(@Caller CurrentUser caller) {
    return SearchAdminResponseMapper.toStatusResponse(
        searchStatusService.statusForOrganization(caller.organizationId()));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping("/diagnosis-context")
  public SearchDiagnosisContextResponse getDiagnosisContext(@Caller CurrentUser caller) {
    return SearchAdminResponseMapper.toDiagnosisContextResponse(
        searchDiagnosisService.diagnosisContext(caller));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/diagnosis")
  public SearchDiagnosisResponse runDiagnosis(
      @Valid @RequestBody SearchDiagnosisRequest request, @Caller CurrentUser caller) {
    if (request.getQuestion() == null || request.getQuestion().isBlank()) {
      throw new ValidationException("Die Testfrage darf nicht leer sein.");
    }
    return SearchAdminResponseMapper.toDiagnosisResponse(
        searchDiagnosisService.diagnose(
            caller,
            new DiagnosisQuery(
                request.getQuestion().trim(),
                toContextType(request.getContextType()),
                request.getPermissionProfileId(),
                request.getTargetUserId(),
                request.getJustification(),
                request.getTrackedDocumentId(),
                MetadataFilterMapper.toDomain(request.getMetadataFilter()))));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping("/chunks/{chunkId}")
  public ChunkInspectionResponse getChunk(
      @PathVariable String chunkId, @Caller CurrentUser caller) {
    return chunkInspectionService
        .findChunk(caller.organizationId(), chunkId)
        .map(SearchAdminResponseMapper::toChunkResponse)
        .orElseThrow(() -> new NotFoundException("Der Chunk wurde nicht gefunden."));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping("/documents/{documentId}/chunks")
  public DocumentChunksResponse listDocumentChunks(
      @PathVariable UUID documentId, @Caller CurrentUser caller) {
    return SearchAdminResponseMapper.toDocumentChunksResponse(
        chunkInspectionService.listDocumentChunks(caller.organizationId(), documentId));
  }

  private static DiagnosisContextType toContextType(
      io.opaa.api.dto.SearchDiagnosisContextType contextType) {
    return switch (contextType) {
      case SELF -> DiagnosisContextType.SELF;
      case PERMISSION_PROFILE -> DiagnosisContextType.PERMISSION_PROFILE;
      case USER -> DiagnosisContextType.USER;
    };
  }
}
