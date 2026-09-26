package io.opaa.indexing.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The opaque settings object the core hands through: a deep, immutable copy with a JSON form. */
class ConnectorDataTest {

  @Test
  void aCopyIsDeepAndImmutable() {
    List<Object> spaces = new ArrayList<>(List.of(Map.of("key", "ENG")));
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("spaces", spaces);
    source.put("days", null);

    ConnectorData data = ConnectorData.of(source);
    spaces.add(Map.of("key", "HR"));

    assertThat((List<?>) data.get("spaces")).hasSize(1);
    assertThat(data.has("days")).isTrue();
    assertThatThrownBy(() -> data.asMap().put("x", 1))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> ((List<Object>) data.get("spaces")).add("x"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void theJsonFormReadsBackIntoAnEqualObject() {
    ConnectorData data =
        ConnectorData.of(Map.of("edition", "CLOUD", "spaces", List.of(Map.of("key", "ENG"))));

    assertThat(ConnectorData.fromJson(data.toJson())).isEqualTo(data);
    assertThat(ConnectorData.fromJson(null)).isNull();
    assertThat(ConnectorData.fromJson(" ")).isNull();
  }

  @Test
  void aStoredValueThatIsNoObjectIsRefused() {
    assertThatThrownBy(() -> ConnectorData.fromJson("[1, 2]"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ConnectorData.fromJson("{kaputt"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
