package io.opaa.mail;

/**
 * A fully rendered mail: subject and plain-text body are always present, the HTML alternative is
 * what makes the message {@code multipart/alternative}. Produced by {@link MailTemplateService} and
 * consumed by {@link MailService}.
 */
public record RenderedMail(String subject, String bodyPlain, String bodyHtml) {

  /** Whether an HTML alternative exists and the message should therefore be multipart. */
  public boolean hasHtml() {
    return bodyHtml != null && !bodyHtml.isBlank();
  }
}
