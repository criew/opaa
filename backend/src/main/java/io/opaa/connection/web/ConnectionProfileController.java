package io.opaa.connection.web;

import io.opaa.api.dto.ConnectionProfileCreateRequest;
import io.opaa.api.dto.ConnectionProfileImpactResponse;
import io.opaa.api.dto.ConnectionProfileOption;
import io.opaa.api.dto.ConnectionProfileResponse;
import io.opaa.api.dto.ConnectionProfileUpdateRequest;
import io.opaa.api.dto.ConnectorLockRequest;
import io.opaa.api.dto.ConnectorProfileRequirementRequest;
import io.opaa.api.dto.ConnectorProfileRequirementResponse;
import io.opaa.api.dto.ConnectorTypeStateResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.connection.ConnectorReleaseService;
import io.opaa.connection.profile.ConnectionProfile;
import io.opaa.connection.profile.ConnectionProfileService;
import io.opaa.connection.profile.ConnectionProfileService.ProfileImpact;
import io.opaa.connection.profile.ConnectionProfileValues;
import io.opaa.connection.profile.ConnectorLockService;
import io.opaa.connection.profile.PersonNumbers;
import io.opaa.connection.profile.PersonNumbers.ProfileCounts;
import io.opaa.connection.profile.ProfileRequirementService;
import io.opaa.connection.request.ConnectionProfileRequestService;
import io.opaa.indexing.source.SourceChangeGate.Answers;
import io.opaa.knowledge.SourceType;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
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
 * The administration of connection profiles, connector locks and the profile requirement ({@code
 * SYSTEM_ADMIN} only) and the selection a library is connected from. No answer carries the client
 * secret.
 */
@RestController
public class ConnectionProfileController {

  private static final String ADMIN = "/api/v1/admin/connection-profiles";

  private final ConnectionProfileService profiles;
  private final ConnectorReleaseService release;
  private final ConnectorLockService locks;
  private final ProfileRequirementService requirements;
  private final PersonNumbers personNumbers;
  private final ConnectionProfileRequestService profileRequests;

