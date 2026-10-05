package io.opaa.connection.web;

import io.opaa.api.dto.ConnectorLockRequest;
import io.opaa.api.dto.McpServerRequest;
import io.opaa.api.dto.McpServerResponse;
import io.opaa.api.dto.McpServerShutdownResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.connection.profile.ConnectionProfile;
import io.opaa.connection.profile.McpServerProfileService;
import io.opaa.connection.profile.PersonNumbers;
import io.opaa.connection.profile.PersonNumbers.ProfileCounts;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The administration of MCP server profiles ({@code SYSTEM_ADMIN} only), an API without a user
 * interface until the MCP client exists. Persons' connections appear only masked.
 */
@RestController
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class McpServerController {

  private static final String ADMIN = "/api/v1/admin/mcp-servers";

  private final McpServerProfileService servers;
  private final PersonNumbers personNumbers;

  public McpServerController(McpServerProfileService servers, PersonNumbers personNumbers) {
    this.servers = servers;
    this.personNumbers = personNumbers;
  }

  @GetMapping(ADMIN)
  public List<McpServerResponse> listMcpServers() {
    List<ConnectionProfile> all = servers.list();
    Map<UUID, ProfileCounts> persons =
        personNumbers.countsOf(all.stream().map(ConnectionProfile::getId).toList());
    return all.stream()
        .map(
            profile ->
                McpServerResponseMapper.toResponse(profile, persons.get(profile.getId()).total()))
        .toList();
  }

  @PostMapping(ADMIN)
  @ResponseStatus(HttpStatus.CREATED)
  public McpServerResponse createMcpServer(
      @Valid @RequestBody McpServerRequest request, @Caller CurrentUser caller) {
    return toResponse(
        servers.create(
            caller, McpServerResponseMapper.toValues(request), request.getClientSecret()));
  }

  @GetMapping(ADMIN + "/{profileId}")
  public McpServerResponse getMcpServer(@PathVariable UUID profileId) {
    return toResponse(servers.get(profileId));
  }

  @PutMapping(ADMIN + "/{profileId}")
  public McpServerResponse updateMcpServer(
      @PathVariable UUID profileId,
      @Valid @RequestBody McpServerRequest request,
      @Caller CurrentUser caller) {
    return toResponse(
        servers.update(
            caller,
            profileId,
            McpServerResponseMapper.toValues(request),
            request.getClientSecret(),
            Boolean.TRUE.equals(request.getConfirmDiscard())));
  }

  @DeleteMapping(ADMIN + "/{profileId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteMcpServer(@PathVariable UUID profileId, @Caller CurrentUser caller) {
    servers.delete(caller, profileId);
  }

  @PostMapping(ADMIN + "/{profileId}/disconnect-all")
  public McpServerShutdownResponse disconnectAllMcpServerConnections(
      @PathVariable UUID profileId, @Caller CurrentUser caller) {
    return new McpServerShutdownResponse()
        .connectedAccounts(
            ConnectionProfileResponseMapper.toCount(servers.disconnectAll(caller, profileId)));
  }

  @PutMapping(ADMIN + "/{profileId}/lock")
  public McpServerResponse lockMcpServer(
      @PathVariable UUID profileId,
      @Valid @RequestBody ConnectorLockRequest request,
      @Caller CurrentUser caller) {
    return toResponse(servers.lock(caller, profileId, request.getLocked()));
  }

  private McpServerResponse toResponse(ConnectionProfile profile) {
    return McpServerResponseMapper.toResponse(profile, personNumbers.totalOf(profile.getId()));
  }
}
