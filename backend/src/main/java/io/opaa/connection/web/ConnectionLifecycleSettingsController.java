package io.opaa.connection.web;

import io.opaa.api.dto.ConnectionLifecycleSettingRequest;
import io.opaa.api.dto.ConnectionLifecycleSettingResponse;
import io.opaa.api.dto.ConnectionLifecycleSettingsResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.connection.ConnectionLifecycleAdministration;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The thresholds and periods of the connections; the system-admin check lives in the service. */
@RestController
public class ConnectionLifecycleSettingsController {

  private static final String PATH = "/api/v1/admin/connection-lifecycle-settings";

  private final ConnectionLifecycleAdministration administration;

  public ConnectionLifecycleSettingsController(ConnectionLifecycleAdministration administration) {
    this.administration = administration;
  }

  @GetMapping(PATH)
  public ConnectionLifecycleSettingsResponse getConnectionLifecycleSettings(
      @Caller CurrentUser caller) {
    return ConnectionLifecycleSettingsResponseMapper.toResponse(administration.read(caller));
  }

  @PutMapping(PATH + "/{setting}")
  public ConnectionLifecycleSettingResponse updateConnectionLifecycleSetting(
      @PathVariable String setting,
      @Valid @RequestBody ConnectionLifecycleSettingRequest request,
      @Caller CurrentUser caller) {
    return ConnectionLifecycleSettingsResponseMapper.toResponse(
        administration.change(caller, setting, request.getValue()));
  }
}
