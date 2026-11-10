package io.opaa.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MimeClassTest {

  @ParameterizedTest
  @CsvSource({
    "application/pdf, pdf",
    "'text/plain; charset=UTF-8', text",
    "text/html, text",
    "application/json, text",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document, office",
    "application/vnd.oasis.opendocument.spreadsheet, office",
    "application/msword, office",
    "message/rfc822, mail",
    "application/vnd.ms-outlook, mail",
    "image/png, image",
    "audio/mpeg, audio",
    "video/mp4, video",
    "application/zip, archive",
    "application/x-whatever, other",
    "'', other"
  })
  void aMediaTypeFallsIntoOneFixedClass(String contentType, String expected) {
    assertThat(MimeClass.of(contentType).tagValue()).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource(
      value = {"NULL"},
      nullValues = "NULL")
  void anUnknownMediaTypeIsOther(String contentType) {
    assertThat(MimeClass.of(contentType)).isEqualTo(MimeClass.OTHER);
  }
}
