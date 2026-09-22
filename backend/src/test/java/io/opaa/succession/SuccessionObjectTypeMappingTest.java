package io.opaa.succession;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.SuccessionObjectType;
import io.opaa.permission.AssetType;
import org.junit.jupiter.api.Test;

/**
 * The one place where an open {@link AssetType} meets the closed enum of object types. A type no
 * source answers for must resolve to nothing at all - a {@code valueOf} here would throw after a
 * transfer has already written everything, rolling back an operation that was correct (#1726).
 */
class SuccessionObjectTypeMappingTest {

  @Test
  void theTypesASourceAnswersForResolve() {
    assertThat(SuccessionService.objectTypeOf(AssetType.of("KNOWLEDGE_LIBRARY")))
        .contains(SuccessionObjectType.KNOWLEDGE_LIBRARY);
    assertThat(SuccessionService.objectTypeOf(AssetType.of("SPACE")))
        .contains(SuccessionObjectType.SPACE);
  }

  @Test
  void aFutureAssetTypeResolvesToNothingInsteadOfThrowing() {
    assertThat(SuccessionService.objectTypeOf(AssetType.of("PROMPT_LIBRARY"))).isEmpty();
  }
}
