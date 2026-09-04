package io.opaa.indexing.pipeline.office;

import io.opaa.indexing.pipeline.DocumentPipelineSource;
import io.opaa.indexing.pipeline.DocumentProperties;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Reads an ODF package's {@code meta.xml} ({@code dc:title}, {@code meta:creation-date}, {@code
 * dc:date} = last modified) into {@link DocumentProperties} through the same hardened reader as
 * {@code content.xml} (ADR-0024). A missing or broken {@code meta.xml} yields {@link
 * DocumentProperties#EMPTY} - supplementary data never fails a document.
 */
final class OdfMetaProperties {

  private static final Logger log = LoggerFactory.getLogger(OdfMetaProperties.class);

  private OdfMetaProperties() {}

  static DocumentProperties read(DocumentPipelineSource source, OdfProperties odfProperties) {
    if (source.file() == null) {
      return DocumentProperties.EMPTY;
    }
    MetaHandler handler = new MetaHandler();
    try {
      OdfContentXml.parse(source.file(), "meta.xml", odfProperties.maxContentXmlBytes(), handler);
    } catch (IOException | RuntimeException e) {
      log.warn("Could not read meta.xml of ODF document {}", source.fileName(), e);
      return DocumentProperties.EMPTY;
    }
    return new DocumentProperties(
        handler.title,
        parseDate(handler.created),
        parseDate(handler.modified),
        null,
        null,
        null,
        null,
        false,
        Map.of());
  }

  /**
   * ODF dates are ISO-8601 local date-times ({@code 2026-03-12T09:15:00}); only the day is used.
   */
  private static LocalDate parseDate(String value) {
    if (value == null || value.length() < 10) {
      return null;
    }
    try {
      return LocalDate.parse(value.strip().substring(0, 10));
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static final class MetaHandler extends DefaultHandler {
    private String title;
    private String created;
    private String modified;
    private StringBuilder current;

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) {
      switch (qName) {
        case "dc:title", "meta:creation-date", "dc:date" -> current = new StringBuilder();
        default -> current = null;
      }
    }

    @Override
    public void characters(char[] ch, int start, int length) {
      if (current != null && current.length() < 2000) {
        current.append(ch, start, length);
      }
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
      if (current == null) {
        return;
      }
      String value = current.toString().strip();
      switch (qName) {
        case "dc:title" -> title = value;
        case "meta:creation-date" -> created = value;
        case "dc:date" -> modified = value;
        default -> {}
      }
      current = null;
    }
  }
}
