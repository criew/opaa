package io.opaa.api;

import io.opaa.api.dto.ConfluenceSpaceRef;
import io.opaa.api.dto.LibraryListResponse;
import io.opaa.api.dto.LibraryRequest;
import io.opaa.api.dto.LibraryResponse;
import io.opaa.api.dto.LibrarySchedule;
import io.opaa.api.dto.LibraryScheduleRequest;
import io.opaa.api.dto.LibraryUpdateRequest;
import io.opaa.api.dto.S3ScopeRef;
import io.opaa.api.dto.S3Settings;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.common.ValidationException;
import io.opaa.indexing.source.s3.S3Scope;
import io.opaa.indexing.source.s3.S3SourceSettings;
import io.opaa.library.ConfluenceSpaceSelection;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps {@link LibraryDetail} and {@link LibrarySummary} onto their generated response counterparts,
 * and {@link LibraryRequest}/{@link LibraryUpdateRequest} onto the domain-level {@link
 * LibraryCreation}/{@link LibraryUpdate} (ADR-0006: API DTOs are generated from the specification,
 * never hand-written).
 */
final class LibraryResponseMapper {

  private static final Logger log = LoggerFactory.getLogger(LibraryResponseMapper.class);

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
        request.getSourceInsecureSsl(),
        request.getConfluenceEdition(),
        toSelections(request.getConfluenceSpaces()),
        request.getConfluenceFullSyncIntervalDays(),
        toS3Settings(request.getS3Settings()));
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
        toScheduleUpdate(request.getSchedule()),
        request.getConfluenceEdition(),
        toSelections(request.getConfluenceSpaces()),
        request.getConfluenceFullSyncIntervalDays(),
        toS3Settings(request.getS3Settings()));
  }

  /**
   * {@code null} stays {@code null} ("leave the settings alone"); the record validates buckets,
   * prefixes, overlap and patterns on construction (ADR-0027, Entscheidung 2) and its German
   * message becomes the 400 the caller sees.
   */
  static S3SourceSettings toS3Settings(S3Settings settings) {
    if (settings == null) {
      return null;
    }
    try {
      List<S3Scope> scopes =
          settings.getScopes() == null
              ? List.of()
              : settings.getScopes().stream()
                  .map(
                      ref -> {
                        if (ref == null) {
                          throw new ValidationException(
                              "s3Settings: jeder Geltungsbereich braucht einen Bucket");
                        }
                        return S3Scope.of(ref.getBucket(), ref.getPrefix());
                      })
                  .toList();
      return new S3SourceSettings(
          settings.getRegion(),
          Boolean.TRUE.equals(settings.getPathStyle()),
          scopes,
          settings.getIncludePatterns(),
          settings.getExcludePatterns());
    } catch (S3Scope.InvalidS3ScopeException
        | S3SourceSettings.InvalidS3SourceSettingsException e) {
      throw new ValidationException("s3Settings: " + e.getMessage());
    }
  }

  static S3Settings toS3SettingsRef(S3SourceSettings settings) {
    return new S3Settings(
            settings.scopes().stream()
                .map(scope -> new S3ScopeRef(scope.bucket()).prefix(scope.prefix()))
                .toList())
        .region(settings.region())
        .pathStyle(settings.pathStyle())
        .includePatterns(settings.includePatterns())
        .excludePatterns(settings.excludePatterns());
  }

  /**
   * {@code null} stays {@code null} ("leave the selection alone"), an empty list stays empty; a
   * {@code null} element (which bean validation lets through) is the caller's 400, never a 500.
   */
  private static List<ConfluenceSpaceSelection> toSelections(List<ConfluenceSpaceRef> refs) {
    if (refs == null) {
      return null;
    }
    return refs.stream()
        .map(
            ref -> {
              if (ref == null) {
                throw new ValidationException(
                    "confluenceSpaces: jeder Eintrag braucht einen Space-Schlüssel");
              }
              return new ConfluenceSpaceSelection(ref.getKey(), ref.getName());
            })
        .toList();
  }

  private static List<ConfluenceSpaceRef> toRefs(List<ConfluenceSpaceSelection> selection) {
    return selection.stream()
        .map(space -> new ConfluenceSpaceRef(space.getSpaceKey()).name(space.getSpaceName()))
        .toList();
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
            .diagnosticsLocked(library.isDiagnosticsLocked())
            .diagnosticsLockToggleable(detail.diagnosticsLockToggleable());
    if (library.getSourceType() == DocumentSourceType.CONFLUENCE) {
      // ADR-0023: edition and selection are visible to every reader - the selection is exactly
      // the scope every reader of this library can see, so naming it is not configuration detail
      // in the sense of the MANAGER-gated fields above.
      response
          .confluenceEdition(library.getSourceConfluenceEdition())
          .confluenceSpaces(toRefs(library.getConfluenceSpaces()));
    }
    if (library.getSourceType() == DocumentSourceType.S3) {
      // ADR-0027: the scopes are the scope every reader sees - visible like confluenceSpaces, and
      // the record carries no credential by construction. A stored document the record no longer
      // accepts hides the settings from this one response instead of failing the whole request.
      try {
        S3SourceSettings s3Settings = library.getS3Settings();
        if (s3Settings != null) {
          response.s3Settings(toS3SettingsRef(s3Settings));
        }
      } catch (S3SourceSettings.InvalidS3SourceSettingsException e) {
        log.warn(
            "Library {} carries S3 settings the record rejects; omitted from the response: {}",
            library.getId(),
            e.getMessage());
      }
    }
    LibraryManagementDetail managementDetail = detail.managementDetail();
    response
        .sourcePath(managementDetail.sourcePath())
        .sourceUrl(
            managementDetail.sourceUrl() == null ? null : URI.create(managementDetail.sourceUrl()))
        .sourceProxy(managementDetail.sourceProxy())
        .sourceInsecureSsl(managementDetail.sourceInsecureSsl())
        .sourceCredentialsSet(managementDetail.sourceCredentialsSet())
        .confluenceWebhookSecretSet(managementDetail.confluenceWebhookSecretSet())
        .confluenceFullSyncIntervalDays(managementDetail.confluenceFullSyncIntervalDays())
        .confluenceFullSyncIntervalDefaultDays(
            managementDetail.confluenceFullSyncIntervalDefaultDays())
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
