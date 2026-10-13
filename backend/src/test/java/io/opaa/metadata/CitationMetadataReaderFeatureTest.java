package io.opaa.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import io.opaa.common.Feature;
import io.opaa.common.TestFeatureFlags;
import io.opaa.knowledge.Document;
import io.opaa.test.SourceTypes;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * With the metadata feature off (ADR-0044) a Beleg carries no format or library field: the reader
 * answers empty without reading a stored value, so the values stay but are not shown.
 */
@ExtendWith(MockitoExtension.class)
class CitationMetadataReaderFeatureTest {

  @Mock private LibraryMetadataFieldRepository fieldRepository;
  @Mock private LibraryMetadataFieldValueRepository valueRepository;
  @Mock private DocumentMetadataValueRepository documentValueRepository;

  @Test
  void switchedOffNoDocumentHasACitationField() {
    Document document =
        new Document("Mail.eml", "/mail/Mail.eml", "message/rfc822", 10L, SourceTypes.FILESYSTEM);
    document.setLibraryId(UUID.randomUUID());

    CitationMetadataReader reader =
        new CitationMetadataReader(
            fieldRepository,
            valueRepository,
            documentValueRepository,
            TestFeatureFlags.without(Feature.METADATA));

    assertThat(reader.forDocuments(List.of(document))).isEmpty();
    verifyNoInteractions(fieldRepository, valueRepository, documentValueRepository);
  }
}
