package io.opaa.indexing.pipeline.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The thread-splitting heuristic (#1060, ingestion-pipelines.md Teil 3, Punkt 5: "Ein Thread ist
 * kein Dokument, sondern eine Folge von Dokumenten").
 */
class MailThreadSplitterTest {

  @Test
  void aMessageWithNoQuoteSeparatorStaysOneSegment() {
    String body = "Hallo Erika,\n\ndies ist eine Testnachricht.\n\nGruss\nMax";

    List<String> segments = MailThreadSplitter.split(body);

    assertThat(segments).containsExactly(body);
  }

  @Test
  void splitsOnGermanInlineAttribution() {
    String body =
        "Danke, das passt.\n\nAm 03.01.2024 um 10:15 schrieb Erika Musterfrau <erika@example.org>:\n"
            + "Bitte um Rueckmeldung bis Freitag.";

    List<String> segments = MailThreadSplitter.split(body);

    assertThat(segments).hasSize(2);
    assertThat(segments.get(0)).isEqualTo("Danke, das passt.");
    assertThat(segments.get(1))
        .startsWith("Am 03.01.2024 um 10:15 schrieb Erika Musterfrau <erika@example.org>:")
        .contains("Bitte um Rueckmeldung bis Freitag.");
  }

  @Test
  void splitsOnEnglishInlineAttribution() {
    String body =
        "Sounds good.\n\nOn Wed, Jan 3, 2024 at 10:15 AM John Doe wrote:\nCan you confirm?";

    List<String> segments = MailThreadSplitter.split(body);

    assertThat(segments).hasSize(2);
    assertThat(segments.get(1)).startsWith("On Wed, Jan 3, 2024 at 10:15 AM John Doe wrote:");
  }

  @Test
  void splitsOnOutlookSeparatorBlockInBothLanguages() {
    String german =
        "Einverstanden.\n\n-----Ursprüngliche Nachricht-----\nVon: Max\nBetreff: Termin\n\nGeht das"
            + " am Montag?";
    String english =
        "Agreed.\n\n-----Original Message-----\nFrom: Max\nSubject: Meeting\n\nHow about Monday?";

    assertThat(MailThreadSplitter.split(german)).hasSize(2);
    assertThat(MailThreadSplitter.split(german).get(1))
        .startsWith("-----Ursprüngliche Nachricht-----");
    assertThat(MailThreadSplitter.split(english)).hasSize(2);
    assertThat(MailThreadSplitter.split(english).get(1)).startsWith("-----Original Message-----");
  }

  @Test
  void aThreeMessageThreadProducesThreeSegments() {
    String body =
        "Passt.\n\n"
            + "Am 02.01.2024 um 09:00 schrieb A <a@example.org>:\n"
            + "Passt mir auch.\n\n"
            + "Am 01.01.2024 um 08:00 schrieb B <b@example.org>:\n"
            + "Erster Vorschlag.";

    List<String> segments = MailThreadSplitter.split(body);

    assertThat(segments).hasSize(3);
    assertThat(segments.get(0)).isEqualTo("Passt.");
    assertThat(segments.get(1)).contains("Passt mir auch.");
    assertThat(segments.get(2)).contains("Erster Vorschlag.");
  }

  @Test
  void aBlankBodyYieldsNoUsableSegmentText() {
    assertThat(MailThreadSplitter.split("")).containsExactly("");
    assertThat(MailThreadSplitter.split(null)).containsExactly("");
  }

  @Test
  void aMidSentenceMentionOfWroteDoesNotTriggerASplit() {
    // A false negative (unrecognized quoting stays one chunk) is preferred over a false positive
    // (ordinary text mis-cut) - see MailThreadSplitter's own Javadoc.
    String body = "Ich habe die E-Mail gestern geschrieben, aber niemand wrote mir zurueck.";

    assertThat(MailThreadSplitter.split(body)).containsExactly(body);
  }
}
