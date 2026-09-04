package io.opaa.chat;

import io.opaa.api.types.DatePrecision;
import io.opaa.api.types.MetadataOrigin;
import io.opaa.indexing.metadata.CoreMetadata;
import java.time.LocalDate;

/**
 * The core metadata fields of a cited document as persisted on a chat turn's source snapshot
 * (ADR-0024) - the chat's own copy of {@link CoreMetadata}, so a later change to the indexing
 * record never rewrites what {@code chat_messages.sources} already holds. Every field nullable; an
 * empty field is simply absent from the Beleg.
 */
public record ChatSourceCoreMetadata(
    String title,
    MetadataOrigin titleOrigin,
    String documentType,
    String documentTypeLabel,
    MetadataOrigin documentTypeOrigin,
    LocalDate documentDate,
    DatePrecision documentDatePrecision,
    MetadataOrigin documentDateOrigin) {

  /** {@code null} when {@code core} carries no field at all, so the snapshot stays compact. */
  public static ChatSourceCoreMetadata fromOrNull(CoreMetadata core) {
    if (core == null || core.isEmpty()) {
      return null;
    }
    return new ChatSourceCoreMetadata(
        core.title(),
        core.titleOrigin(),
        core.documentTypeCode(),
        core.documentTypeLabel(),
        core.documentTypeOrigin(),
        core.documentDate(),
        core.documentDatePrecision(),
        core.documentDateOrigin());
  }
}
