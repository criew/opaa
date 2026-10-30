package io.opaa.format.file.mail;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.format.DiscoveredAttachment;
import io.opaa.format.DocumentFormatResult;
import io.opaa.format.DocumentFormatSource;
import io.opaa.format.chunk.ChunkingService;
import io.opaa.indexing.IndexingProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An Outlook message cannot unpack to more than it stores: POI does not notice when several
 * attachment entries of the compound file point at the same sectors, so a message of a few dozen
 * kilobytes could hand out the same data again and again. Identical attachments of one message are
 * extracted once, and all attachments together never exceed the size of the message file.
 */
class MsgAmplificationTest {

  private static final int ATTACHMENT_BYTES = 64 * 1024;
  private static final int SHARING_ENTRIES = 30;

  @TempDir Path tempDir;

  private final List<DocumentFormatResult> results = new ArrayList<>();

  @AfterEach
  void deleteTempFiles() throws IOException {
    for (DocumentFormatResult result : results) {
      for (DiscoveredAttachment attachment : result.discoveredAttachments()) {
        Files.deleteIfExists(attachment.tempFile());
      }
    }
  }

  /** Regression guard: thirty entries share the sectors of one attachment. */
  @Test
  void attachmentsSharingTheirSectorsAreExtractedOnce() throws IOException {
    Path file = sharingMessage("geteilt.msg", 0);

    DocumentFormatResult result = run(file);

    assertThat(result.discoveredAttachments()).hasSize(1);
  }

  /**
   * Regression guard: thirty entries start one sector apart inside the same attachment, so every
   * one of them is different and together they are many times the file.
   */
  @Test
  void attachmentsTogetherNeverExceedTheMessageFile() throws IOException {
    Path file = sharingMessage("versetzt.msg", 512);

    DocumentFormatResult result = run(file);

    long total = 0;
    for (DiscoveredAttachment attachment : result.discoveredAttachments()) {
      total += Files.size(attachment.tempFile());
    }
    assertThat(result.discoveredAttachments()).isNotEmpty();
    assertThat(total).isLessThanOrEqualTo(Files.size(file));
  }

  /** Distinct attachments that the file really stores are all extracted. */
  @Test
  void distinctStoredAttachmentsAreAllExtracted() throws IOException {
    Path file = tempDir.resolve("echt.msg");
    try (POIFSFileSystem fs = new POIFSFileSystem()) {
      subject(fs, "Drei Anhänge");
      for (int i = 0; i < 3; i++) {
        attachment(fs, i, "anhang" + i + ".txt", ("Inhalt " + i).getBytes(StandardCharsets.UTF_8));
      }
      try (OutputStream out = Files.newOutputStream(file)) {
        fs.writeFilesystem(out);
      }
    }

    assertThat(run(file).discoveredAttachments()).hasSize(3);
  }

  private DocumentFormatResult run(Path file) {
    MailDocumentFormat format =
        new MailDocumentFormat(
            new ChunkingService(new IndexingProperties(1000, 100, 50, null, null, null, 1)),
            new MailProperties(0, 0, 0),
            Clock.systemUTC());
    DocumentFormatResult result =
        format.run(DocumentFormatSource.ofFile(file, file.getFileName().toString()));
    results.add(result);
    return result;
  }

  /**
   * One stored attachment of {@link #ATTACHMENT_BYTES} random bytes and {@link #SHARING_ENTRIES}
   * further attachment entries whose data entry is rewritten to point into its sectors, each {@code
   * step} bytes further in than the one before.
   */
  private Path sharingMessage(String name, int step) throws IOException {
    byte[] stored = new byte[ATTACHMENT_BYTES];
    new Random(3376).nextBytes(stored);
    Path file = tempDir.resolve(name);
    try (POIFSFileSystem fs = new POIFSFileSystem()) {
      subject(fs, "Geteilte Sektoren");
      attachment(fs, 0, "gross.bin", stored);
      for (int i = 1; i <= SHARING_ENTRIES; i++) {
        attachment(fs, i, "klein" + i + ".bin", new byte[] {(byte) i});
      }
      try (OutputStream out = Files.newOutputStream(file)) {
        fs.writeFilesystem(out);
      }
    }
    byte[] bytes = Files.readAllBytes(file);
    List<Integer> dataEntries = directoryEntries(bytes, "__substg1.0_37010102");
    ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    int big =
        dataEntries.stream()
            .filter(entry -> buffer.getInt(entry + 120) == ATTACHMENT_BYTES)
            .findFirst()
            .orElseThrow();
    int start = buffer.getInt(big + 116);
    int k = 0;
    for (int entry : dataEntries) {
      if (entry == big) {
        continue;
      }
      k++;
      int offset = step * k;
      buffer.putInt(entry + 116, start + offset / 512);
      buffer.putInt(entry + 120, ATTACHMENT_BYTES - offset);
    }
    Files.write(file, bytes);
    // the rewritten file is still a compound file POI reads
    try (POIFSFileSystem check = new POIFSFileSystem(new ByteArrayInputStream(bytes))) {
      assertThat(check.getRoot().getEntryCount()).isGreaterThan(SHARING_ENTRIES);
    }
    return file;
  }

  private static void subject(POIFSFileSystem fs, String subject) throws IOException {
    fs.createDocument(
        new ByteArrayInputStream(subject.getBytes(StandardCharsets.UTF_16LE)),
        "__substg1.0_0037001F");
  }

  private static void attachment(POIFSFileSystem fs, int index, String fileName, byte[] data)
      throws IOException {
    DirectoryEntry dir = fs.createDirectory(String.format("__attach_version1.0_#%08X", index));
    dir.createDocument("__substg1.0_37010102", new ByteArrayInputStream(data));
    dir.createDocument(
        "__substg1.0_3707001F",
        new ByteArrayInputStream(fileName.getBytes(StandardCharsets.UTF_16LE)));
  }

  /** File offsets of the 128-byte directory entries named {@code name}. */
  private static List<Integer> directoryEntries(byte[] bytes, String name) {
    byte[] needle = name.getBytes(StandardCharsets.UTF_16LE);
    List<Integer> entries = new ArrayList<>();
    for (int at = 512; at + 128 <= bytes.length; at += 128) {
      boolean match = true;
      for (int i = 0; i < needle.length && match; i++) {
        match = bytes[at + i] == needle[i];
      }
      if (match) {
        entries.add(at);
      }
    }
    return entries;
  }
}
