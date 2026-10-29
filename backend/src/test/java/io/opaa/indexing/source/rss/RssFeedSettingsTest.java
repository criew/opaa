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

  @Test
  void allowsOneHasButNotANestedOrSecondOne() {
    assertThat(RssFeedSettings.read(selector("div:has(> h1)")).mainContentSelector())
        .isEqualTo("div:has(> h1)");
    assertThatThrownBy(() -> RssFeedSettings.read(selector("div:has(p:has(a))")))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining(":has");
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
    String gsb = RssFeedSettings.read(ConnectorData.of(Map.of("attachmentProfile", "GSB")))
        .fingerprint();
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
