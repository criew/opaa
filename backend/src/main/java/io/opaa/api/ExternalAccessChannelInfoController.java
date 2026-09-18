package io.opaa.api;

import io.opaa.api.dto.ExternalAccessChannelInfoResponse;
import io.opaa.externalaccess.ExternalAccessSettings.Values;
import io.opaa.externalaccess.ExternalAccessSettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two channel values a person needs to manage their own tokens (#1719): whether the channel is
 * open at all, and how long a token may live.
 *
 * <p>Deliberately a self view of its own rather than a widened role gate on {@link
 * ExternalAccessSettingsController}: quota, networks, instructions text and the last change belong
 * to the Systemverwaltung, and a person managing their own tokens has no use for them.
 */
@RestController
@RequestMapping("/api/v1/external-access/settings")
public class ExternalAccessChannelInfoController {

  private final ExternalAccessSettingsService settingsService;

  public ExternalAccessChannelInfoController(ExternalAccessSettingsService settingsService) {
    this.settingsService = settingsService;
  }

  @GetMapping
  public ExternalAccessChannelInfoResponse getOwnExternalAccessChannelInfo() {
    Values values = settingsService.current().values();
    return new ExternalAccessChannelInfoResponse(values.enabled(), values.tokenMaxLifetimeDays());
  }
}
