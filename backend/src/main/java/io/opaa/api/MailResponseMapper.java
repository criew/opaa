package io.opaa.api;

import io.opaa.api.dto.MailSendOutcome;
import io.opaa.api.dto.MailSendResultResponse;
import io.opaa.api.dto.MailSettingsResponse;
import io.opaa.api.dto.MailTemplatePreviewResponse;
import io.opaa.api.dto.MailTemplateResponse;
import io.opaa.api.dto.MailTemplateSource;
import io.opaa.api.dto.MailTemplateSummaryResponse;
import io.opaa.mail.MailPreview;
import io.opaa.mail.MailSettings;
import io.opaa.mail.MailSettingsService;
import io.opaa.mail.MailTemplateView;
import io.opaa.mail.SendResult;

/**
 * Maps the mail domain onto the generated DTOs (ADR-0006: API DTOs come from the specification,
 * never hand-written). Shared by {@link SystemMailSettingsController} and {@link
 * SystemMailTemplateController} so the two cannot drift apart in how they represent a send result.
 *
 * <p><b>The password is masked here and nowhere else.</b> {@link #toResponse(MailSettings)} answers
 * {@link MailSettingsService#PASSWORD_MASK} when one is stored and {@code null} when none is - the
 * ciphertext never reaches this class's output, and nothing else in the API returns the field at
 * all.
 */
final class MailResponseMapper {

  private MailResponseMapper() {}

  /**
   * {@code publicBaseUrlConfigured} is passed in rather than read here: it is a property of the
   * deployment ({@code OPAA_PUBLIC_BASE_URL}), not of the stored settings, and every mail carrying
   * a link depends on it.
   */
  static MailSettingsResponse toResponse(MailSettings settings, boolean publicBaseUrlConfigured) {
    boolean passwordSet = settings.getPasswordCiphertext() != null;
    MailSettingsResponse response =
        new MailSettingsResponse(
            settings.isEnabled(),
            passwordSet,
            settings.getEncryption(),
            settings.getUpdatedAt(),
            publicBaseUrlConfigured);
    response.setHost(settings.getHost());
    response.setPort(settings.getPort());
    response.setUsername(settings.getUsername());
    response.setPassword(passwordSet ? MailSettingsService.PASSWORD_MASK : null);
    response.setFromAddress(settings.getFromAddress());
    response.setFromName(settings.getFromName());
    response.setLastSuccessAt(settings.getLastSuccessAt());
    response.setLastFailureAt(settings.getLastFailureAt());
    response.setLastFailureReason(settings.getLastFailureReason());
    return response;
  }

  static MailSendResultResponse toResponse(SendResult result) {
    MailSendResultResponse response = new MailSendResultResponse(outcomeOf(result));
    response.setReason(result.reasonOrNull());
    if (result instanceof SendResult.Sent sent) {
      response.setRecipient(sent.recipient());
    }
    return response;
  }

  static MailTemplateSummaryResponse toSummary(MailTemplateView view) {
    MailTemplateSummaryResponse response =
        new MailTemplateSummaryResponse(
            view.key().key(),
            view.key().label(),
            view.locale(),
            view.subject(),
            sourceOf(view),
            view.placeholders());
    response.setUpdatedAt(view.updatedAt());
    return response;
  }

  static MailTemplateResponse toResponse(MailTemplateView view) {
    MailTemplateResponse response =
        new MailTemplateResponse(
            view.key().key(),
            view.key().label(),
            view.locale(),
            view.subject(),
            view.bodyPlain(),
            sourceOf(view),
            view.placeholders(),
            view.defaultSubject(),
            view.defaultBodyPlain());
    response.setBodyHtml(view.bodyHtml());
    response.setDefaultBodyHtml(view.defaultBodyHtml());
    response.setUpdatedAt(view.updatedAt());
    response.setUpdatedBy(view.updatedBy());
    return response;
  }

  static MailTemplatePreviewResponse toResponse(MailPreview preview) {
    return new MailTemplatePreviewResponse(
        preview.rendered().subject(),
        preview.rendered().bodyPlain(),
        preview.rendered().bodyHtml(),
        preview.variables());
  }

  private static MailSendOutcome outcomeOf(SendResult result) {
    return switch (result) {
      case SendResult.Sent ignored -> MailSendOutcome.SENT;
      case SendResult.Skipped ignored -> MailSendOutcome.SKIPPED;
      case SendResult.Failed ignored -> MailSendOutcome.FAILED;
    };
  }

  private static MailTemplateSource sourceOf(MailTemplateView view) {
    return view.source() == MailTemplateView.Source.DATABASE
        ? MailTemplateSource.DATABASE
        : MailTemplateSource.DEFAULT;
  }
}
