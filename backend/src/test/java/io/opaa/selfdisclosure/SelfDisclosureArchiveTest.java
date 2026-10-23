package io.opaa.selfdisclosure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.compliance.SelfDisclosureCategory;
import io.opaa.compliance.SelfDisclosureCategory.Field;
import io.opaa.compliance.SelfDisclosureCategory.Section;
import io.opaa.compliance.SelfDisclosureLabels;
import io.opaa.compliance.SelfDisclosureSink.RecordWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

/**
 * The archive of a self-disclosure without a database: entry order, the positive list at runtime,
 * forward-only sections, derived fields, the bounded overview and the delivered bytes and checksum.
 */
class SelfDisclosureArchiveTest {

  private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

  private static final SelfDisclosureCategory CHATS =
      new SelfDisclosureCategory(
          "chats",
          "Chats",
          List.of(
              new Section(
                  "chats",
                  "Ihre Chats",
                  List.of(
                      Field.of("title", "Titel", "chats.title"),
                      Field.of("createdAt", "Angelegt", "chats.created_at"))),
              new Section(
                  "messages",
                  "Nachrichten",
                  List.of(
                      Field.of("content", "Inhalt", "chat_messages.content"),
                      Field.derived("author", "Verfasst von", "chats.author_id")))));

  private static final SelfDisclosureCategory EMPTY =
      new SelfDisclosureCategory(
          "favorites",
          "Favoriten",
          List.of(new Section("favorites", "Favoriten", List.of(Field.of("x", "X", "a.b")))));

  @Test
  void writesOneJsonPerCategoryWithRecordsThenManifestThenOverview() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    SelfDisclosureArchive archive = new SelfDisclosureArchive(out, ZONE, () -> {});

    archive.category(
        CHATS,
        sink -> {
          sink.section("chats")
              .write(
                  Map.of("title", "Bauantrag", "createdAt", Instant.parse("2026-10-01T08:00:00Z")));
          sink.section("messages")
              .write(Map.of("content", "Frage", "author", SelfDisclosureLabels.YOU));
        });
    archive.category(EMPTY, sink -> {});
    archive.finish(
        "{}".getBytes(StandardCharsets.UTF_8), "<html></html>".getBytes(StandardCharsets.UTF_8));

