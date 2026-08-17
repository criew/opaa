package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guard test against regression of the statement form fixed in the #454 re-review (finding 2):
 * {@link AuditRetentionSettings} must stay read-only end to end, so a future {@code
 * repository.save(entity)} call can never reintroduce the dirty-checked, all-columns {@code UPDATE}
 * that fails against the real, restricted database grant (migration 023 - the application account
 * has {@code UPDATE} only on {@code retention_months}/{@code updated_at}, never {@code
 * last_cutoff}/{@code last_run_month}).
 *
 * <p>Plain reflection, not a database-backed test: the property being guarded ("every mapped column
 * is {@code insertable = false, updatable = false}, and there is no setter") is a static fact about
 * the class, independent of any database state - {@code Migration023AuditRetentionTest} already
 * covers the actual privilege boundary against a real, restricted role; this test is the cheap,
 * always-run companion that catches the moment someone reintroduces a setter or drops an {@code
 * insertable}/{@code updatable} flag, well before that change ever reaches a database.
 */
class AuditRetentionSettingsTest {

  @Test
  void everyColumnMappedFieldOtherThanTheIdIsInsertableAndUpdatableFalse() {
    List<Field> columnFields =
        Arrays.stream(AuditRetentionSettings.class.getDeclaredFields())
            .filter(field -> field.isAnnotationPresent(Column.class))
            .toList();

    assertThat(columnFields)
        .as(
            "expected the four non-id mapped fields (retentionMonths, lastCutoff, lastRunMonth,"
                + " updatedAt) - a change to the field set here means this assertion's field count"
                + " below must be revisited too")
        .hasSize(4);

    for (Field field : columnFields) {
      Column column = field.getAnnotation(Column.class);
      assertThat(column.insertable())
          .as("%s must stay insertable = false - see the class Javadoc", field.getName())
          .isFalse();
      assertThat(column.updatable())
          .as("%s must stay updatable = false - see the class Javadoc", field.getName())
          .isFalse();
    }
  }

  @Test
  void theIdFieldCarriesNoColumnAnnotationAndNeedsNoInsertableUpdatableGuard() {
    Field idField = fieldNamed("id");

    assertThat(idField.isAnnotationPresent(Id.class)).isTrue();
    assertThat(idField.isAnnotationPresent(Column.class)).isFalse();
  }

  @Test
  void theClassDeclaresNoSetterAtAll() {
    List<Method> setters =
        Arrays.stream(AuditRetentionSettings.class.getDeclaredMethods())
            .filter(method -> method.getName().startsWith("set"))
            .toList();

    assertThat(setters)
        .as(
            "AuditRetentionSettings must stay read-only end to end - reintroducing any setter"
                + " (e.g. setRetentionMonths) would make repository.save(entity) a live option"
                + " again for callers, and Hibernate's dirty-checked save writes every mapped"
                + " column regardless of which one a setter actually touched, not just the"
                + " ones the application account may write (migration 023)")
        .isEmpty();
  }

  private Field fieldNamed(String name) {
    try {
      return AuditRetentionSettings.class.getDeclaredField(name);
    } catch (NoSuchFieldException e) {
      throw new AssertionError("Expected field " + name + " on AuditRetentionSettings", e);
    }
  }
}
