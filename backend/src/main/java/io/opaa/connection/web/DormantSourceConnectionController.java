package io.opaa.connection.web;

import io.opaa.api.dto.DormantSourceConnection;
import io.opaa.api.dto.SourceBlockReason;
import io.opaa.api.dto.SourceConnectionResponsible;
import io.opaa.api.dto.SourceConnectionResponsibleType;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.connection.consent.DormantSourceConnections;
import io.opaa.connection.consent.DormantSourceConnections.Dormant;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The resting source connections of shared libraries, for the system administration only. */
@RestController
public class DormantSourceConnectionController {

  private final DormantSourceConnections dormant;

  public DormantSourceConnectionController(DormantSourceConnections dormant) {
    this.dormant = dormant;
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping("/api/v1/admin/source-connections/dormant")
  public List<DormantSourceConnection> listDormantSourceConnections(@Caller CurrentUser caller) {
    return dormant.of(caller.organizationId()).stream()
        .map(DormantSourceConnectionController::toResponse)
        .toList();
  }

  private static DormantSourceConnection toResponse(Dormant entry) {
    return new DormantSourceConnection()
        .libraryId(entry.libraryId())
        .libraryName(entry.libraryName())
        .profileId(entry.profileId())
        .profileName(entry.profileName())
        .accountLabel(entry.accountLabel())
        .reason(SourceBlockReason.valueOf(entry.reason().name()))
        .endedCause(entry.endedCause())
        .endedAt(entry.endedAt())
        .responsible(
            entry.responsible() == null
                ? null
                : new SourceConnectionResponsible()
                    .type(
                        SourceConnectionResponsibleType.valueOf(entry.responsible().type().name()))
                    .id(entry.responsible().id())
                    .name(entry.responsibleName()));
  }
}
