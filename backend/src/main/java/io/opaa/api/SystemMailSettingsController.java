package io.opaa.api;

import io.opaa.api.dto.MailSendResultResponse;
import io.opaa.api.dto.MailSettingsResponse;
import io.opaa.api.dto.MailSettingsUpdateRequest;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.mail.MailSettingsService;
import io.opaa.mail.MailSettingsUpdate;
import io.opaa.mail.MailTestService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The SMTP configuration of the installation (#1536, ADR-0033 Entscheidung 10), {@code
 * SYSTEM_ADMIN} only - under {@code /api/v1/system/} like branding, because it is an administrative
 * act rather than something every signed-in user reads.
 *
 * <p><b>The password is write-only in both directions.</b> Responses carry the mask {@code ***},
 * and the same mask sent back means "unverändert" - a secret never travels to the browser and back
 * simply because a form was rendered.
 *
 * <p>The test send goes to the calling administrator's own address, never to one named in the
 * request; see {@link MailTestService}.
 */
@RestController
@RequestMapping("/api/v1/system/mail-settings")
public class SystemMailSettingsController {

  private final MailSettingsService mailSettingsService;
  private final MailTestService mailTestService;

  public SystemMailSettingsController(
      MailSettingsService mailSettingsService, MailTestService mailTestService) {
    this.mailSettingsService = mailSettingsService;
    this.mailTestService = mailTestService;
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping
  public MailSettingsResponse getMailSettings() {
    return MailResponseMapper.toResponse(mailSettingsService.currentSettings());
  }

  /**
   * Replaces the configuration. Takes effect on the next message without a restart - the cached
   * transport is dropped after this transaction commits.
   */
  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PutMapping
  public MailSettingsResponse updateMailSettings(
      @Valid @RequestBody MailSettingsUpdateRequest request, @Caller CurrentUser caller) {
    return MailResponseMapper.toResponse(
        mailSettingsService.updateSettings(
            caller.organizationId(),
            caller.id(),
            new MailSettingsUpdate(
                Boolean.TRUE.equals(request.getEnabled()),
                request.getHost(),
                request.getPort(),
                request.getUsername(),
                request.getPassword(),
                request.getEncryption(),
                request.getFromAddress(),
                request.getFromName())));
  }

  /**
   * Sends a test message to the calling administrator. Answers 200 whatever happens; the outcome is
   * in the body, the same convention {@code SourceConnectionTestResponse} uses - a failed test is a
   * result, not a server error.
   */
  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/test")
  public MailSendResultResponse sendMailSettingsTest(@Caller CurrentUser caller) {
    return MailResponseMapper.toResponse(
        mailTestService.sendTestMail(
            caller.organizationId(), caller.id(), caller.email(), caller.displayName()));
  }
}