  public ConnectionProfileController(
      ConnectionProfileService profiles,
      ConnectorReleaseService release,
      ConnectorLockService locks,
      ProfileRequirementService requirements,
      PersonNumbers personNumbers,
      ConnectionProfileRequestService profileRequests) {
    this.profiles = profiles;
    this.release = release;
    this.locks = locks;
    this.requirements = requirements;
    this.personNumbers = personNumbers;
    this.profileRequests = profileRequests;
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping(ADMIN)
  public List<ConnectionProfileResponse> listConnectionProfiles() {
    List<ConnectionProfile> all = profiles.list();
    Map<UUID, Long> counts = profiles.connectionCounts(all);
    Map<UUID, ProfileCounts> persons =
        personNumbers.countsOf(all.stream().map(ConnectionProfile::getId).toList());
    return all.stream()
        .map(
            profile ->
                ConnectionProfileResponseMapper.toResponse(
                    profile,
                    profiles.secretExpiresSoon(profile),
                    counts.getOrDefault(profile.getId(), 0L),
                    persons.get(profile.getId())))
        .toList();
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping(ADMIN)
  @ResponseStatus(HttpStatus.CREATED)
  public ConnectionProfileResponse createConnectionProfile(
      @Valid @RequestBody ConnectionProfileCreateRequest request, @Caller CurrentUser caller) {
    SourceType sourceType = ConnectionProfileResponseMapper.toSourceType(request.getSourceType());
    ConnectionProfile created =
        request.getFulfillsRequestId() == null
            ? profiles.create(
                caller,
                sourceType,
                ConnectionProfileResponseMapper.toValues(request),
                request.getClientSecret())
            : profileRequests.createProfileFor(
                caller,
                request.getFulfillsRequestId(),
                sourceType,
                ConnectionProfileResponseMapper.toValues(request),
                request.getClientSecret());
    return toResponse(created);
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
    ConnectionProfileValues values = ConnectionProfileResponseMapper.toValues(request);
    boolean confirmed = Boolean.TRUE.equals(request.getConfirmDiscard());
    // the connectors are asked before the write transaction, which then reuses their answers
    Answers answers = profiles.check(profileId, values, confirmed);
    return toResponse(
        profiles.update(caller, profileId, values, request.getClientSecret(), confirmed, answers));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping(ADMIN + "/{profileId}/impact")
  public ConnectionProfileImpactResponse previewConnectionProfileChange(
      @PathVariable UUID profileId, @Valid @RequestBody ConnectionProfileUpdateRequest request) {
    return ConnectionProfileResponseMapper.toResponse(
        profiles.preview(profileId, ConnectionProfileResponseMapper.toValues(request)),
        requirements.lastForRequirement(profiles.get(profileId)));
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
    ProfileImpact impact = profiles.impact(profileId);
    return ConnectionProfileResponseMapper.toResponse(
        impact, requirements.lastForRequirement(profiles.get(profileId)));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping(ADMIN + "/{profileId}/disconnect-all")
  public ConnectionProfileImpactResponse disconnectAllConnectionProfileConnections(
      @PathVariable UUID profileId, @Caller CurrentUser caller) {
    ProfileImpact impact = profiles.disconnectAll(caller, profileId);
    return ConnectionProfileResponseMapper.toResponse(
        impact, requirements.lastForRequirement(profiles.get(profileId)));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PutMapping(ADMIN + "/{profileId}/lock")
  public ConnectionProfileResponse lockConnectionProfile(
      @PathVariable UUID profileId,
      @Valid @RequestBody ConnectorLockRequest request,
      @Caller CurrentUser caller) {
    return toResponse(locks.lockProfile(caller, profileId, request.getLocked()));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping("/api/v1/admin/connector-types")
  public List<ConnectorTypeStateResponse> listConnectorTypeStates() {
    return locks.typeStates().stream().map(ConnectionProfileResponseMapper::toResponse).toList();
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PutMapping("/api/v1/admin/connector-types/{sourceType}/lock")
  public ConnectorTypeStateResponse lockConnectorType(
      @PathVariable String sourceType,
      @Valid @RequestBody ConnectorLockRequest request,
      @Caller CurrentUser caller) {
    return ConnectionProfileResponseMapper.toResponse(
        locks.lockType(
            caller, ConnectionProfileResponseMapper.toSourceType(sourceType), request.getLocked()));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping("/api/v1/admin/connector-types/{sourceType}/profile-requirement")
  public ConnectorProfileRequirementResponse getConnectorProfileRequirement(
      @PathVariable String sourceType, @Caller CurrentUser caller) {
    return ConnectionProfileResponseMapper.toResponse(
        requirements.overview(
            ConnectionProfileResponseMapper.toSourceType(sourceType), caller.organizationId()));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PutMapping("/api/v1/admin/connector-types/{sourceType}/profile-requirement")
  public ConnectorTypeStateResponse setConnectorProfileRequirement(
      @PathVariable String sourceType,
      @Valid @RequestBody ConnectorProfileRequirementRequest request,
      @Caller CurrentUser caller) {
    return ConnectionProfileResponseMapper.toResponse(
        requirements.require(
            caller,
            ConnectionProfileResponseMapper.toSourceType(sourceType),
            request.getRequired(),
            ConnectionProfileResponseMapper.toStock(request.getOwnAddressStock())));
  }

  /** Without a library; the variant for its managers lives with the library administration. */
  @GetMapping(value = "/api/v1/connection-profiles", params = "!libraryId")
  public List<ConnectionProfileOption> listConnectionProfileOptions(
      @RequestParam String sourceType, @Caller CurrentUser caller) {
    release.requireAnyRelease(caller);
    return release
        .profileOptions(caller, ConnectionProfileResponseMapper.toSourceType(sourceType))
        .stream()
        .map(ConnectionProfileResponseMapper::toOption)
        .toList();
  }

  private ConnectionProfileResponse toResponse(ConnectionProfile profile) {
    return ConnectionProfileResponseMapper.toResponse(
        profile,
        profiles.secretExpiresSoon(profile),
        profiles.impact(profile.getId()).connections(),
        personNumbers.countsOf(List.of(profile.getId())).get(profile.getId()));
  }
}
