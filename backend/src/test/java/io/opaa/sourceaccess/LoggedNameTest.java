package io.opaa.sourceaccess;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;

class LoggedNameTest {

  private static final LoggedName WITHHELD = LoggedName.withheld("<private library 7>");

  @Test
  void openNamesShowAsTheyAre() {
    IOException thrown = new IOException("Akte-Geheim.pdf");

    assertThat(LoggedName.OPEN.of("/Akten/Akte-Geheim.pdf")).isEqualTo("/Akten/Akte-Geheim.pdf");
    assertThat(LoggedName.OPEN.of("/Akten/x", 3)).isEqualTo("/Akten/x");
    assertThat(LoggedName.OPEN.of(thrown)).isSameAs(thrown);
  }

  @Test
  void withheldNamesShowTheReferenceOnly() {
    assertThat(WITHHELD.of("/Akten/Akte-Geheim.pdf")).isEqualTo("<private library 7>");
    assertThat(WITHHELD.of("/Akten/Akte-Geheim.pdf", "d-1")).isEqualTo("<private library 7>/d-1");
    assertThat(WITHHELD.of((Object) null)).isNull();
  }

  /** The stand-in keeps type and stack of every cause, never a message. */
  @Test
  void aWithheldThrowableCarriesNoMessage() {
    IOException cause = new IOException("die Datei „Akte-Geheim.pdf“");
    UncheckedIOException thrown = new UncheckedIOException("Akte-Geheim.pdf", cause);

    Throwable logged = WITHHELD.of(thrown);

    assertThat(logged.getMessage()).contains("UncheckedIOException").doesNotContain("Geheim");
    assertThat(logged.getStackTrace()).isEqualTo(thrown.getStackTrace());
    assertThat(logged.getCause().getMessage()).contains("IOException").doesNotContain("Geheim");
  }
}
