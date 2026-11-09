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
 * MeterTagCardinalityIntegrationTest} never takes. Reads the literal key of every {@code .tag("…"},
 * {@code Tag.of("…"}, {@code Tags.of("…"} and {@code KeyValue.of("…"}.
 */
class MetricTagSourceTest {

  private static final Pattern TAG_KEY =
      Pattern.compile("(?:\\.tag|Tag\\.of|Tags\\.of|KeyValue\\.of)\\(\\s*\"([^\"]+)\"");

  @Test
  void noMainSourceTagsAMeterWithAForbiddenKey() throws IOException {
    List<String> offending = new ArrayList<>();
    for (Path source : JavaSources.mainSources()) {
      Matcher matcher = TAG_KEY.matcher(JavaSources.readFile(source));
      while (matcher.find()) {
        if (ForbiddenMetricTags.isForbidden(matcher.group(1))) {
          offending.add(source.getFileName() + ": " + matcher.group(1));
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
    assertThat(ForbiddenMetricTags.isForbidden("gen_ai.token.type")).isFalse();
  }
}
