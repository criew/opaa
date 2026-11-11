package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentRunStampTest {

  /**
   * A control character would break the XML of a result file, a direction control would reorder the
   * draft notice; neither reaches the stamp of a file or a copy.
   */
  @Test
  void namesAndModelReachTheStampWithoutControlFormatOrBreakCharacters() {
    AgentRunStamp stamp =
        new AgentRunStamp(
            3,
            List.of(new SearchedLibraryRef(UUID.randomUUID(), "Erl\u0001asse\u202E")),
            List.of(),
            "modell\u2028a");

    String text =
        stamp.documentText(
            "Prüf\u200Fhilfe",
            new AgentRunStamp.TemplateRef(UUID.randomUUID(), "Be\u0000scheid", 2));

    assertThat(text)
        .isEqualTo(
            "Entwurf, nicht geprüft · Agent „Prüf hilfe“, Fassung 3 · Vorlage „Be scheid“,"
                + " Fassung 2 · Bibliotheken: Erl asse · Modell modell a")
        .doesNotContainPattern("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]");
    assertThat(stamp.shortText("\u202E")).isEqualTo("Entwurf, nicht geprüft · Agent, Fassung 3");
  }
}
