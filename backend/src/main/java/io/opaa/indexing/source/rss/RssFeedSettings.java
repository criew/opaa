package io.opaa.indexing.source.rss;

import io.opaa.common.ValidationException;
import io.opaa.format.file.html.HtmlContentRoots;
import io.opaa.indexing.attachment.AttachmentProfile;
import io.opaa.indexing.spi.ConnectorData;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jsoup.select.Evaluator;
import org.jsoup.select.QueryParser;
import org.jsoup.select.Selector;

/**
 * The connector settings of one feed library ({@code source_settings}, ADR-0038): the CSS selector
 * of a detail page's content areas and the {@link AttachmentProfile}. Either may be absent; absent
 * means the factory default in code, there is no installation-wide value (ADR-0060, Entscheidung
 * 1). A selector runs against every element of a page the feed operator controls, so only type, id,
 * class and attribute selectors joined by space, {@code >} or comma pass, bounded in length,
 * combinators and groups: every pseudo-class and the sibling combinators can cost more than linear
 * in the elements of a page.
 */
record RssFeedSettings(String mainContentSelector, AttachmentProfile attachmentProfile) {

  static final String MAIN_CONTENT_SELECTOR = "mainContentSelector";
  static final String ATTACHMENT_PROFILE = "attachmentProfile";
  static final Set<String> KEYS = Set.of(MAIN_CONTENT_SELECTOR, ATTACHMENT_PROFILE);

  static final int MAX_SELECTOR_LENGTH = 200;

  static final int MAX_COMBINATORS = 3;
  static final int MAX_GROUPS = 5;

  /** The effective selector, parsed once per run and reused for every detail page. */
  Evaluator compiledSelector() {
    return QueryParser.parse(effectiveSelector());
  }

  static final AttachmentProfile DEFAULT_PROFILE = AttachmentProfile.GENERIC;

  /** The settings of {@code data}, normalised; {@code null} reads as the factory defaults. */
  static RssFeedSettings read(ConnectorData data) {
    if (data == null) {
      return new RssFeedSettings(null, null);
    }
    data.requireOnly(KEYS);
    return new RssFeedSettings(
        selector(text(data.get(MAIN_CONTENT_SELECTOR), MAIN_CONTENT_SELECTOR)),
        profile(text(data.get(ATTACHMENT_PROFILE), ATTACHMENT_PROFILE)));
  }

  /** Only the fields that are set, so an empty object stands for the factory defaults. */
  ConnectorData toData() {
    Map<String, Object> values = new LinkedHashMap<>();
    if (mainContentSelector != null) {
      values.put(MAIN_CONTENT_SELECTOR, mainContentSelector);
    }
    if (attachmentProfile != null) {
      values.put(ATTACHMENT_PROFILE, attachmentProfile.name());
    }
    return ConnectorData.of(values);
  }

  String effectiveSelector() {
    return mainContentSelector != null
        ? mainContentSelector
        : HtmlContentRoots.DEFAULT_MAIN_CONTENT_SELECTOR;
  }

  AttachmentProfile effectiveProfile() {
    return attachmentProfile != null ? attachmentProfile : DEFAULT_PROFILE;
  }

  /** Whether a run under these settings extracts exactly as under the factory defaults. */
  boolean isFactoryDefault() {
    return effectiveSelector().equals(HtmlContentRoots.DEFAULT_MAIN_CONTENT_SELECTOR)
        && effectiveProfile() == DEFAULT_PROFILE;
  }

  /**
   * What a run extracted entries under, compared against the feed state: {@code null} for the
   * factory defaults, so a state saved before per-library settings existed matches them.
   */
  String fingerprint() {
    if (isFactoryDefault()) {
      return null;
    }
    Map<String, Object> effective = new LinkedHashMap<>();
    effective.put(MAIN_CONTENT_SELECTOR, effectiveSelector());
    effective.put(ATTACHMENT_PROFILE, effectiveProfile().name());
    return ConnectorData.of(effective).toJson();
  }

  private static String text(Object value, String field) {
    if (value == null) {
      return null;
    }
    if (!(value instanceof String string)) {
      throw new ValidationException("sourceSettings." + field + " muss ein Text sein");
    }
    String stripped = string.strip();
    return stripped.isEmpty() ? null : stripped;
  }

  private static AttachmentProfile profile(String value) {
    if (value == null) {
      return null;
    }
    try {
      return AttachmentProfile.valueOf(value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ValidationException("sourceSettings.attachmentProfile muss GENERIC oder GSB sein");
    }
  }

  private static String selector(String value) {
    if (value == null) {
      return null;
    }
    String field = "sourceSettings." + MAIN_CONTENT_SELECTOR;
    if (value.length() > MAX_SELECTOR_LENGTH) {
      throw new ValidationException(
          field + " darf höchstens " + MAX_SELECTOR_LENGTH + " Zeichen lang sein");
    }
    // only refuses earlier with a clearer message; the parsed tree below is what decides
    String lower = value.toLowerCase(Locale.ROOT);
    if (lower.contains(":matches") || lower.contains("~=")) {
      throw new ValidationException(
          field + ": reguläre Ausdrücke (:matches…, [attr~=…]) sind nicht erlaubt");
    }
    Evaluator parsed;
    try {
      parsed = QueryParser.parse(value);
    } catch (Selector.SelectorParseException | IllegalArgumentException e) {
      throw new ValidationException(
          field + " ist kein gültiger CSS-Selektor (Jsoup-Syntax), etwa main, #inhalt oder .text");
    }
    requireLinearCost(parsed, field);
    return value;
  }

  /**
   * Refuses a parsed selector beyond type, id, class and attribute tests joined by space, {@code >}
   * or comma, with more than {@link #MAX_COMBINATORS} combinators in a group or more than {@link
   * #MAX_GROUPS} groups ({@link SelectorCost}).
   */
  private static void requireLinearCost(Evaluator parsed, String field) {
    SelectorCost.Shape shape;
    try {
      shape = SelectorCost.of(parsed);
    } catch (SelectorCost.Refused e) {
      throw new ValidationException(
          e.regex
              ? field + ": reguläre Ausdrücke (:matches…, [attr~=…]) sind nicht erlaubt"
              : field
                  + " darf nur Element-, ID-, Klassen- und Attributselektoren mit Leerzeichen, >"
                  + " oder Komma verbinden; Pseudoklassen (:…) und die Kombinatoren ~ und + sind"
                  + " nicht erlaubt");
    }
    if (shape.groups() > MAX_GROUPS) {
      throw new ValidationException(
          field + " darf höchstens " + MAX_GROUPS + " durch Komma getrennte Selektoren enthalten");
    }
    if (shape.combinators() > MAX_COMBINATORS) {
      throw new ValidationException(
          field + " darf je Selektor höchstens " + MAX_COMBINATORS + " Kombinatoren enthalten");
    }
  }
}