    Map<String, String> entries = entries(out.toByteArray());
    assertThat(entries.keySet()).containsExactly("chats.json", "manifest.json", "index.html");
    assertThat(entries.get("chats.json"))
        .contains("\"category\":\"chats\"")
        .contains("\"key\":\"messages\"")
        .contains("\"createdAt\":\"2026-10-01T08:00:00Z\"")
        .contains("\"author\":\"Sie\"");
    assertThat(archive.results())
        .extracting(SelfDisclosureArchive.CategoryResult::fileName)
        .containsExactly("chats.json", null);
    assertThat(archive.bytes()).isEqualTo(out.size());
    assertThat(archive.sha256())
        .isEqualTo(
            HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(out.toByteArray())));
  }

  @Test
  void writesEveryKindOfNumberThePortPromises() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    SelfDisclosureArchive archive = new SelfDisclosureArchive(out, ZONE, () -> {});
    List<Number> numbers =
        List.of(
            (byte) 1,
            (short) 2,
            3,
            4L,
            5.5f,
            6.25d,
            new java.math.BigDecimal("7.125"),
            new java.math.BigInteger("80000000000000000000"),
            new java.util.concurrent.atomic.AtomicLong(9));

    archive.category(
        CHATS,
        sink -> {
          RecordWriter writer = sink.section("chats");
          for (Number number : numbers) {
            writer.write(Map.of("title", number));
          }
        });
    archive.finish(new byte[0], new byte[0]);

    assertThat(entries(out.toByteArray()).get("chats.json"))
        .contains("\"title\":1,", "\"title\":2,", "\"title\":3,", "\"title\":4,")
        .contains("\"title\":5.5,", "\"title\":6.25,", "\"title\":7.125,")
        .contains("\"title\":80000000000000000000,", "\"title\":9,");
  }

  @Test
  void refusesAFieldOutsideThePositiveListAndSectionsOutOfOrder() {
    SelfDisclosureArchive archive =
        new SelfDisclosureArchive(new ByteArrayOutputStream(), ZONE, () -> {});

    assertThatThrownBy(
            () ->
                archive.category(CHATS, sink -> sink.section("chats").write(Map.of("secret", "x"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("positive list");
    assertThatThrownBy(
            () ->
                archive.category(
                    CHATS,
                    sink -> {
                      sink.section("messages");
                      sink.section("chats");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("out of order");
    assertThatThrownBy(
            () ->
                archive.category(
                    CHATS,
                    sink -> sink.section("messages").write(Map.of("author", "Erika Muster"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("fixed label");
  }

  @Test
  void theOverviewKeepsTheFirst200RecordsWithTextsCutAt2000Characters() throws Exception {
    SelfDisclosureArchive archive =
        new SelfDisclosureArchive(OutputStream.nullOutputStream(), ZONE, () -> {});
    String longText = "x".repeat(5000);

    archive.category(
        CHATS,
        sink -> {
          RecordWriter writer = sink.section("chats");
          for (int i = 0; i < 250; i++) {
            writer.write(Map.of("title", longText));
          }
        });

    SelfDisclosureArchive.CategoryResult result = archive.results().getFirst();
    assertThat(result.records()).isEqualTo(250);
    assertThat(result.overview()).hasSize(SelfDisclosureArchive.OVERVIEW_ROWS);
    assertThat(result.overview().getFirst().cells().getFirst())
        .startsWith("x".repeat(2000) + " …")
        .contains("vollständig in chats.json")
        .hasSizeLessThan(2100);
  }

  @Test
  void memoryDoesNotGrowWithTheNumberOfRecords() throws Exception {
    SelfDisclosureArchive archive =
        new SelfDisclosureArchive(OutputStream.nullOutputStream(), ZONE, () -> {});
    Runtime runtime = Runtime.getRuntime();
    System.gc();
    long before = runtime.totalMemory() - runtime.freeMemory();
    Map<String, Object> record = new LinkedHashMap<>();
    record.put("title", "t".repeat(1000));
    record.put("createdAt", Instant.EPOCH);

    archive.category(
        CHATS,
        sink -> {
          RecordWriter writer = sink.section("chats");
          for (int i = 0; i < 200_000; i++) {
            writer.write(record);
          }
        });

    // 200 000 records of about 1 KB are about 200 MB of JSON; only the overview stays
    assertThat(archive.results().getFirst().records()).isEqualTo(200_000);
    assertThat(archive.results().getFirst().overview())
        .hasSize(SelfDisclosureArchive.OVERVIEW_ROWS);
    System.gc();
    long grown = runtime.totalMemory() - runtime.freeMemory() - before;
    assertThat(grown).isLessThan(64L * 1024 * 1024);
  }

  @Test
  void aHeartbeatThatThrowsEndsTheExport() {
    SelfDisclosureArchive archive =
        new SelfDisclosureArchive(
            new ByteArrayOutputStream(),
            ZONE,
            () -> {
              throw new SelfDisclosureAbort(SelfDisclosureAbort.AbortCause.TIMEOUT, "late");
            });

    assertThatThrownBy(
            () ->
                archive.category(CHATS, sink -> sink.section("chats").write(Map.of("title", "a"))))
        .isInstanceOf(SelfDisclosureAbort.class);
    assertThat(
            SelfDisclosureService.causeOf(
                new SelfDisclosureAbort(SelfDisclosureAbort.AbortCause.TIMEOUT, "late")))
        .isEqualTo(SelfDisclosureAbort.AbortCause.TIMEOUT);
    assertThat(SelfDisclosureService.causeOf(new RuntimeException(new IOException("pipe"))))
        .isEqualTo(SelfDisclosureAbort.AbortCause.CLIENT_DISCONNECTED);
    assertThat(SelfDisclosureService.causeOf(new IllegalStateException("bug")))
        .isEqualTo(SelfDisclosureAbort.AbortCause.ERROR);
  }

  private static Map<String, String> entries(byte[] zip) throws IOException {
    Map<String, String> entries = new LinkedHashMap<>();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
      for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
        entries.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    return entries;
  }
}
