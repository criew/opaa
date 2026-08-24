package io.opaa.indexing;

import java.io.InputStream;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses a standard RSS 2.0 feed (ADR-0017) into its {@link RssFeedEntry} items, using only the
 * JDK's own StAX implementation - no additional library is needed for a format this small.
 *
 * <p>Scope (ADR-0017, decision 2): this class stops at "a list of content elements with a name, a
 * reference to the origin and a change marker" - here, {@code title}/{@code link}/{@code
 * description}/{@code pubDate}. Resolving a {@code <link>} to its detail page's article text and
 * attachments is a separate, later step, deliberately not part of this parser: parsing is pure
 * transformation (text in, entries out) and is fully checkable against fixtures, without network or
 * database.
 *
 * <p>Hardening against XXE: a feed is attacker-controlled content from a third party; the JDK's
 * default StAX settings would otherwise let a feed instruct the parser to read local files or reach
 * out over the network via a DOCTYPE-declared external entity. Both DTDs and external entities are
 * disabled below, following the standard StAX XXE mitigation.
 *
 * <p>Leniency: unknown elements and elements from a foreign namespace (Atom's {@code atom:link},
 * Media RSS's {@code media:*}, {@code content:encoded}, ...) are simply skipped rather than treated
 * as errors - only the four fields above, read directly under {@code <item>} without a namespace,
 * are recognised. An item without a {@code <link>} is skipped entirely. A document that does not
 * parse as XML at all fails with a German, user-facing {@link RssFeedParseException} - unlike the
 * rest of this class's log/exception text, which stays English per AGENTS.md's language split.
 */
public class RssFeedParser {

  private static final Logger log = LoggerFactory.getLogger(RssFeedParser.class);

  private static final Set<String> RECOGNIZED_ITEM_FIELDS =
      Set.of("title", "link", "description", "pubDate");

  /**
   * RFC 822/2822 "obsolete" zone abbreviations that {@link DateTimeFormatter#RFC_1123_DATE_TIME}
   * cannot resolve on its own (it only understands numeric offsets and a handful of zone IDs) -
   * feed generators commonly emit these instead of a numeric offset. Mapped to the numeric offset
   * RFC 2822 section 4.3 assigns them before parsing.
   */
  private static final Map<String, String> ZONE_ABBREVIATIONS =
      Map.ofEntries(
          Map.entry("UT", "+0000"),
          Map.entry("GMT", "+0000"),
          Map.entry("UTC", "+0000"),
          Map.entry("EST", "-0500"),
          Map.entry("EDT", "-0400"),
          Map.entry("CST", "-0600"),
          Map.entry("CDT", "-0500"),
          Map.entry("MST", "-0700"),
          Map.entry("MDT", "-0600"),
          Map.entry("PST", "-0800"),
          Map.entry("PDT", "-0700"));

  /**
   * A two-digit-year variant of RFC 822/2822, tried before {@link
   * DateTimeFormatter#RFC_1123_DATE_TIME} - that formatter also accepts a two-digit year but
   * resolves it literally (e.g. {@code "24"} becomes the year 24, not 2024). {@link
   * java.time.format.DateTimeFormatterBuilder#appendValueReduced} maps the two parsed digits into
   * the century-spanning window {@code [1970, 2069]}, the same windowing scheme {@code strptime}'s
   * {@code %y} uses. A four-digit year does not match this formatter at all, so trying this first
   * never misinterprets an ordinary four-digit date.
   */
  private static final DateTimeFormatter TWO_DIGIT_YEAR_DATE_TIME =
      new DateTimeFormatterBuilder()
          .parseCaseInsensitive()
          .optionalStart()
          .appendPattern("EEE, ")
          .optionalEnd()
          .appendPattern("d MMM ")
          .appendValueReduced(ChronoField.YEAR, 2, 2, 1970)
          .appendPattern(" HH:mm")
          .optionalStart()
          .appendPattern(":ss")
          .optionalEnd()
          .appendPattern(" Z")
          .toFormatter(Locale.ENGLISH);

  /**
   * Parses an RSS 2.0 document into its entries. Items without a {@code <link>} are silently
   * omitted (see class Javadoc).
   *
   * @throws RssFeedParseException if the input does not parse as XML at all - message is German and
   *     user-facing (see class Javadoc)
   */
  public List<RssFeedEntry> parse(InputStream input) {
    XMLInputFactory factory = XMLInputFactory.newInstance();
    // XXE hardening: no DTDs, no external entities. See class Javadoc.
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);

    XMLStreamReader reader;
    try {
      reader = factory.createXMLStreamReader(input);
    } catch (XMLStreamException e) {
      throw new RssFeedParseException(
          "Der RSS-Feed konnte nicht gelesen werden: kein gültiges XML.", e);
    }
    try {
      return readEntries(reader);
    } catch (XMLStreamException e) {
      // Only a genuine XML well-formedness failure is reported this way - RssFeedParseException
      // thrown deliberately inside readEntries (e.g. the wrong-root-element check below) is a
      // RuntimeException and passes through this catch untouched, keeping its own specific
      // message instead of being overwritten with the generic one here.
      throw new RssFeedParseException(
          "Der RSS-Feed konnte nicht gelesen werden: kein gültiges XML.", e);
    } finally {
      try {
        reader.close();
      } catch (XMLStreamException ignored) {
        // closing failure after a result (or another exception) was already determined - nothing
        // more this method can usefully do about it.
      }
    }
  }

  private List<RssFeedEntry> readEntries(XMLStreamReader reader) throws XMLStreamException {
    List<RssFeedEntry> entries = new ArrayList<>();

    int depth = 0;
    int itemDepth = -1;
    boolean inItem = false;

    String currentField = null;
    StringBuilder currentText = null;

    String title = null;
    String link = null;
    String description = null;
    String pubDate = null;

    while (reader.hasNext()) {
      int event = reader.next();
      switch (event) {
        case XMLStreamConstants.START_ELEMENT -> {
          depth++;
          String localName = reader.getLocalName();
          if (depth == 1 && !"rss".equals(localName)) {
            throw new RssFeedParseException(
                "Das Dokument ist kein RSS-Feed (Wurzelelement <"
                    + localName
                    + "> statt <rss>). Andere Formate wie Atom werden hier nicht unterstützt.",
                null);
          }
          boolean noNamespace = reader.getNamespaceURI() == null;
          if (!inItem && noNamespace && "item".equals(localName)) {
            inItem = true;
            itemDepth = depth;
            title = null;
            link = null;
            description = null;
            pubDate = null;
          } else if (inItem
              && depth == itemDepth + 1
              && noNamespace
              && RECOGNIZED_ITEM_FIELDS.contains(localName)
              && !("link".equals(localName) && link != null)) {
            // The "link" exclusion keeps the first <link> of an item, not the last - the more
            // reliable choice if an item ever carries more than one.
            currentField = localName;
            currentText = new StringBuilder();
          }
        }
        case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
          if (currentField != null) {
            currentText.append(reader.getText());
          }
        }
        case XMLStreamConstants.END_ELEMENT -> {
          String localName = reader.getLocalName();
          if (currentField != null && depth == itemDepth + 1 && localName.equals(currentField)) {
            String value = currentText.toString().trim();
            switch (currentField) {
              case "title" -> title = value;
              case "link" -> link = value;
              case "description" -> description = value;
              case "pubDate" -> pubDate = value;
              default -> {
                // unreachable - currentField is only ever one of RECOGNIZED_ITEM_FIELDS
              }
            }
            currentField = null;
            currentText = null;
          }
          if (inItem && depth == itemDepth && "item".equals(localName)) {
            if (link != null && !link.isBlank()) {
              entries.add(new RssFeedEntry(title, link, description, parsePubDate(pubDate)));
            } else {
              log.debug("Skipping RSS item without a link (title: {})", title);
            }
            inItem = false;
            itemDepth = -1;
          }
          depth--;
        }
        default -> {
          // ignored: comments, processing instructions, whitespace-only events etc.
        }
      }
    }

    return entries;
  }

  /**
   * Parses an RSS {@code pubDate} (nominally RFC 822/2822) leniently. An unparseable or missing
   * value yields {@link Optional#empty()} rather than a failure - a date the parser cannot read
   * must not invalidate the entry.
   */
  Optional<Instant> parsePubDate(String rawValue) {
    if (rawValue == null || rawValue.isBlank()) {
      return Optional.empty();
    }
    String normalized = normalizeZoneAbbreviation(rawValue.trim());

    // Tried first, deliberately: RFC_1123_DATE_TIME below also accepts a two-digit year, but
    // resolves it literally rather than into a sensible century (see TWO_DIGIT_YEAR_DATE_TIME's
    // own Javadoc).
    try {
      return Optional.of(ZonedDateTime.parse(normalized, TWO_DIGIT_YEAR_DATE_TIME).toInstant());
    } catch (DateTimeException e) {
      // fall through - not a two-digit-year date, try the standard formatter below
    }

    try {
      return Optional.of(
          ZonedDateTime.parse(normalized, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
    } catch (DateTimeException e) {
      // fall through - unparseable
    }

    log.debug("Could not parse RSS pubDate '{}', leaving it empty", rawValue);
    return Optional.empty();
  }

  private static String normalizeZoneAbbreviation(String value) {
    for (Map.Entry<String, String> abbreviation : ZONE_ABBREVIATIONS.entrySet()) {
      String suffix = " " + abbreviation.getKey();
      if (value.endsWith(suffix)) {
        return value.substring(0, value.length() - suffix.length()) + " " + abbreviation.getValue();
      }
    }
    return value;
  }
}
