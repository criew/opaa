package io.opaa.compliance;

import java.io.IOException;
import java.util.Map;

/**
 * Where a {@link SelfDisclosureExporter} writes its records; the self-disclosure streams them into
 * the archive as they come. Sections are opened forward only, each at most once, in any order of
 * the category. A record carries only keys of its section's positive list; its values are {@code
 * null}, {@link String}, {@link Number}, {@link Boolean}, {@link java.time.Instant} (written in
 * UTC), {@link java.time.LocalDate}, {@link java.util.UUID} or a {@link java.util.List} of strings.
 * Anything else is a programming error and raises {@link IllegalStateException}.
 */
public interface SelfDisclosureSink {

  /** Opens the section {@code key} of the exporter's category; the previous one is closed. */
  RecordWriter section(String key) throws IOException;

  /** Writes the records of one open section. */
  @FunctionalInterface
  interface RecordWriter {

    /** Writes one flat record, field key to value; a field absent from the map is written empty. */
    void write(Map<String, ?> record) throws IOException;
  }
}
