package io.opaa.selfdisclosure;

import io.opaa.compliance.SelfDisclosureCategory;
import io.opaa.compliance.SelfDisclosureCategory.Field;
import io.opaa.compliance.SelfDisclosureCategory.Section;
import io.opaa.compliance.SelfDisclosureLabels;
import io.opaa.compliance.SelfDisclosureSink;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.json.JsonMapper;

/**
 * The ZIP of one self-disclosure, written in one pass straight into the response: one {@code
 * <category>.json} per category with at least one record, then {@code manifest.json}, last {@code
 * index.html}. While a category is written, its first {@link #OVERVIEW_ROWS} records are kept, each
 * text cut at {@link #OVERVIEW_TEXT_LIMIT} characters, for the overview; nothing else stays in
 * memory. The sink enforces the positive list of the category at runtime.
 */
final class SelfDisclosureArchive {

  /** Records per category the overview shows; the rest is only in the JSON file. */
  static final int OVERVIEW_ROWS = 200;

  /** Characters of one text value in the overview. */
  static final int OVERVIEW_TEXT_LIMIT = 2000;

  private static final DateTimeFormatter LOCAL_TIME =
      DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
  private static final DateTimeFormatter LOCAL_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  /** Called before every record; throws when the export has been told to end. */
  @FunctionalInterface
  interface Heartbeat {
    void beat();
  }

  private final DeliveredBytes delivered;
  private final ZipOutputStream zip;
  private final JsonMapper json = JsonMapper.builder().build();
  private final ZoneId zone;
  private final Heartbeat heartbeat;
  private final List<CategoryResult> results = new ArrayList<>();

  SelfDisclosureArchive(OutputStream response, ZoneId zone, Heartbeat heartbeat) {
    this.delivered = new DeliveredBytes(response);
    this.zip = new ZipOutputStream(delivered, StandardCharsets.UTF_8);
    this.zone = zone;
    this.heartbeat = heartbeat;
  }

  /** Writes one category through {@code body}, which gets the sink of that category. */
  void category(SelfDisclosureCategory category, CategoryBody body) throws IOException {
    CategorySink sink = new CategorySink(category);
    // a failure ends the whole export; the half-written entry is left as it is
    body.write(sink);
    sink.closeIfOpen();
    results.add(sink.result());
  }

  /** Writes {@code manifest.json} and {@code index.html}, the central directory and flushes. */
  void finish(byte[] manifestJson, byte[] indexHtml) throws IOException {
    entry("manifest.json", manifestJson);
    entry("index.html", indexHtml);
    zip.finish();
    delivered.flush();
  }

  List<CategoryResult> results() {
    return List.copyOf(results);
  }

  long bytes() {
    return delivered.count();
  }

  String sha256() {
    return delivered.sha256();
  }

  ZoneId zone() {
    return zone;
  }

