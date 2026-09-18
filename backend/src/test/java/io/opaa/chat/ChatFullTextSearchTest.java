package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.chat.ChatFullTextSearch.MatchText;
import io.opaa.chat.ChatSearchMatch.Highlight;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The pure parts of the chat search: the marker-to-offset conversion and the tsquery shape. */
class ChatFullTextSearchTest {

  private static final char START = (char) 0xE000;
  private static final char END = (char) 0xE001;
  private static final char LESS_THAN = (char) 0xE002;
  private static final char GREATER_THAN = (char) 0xE003;
  private static final String DOCUMENT_EMOJI = new String(Character.toChars(0x1F4C4));

  @Test
  void markersBecomeOffsetsIntoThePlainText() {
    MatchText text =
        ChatFullTextSearch.toMatchText("Der " + START + "Widerspruch" + END + " läuft");

    assertThat(text.excerpt()).isEqualTo("Der Widerspruch läuft");
    assertThat(text.highlights()).containsExactly(new Highlight(4, 15));
    assertThat(text.excerpt().substring(4, 15)).isEqualTo("Widerspruch");
  }

  /** Markup is returned as literal characters, and offsets count them like any other. */
  @Test
  void anglePlaceholdersBecomeLiteralBracketsAndCountInTheOffsets() {
    MatchText text =
        ChatFullTextSearch.toMatchText(
            LESS_THAN
                + "b"
                + GREATER_THAN
                + START
                + "Frist"
                + END
                + LESS_THAN
                + "/b"
                + GREATER_THAN
                + " "
                + LESS_THAN
                + "script"
                + GREATER_THAN);

    assertThat(text.excerpt()).isEqualTo("<b>Frist</b> <script>");
    assertThat(text.highlights()).containsExactly(new Highlight(3, 8));
  }

  @Test
  void offsetsAreUtf16CodeUnitsSoASurrogatePairCountsTwice() {
    MatchText text = ChatFullTextSearch.toMatchText(DOCUMENT_EMOJI + " " + START + "Akte" + END);

    assertThat(text.highlights()).containsExactly(new Highlight(3, 7));
  }

  @Test
  void aLongExcerptIsCutAndARangeReachingPastTheCutIsClipped() {
    String before = "a".repeat(ChatFullTextSearch.MAX_EXCERPT_LENGTH - 3);
    MatchText text =
        ChatFullTextSearch.toMatchText(
            before + START + "Widerspruch" + END + " " + START + "Frist" + END);

    int cut = ChatFullTextSearch.MAX_EXCERPT_LENGTH;
    assertThat(text.excerpt()).hasSize(cut);
    assertThat(text.highlights()).containsExactly(new Highlight(cut - 3, cut));
  }

  @Test
  void theCutNeverSplitsASurrogatePair() {
    String before = "a".repeat(ChatFullTextSearch.MAX_EXCERPT_LENGTH - 1);
    MatchText text = ChatFullTextSearch.toMatchText(before + DOCUMENT_EMOJI + " Rest");

    assertThat(text.excerpt()).isEqualTo(before);
  }

  @Test
  void anEmptyRangeIsDropped() {
    MatchText text = ChatFullTextSearch.toMatchText("x" + START + END + "y");

    assertThat(text.excerpt()).isEqualTo("xy");
    assertThat(text.highlights()).isEmpty();
  }

  @Test
  void aTermEndingInAWordAlsoMatchesThatWordAsAPrefix() {
    List<String> parameters = new ArrayList<>();

    String expression = ChatFullTextSearch.tsQueryExpression("Frist Widersp", parameters);

    assertThat(expression).contains("plainto_tsquery").contains(":*");
    assertThat(parameters).containsExactly("Frist Widersp", "Frist ", "Widersp");
  }

  /** Nothing of the term but letters and digits reaches to_tsquery. */
  @Test
  void onlyLettersAndDigitsBecomeThePrefixWord() {
    List<String> parameters = new ArrayList<>();

    ChatFullTextSearch.tsQueryExpression("a|b & c:*!(Az12", parameters);

    assertThat(parameters.getLast()).isEqualTo("Az12");
  }

  @Test
  void aTermEndingInAPunctuationMarkHasNoPrefixPart() {
    List<String> parameters = new ArrayList<>();

    String expression = ChatFullTextSearch.tsQueryExpression("Widerspruch.", parameters);

    assertThat(expression).doesNotContain(":*");
    assertThat(parameters).containsExactly("Widerspruch.");
  }

  @Test
  void aSingleTrailingCharacterIsNoPrefix() {
    List<String> parameters = new ArrayList<>();

    String expression = ChatFullTextSearch.tsQueryExpression("Anlage 3", parameters);

    assertThat(expression).doesNotContain(":*");
    assertThat(parameters).containsExactly("Anlage 3");
  }
}
