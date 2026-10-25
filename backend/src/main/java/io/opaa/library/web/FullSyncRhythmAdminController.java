package io.opaa.library.web;

import io.opaa.api.dto.FullSyncRhythmRequest;
import io.opaa.api.dto.FullSyncRhythmResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.library.FullSyncRhythmAdministration;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The installation-wide rhythm of the full sync per source type, for the system administration; the
 * check of the role lives in {@link FullSyncRhythmAdministration}.
 */
@RestController
@RequestMapping("/api/v1/admin/full-sync-rhythms")
public class FullSyncRhythmAdminController {

  private final FullSyncRhythmAdministration administration;

  public FullSyncRhythmAdminController(FullSyncRhythmAdministration administration) {
    this.administration = administration;
  }

  @GetMapping
  public List<FullSyncRhythmResponse> listFullSyncRhythms(@Caller CurrentUser caller) {
    return administration.list(caller).stream()
        .map(FullSyncRhythmResponseMapper::toResponse)
        .toList();
  }

  @PutMapping("/{sourceType}")
  public FullSyncRhythmResponse updateFullSyncRhythm(
      @PathVariable String sourceType,
      @Valid @RequestBody FullSyncRhythmRequest request,
      @Caller CurrentUser caller) {
    return FullSyncRhythmResponseMapper.toResponse(
        administration.change(caller, sourceType, request.getIntervalDays()));
  }
}
