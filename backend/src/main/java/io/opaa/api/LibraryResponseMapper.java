package io.opaa.api;

import io.opaa.api.dto.AssetReachResponse;
import io.opaa.api.dto.IndexingStatus;
import io.opaa.api.dto.LibraryListResponse;
import io.opaa.api.dto.LibraryRequest;
import io.opaa.api.dto.LibraryResponse;
import io.opaa.api.dto.LibrarySchedule;
import io.opaa.api.dto.LibraryScheduleRequest;
import io.opaa.api.dto.LibraryUpdateRequest;
import io.opaa.indexing.job.JobStatus;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.library.LibraryCreation;
import io.opaa.library.LibraryDetail;
import io.opaa.library.LibraryManagementDetail;
import io.opaa.library.LibraryScheduleDetail;
import io.opaa.library.LibraryScheduleUpdate;
import io.opaa.library.LibrarySummary;
import io.opaa.library.LibraryUpdate;
import io.opaa.permission.AssetReach;
import io.opaa.permission.PermissionTransferMark;
import io.opaa.permission.SuccessionFinding;
import java.net.URI;
import java.util.List;

/**
 * Maps {@link LibraryDetail} and {@link LibrarySummary} onto their generated response counterparts,
 * and {@link LibraryRequest}/{@link LibraryUpdateRequest} onto the domain-level {@link
 * LibraryCreation}/{@link LibraryUpdate} (ADR-0006: API DTOs are generated from the specification,
 * never hand-written). The connector-owned fields travel through {@link FlatSourceSettings}.
 */
final class LibraryResponseMapper {

  private LibraryResponseMapper() {}

  static LibraryCreation toCreation(LibraryRequest request, SourceConnectorRegistry connectors) {
    return new LibraryCreation(
        request.getName(),
        request.getDescription(),
        request.getOwnerType(),
        request.getOwnerId(),
        request.getListed(),
        request.getSourceType(),
        request.getSourcePath(),
        request.getSourceUrl(),
        request.getSourceProxy(),
        request.getSourceCredentials(),
        request.getSourceInsecureSsl(),
        FlatSourceSettings.of(
            request.getConfluenceEdition(),
            request.getConfluenceSpaces(),
            request.getConfluenceFullSyncIntervalDays(),
            request.getS3Settings(),
            connectors),
        toScheduleUpdate(request.getSchedule()));
  }

  static LibraryUpdate toUpdate(LibraryUpdateRequest request, SourceConnectorRegistry connectors) {
    return new LibraryUpdate(
        request.getName(),
        request.getDescription(),
        request.getListed(),
        request.getSourceType(),
        request.getSourcePath(),
        request.getSourceUrl(),
        request.getSourceProxy(),
        request.getSourceCredentials(),
        request.getSourceInsecureSsl(),
        toScheduleUpdate(request.getSchedule()),
        FlatSourceSettings.of(
            request.getConfluenceEdition(),
            request.getConfluenceSpaces(),
            request.getConfluenceFullSyncIntervalDays(),
            request.getS3Settings(),
            connectors));
  }

  private static LibraryScheduleUpdate toScheduleUpdate(LibraryScheduleRequest request) {
    if (request == null) {
      return null;
    }
    return new LibraryScheduleUpdate(
        request.getFrequency(), request.getHour(), request.getMinute(), request.getWeekday());
  }

  static LibraryResponse toResponse(LibraryDetail detail) {
    return toResponse(detail, null, null);
  }

