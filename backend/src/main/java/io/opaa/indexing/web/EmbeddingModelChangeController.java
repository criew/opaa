package io.opaa.indexing.web;

import io.opaa.api.dto.EmbeddingInfoResponse;
import io.opaa.api.dto.EmbeddingModelChangeKind;
import io.opaa.api.dto.EmbeddingModelChangePreview;
import io.opaa.api.dto.EmbeddingModelChangeRequest;
import io.opaa.api.dto.EmbeddingModelDraft;
import io.opaa.api.dto.EmbeddingModelProbeResponse;
import io.opaa.api.dto.EmbeddingReindexStatusResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.indexing.maintenance.EmbeddingModelChangeService;
import io.opaa.indexing.maintenance.EmbeddingModelChangeService.Preview;
import io.opaa.indexing.maintenance.EmbeddingModelChangeService.ProbeResult;
import io.opaa.indexing.maintenance.EmbeddingReindexService;
import io.opaa.llm.EmbeddingInfo;
import io.opaa.llm.EmbeddingInfoService;
import io.opaa.llm.EmbeddingModelAdministration;
import io.opaa.llm.EmbeddingModelAdministration.Draft;
import io.opaa.metadata.EmbeddingRateEstimator.RateSource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The guided change of the embedding model (ADR-0061), {@code SYSTEM_ADMIN} only: probe and
 * preview, confirmation, discard and the endpoint change. It lives with the vector index, whose run
 * every change starts or ends; the key is write-only and never answered.
 */
@RestController
@RequestMapping("/api/v1/admin/models")
public class EmbeddingModelChangeController {

  private final EmbeddingModelChangeService changes;
  private final EmbeddingInfoService embeddingInfoService;
  private final EmbeddingModelAdministration embeddingAdministration;
  private final EmbeddingReindexService reindexService;

  public EmbeddingModelChangeController(
      EmbeddingModelChangeService changes,
      EmbeddingInfoService embeddingInfoService,
      EmbeddingModelAdministration embeddingAdministration,
      EmbeddingReindexService reindexService) {
    this.changes = changes;
    this.embeddingInfoService = embeddingInfoService;
    this.embeddingAdministration = embeddingAdministration;
    this.reindexService = reindexService;
  }

  /** The installed model, never with its key, together with the state of the vector index. */
  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping("/embedding-info")
  public EmbeddingInfoResponse getEmbeddingInfo() {
    EmbeddingInfo info = embeddingInfoService.getEmbeddingInfo();
    EmbeddingInfoResponse response =
        new EmbeddingInfoResponse(info.provider(), info.model(), info.dimensions())
            .baseUrl(info.baseUrl());
    embeddingAdministration
        .installed()
        .ifPresent(
            model ->
                response
                    .maxInputTokens(model.maxInputTokens())
                    .apiKeySet(model.apiKeySet())
                    .generation(model.generation())
                    .anchorRecorded(model.anchorRecorded())
                    .index(EmbeddingReindexResponseMapper.toResponse(reindexService.progress())));
    return response;
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/embedding/probe")
  public EmbeddingModelProbeResponse probeEmbeddingModelChange(
      @Valid @RequestBody EmbeddingModelDraft request) {
    return toResponse(
        changes.probe(
            new Draft(
                request.getBaseUrl(),
                request.getModel(),
                request.getApiKey(),
                request.getMaxInputTokens())));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/embedding/change")
  public EmbeddingReindexStatusResponse startEmbeddingModelChange(
      @Valid @RequestBody EmbeddingModelChangeRequest request, @Caller CurrentUser caller) {
    return EmbeddingReindexResponseMapper.toResponse(
        changes.confirm(
            new Draft(
                request.getBaseUrl(),
                request.getModel(),
                request.getApiKey(),
                request.getMaxInputTokens()),
            request.getExpectedDimensions(),
            caller.id(),
            caller.organizationId()));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @DeleteMapping("/embedding/change")
  public EmbeddingReindexStatusResponse discardEmbeddingModelChange(@Caller CurrentUser caller) {
    return EmbeddingReindexResponseMapper.toResponse(
        changes.discard(caller.id(), caller.organizationId()));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PutMapping("/embedding/endpoint")
  public EmbeddingReindexStatusResponse changeEmbeddingModelEndpoint(
      @Valid @RequestBody EmbeddingModelDraft request, @Caller CurrentUser caller) {
    return EmbeddingReindexResponseMapper.toResponse(
        changes.changeEndpoint(
            new Draft(
                request.getBaseUrl(),
                request.getModel(),
                request.getApiKey(),
                request.getMaxInputTokens()),
            caller.id(),
            caller.organizationId()));
  }

  private static EmbeddingModelProbeResponse toResponse(ProbeResult result) {
    EmbeddingModelProbeResponse response =
        new EmbeddingModelProbeResponse(result.succeeded())
            .message(result.message())
            .dimensions(result.dimensions())
            .similarity(result.similarity());
    if (result.kind() != null) {
      response.kind(EmbeddingModelChangeKind.valueOf(result.kind().name()));
    }
    Preview preview = result.preview();
    if (preview != null) {
      response.preview(
          new EmbeddingModelChangePreview(
              preview.totalChunks(),
              preview.privateLibrariesCounted(),
              preview.estimatedTokens(),
              preview.estimatedSeconds(),
              preview.rateSource() == RateSource.MEASURED,
              preview.storageLowerBytes(),
              preview.storageUpperBytes(),
              preview.currentTableBytes()));
    }
    return response;
  }
}
