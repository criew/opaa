package io.opaa.auth.local;

import io.opaa.api.types.MailDeliveryPath;
import java.util.Objects;

/**
 * How a single-use link reached the person (ADR-0033, Entscheidung 11): after {@code MAIL_SENT} the
 * link is gone - it stands in nobody's response; on every other path it is carried exactly once,
 * here, for the administrator to hand over.
 */
public record LinkDelivery(MailDeliveryPath path, String link) {

  public LinkDelivery {
    Objects.requireNonNull(path, "path");
    if ((path == MailDeliveryPath.MAIL_SENT) != (link == null)) {
      throw new IllegalArgumentException(
          "the link is carried exactly when the mail was not sent (" + path + ")");
    }
  }

  public boolean emailSent() {
    return path == MailDeliveryPath.MAIL_SENT;
  }

  /** Never the link: a delivery may be logged, a link never. */
  @Override
  public String toString() {
    return "LinkDelivery[path=" + path + "]";
  }
}
