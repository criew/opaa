package io.opaa.linktarget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.common.ValidationException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class LinkTargetOriginsTest {

  @ParameterizedTest
  @CsvSource({
    "https://intranet.stadt.example, https://intranet.stadt.example",
    "HTTPS://Intranet.Stadt.Example/, https://intranet.stadt.example",
    "https://intranet.stadt.example:443, https://intranet.stadt.example",
    "http://intranet.stadt.example:80, http://intranet.stadt.example",
    "https://intranet.stadt.example:8443, https://intranet.stadt.example:8443",
    "'  https://www.service.bund.de  ', https://www.service.bund.de",
    "https://bücher.example, https://xn--bcher-kva.example"
  })
  void normalizesSchemeHostAndDefaultPort(String input, String expected) {
    assertThat(LinkTargetOrigins.normalize(input)).isEqualTo(expected);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "intranet.stadt.example",
        "ftp://intranet.stadt.example",
        "javascript:alert(1)",
        "mailto:poststelle@stadt.example",
        "https://intranet.stadt.example/formulare",
        "https://intranet.stadt.example?x=1",
        "https://intranet.stadt.example#anker",
        "https://*.stadt.example",
        "https://nutzer:geheim@intranet.stadt.example",
        "https://",
        "https://intra net.example"
      })
  void refusesEverythingButAnOriginWithAGermanMessage(String input) {
    assertThatThrownBy(() -> LinkTargetOrigins.normalize(input))
        .isInstanceOf(ValidationException.class)
        .hasMessageStartingWith("Ungültiges Linkziel");
  }

  @Test
  void theListIsNormalizedDeduplicatedAndKeepsItsOrder() {
    assertThat(
            LinkTargetOrigins.normalizeAll(
                Arrays.asList(
                    "https://b.example", "https://A.example/", " ", null, "https://b.example:443")))
        .containsExactly("https://b.example", "https://a.example");
    assertThat(LinkTargetOrigins.normalizeAll(null)).isEmpty();
  }

  @Test
  void moreThanTheUpperBoundIsRefused() {
    List<String> origins = new ArrayList<>();
    for (int i = 0; i <= LinkTargetOrigins.MAX_ORIGINS; i++) {
      origins.add("https://ziel" + i + ".example");
    }

    assertThatThrownBy(() -> LinkTargetOrigins.normalizeAll(origins))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("höchstens " + LinkTargetOrigins.MAX_ORIGINS);
  }
}
