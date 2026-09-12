package io.opaa.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * #1536: the branded frame carries the operator's product name and accent colour, renders the link
 * both as a button and as readable text, and escapes everything it places into the frame itself -
 * the content fragment arrives already escaped, these values do not.
 */
class EmailLayoutBuilderTest {

  private final EmailLayoutBuilder builder = new EmailLayoutBuilder();

  @Test
  void carriesTheOperatorsProductNameAndAccentColour() {
    String html =
        builder.wrap(
            "Landesamt-Assistent",
            "#7A1FA2",
            "Vorschauzeile",
            "<p>Inhalt</p>",
            "https://opaa.example.org/aktion",
            "Passwort festlegen");

    assertThat(html).contains("Landesamt-Assistent").contains("#7A1FA2").contains("<p>Inhalt</p>");
    assertThat(html).contains("Vorschauzeile").contains(EmailLayoutBuilder.FOOTER_LINE);
  }

  @Test
  void showsTheLinkAsAButtonAndAsPlainTextSoAStrippedButtonIsNotTheEndOfTheRoad() {
    String html =
        builder.wrap(
            "OPAA", "#1292EE", "x", "<p>y</p>", "https://opaa.example.org/a?token=T", "Los");

    assertThat(html).contains("Los");
    assertThat(html)
        .containsSubsequence(
            "https://opaa.example.org/a?token=T", "https://opaa.example.org/a?token=T");
    assertThat(html).contains("Falls die Schaltfläche nicht funktioniert");
  }

  @Test
  void rendersNoButtonWhenThereIsNoLink() {
    String html = builder.wrap("OPAA", "#1292EE", "x", "<p>y</p>", null, null);

    assertThat(html).doesNotContain("Falls die Schaltfläche nicht funktioniert");
  }

  @Test
  void escapesTheValuesItPlacesIntoTheFrameItself() {
    String html =
        builder.wrap(
            "<script>alert(1)</script>",
            "#1292EE",
            "\"quote\" & <b>",
            "<p>bereits geprüfter Inhalt</p>",
            null,
            null);

    assertThat(html).doesNotContain("<script>");
    assertThat(html).contains("&lt;script&gt;").contains("&quot;quote&quot; &amp; &lt;b&gt;");
    assertThat(html).contains("<p>bereits geprüfter Inhalt</p>");
  }

  @Test
  void declaresALightColourSchemeSoNoClientInvertsItIntoUnreadability() {
    String html = builder.wrap("OPAA", "#1292EE", "x", "<p>y</p>", null, null);

    assertThat(html).contains("<!DOCTYPE html>").contains("color-scheme").contains("light only");
    assertThat(html).contains("lang=\"de\"");
  }
}
