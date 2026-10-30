package io.opaa.format;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.SplittableRandom;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The value pattern of the sender field: an address, read in linear time. */
class FormatMetadataFieldTest {

  /** The plain form of the sender pattern, which backtracks quadratically on a run of dots. */
  private static final Pattern PLAIN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

  private static final char[] ALPHABET = {'a', 'b', '@', '.', '.', ' ', '\t'};

  @Test
  void theSenderPatternAcceptsExactlyWhatThePlainFormAccepts() {
    Pattern sender = FormatMetadataField.MAIL_SENDER.valuePattern();
    SplittableRandom random = new SplittableRandom(3379);
    for (int i = 0; i < 200_000; i++) {
      char[] chars = new char[random.nextInt(0, 14)];
      for (int j = 0; j < chars.length; j++) {
        chars[j] = ALPHABET[random.nextInt(ALPHABET.length)];
      }
      String value = new String(chars);

      assertThat(sender.matcher(value).matches())
          .as("\"%s\"", value)
          .isEqualTo(PLAIN.matcher(value).matches());
    }
  }

  @Test
  void anAddressIsAcceptedAndALookalikeIsNot() {
    assertThat(FormatMetadataField.MAIL_SENDER.normalize("Anna.Berg@Example.org"))
        .contains("anna.berg@example.org");
    assertThat(FormatMetadataField.MAIL_SENDER.normalize("anna@example.")).isEmpty();
    assertThat(FormatMetadataField.MAIL_SENDER.normalize("anna@.example")).isEmpty();
    assertThat(FormatMetadataField.MAIL_SENDER.normalize("anna@example")).isEmpty();
  }
}
