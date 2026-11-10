package io.opaa.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.test.JavaSources;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * No main source gives a meter one of the {@link ForbiddenMetricTags}, also where the meter is
 * registered only on a rare path the running application of {@link
 * MeterTagCardinalityIntegrationTest} never takes (regression guard for ADR-0071, decision 2).
 * Reads every literal key of {@code .tag(k, v)}, {@code Tag.of(k, v)}, {@code KeyValue.of(k, v)},
 * {@code .and(k, v, …)} and {@code Tags.of(k, v, …)}: the arguments at even positions.
 */
class MetricTagSourceTest {

  private static final Pattern TAG_CALL =
      Pattern.compile("(?:\\.tag|\\.and|Tag\\.of|Tags\\.of|KeyValue\\.of|KeyValues\\.of)\\(");

  private static final Pattern STRING_LITERAL = Pattern.compile("\\s*\"([^\"]*)\"\\s*");

  @Test
  void noMainSourceTagsAMeterWithAForbiddenKey() throws IOException {
    List<String> offending = new ArrayList<>();
    for (Path source : JavaSources.mainSources()) {
      for (String key : literalKeys(JavaSources.readFile(source))) {
        if (ForbiddenMetricTags.isForbidden(key)) {
          offending.add(source.getFileName() + ": " + key);
        }
      }
    }

    assertThat(offending).isEmpty();
  }

  @Test
  void theRuleSeesTheKeysItForbids() {
    assertThat(ForbiddenMetricTags.isForbidden("library")).isTrue();
    assertThat(ForbiddenMetricTags.isForbidden("library_id")).isTrue();
    assertThat(ForbiddenMetricTags.isForbidden("User.Id")).isTrue();
    assertThat(ForbiddenMetricTags.isForbidden("connection_type")).isFalse();
    assertThat(ForbiddenMetricTags.isForbidden("job")).isTrue();
    assertThat(ForbiddenMetricTags.isForbidden("Namespace")).isTrue();
    assertThat(ForbiddenMetricTags.isForbidden("scheduled_job")).isFalse();
    assertThat(ForbiddenMetricTags.isForbidden("gen_ai.token.type")).isFalse();
    assertThat(ForbiddenMetricTags.isIdentifyingValue("3f2c1b9e-0a4d-4c5e-9f8a-1b2c3d4e5f60"))
        .isTrue();
    assertThat(ForbiddenMetricTags.isIdentifyingValue("anna.schmidt@stadt.example")).isTrue();
    assertThat(ForbiddenMetricTags.isIdentifyingValue("text-embedding-3-small")).isFalse();
    assertThat(ForbiddenMetricTags.isIdentifyingValue("opaa-backend-7d9f8c6b5-x2k4p")).isFalse();
  }

  @Test
  void theParserFindsEveryKeyOfAMultiPairCall() {
    String source =
        """
        Tags.of("class", kind, "library", name(id, "x"));
        timer.tag("outcome", "ok").and("space", spaceId);
        Tag.of(key, "document");
        """;

    assertThat(literalKeys(source)).containsExactly("class", "library", "outcome", "space");
  }

  /** The string literals at even argument positions of every tag call in {@code source}. */
  static List<String> literalKeys(String source) {
    List<String> keys = new ArrayList<>();
    Matcher call = TAG_CALL.matcher(source);
    while (call.find()) {
      List<String> arguments = arguments(source, call.end());
      for (int i = 0; i < arguments.size(); i += 2) {
        Matcher literal = STRING_LITERAL.matcher(arguments.get(i));
        if (literal.matches()) {
          keys.add(literal.group(1));
        }
      }
    }
    return keys;
  }

  /**
   * The top-level arguments of the call whose opening parenthesis ends just before {@code from}.
   */
  private static List<String> arguments(String source, int from) {
    List<String> arguments = new ArrayList<>();
    int depth = 0;
    boolean inString = false;
    int start = from;
    for (int i = from; i < source.length(); i++) {
      char c = source.charAt(i);
      if (inString) {
        if (c == '\\') {
          i++;
        } else if (c == '"') {
          inString = false;
        }
      } else if (c == '"') {
        inString = true;
      } else if (c == '(' || c == '[' || c == '{') {
        depth++;
      } else if (c == ')' || c == ']' || c == '}') {
        if (depth == 0) {
          arguments.add(source.substring(start, i));
          return arguments;
        }
        depth--;
      } else if (c == ',' && depth == 0) {
        arguments.add(source.substring(start, i));
        start = i + 1;
      }
    }
    return arguments;
  }
}
