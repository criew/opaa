package io.opaa.indexing;

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
 * <p><b>Attachment size bound is best-effort, unlike {@link EmlReader}'s.</b> {@link MAPIMessage}
 * loads an entire {@code .msg} compound file - including every attachment's bytes - into memory as
 * part of parsing it, before this class ever sees it; there is no streaming point left to enforce
 * {@link MailProperties#maxAttachmentBytes()} against while reading, the way {@link
 * MailAttachmentIo#copyBounded} does for EML. This class adds a post-hoc check that skips writing
 * an oversized attachment to disk rather than routing it through the pipeline registry, so at least
 * the downstream steps (format detection, embedding) never see it - but by the time that check
 * runs, the whole message (including every attachment's bytes) is already fully resident in memory.
 * The actual memory bound is {@link MailProperties#maxMessageBytes()}, checked by {@link
 * MailDocumentPipeline} against the message file's own size before this reader ever runs at all
 * (see {@link MailProperties}'s own Javadoc).
 *
 * <p><b>An embedded Outlook item attachment (a message attached as its own MAPI object, not as
 * bytes) is skipped, not recursed into.</b> {@link AttachmentChunks#getAttachData()} is {@code
 * null} for that case - reconstructing a standalone {@code .msg} from the embedded MAPI directory
 * would need to rebuild a full OLE2 compound file, which POI does not offer a public writer for. A
 * message forwarded as a genuine file attachment (the far more common case) is unaffected.
 */
final class MsgReader {

  private static final Logger log = LoggerFactory.getLogger(MsgReader.class);

  private MsgReader() {}

  static ParsedMailMessage read(Path file, MailProperties properties) throws IOException {
    try (InputStream in = Files.newInputStream(file);
        MAPIMessage message = new MAPIMessage(in)) {
      String subject = safeGet(message::getSubject);
      String from = safeGet(message::getDisplayFrom);
      String to = safeGet(message::getDisplayTo);
      Instant date = safeDate(message);
      String body = extractBody(message);
      List<ParsedMailAttachment> attachments = extractAttachments(message, file, properties);
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
      MAPIMessage message, Path file, MailProperties properties) throws IOException {
    AttachmentChunks[] chunks = message.getAttachmentFiles();
    List<ParsedMailAttachment> attachments = new ArrayList<>(chunks.length);
    AttachmentBudget budget = new AttachmentBudget(properties.maxAttachmentsPerMessage());
    try {
      for (AttachmentChunks chunk : chunks) {
        if (!budget.hasCapacity()) {
          // Not extracted at all - no temp file is ever created for an attachment beyond the
          // configured limit (#1101 review, finding 3c).
          continue;
        }
        budget.reserve();
        ParsedMailAttachment attachment = extractAttachment(chunk, properties);
        if (attachment != null) {
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

  private static ParsedMailAttachment extractAttachment(
      AttachmentChunks chunk, MailProperties properties) throws IOException {
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
