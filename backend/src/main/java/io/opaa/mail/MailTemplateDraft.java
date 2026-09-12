package io.opaa.mail;

/**
 * Unsaved editor content rendered in place of the stored version for a live preview (#1536). All
 * three fields may be {@code null}, in which case the stored or delivered content is used for that
 * part - a preview of a subject change should not force the bodies to be resubmitted.
 */
public record MailTemplateDraft(String subject, String bodyPlain, String bodyHtml) {

  /** Whether this draft carries anything at all; an empty one means "preview what is stored". */
  public boolean isEmpty() {
    return isBlank(subject) && isBlank(bodyPlain) && isBlank(bodyHtml);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
