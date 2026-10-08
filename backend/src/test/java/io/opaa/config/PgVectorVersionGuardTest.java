package io.opaa.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PgVectorVersionGuardTest {

  @Test
  void refusesAVersionWithoutIterativeIndexScans() {
    assertThatThrownBy(() -> guardFor(List.of("0.7.4")).run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("0.7.4")
        .hasMessageContaining("0.8.0");
  }

  @Test
  void acceptsTheMinimumAndEveryLaterVersion() {
    assertThatCode(() -> guardFor(List.of("0.8.0")).run(null)).doesNotThrowAnyException();
    assertThatCode(() -> guardFor(List.of("0.8.7")).run(null)).doesNotThrowAnyException();
    assertThat(PgVectorVersionGuard.isAtLeastMinimum("0.10.0")).isTrue();
    assertThat(PgVectorVersionGuard.isAtLeastMinimum("1.0.0")).isTrue();
  }

  @Test
  void leavesAMissingExtensionToTheSchemaInitialization() {
    assertThatCode(() -> guardFor(List.of()).run(null)).doesNotThrowAnyException();
  }

  private static PgVectorVersionGuard guardFor(List<String> versions) {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.queryForList(anyString(), eq(String.class))).thenReturn(versions);
    return new PgVectorVersionGuard(jdbcTemplate);
  }
}
