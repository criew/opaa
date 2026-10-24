package io.opaa.indexing.document;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.format.shared.IngestTextSanitizer.Finding;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.SourceDocumentContext;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Name, declared title and hierarchy path reach index and prompt like the text, so they are cleaned
 * the same way; the identity of the document stays as the source names it.
 */
class DocumentIngestCleanNamesTest {

  private static final String TAG_A = Character.toString(0xE0041);
  private static final String ZERO_WIDTH_SPACE = Character.toString(0x200B);

  @Test
  void nameTitleAndHierarchyPathAreCleanedAndTheirFindingsCounted() {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(UUID.randomUUID(), "Feed", null, UUID.randomUUID());
    String url = "https://feed.example/eintrag?id=" + ZERO_WIDTH_SPACE;
    DocumentIngest ingest =
        DocumentIngest.text(library, url, "<p>Text</p>")
            .sourceType(io.opaa.test.SourceTypes.UPLOAD)
            .title("Neue Frist" + TAG_A + TAG_A)
            .context(new SourceDocumentContext("ENG", "Handbuch" + ZERO_WIDTH_SPACE))
            .build();
    Map<Finding, Integer> findings = new EnumMap<>(Finding.class);

    DocumentIngest cleaned = ingest.withCleanNames(findings);

    assertThat(cleaned.title()).isEqualTo("Neue Frist");
    assertThat(cleaned.fileName()).isEqualTo("Neue Frist");
    assertThat(cleaned.context().hierarchyPath()).isEqualTo("Handbuch");
    assertThat(cleaned.context().containerKey()).isEqualTo("ENG");
    assertThat(cleaned.filePath()).isEqualTo(url);
    assertThat(findings).containsEntry(Finding.INVISIBLE_CHARACTERS, 5);
  }
}
