package io.opaa.api;

import io.opaa.api.dto.DirectorySyncReportResponse;
import io.opaa.api.dto.DirectorySyncStatusResponse;
import io.opaa.auth.CurrentUser;
import io.opaa.group.sync.DirectorySyncService;
import io.opaa.group.sync.DirectorySyncStatus;
import io.opaa.group.sync.SyncReport;
import java.util.Optional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/directory-sync")
public class DirectorySyncController {

  private final DirectorySyncService directorySyncService;

  public DirectorySyncController(DirectorySyncService directorySyncService) {
    this.directorySyncService = directorySyncService;
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/dry-run")
  public DirectorySyncReportResponse dryRun(CurrentUser caller) {
    SyncReport report = directorySyncService.dryRun(caller.organizationId());
    return DirectorySyncResponseMapper.toReportResponse(report);
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/run")
  public DirectorySyncReportResponse run(CurrentUser caller) {
    SyncReport report = directorySyncService.run(caller.organizationId());
    return DirectorySyncResponseMapper.toReportResponse(report);
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping("/status")
  public DirectorySyncStatusResponse status(CurrentUser caller) {
    Optional<DirectorySyncStatus> status = directorySyncService.getStatus(caller.organizationId());
    return DirectorySyncResponseMapper.toStatusResponse(status);
  }
}
