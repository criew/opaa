package io.opaa.connection.web;

import io.opaa.api.dto.ConnectionProfileCreateRequest;
import io.opaa.api.dto.ConnectionProfileImpactResponse;
import io.opaa.api.dto.ConnectionProfileOption;
import io.opaa.api.dto.ConnectionProfileResponse;
import io.opaa.api.dto.ConnectionProfileUpdateRequest;
import io.opaa.api.types.Capability;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.connection.profile.ConnectionProfile;
import io.opaa.connection.profile.ConnectionProfileService;
import io.opaa.permission.CapabilityService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The administration of connection profiles ({@code SYSTEM_ADMIN} only) and the selection a library
 * is connected from. No answer carries the client secret.
 */
@RestController
public class ConnectionProfileController {

  private static final String ADMIN = "/api/v1/admin/connection-profiles";

  private final ConnectionProfileService profiles;
  private final CapabilityService capabilities;

  public ConnectionProfileController(
      ConnectionProfileService profiles, CapabilityService capabilities) {
    this.profiles = profiles;
    this.capabilities = capabilities;
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping(ADMIN)
  public List<ConnectionProfileResponse> listConnectionProfiles() {
    return profiles.list().stream().map(this::toResponse).toList();
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping(ADMIN)
  @ResponseStatus(HttpStatus.CREATED)
  public ConnectionProfileResponse createConnectionProfile(
      @Valid @RequestBody ConnectionProfileCreateRequest request, @Caller CurrentUser caller) {
    return toResponse(
        profiles.create(
            caller,
            ConnectionProfileResponseMapper.toSourceType(request.getSourceType()),
            ConnectionProfileResponseMapper.toValues(request),
            request.getClientSecret()));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping(ADMIN + "/{profileId}")
  public ConnectionProfileResponse getConnectionProfile(@PathVariable UUID profileId) {
    return toResponse(profiles.get(profileId));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PutMapping(ADMIN + "/{profileId}")
  public ConnectionProfileResponse updateConnectionProfile(
      @PathVariable UUID profileId,
      @Valid @RequestBody ConnectionProfileUpdateRequest request,
      @Caller CurrentUser caller) {
    return toResponse(
        profiles.update(
            caller,
            profileId,
            ConnectionProfileResponseMapper.toValues(request),
            request.getClientSecret(),
            Boolean.TRUE.equals(request.getConfirmDiscard())));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @DeleteMapping(ADMIN + "/{profileId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteConnectionProfile(@PathVariable UUID profileId, @Caller CurrentUser caller) {
    profiles.delete(caller, profileId);
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping(ADMIN + "/{profileId}/impact")
  public ConnectionProfileImpactResponse getConnectionProfileImpact(@PathVariable UUID profileId) {
    return ConnectionProfileResponseMapper.toResponse(profiles.impact(profileId));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping(ADMIN + "/{profileId}/disconnect-all")
  public ConnectionProfileImpactResponse disconnectAllConnectionProfileConnections(
      @PathVariable UUID profileId, @Caller CurrentUser caller) {
    return ConnectionProfileResponseMapper.toResponse(profiles.disconnectAll(caller, profileId));
  }

  @GetMapping("/api/v1/connection-profiles")
  public List<ConnectionProfileOption> listConnectionProfileOptions(
      @RequestParam String sourceType, @Caller CurrentUser caller) {
    capabilities.requireCapability(caller, Capability.CREATE_CONNECTOR_LIBRARY);
    return profiles.selectableFor(ConnectionProfileResponseMapper.toSourceType(sourceType)).stream()
        .map(ConnectionProfileResponseMapper::toOption)
        .toList();
  }

  private ConnectionProfileResponse toResponse(ConnectionProfile profile) {
    return ConnectionProfileResponseMapper.toResponse(
        profile,
        profiles.secretExpiresSoon(profile),
        profiles.impact(profile.getId()).connections());
  }
}
