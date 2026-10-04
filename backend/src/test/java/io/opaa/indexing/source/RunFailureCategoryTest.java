package io.opaa.indexing.source;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Every block a run can end with has a category of its own, and every category fits the column. */
class RunFailureCategoryTest {

  @ParameterizedTest
  @EnumSource(SourceBlock.Reason.class)
  void everyBlockReasonIsACategoryOfTheSameName(SourceBlock.Reason reason) {
    assertThat(RunFailureCategory.of(reason).name()).isEqualTo(reason.name());
  }

  @Test
  void everyCategoryFitsTheColumnAndItsCheck() {
    for (RunFailureCategory category : RunFailureCategory.values()) {
      assertThat(category.name()).hasSizeLessThanOrEqualTo(40).matches("[A-Z][A-Z_]*");
    }
  }
}
