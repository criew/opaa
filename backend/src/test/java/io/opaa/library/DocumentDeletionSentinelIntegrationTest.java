package io.opaa.library;

import static io.opaa.library.LibraryCreationBuilder.libraryCreation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.opaa.api.types.DocumentStatus;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.SourceType;
import io.opaa.llm.ActiveChatModelDescription;
import io.opaa.llm.ActiveChatModelResolver;
import io.opaa.metadata.DocumentMetadataCorrectionService;
import io.opaa.metadata.MetadataValueInput;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.permission.AssetGrantHistoryRepository;
import io.opaa.permission.GroupMembershipHistoryRepository;
import io.opaa.test.OpaaMockedChatModelIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import io.opaa.test.SentinelScan;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Deletion completeness of everything derived from a document: a unique marker goes into the file
 * name, the content, a metadata value set by hand and the values a model proposed - an accepted
 * keyword and a discarded Dokumentart - and after the document's deletion no column of any table of
 * the schema holds it any more ({@link SentinelScan}), except the columns of {@link #RETAINED}.
 */
@OpaaMockedChatModelIntegrationTest
class DocumentDeletionSentinelIntegrationTest {

  /**
   * Columns that keep the marker after the deletion by decision, with the reason. Nothing else may
   * hold it; a new hit is a deletion gap, never an entry for this list without a decision.
   */
  private static final Map<String, String> RETAINED =
      Map.of(
          "audit_log.before",
          "The Revisionsprotokoll is append-only and hash-chained (ADR-0059): its events about the"
              + " document name its file and a changed metadata value; its retention ends them,"
              + " not the document's deletion",
          "audit_log.after",
          "Same as audit_log.before");

  @Autowired private LibraryDocumentService documentService;
  @Autowired private KnowledgeLibraryService libraryService;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private DocumentMetadataCorrectionService metadataCorrection;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private OwnLibraryFixtures ownLibraryFixtures;
  @Autowired private AssetGrantHistoryRepository grantHistoryRepository;
  @Autowired private GroupMembershipHistoryRepository membershipHistoryRepository;
  @Autowired private ChatModel chatModel;
  @Autowired private ActiveChatModelResolver activeChatModelResolver;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;
  private User editor;
  private CurrentUser caller;
  private UUID libraryId;

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Sentinel")).getId();
    editor =
        new User("sentinel-" + organizationId, "issuer", "sentinel@example.com", "Sentinel Editor");
    editor.setOrganizationId(organizationId);
    editor = userRepository.save(editor);
    caller =
        CurrentUser.of(
            editor.getId(),
            organizationId,
            editor.getSystemRole(),
            editor.getDisplayName(),
            editor.getEmail());
    libraryId =
        libraryService
            .createLibrary(libraryCreation("Sentinel", SourceType.UPLOAD).build(), caller)
            .library()
            .getId();
    KnowledgeLibrary library = libraryRepository.findById(libraryId).orElseThrow();
    library.setModelExtractionSwitches(true, true);
    libraryRepository.save(library);
  }

  @AfterEach
  void tearDown() {
    List<UUID> ownLibraryIds =
        jdbcTemplate.queryForList(
            "SELECT id FROM knowledge_libraries WHERE organization_id = ?",
            UUID.class,
            organizationId);
    jdbcTemplate.update("DELETE FROM indexing_jobs WHERE organization_id = ?", organizationId);
    ownLibraryFixtures.removeLibraries(ownLibraryIds.toArray(new UUID[0]));
    grantHistoryRepository.deleteBySubjectUserIdIn(List.of(editor.getId()));
    membershipHistoryRepository.deleteByUserIdIn(List.of(editor.getId()));
    jdbcTemplate.update(
        "DELETE FROM asset_ownership_history WHERE organization_id = ?", organizationId);
    userRepository.deleteById(editor.getId());
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    organizationRepository.deleteById(organizationId);
  }

  @Test
  void nothingDerivedFromADeletedDocumentStaysInAnyTable() {
    String sentinel = SentinelScan.newSentinel();
    answerWith(
        """
        {"fields": {"document_type": {"value": "%s", "confidence": 1.0}},
         "keywords": ["%s"]}
        """
            .formatted(sentinel, sentinel));
    UUID documentId =
        documentService
            .uploadDocument(
                libraryId,
                new MockMultipartFile(
                    "file",
                    sentinel + ".txt",
                    "text/plain",
                    (sentinel
                            + "\n\nVermerk "
                            + sentinel
                            + " zur Nutzung der Abstellanlagen am Rathaus. Die Nutzung ist"
                            + " unentgeltlich und richtet sich nach den Regelungen der Stadt.")
                        .getBytes()),
                null,
                caller)
            .document()
            .getId();
    awaitIndexed(documentId);
    metadataCorrection.setValue(
        libraryId, documentId, "title", MetadataValueInput.text("Titel " + sentinel), caller);
    assertThat(SentinelScan.find(jdbcTemplate, sentinel))
        .as("the marker must first be stored where the deletion has to reach")
        .extracting(SentinelScan.Hit::qualifiedColumn)
        .contains(
            "documents.file_name",
            "chunk_full_text.content_tsv",
            "document_metadata_values.text_value",
            "document_keywords.keyword");

    documentService.deleteDocument(libraryId, documentId, caller);

    assertThat(SentinelScan.find(jdbcTemplate, sentinel))
        .extracting(SentinelScan.Hit::qualifiedColumn)
        .allSatisfy(column -> assertThat(RETAINED).containsKey(column));
  }

  private void awaitIndexed(UUID documentId) {
    await()
        .atMost(30, TimeUnit.SECONDS)
        .until(
            () ->
                documentRepository
                    .findById(documentId)
                    .map(Document::getStatus)
                    .filter(status -> status != DocumentStatus.PENDING)
                    .isPresent());
    assertThat(documentRepository.findById(documentId).orElseThrow().getStatus())
        .isEqualTo(DocumentStatus.INDEXED);
  }

  private void answerWith(String json) {
    when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
    when(activeChatModelResolver.resolveChatClient(any(Duration.class)))
        .thenReturn(ChatClient.builder(chatModel).build());
    when(activeChatModelResolver.resolveDescription())
        .thenReturn(new ActiveChatModelDescription("http://localhost:11434/v1", "test-model"));
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(json)))));
  }
}
