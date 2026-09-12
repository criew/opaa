package io.opaa.api;

import io.opaa.api.dto.MailSendResultResponse;
import io.opaa.api.dto.MailTemplatePreviewRequest;
import io.opaa.api.dto.MailTemplatePreviewResponse;
import io.opaa.api.dto.MailTemplateResponse;
import io.opaa.api.dto.MailTemplateSummaryResponse;
import io.opaa.api.dto.MailTemplateUpdateRequest;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.common.NotFoundException;
import io.opaa.mail.MailTemplateDraft;
import io.opaa.mail.MailTemplateKey;
import io.opaa.mail.MailTemplateService;
import io.opaa.mail.MailTestService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The wording of the mails this installation sends (#1536, ADR-0033 Entscheidung 10), {@code
 * SYSTEM_ADMIN} only.
 *
 * <p><b>The registry is closed</b>: there is no create and no delete, only override ({@code PUT})
 * and reset ({@code DELETE}) of one of the twelve keys {@link MailTemplateKey} declares. That is
 * what makes "zurück zum Standard" always possible and keeps a deployment from ending up with a
 * template that has no content.
 *
 * <p>Preview and test send exist next to each other on purpose: the preview shows the rendering,
 * the test shows what a real mail client makes of it - and only the second one proves that SMTP
 * works.
 */
@RestController
@RequestMapping("/api/v1/system/mail-templates")
public class SystemMailTemplateController {

  private final MailTemplateService templateService;
  private final MailTestService mailTestService;

  public SystemMailTemplateController(
      MailTemplateService templateService, MailTestService mailTestService) {
    this.templateService = templateService;
    this.mailTestService = mailTestService;
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping
  public List<MailTemplateSummaryResponse> listMailTemplates() {
    return templateService.list(MailTemplateService.DEFAULT_LOCALE).stream()
        .map(MailResponseMapper::toSummary)
        .toList();
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @GetMapping("/{templateKey}")
  public MailTemplateResponse getMailTemplate(@PathVariable String templateKey) {
    return MailResponseMapper.toResponse(
        templateService.get(resolve(templateKey), MailTemplateService.DEFAULT_LOCALE));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PutMapping("/{templateKey}")
  public MailTemplateResponse updateMailTemplate(
      @PathVariable String templateKey,
      @Valid @RequestBody MailTemplateUpdateRequest request,
      @Caller CurrentUser caller) {
    return MailResponseMapper.toResponse(
        templateService.update(
            caller.organizationId(),
            caller.id(),
            resolve(templateKey),
            MailTemplateService.DEFAULT_LOCALE,
            request.getSubject(),
            request.getBodyPlain(),
            request.getBodyHtml()));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @DeleteMapping("/{templateKey}")
  public MailTemplateResponse resetMailTemplate(
      @PathVariable String templateKey, @Caller CurrentUser caller) {
    return MailResponseMapper.toResponse(
        templateService.reset(
            caller.organizationId(),
            caller.id(),
            resolve(templateKey),
            MailTemplateService.DEFAULT_LOCALE));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/{templateKey}/preview")
  public MailTemplatePreviewResponse previewMailTemplate(
      @PathVariable String templateKey, @Valid @RequestBody MailTemplatePreviewRequest request) {
    return MailResponseMapper.toResponse(
        templateService.preview(
            resolve(templateKey),
            MailTemplateService.DEFAULT_LOCALE,
            new MailTemplateDraft(
                request.getSubject(), request.getBodyPlain(), request.getBodyHtml()),
            request.getVariables()));
  }

  @PreAuthorize("hasRole('SYSTEM_ADMIN')")
  @PostMapping("/{templateKey}/test")
  public MailSendResultResponse sendMailTemplateTest(
      @PathVariable String templateKey, @Caller CurrentUser caller) {
    return MailResponseMapper.toResponse(
        mailTestService.sendTemplateTest(
            caller.organizationId(),
            caller.id(),
            resolve(templateKey),
            caller.email(),
            caller.displayName()));
  }

  private static MailTemplateKey resolve(String templateKey) {
    return MailTemplateKey.fromKey(templateKey)
        .orElseThrow(() -> new NotFoundException("Unbekannte E-Mail-Vorlage: " + templateKey));
  }
}