  /**
   * The detail view additionally names the transfer that last touched this library (#1834, ADR-0036
   * Entscheidung 10), or nothing if none ever did.
   */
  static LibraryResponse toResponse(
      LibraryDetail detail, PermissionTransferMark lastTransfer, SuccessionFinding succession) {
    KnowledgeLibrary library = detail.library();
    LibraryResponse response =
        new LibraryResponse(
                library.getId(),
                library.getName(),
                library.getOwnerType(),
                library.getOwnerId(),
                toReachResponse(detail.reach()),
                library.isListed(),
                detail.myRole(),
                library.getSourceType(),
                library.getCreatedAt(),
                library.getUpdatedAt())
            .description(library.getDescription())
            .ownerName(detail.ownerName())
            .documentCount(detail.documentCount())
            .diagnosticsLocked(library.isDiagnosticsLocked())
            .diagnosticsLockToggleable(detail.diagnosticsLockToggleable())
            .lastTransfer(PermissionTransferResponseMapper.toResponse(lastTransfer))
            .succession(SuccessionResponseMapper.toStateResponse(succession));
    LibraryManagementDetail managementDetail = detail.managementDetail();
    response
        .sourcePath(managementDetail.sourcePath())
        .sourceUrl(
            managementDetail.sourceUrl() == null ? null : URI.create(managementDetail.sourceUrl()))
        .sourceProxy(managementDetail.sourceProxy())
        .sourceInsecureSsl(managementDetail.sourceInsecureSsl())
        .sourceCredentialsSet(managementDetail.sourceCredentialsSet())
        .storageQuotaBytes(managementDetail.storageQuotaBytes())
        .storageUsedBytes(managementDetail.storageUsedBytes())
        .externalAccess(
            managementDetail.externalAccess() == null
                ? null
                : LibraryExternalAccessResponseMapper.toResponse(managementDetail.externalAccess()))
        .allAccountsGrantAllowed(managementDetail.allAccountsGrantAllowed())
        .listedCap(managementDetail.listedCap());
    // ADR-0023/ADR-0027: edition, selection and scopes are the scope every reader sees; the rhythm
    // and the push secret's flag stay behind the management bar
    FlatSourceSettings.writeTo(response, detail);
    LibraryScheduleDetail schedule = managementDetail.schedule();
    if (schedule != null) {
      response
          .schedule(
              new LibrarySchedule(schedule.frequency())
                  .hour(schedule.hour())
                  .minute(schedule.minute())
                  .weekday(schedule.weekday())
                  .nextRunAt(schedule.nextRunAt()))
          .lastScheduledRunsFailed(managementDetail.lastScheduledRunsFailed());
    }
    return response;
  }

  static LibraryListResponse toListResponse(LibrarySummary summary) {
    KnowledgeLibrary library = summary.library();
    return new LibraryListResponse(
            library.getId(),
            library.getName(),
            library.getOwnerType(),
            toReachResponse(summary.reach()),
            library.isListed(),
            summary.myRole(),
            library.getSourceType(),
            summary.documentCount(),
            library.getCreatedAt(),
            library.getUpdatedAt())
        .description(library.getDescription())
        .ownerName(summary.ownerName())
        .lastIndexedAt(summary.lastIndexedAt())
        .lastRunStatus(toIndexingStatus(summary.lastRunStatus()))
        .succession(SuccessionResponseMapper.toStateResponse(summary.succession()));
  }

  /**
   * #1940: {@code null} stays {@code null} - the absence of the field is what says "never indexed".
   * {@link IndexingStatus#IDLE} is therefore never produced here.
   */
  private static IndexingStatus toIndexingStatus(JobStatus status) {
    if (status == null) {
      return null;
    }
    return switch (status) {
      case RUNNING -> IndexingStatus.RUNNING;
      case COMPLETED -> IndexingStatus.COMPLETED;
      case FAILED -> IndexingStatus.FAILED;
    };
  }

  static List<LibraryListResponse> toListResponses(List<LibrarySummary> summaries) {
    return summaries.stream().map(LibraryResponseMapper::toListResponse).toList();
  }

  /** The derived reach (#1931) - three figures, no stored level. */
  private static AssetReachResponse toReachResponse(AssetReach reach) {
    return new AssetReachResponse(reach.allAccounts(), reach.groupCount(), reach.userCount());
  }
}
