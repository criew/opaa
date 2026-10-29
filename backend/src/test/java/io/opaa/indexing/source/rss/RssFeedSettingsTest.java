package io.opaa.indexing.source.rss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.common.ValidationException;
import io.opaa.format.file.html.HtmlContentRoots;
import io.opaa.indexing.attachment.AttachmentProfile;
import io.opaa.indexing.spi.ConnectorData;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RssFeedSettingsTest {

  @Test
  void noSettingsMeanTheFactoryDefaults() {
    RssFeedSettings settings = RssFeedSettings.read(null);

    assertThat(settings.mainContentSelector()).isNull();
    assertThat(settings.attachmentProfile()).isNull();
    assertThat(settings.effectiveSelector())
        .isEqualTo(HtmlContentRoots.DEFAULT_MAIN_CONTENT_SELECTOR);
    assertThat(settings.effectiveProfile()).isEqualTo(AttachmentProfile.GENERIC);
    assertThat(settings.fingerprint()).isNull();
    assertThat(settings.toData().isEmpty()).isTrue();
  }

  @Test
  void readsAndNormalisesBothFields() {
    RssFeedSettings settings =
        RssFeedSettings.read(
            ConnectorData.of(
                Map.of("mainContentSelector", "  #inhalt .text  ", "attachmentProfile", "gsb")));

    assertThat(settings.mainContentSelector()).isEqualTo("#inhalt .text");
    assertThat(settings.attachmentProfile()).isEqualTo(AttachmentProfile.GSB);
    assertThat(settings.effectiveSelector()).isEqualTo("#inhalt .text");
    assertThat(settings.toData().asMap())
        .containsEntry("mainContentSelector", "#inhalt .text")
        .containsEntry("attachmentProfile", "GSB");
  }

  @Test
  void blankOrNullFieldsFallBackToTheFactoryDefault() {
    Map<String, Object> values = new HashMap<>();
    values.put("mainContentSelector", "   ");
    values.put("attachmentProfile", null);

    RssFeedSettings settings = RssFeedSettings.read(ConnectorData.of(values));

    assertThat(settings).isEqualTo(RssFeedSettings.read(null));
    assertThat(settings.toData().isEmpty()).isTrue();
  }

  @Test
  void aSelectorAtTheLengthLimitPassesAndOneCharacterMoreIsRefused() {
    String atLimit = "#" + "a".repeat(RssFeedSettings.MAX_SELECTOR_LENGTH - 1);

    assertThat(RssFeedSettings.read(selector(atLimit)).mainContentSelector()).isEqualTo(atLimit);
    assertThatThrownBy(() -> RssFeedSettings.read(selector(atLimit + "b")))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("sourceSettings.mainContentSelector")
        .hasMessageContaining(String.valueOf(RssFeedSettings.MAX_SELECTOR_LENGTH));
  }

  @ParameterizedTest
  @ValueSource(strings = {"div[", "main >", "a:unknownpseudo", ":has("})
  void refusesASelectorJsoupCannotParse(String invalid) {
    assertThatThrownBy(() -> RssFeedSettings.read(selector(invalid)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("kein gültiger CSS-Selektor");
  }

  /** A regular expression would run against every element of a page the feed operator controls. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "p:matches((a+)+$)",
        "p:MATCHESOWN(x)",
        "div:matchesWholeText(.*)",
        "div:matchesWholeOwnText(.*)",
        "a[href~=(a+)+$]"
      })
  void refusesRegularExpressions(String withRegex) {
    assertThatThrownBy(() -> RssFeedSettings.read(selector(withRegex)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("reguläre Ausdrücke");
  }

  /**
   * Every pseudo-class and the sibling combinators can cost more than linear in the elements of a
   * page the feed operator controls ({@code :has} and {@code ~} scan siblings or subtrees per
   * element, {@code :contains} the text below it, {@code :nth-*} the sibling list).
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "div:has(> h1)",
        "p:has(~ p)",
        "*:contains(Meldung)",
        "p:containsOwn(x)",
        "li:nth-child(2)",
        "div:not(.nav)",
        ":is(main, article)",
        "h1 ~ p",
        "h1 + p",
        "div:first-child",
      })
  void refusesPseudoClassesAndSiblingCombinators(String costly) {
    assertThatThrownBy(() -> RssFeedSettings.read(selector(costly)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("sourceSettings.mainContentSelector")
        .hasMessageContaining("Pseudoklassen");
  }

  /**
   * Attempts to hide a costly part from the check: the check judges the selector as Jsoup parses
   * it, so quoting, escaping, case and nesting change nothing.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "[title=it's]:has(~ p)",
        "[title=it's] ~ p",
        "p:HAS(~ q)",
        "p :has(q)",
        "p:not(:has(q))",
        "[a]:is(p, q ~ r)",
        "p, :has(q)",
        "[title=']']:has(q)",
        "p:containsData(x)",
        "p:matchText"
      })
  void refusesCostlyPartsHowEverTheyAreWritten(String disguised) {
    assertThatThrownBy(() -> RssFeedSettings.read(selector(disguised)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("sourceSettings.mainContentSelector");
  }

  /** An escaped colon is part of a name, never a pseudo-class: Jsoup reads a plain tag. */
  @ParameterizedTest
  @ValueSource(strings = {"div\\3a has", "p\\:has"})
  void anEscapedColonStaysAPlainName(String escaped) {
    RssFeedSettings settings = RssFeedSettings.read(selector(escaped));

    assertThat(settings.compiledSelector().toString()).doesNotContain(":has(");
  }

  @Test
  void theStoredSelectorIsTheOneTheCheckParsed() {
    RssFeedSettings settings = RssFeedSettings.read(selector("  main   .text  "));

    assertThat(settings.mainContentSelector()).isEqualTo("main   .text");
    assertThat(RssFeedSettings.read(settings.toData())).isEqualTo(settings);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "#inhalt",
        ".artikel-text",
        "main .text",
        "div.inhalt > article p",
        "[role=main]",
        "div[itemprop=articleBody]",
        "a[title=\"a ~ b: c\"]",
        "main, article, [role=main]",
        "#a, #b, #c, #d, #e"
      })
  void acceptsTypeIdClassAndAttributeSelectorsJoinedBySpaceChildOrComma(String plain) {
    assertThat(RssFeedSettings.read(selector(plain)).mainContentSelector()).isEqualTo(plain);
  }

  @Test
  void boundsTheCombinatorsPerGroupAndTheNumberOfGroups() {
    assertThat(RssFeedSettings.read(selector("main > div .a .b")).mainContentSelector())
        .isNotNull();
    assertThatThrownBy(() -> RssFeedSettings.read(selector("main > div .a .b .c")))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining(String.valueOf(RssFeedSettings.MAX_COMBINATORS));
    assertThatThrownBy(() -> RssFeedSettings.read(selector("#a, #b, #c, #d, #e, #f")))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining(String.valueOf(RssFeedSettings.MAX_GROUPS));
  }

  @Test
  void refusesAnUnknownProfileAKindMismatchAndAnUnknownField() {
    assertThatThrownBy(
            () -> RssFeedSettings.read(ConnectorData.of(Map.of("attachmentProfile", "WORDPRESS"))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("GENERIC")
        .hasMessageContaining("GSB");
    assertThatThrownBy(
            () -> RssFeedSettings.read(ConnectorData.of(Map.of("mainContentSelector", 42))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("sourceSettings.mainContentSelector");
    assertThatThrownBy(() -> RssFeedSettings.read(ConnectorData.of(Map.of("selector", "main"))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("ist nicht vorgesehen");
  }

  @Test
  void theFingerprintFollowsTheEffectiveSettings() {
    String gsb =
        RssFeedSettings.read(ConnectorData.of(Map.of("attachmentProfile", "GSB"))).fingerprint();
    String gsbAgain =
        RssFeedSettings.read(ConnectorData.of(Map.of("attachmentProfile", "GSB"))).fingerprint();
    String selectorOnly = RssFeedSettings.read(selector("#inhalt")).fingerprint();

    assertThat(gsb).isNotNull().isEqualTo(gsbAgain).isNotEqualTo(selectorOnly);
    assertThat(selectorOnly).isNotNull();
  }

  private static ConnectorData selector(String value) {
    return ConnectorData.of(Map.of("mainContentSelector", value));
  }
}
