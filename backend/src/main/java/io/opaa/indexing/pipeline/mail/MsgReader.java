package io.opaa.indexing.pipeline.mail;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.hsmf.MAPIMessage;
import org.apache.poi.hsmf.datatypes.AttachmentChunks;
import org.apache.poi.hsmf.exceptions.ChunkNotFoundException;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads an Outlook MSG message's Kopfdaten, body and attachments via Apache POI's HSMF module
 * (docs/features/ingestion-pipelines.md, Teil 3, Punkt 5) - the same header/body/attachment split
 * {@link EmlReader} makes for EML, using the MAPI-native reader instead of Tika's flattening one.
 *
 * <p>Attachment size bounding is best-effort, unlike {@link EmlReader}'s: {@link MAPIMessage} loads
 * the whole compound file, attachments included, into memory before this class ever sees it, so an
 * oversized attachment is only skipped post-hoc rather than never streamed to disk. {@link
 * MailProperties#maxMessageBytes()} remains the actual memory bound, checked by the caller before
 * this reader ever runs.
 *
 * <p>An embedded Outlook item attachment (a message attached as its own MAPI object, not as bytes)
 * is skipped, not recursed into - POI offers no public writer to reconstruct a standalone {@code
 * .msg} from it.
 *
 * <p><b>Selective extraction</b> (#1243): with a {@code wantedIndex}, every attachment is still
 * classified in the same order - one skipped for its size or an unreadable chunk included, so the
 * extraction positions stay identical to an unfiltered run's - but only the one at that position is
 * written to a temp file. The returned {@link ParsedMailMessage} then carries that one attachment
 * alone, or none.
 */
final class MsgReader {

  private static final Logger log = LoggerFactory.getLogger(MsgReader.class);

  private MsgReader() {}

  static ParsedMailMessage read(Path file, MailProperties properties) throws IOException {
    return read(file, properties, null);
  }

  /** {@code wantedIndex} restricts what is materialized - see this class' own Javadoc. */
  static ParsedMailMessage read(Path file, MailProperties properties, Integer wantedIndex)
      throws IOException {
    try (InputStream in = Files.newInputStream(file);
        MAPIMessage message = new MAPIMessage(in)) {
      String subject = safeGet(message::getSubject);
      String from = safeGet(message::getDisplayFrom);
      String to = safeGet(message::getDisplayTo);
      Instant date = safeDate(message);
      String body = extractBody(message);
      List<ParsedMailAttachment> attachments =
          extractAttachments(message, file, properties, wantedIndex);
      return new ParsedMailMessage(subject, from, to, date, body, attachments);
    }
  }

  private static String extractBody(MAPIMessage message) {
    String plain = safeGet(message::getTextBody);
    if (plain != null && !plain.isBlank()) {
      return plain;
    }
    String html = safeGet(message::getHtmlBody);
    return html != null ? Jsoup.parse(html).text() : "";
  }

  private static List<ParsedMailAttachment> extractAttachments(
      MAPIMessage message, Path file, MailProperties properties, Integer wantedIndex)
      throws IOException {
    AttachmentChunks[] chunks = message.getAttachmentFiles();
    List<ParsedMailAttachment> attachments = new ArrayList<>(chunks.length);
    AttachmentBudget budget = new AttachmentBudget(properties.maxAttachmentsPerMessage());
    // The extraction position an unfiltered run would assign - advanced by every attachment that
    // was read successfully, materialized or not, so a filtered run numbers them identically.
    int position = 0;
    try {
      for (AttachmentChunks chunk : chunks) {
        if (!budget.hasCapacity()) {
          // Not extracted at all - no temp file is ever created for an attachment beyond the
          // configured limit (#1101 review, finding 3c).
          continue;
        }
        budget.reserve();
        boolean materialize = wantedIndex == null || wantedIndex == position;
        ParsedMailAttachment attachment = extractAttachment(chunk, properties, materialize);
        if (attachment == null) {
          continue;
        }
        position++;
        if (attachment.tempFile() != null) {
          attachments.add(attachment);
        }
      }
    } catch (IOException | RuntimeException e) {
      // See EmlReader#read's identical reasoning: whatever this pass already extracted must not
      // leak as an orphaned temp file just because a later attachment failed to read.
      for (ParsedMailAttachment attachment : attachments) {
        deleteQuietly(attachment.tempFile());
      }
      throw e;
    }
    if (budget.exhausted()) {
      log.warn(
          "{} carries more than the configured limit of {} attachments; the remainder was never"
              + " extracted (opaa.indexing.mail.max-attachments-per-message)",
          file,
          properties.maxAttachmentsPerMessage());
    }
    return attachments;
  }

  /**
   * The attachment behind {@code chunk}, or {@code null} when it could not be read at all (which
   * also skips an extraction position). With {@code materialize} {@code false} the same decisions
   * are made but no temp file is written - the returned record then carries a {@code null} {@link
   * ParsedMailAttachment#tempFile()}, which the caller counts and drops.
   */
  private static ParsedMailAttachment extractAttachment(
      AttachmentChunks chunk, MailProperties properties, boolean materialize) throws IOException {
    byte[] data;
    String fileName;
    try {
      data = chunk.getAttachData() != null ? chunk.getAttachData().getValue() : null;
      fileName = resolveFilename(chunk);
    } catch (RuntimeException e) {
      log.warn("Skipping a mail attachment that could not be read", e);
      return null;
    }
    if (data == null) {
      // An embedded Outlook item attachment, not a plain file - see this class's own Javadoc.
      log.info("Skipping unsupported (embedded-message) mail attachment chunk");
      return null;
    }
    if (data.length > properties.maxAttachmentBytes()) {
      log.warn(
          "Skipping mail attachment {} exceeding the size limit of {} bytes",
          fileName,
          properties.maxAttachmentBytes());
      return null;
    }
    if (!materialize) {
      return new ParsedMailAttachment(fileName, null);
    }
    Path tempFile = MailAttachmentIo.createTempFile(fileName);
    try (OutputStream out = Files.newOutputStream(tempFile)) {
      out.write(data);
    } catch (IOException e) {
      Files.deleteIfExists(tempFile);
      throw e;
    }
    return new ParsedMailAttachment(fileName, tempFile);
  }

  private static String resolveFilename(AttachmentChunks chunk) {
    String longName =
        chunk.getAttachLongFileName() != null ? chunk.getAttachLongFileName().getValue() : null;
    if (longName != null && !longName.isBlank()) {
      return longName;
    }
    String shortName =
        chunk.getAttachFileName() != null ? chunk.getAttachFileName().getValue() : null;
    return shortName != null && !shortName.isBlank() ? shortName : "anhang";
  }

  private static Instant safeDate(MAPIMessage message) {
    try {
      var calendar = message.getMessageDate();
      return calendar != null ? calendar.toInstant() : null;
    } catch (ChunkNotFoundException e) {
      return null;
    }
  }

  @FunctionalInterface
  private interface ChunkSupplier {
    String get() throws ChunkNotFoundException;
  }

  private static String safeGet(ChunkSupplier supplier) {
    try {
      return supplier.get();
    } catch (ChunkNotFoundException e) {
      return null;
    }
  }

  /**
   * Logs rather than throws on a failed delete - called from a {@code catch} block that is already
   * about to rethrow the real failure; a secondary I/O error here must not replace it.
   */
  private static void deleteQuietly(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      log.warn("Failed to delete temp file: {}", file, e);
    }
  }
}
