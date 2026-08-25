package io.opaa.library;

import io.opaa.audit.AuditEventType;
import java.util.Map;
import java.util.UUID;

/**
 * Domain event for the {@link KnowledgeLibrary} permission-history/audit double bookkeeping
 * (#238/#392, #892), the library-visibility counterpart of {@link GrantChanged}: creating a library
 * or changing its visibility/listed state writes one {@link PermissionHistoryService} interval
 * ({@link PermissionHistoryService#recordLibraryCreated}/{@link
 * PermissionHistoryService#recordVisibilityChanged}) and one audit entry, side by side. {@link
 * KnowledgeLibraryService} publishes exactly one of these per operation; {@link AuditListener} and
 * {@link PermissionHistoryListener} each react with their own half. Scoped to {@code CREATED} and
 * {@code VISIBILITY_CHANGED} only - a plain rename/description edit ({@code LIBRARY_CHANGED}) and a
 * source-configuration edit ({@code LIBRARY_SOURCE_UPDATED}) write an audit entry with no
 * permission-history counterpart, so they stay direct {@code AuditEventRecorder} calls; library
 * deletion closes a variable number of grant/visibility intervals in a loop, a different shape than
 * this event's one-history-write-per-publish, so it also stays direct.
 *
 * <p>Same transaction/publishing contract as {@link GrantChanged} - see its Javadoc.
 */
public record LibraryChanged(
    KnowledgeLibrary library,
    Cause cause,
    UUID actorUserId,
    AuditEventType auditEventType,
    Map<String, Object> auditBefore,
    Map<String, Object> auditAfter) {

  /** Which {@link PermissionHistoryService} writer {@link PermissionHistoryListener} calls. */
  public enum Cause {
    CREATED,
    VISIBILITY_CHANGED
  }
}
