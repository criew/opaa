package io.opaa.observability;

import java.util.Locale;

/**
 * The fixed classes a media type falls into as the {@code mime_class} tag, so a tag never carries
 * the media type itself (whatever a file declares) or its name (ADR-0071, Entscheidung 2).
 */
public enum MimeClass {
  PDF,
  OFFICE,
  TEXT,
  IMAGE,
  MAIL,
  ARCHIVE,
  AUDIO,
  VIDEO,
  OTHER;

  /** The class of {@code contentType}, parameters ignored; {@link #OTHER} for an unknown one. */
  public static MimeClass of(String contentType) {
    if (contentType == null) {
      return OTHER;
    }
    String type = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    if (type.equals("application/pdf")) {
      return PDF;
    }
    if (type.startsWith("image/")) {
      return IMAGE;
    }
    if (type.startsWith("audio/")) {
      return AUDIO;
    }
    if (type.startsWith("video/")) {
      return VIDEO;
    }
    if (type.startsWith("message/") || type.equals("application/vnd.ms-outlook")) {
      return MAIL;
    }
    if (type.startsWith("text/")
        || type.endsWith("+xml")
        || type.endsWith("/xml")
        || type.endsWith("/json")
        || type.equals("application/xhtml+xml")) {
      return TEXT;
    }
    if (type.equals("application/msword")
        || type.startsWith("application/vnd.openxmlformats-officedocument.")
        || type.startsWith("application/vnd.oasis.opendocument.")
        || type.startsWith("application/vnd.ms-")
        || type.equals("application/rtf")) {
      return OFFICE;
    }
    if (type.equals("application/zip")
        || type.equals("application/gzip")
        || type.equals("application/x-tar")
        || type.equals("application/x-7z-compressed")
        || type.equals("application/x-rar-compressed")) {
      return ARCHIVE;
    }
    return OTHER;
  }

  /** The value of the {@code mime_class} tag. */
  public String tagValue() {
    return name().toLowerCase(Locale.ROOT);
  }
}