  private void entry(String name, byte[] content) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(content);
    zip.closeEntry();
  }

  /** The exporter call of one category. */
  @FunctionalInterface
  interface CategoryBody {
    void write(SelfDisclosureSink sink) throws IOException;
  }

  /**
   * What one category produced.
   *
   * @param fileName the JSON file, {@code null} when the category had no record
   * @param overview the first records as display texts, in the order of their section's fields
   */
  record CategoryResult(
      SelfDisclosureCategory category,
      long records,
      Map<String, Long> sectionRecords,
      List<OverviewRow> overview,
      String fileName) {}

  /** One record of the overview: its section and one display text per field. */
  record OverviewRow(String sectionKey, List<String> cells) {}

  private final class CategorySink implements SelfDisclosureSink {

    private final SelfDisclosureCategory category;
    private final Map<String, Long> sectionRecords = new LinkedHashMap<>();
    private final List<OverviewRow> overview = new ArrayList<>();
    private int lastOpened = -1;
    private Section current;
    private boolean currentWritten;
    private JsonGenerator generator;
    private long records;

    CategorySink(SelfDisclosureCategory category) {
      this.category = category;
    }

    @Override
    public RecordWriter section(String key) {
      int index = category.sections().indexOf(category.section(key));
      if (index < 0) {
        throw new IllegalStateException(
            "Category " + category.id() + " has no section '" + key + "'");
      }
      if (index <= lastOpened) {
        throw new IllegalStateException(
            "Section '" + key + "' of " + category.id() + " opened out of order or twice");
      }
      closeSectionIfWritten();
      lastOpened = index;
      Section section = category.sections().get(index);
      current = section;
      currentWritten = false;
      return record -> write(section, record);
    }

    private void write(Section section, Map<String, ?> record) throws IOException {
      if (section != current) {
        throw new IllegalStateException(
            "Section '" + section.key() + "' of " + category.id() + " is no longer open");
      }
      for (String key : record.keySet()) {
        if (section.field(key) == null) {
          throw new IllegalStateException(
              "Field '"
                  + key
                  + "' is not on the positive list of "
                  + category.id()
                  + "/"
                  + section.key());
        }
      }
      for (Field field : section.fields()) {
        checkDerived(section, field, record.get(field.key()));
      }
      heartbeat.beat();
      if (generator == null) {
        open();
      }
      if (!currentWritten) {
        generator.writeStartObject();
        generator.writeStringProperty("key", section.key());
        generator.writeStringProperty("title", section.title());
        generator.writeArrayPropertyStart("fields");
        for (Field field : section.fields()) {
          generator.writeStartObject();
          generator.writeStringProperty("key", field.key());
          generator.writeStringProperty("label", field.label());
          generator.writeEndObject();
        }
        generator.writeEndArray();
        generator.writeArrayPropertyStart("records");
        currentWritten = true;
      }
      List<String> cells = overview.size() < OVERVIEW_ROWS ? new ArrayList<>() : null;
      generator.writeStartObject();
      for (Field field : section.fields()) {
        Object value = record.get(field.key());
        generator.writeName(field.key());
        writeValue(section, field, value);
        if (cells != null) {
          cells.add(display(value));
        }
      }
      generator.writeEndObject();
      if (cells != null) {
        overview.add(new OverviewRow(section.key(), List.copyOf(cells)));
      }
      records++;
      sectionRecords.merge(section.key(), 1L, Long::sum);
    }

    private void checkDerived(Section section, Field field, Object value) {
      if (field.derived()
          && value != null
          && !(value instanceof Boolean)
          && !(value instanceof String label && SelfDisclosureLabels.ALL.contains(label))) {
        throw new IllegalStateException(
            "Derived field "
                + category.id()
                + "/"
                + section.key()
                + "/"
                + field.key()
                + " carries more than yes/no or a fixed label");
      }
    }

    private void writeValue(Section section, Field field, Object value) {
      switch (value) {
        case null -> generator.writeNull();
        case String text -> generator.writeString(text);
        case Boolean flag -> generator.writeBoolean(flag);
        case Integer number -> generator.writeNumber(number);
        case Long number -> generator.writeNumber(number);
        case Short number -> generator.writeNumber(number);
        case Byte number -> generator.writeNumber(number.intValue());
        case java.math.BigDecimal number -> generator.writeNumber(number);
        case java.math.BigInteger number -> generator.writeNumber(number);
        case Double number -> generator.writeNumber(number);
        case Float number -> generator.writeNumber(number);
        case Number number -> generator.writeNumber(number.toString());
        case Instant instant -> generator.writeString(instant.toString());
        case LocalDate date -> generator.writeString(date.toString());
        case UUID id -> generator.writeString(id.toString());
        case List<?> list -> {
          generator.writeStartArray();
          for (Object item : list) {
            if (!(item instanceof String text)) {
              throw unsupported(section, field, item);
            }
            generator.writeString(text);
          }
          generator.writeEndArray();
        }
        default -> throw unsupported(section, field, value);
      }
    }

    private IllegalStateException unsupported(Section section, Field field, Object value) {
      return new IllegalStateException(
          "Field "
              + category.id()
              + "/"
              + section.key()
              + "/"
              + field.key()
              + " carries an unsupported "
              + value.getClass().getName());
    }

    private String display(Object value) {
      String text =
          switch (value) {
            case null -> "–";
            case Boolean flag -> flag ? "ja" : "nein";
            case Instant instant -> LOCAL_TIME.format(instant.atZone(zone));
            case LocalDate date -> LOCAL_DATE.format(date);
            case List<?> list -> String.join(", ", list.stream().map(String::valueOf).toList());
            default -> String.valueOf(value);
          };
      if (text.length() > OVERVIEW_TEXT_LIMIT) {
        return text.substring(0, OVERVIEW_TEXT_LIMIT)
            + " … (gekürzt, vollständig in "
            + fileName()
            + ")";
      }
      return text;
    }

    private void open() throws IOException {
      zip.putNextEntry(new ZipEntry(fileName()));
      generator = json.createGenerator(new NonClosing(zip));
      generator.writeStartObject();
      generator.writeStringProperty("category", category.id());
      generator.writeStringProperty("title", category.title());
      generator.writeArrayPropertyStart("sections");
    }

    private void closeSectionIfWritten() {
      if (currentWritten) {
        generator.writeEndArray();
        generator.writeEndObject();
        currentWritten = false;
      }
    }

    void closeIfOpen() throws IOException {
      if (generator == null) {
        return;
      }
      closeSectionIfWritten();
      generator.writeEndArray();
      generator.writeEndObject();
      generator.close();
      zip.closeEntry();
    }

    private String fileName() {
      return category.id() + ".json";
    }

    CategoryResult result() {
      return new CategoryResult(
          category,
          records,
          Map.copyOf(sectionRecords),
          List.copyOf(overview),
          generator == null ? null : fileName());
    }
  }

  /** Lets the generator flush into the open ZIP entry without closing the archive. */
  private static final class NonClosing extends FilterOutputStream {

    NonClosing(OutputStream out) {
      super(out);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      out.write(b, off, len);
    }

    @Override
    public void close() throws IOException {
      flush();
    }
  }
}
