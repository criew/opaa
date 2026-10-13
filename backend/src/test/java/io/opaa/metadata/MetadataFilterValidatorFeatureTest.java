package io.opaa.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.common.Feature;
import io.opaa.common.FeatureFlags;
import io.opaa.common.TestFeatureFlags;
import io.opaa.common.ValidationException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The one gate every metadata filter passes - chat, search API and MCP alike (ADR-0044): with the
 * feature off, no filter narrows anything, and a filter a chat already stores is left untouched
 * rather than overwritten.
 */
@ExtendWith(MockitoExtension.class)
class MetadataFilterValidatorFeatureTest {

  private static final MetadataFilter UNKNOWN_TYPE =
      MetadataFilter.ofDocumentTypes(List.of("GIBT_ES_NICHT"));

  @Mock private DocumentTypeVocabularyRepository vocabularyRepository;
  @Mock private LibraryMetadataFieldRepository fieldRepository;
  @Mock private LibraryMetadataFieldValueRepository valueRepository;

  @Test
  void switchedOffEveryFilterBecomesNoFilterWithoutReadingTheSchema() {
    MetadataFilter effective =
        validator(TestFeatureFlags.without(Feature.METADATA))
            .validate(UNKNOWN_TYPE, Set.of(UUID.randomUUID()));

    assertThat(effective).isEqualTo(MetadataFilter.NONE);
    verifyNoInteractions(vocabularyRepository, fieldRepository, valueRepository);
  }

  @Test
  void switchedOffAChatKeepsTheFilterItStores() {
    assertThat(
            validator(TestFeatureFlags.without(Feature.METADATA))
                .validateToStore(UNKNOWN_TYPE, Set.of()))
        .isNull();
    verifyNoInteractions(vocabularyRepository, fieldRepository, valueRepository);
  }

  @Test
  void switchedOnAFilterIsCheckedAsBefore() {
    when(vocabularyRepository.snapshot()).thenReturn(TestVocabularies.delivered());
    MetadataFilterValidator validator = validator(TestFeatureFlags.allOn());

    assertThatThrownBy(() -> validator.validate(UNKNOWN_TYPE, Set.of()))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> validator.validateToStore(UNKNOWN_TYPE, Set.of()))
        .isInstanceOf(ValidationException.class);
  }

  private MetadataFilterValidator validator(FeatureFlags flags) {
    return new MetadataFilterValidator(
        vocabularyRepository, fieldRepository, valueRepository, flags);
  }
}
