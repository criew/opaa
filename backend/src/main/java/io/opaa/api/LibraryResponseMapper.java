package io.opaa.api;

import io.opaa.api.dto.LibraryListResponse;
import io.opaa.api.dto.LibraryRequest;
import io.opaa.api.dto.LibraryResponse;
import io.opaa.api.dto.LibrarySchedule;
import io.opaa.api.dto.LibraryScheduleRequest;
import io.opaa.api.dto.LibraryUpdateRequest;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryCreation;
import io.opaa.library.LibraryDetail;
import io.opaa.library.LibraryManagementDetail;
import io.opaa.library.LibraryScheduleDetail;
import io.opaa.library.LibraryScheduleUpdate;
import io.opaa.library.LibrarySummary;
import io.opaa.library.LibraryUpdate;
import java.net.URI;
import java.util.List;

/**
 * Maps {@link LibraryDetail} and {@link LibrarySummary} onto their generated response counterparts,
 * and {@link LibraryRequest}/{@link LibraryUpdateRequest} onto the domain-level {@link
 * LibraryCreation}/{@link LibraryUpdate} (ADR-0006: API DTOs are generated from the specification,
 * never hand-written).
 */
final class LibraryResponseMapper {

  private LibraryResponseMapper() {}

  static LibraryCreation toCreation(LibraryRequest request) {
    return new LibraryCreation(
        request.getName(),
        request.getDescription(),
        request.getOwnerType(),
        request.getOwnerId(),
        request.getVisibility(),
        request.getListed(),
        request.getSourceType(),
        request.getSourcePath(),
        request.getSourceUrl(),
        request.getSourceProxy(),
        request.getSourceCredentials(),
        request.getSourceInsecureSsl());
  }

  static LibraryUpdate toUpdate(LibraryUpdateRequest request) {
    return new LibraryUpdate(
        request.getName(),
        request.getDescription(),
        request.getVisibility(),
        request.getListed(),
        request.getSourceType(),
        request.getSourcePath(),
        request.getSourceUrl(),
        request.getSourceProxy(),
        request.getSourceCredentials(),
        request.getSourceInsecureSsl(),
        toScheduleUpdate(request.getSchedule()));
  }

  private static LibraryScheduleUpdate toScheduleUpdate(LibraryScheduleRequest request) {
    if (request == null) {
      return null;
    }
    return new LibraryScheduleUpdate(
        request.getFrequency(), request.getHour(), request.getMinute(), request.getWeekday());
  }

  static LibraryResponse toResponse(LibraryDetail detail) {
    KnowledgeLibrary library = detail.library();
    LibraryResponse response =
        new LibraryResponse(
                library.getId(),
                library.getName(),
                library.getOwnerType(),
                library.getOwnerId(),
                library.getVisibility(),
                library.isListed(),
                detail.myRole(),
                library.getSourceType(),
                library.getCreatedAt(),
                library.getUpdatedAt())
            .description(library.getDescription())
            .documentCount(detail.documentCount())
            .diagnosticsLocked(library.isDiagnosticsLocked());
    LibraryManagementDetail managementDetail = detail.managementDetail();
    response
        .sourcePath(managementDetail.sourcePath())
        .sourceUrl(
            managementDetail.sourceUrl() == null ? null : URI.create(managementDetail.sourceUrl()))
        .sourceProxy(managementDetail.sourceProxy())
        .sourceInsecureSsl(managementDetail.sourceInsecureSsl())
        .sourceCredentialsSet(managementDetail.sourceCredentialsSet())
        .storageQuotaBytes(managementDetail.storageQuotaBytes())
        .storageUsedBytes(managementDetail.storageUsedBytes());
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
            library.getVisibility(),
            library.isListed(),
            summary.myRole(),
            library.getSourceType(),
            summary.documentCount(),
            library.getCreatedAt(),
            library.getUpdatedAt())
        .description(library.getDescription())
        .ownerName(summary.ownerName())
        .lastIndexedAt(summary.lastIndexedAt());
  }

  static List<LibraryListResponse> toListResponses(List<LibrarySummary> summaries) {
    return summaries.stream().map(LibraryResponseMapper::toListResponse).toList();
  }
}
