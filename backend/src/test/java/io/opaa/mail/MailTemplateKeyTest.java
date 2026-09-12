package io.opaa.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.samskivert.mustache.Mustache;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * #1536, ADR-0033 Entscheidung 10: the twelve delivered templates are internally consistent - each
 * declares exactly the placeholders its own text references, renders with its sample values under
 * the same strict compiler {@code MailTemplateService} uses, and carries a sample value for every
 * placeholder.
 *
 * <p>This is the test that makes the registry safe to extend: a thirteenth key with a typo in a
 * placeholder, a missing sample value or an undeclared variable fails here rather than at the
 * moment somebody's invitation is sent.
 */
class MailTemplateKeyTest {

  private static final List<String> EXPECTED_KEYS =
      List.of(
          "LOCAL_ACCOUNT_INVITATION",
          "PASSWORD_RESET",
          "ADMIN_PASSWORD_RESET",
          "REGISTRATION_VERIFICATION",
          "ACCOUNT_LOCKED",
          "ACCOUNT_UNLOCKED",
          "ACCOUNT_EXPIRING",
          "ACCOUNT_HANDOVER_REQUESTED",
          "ACCOUNT_HANDED_OVER",
          "BOOTSTRAP_ACCOUNT_USED",
          "ADMIN_REVIEW_REMINDER",
          "TEST_MAIL");

  private final Mustache.Compiler plain = Mustache.compiler().escapeHTML(false);
  private final Mustache.Compiler html = Mustache.compiler().escapeHTML(true);

  @Test
  void theRegistryCarriesExactlyTheTwelveKeysTheAdrNames() {
    assertThat(Arrays.stream(MailTemplateKey.values()).map(MailTemplateKey::key))
        .containsExactlyElementsOf(EXPECTED_KEYS);
  }

  @ParameterizedTest
  @EnumSource(MailTemplateKey.class)
  void everyDeclaredPlaceholderHasASampleValue(MailTemplateKey key) {
    assertThat(key.sampleValues()).containsOnlyKeys(key.placeholders().toArray(String[]::new));
    assertThat(key.sampleValues().values()).noneMatch(String::isBlank);
  }

  @ParameterizedTest
  @EnumSource(MailTemplateKey.class)
  void everyKeyDeclaresProductNameAndDisplayName(MailTemplateKey key) {
    assertThat(key.placeholders()).contains("productName", "displayName");
  }

  @ParameterizedTest
  @EnumSource(MailTemplateKey.class)
  void theDeliveredTextReferencesOnlyDeclaredPlaceholders(MailTemplateKey key) {
    assertThatCode(
            () -> {
              MailPlaceholderValidator.requireDeclaredPlaceholders(
                  key, "defaultSubject", key.defaultSubject());
              MailPlaceholderValidator.requireDeclaredPlaceholders(
                  key, "defaultBodyPlain", key.defaultBodyPlain());
              MailPlaceholderValidator.requireDeclaredPlaceholders(
                  key, "defaultBodyHtmlContent", key.defaultBodyHtmlContent());
              MailPlaceholderValidator.requireDeclaredPlaceholders(
                  key, "preheader", key.preheader());
              MailPlaceholderValidator.requireSupportedTags("defaultSubject", key.defaultSubject());
              MailPlaceholderValidator.requireSupportedTags(
                  "defaultBodyPlain", key.defaultBodyPlain());
              MailPlaceholderValidator.requireSupportedTags(
                  "defaultBodyHtmlContent", key.defaultBodyHtmlContent());
              MailPlaceholderValidator.requireSupportedTags("preheader", key.preheader());
            })
        .doesNotThrowAnyException();
  }

  /**
   * Renders with the strict compilers rather than asserting on the text: a
   * declared-but-never-passed placeholder raises here, which is precisely the failure mode strict
   * rendering exists to surface early.
   */
  @ParameterizedTest
  @EnumSource(MailTemplateKey.class)
  void everyKeyRendersWithItsOwnSampleValues(MailTemplateKey key) {
    Map<String, Object> variables = new HashMap<>(key.sampleValues());

    String subject = plain.compile(key.defaultSubject()).execute(variables);
    String bodyPlain = plain.compile(key.defaultBodyPlain()).execute(variables);
    String bodyHtml = html.compile(key.defaultBodyHtmlContent()).execute(variables);
    String preheader = plain.compile(key.preheader()).execute(variables);

    assertThat(subject).isNotBlank().doesNotContain("{{");
    assertThat(bodyPlain).isNotBlank().doesNotContain("{{");
    assertThat(bodyHtml).isNotBlank().doesNotContain("{{");
    assertThat(preheader).isNotBlank().doesNotContain("{{");
  }

  @ParameterizedTest
  @EnumSource(MailTemplateKey.class)
  void aKeyCarriesAButtonLabelExactlyWhenItCarriesALink(MailTemplateKey key) {
    boolean hasLink = key.placeholders().contains("actionUrl");
    assertThat(key.ctaLabel() != null).isEqualTo(hasLink);
  }

  @Test
  void aKeyIsResolvableByItsStorageKeyAndAnUnknownOneIsNot() {
    assertThat(MailTemplateKey.fromKey("PASSWORD_RESET")).contains(MailTemplateKey.PASSWORD_RESET);
    assertThat(MailTemplateKey.fromKey("password_reset")).isEmpty();
    assertThat(MailTemplateKey.fromKey(null)).isEmpty();
  }
}
