package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.opaa.api.types.ChatRole;
import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.SpaceRole;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.SourceType;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.query.citation.ChatSourceAssembler;
import io.opaa.space.Space;
import io.opaa.space.SpaceMembership;
import io.opaa.space.SpaceRepository;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnOrganizationFixtures;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The seed-only transcript import against the real Liquibase schema: instants and sources end up
 * where the chat list, the conversation view and the chat search read them, and the import grants
 * the author nothing a regular chat would not - membership, a writable space, readable documents.
 */
@OpaaIntegrationTest
class ChatImportServiceIntegrationTest {

  private static final Instant FIRST_ASKED =
      Instant.now().minus(Duration.ofDays(20)).truncatedTo(ChronoUnit.MILLIS);

  @Autowired private ChatImportService chatImportService;
  @Autowired private ChatService chatService;
  @Autowired private SpaceRepository spaceRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private OwnOrganizationFixtures ownOrganizationFixtures;
  @Autowired private ChatSourceAssembler chatSourceAssembler;

  private UUID organizationId;
  private UUID author;
  private UUID spaceId;
  private UUID readable;
  private UUID satzung;
  private UUID leistung;
  private UUID foreignDocument;

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Org Chatimport")).getId();
    author = createUser();
    UUID stranger = createUser();
    spaceId = createSpace(author);
    readable = insertLibrary(author, "Satzungen");
    UUID unreadable = insertLibrary(stranger, "Fremde Ablage");
    satzung = insertDocument(readable, "01_verwaltungsgebuehrensatzung.pdf");
    leistung = insertDocument(readable, "001_personalausweis.md");
    foreignDocument = insertDocument(unreadable, "geheim.docx");
  }

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("DELETE FROM documents WHERE organization_id = ?", organizationId);
    ownOrganizationFixtures.removeOrganizations(organizationId);
  }

  @Test
  void theChatCarriesTheTranscriptsInstantsAndItsMessagesInOrder() {
    Instant firstAnswered = FIRST_ASKED.plusSeconds(20);
    Instant secondAsked = FIRST_ASKED.plus(Duration.ofMinutes(4));
    Instant secondAnswered = secondAsked.plusSeconds(25);

    UUID chatId =
        chatImportService.importChat(
            spaceId,
            author,
            new ChatImport(
                "Gebühr Personalausweis",
                "Gebühr Personalausweis",
                List.of(
                    turn("Was kostet ein Personalausweis?", FIRST_ASKED, firstAnswered),
                    turn("Und für unter 24-Jährige?", secondAsked, secondAnswered))));

    ChatConversation chat = chatService.getChat(chatId, author);
    assertThat(chat.getTitle()).isEqualTo("Gebühr Personalausweis");
    assertThat(chat.getAuthorId()).isEqualTo(author);
    assertThat(chat.getCreatedAt()).isEqualTo(FIRST_ASKED);
    assertThat(chat.getUpdatedAt()).isEqualTo(secondAnswered);
    assertThat(chat.getMessages())
        .extracting(ChatTurn::getRole)
        .containsExactly(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER, ChatRole.ASSISTANT);
    assertThat(chat.getMessages())
        .extracting(ChatTurn::getCreatedAt)
        .containsExactly(FIRST_ASKED, firstAnswered, secondAsked, secondAnswered);
    assertThat(chat.getMessages().get(2).getContent()).isEqualTo("Und für unter 24-Jährige?");
  }

  @Test
  void aSourceShowsWhatItsDocumentSaysNotWhatTheTranscriptCouldClaim() {
    UUID chatId =
        chatImportService.importChat(
            spaceId,
            author,
            new ChatImport(
                "Belege",
                "Belege",
                List.of(
                    new ChatImport.Turn(
                        "Welche Gebühr gilt?",
                        "Laut Satzung 42,60 Euro.",
                        FIRST_ASKED,
                        FIRST_ASKED.plusSeconds(30),
                        List.of(
                            new ChatImport.Source(satzung, true),
                            new ChatImport.Source(leistung, false))))));

    List<ChatTurn> messages = chatService.getChat(chatId, author).getMessages();
    assertThat(messages.getFirst().getSources()).isNull();
    List<ChatSource> sources = messages.get(1).getSources();
    assertThat(sources).extracting(ChatSource::getDocumentId).containsExactly(satzung, leistung);
    assertThat(sources)
        .extracting(ChatSource::getFileName)
        .containsExactly("01_verwaltungsgebuehrensatzung.pdf", "001_personalausweis.md");
    assertThat(sources).extracting(ChatSource::getCited).containsExactly(true, false);
    assertThat(sources).extracting(ChatSource::getRelevanceScore).containsExactly(1.0, 0.5);
    assertThat(sources).extracting(ChatSource::getSourceType).containsOnly("UPLOAD");
  }

  @Test
  void theListOrdersImportedChatsByTheirLastAnswer() {
    UUID older =
        chatImportService.importChat(
            spaceId,
            author,
            new ChatImport(
                "Älter", "Älter", List.of(turn("Frage", FIRST_ASKED, FIRST_ASKED.plusSeconds(5)))));
    Instant later = FIRST_ASKED.plus(Duration.ofDays(3));
    UUID newer =
        chatImportService.importChat(
            spaceId,
            author,
            new ChatImport("Neuer", "Neuer", List.of(turn("Frage", later, later.plusSeconds(5)))));

    assertThat(chatService.listChats(spaceId, author))
        .extracting(entry -> entry.chat().getId())
        .containsExactly(newer, older);
  }

  @Test
  void anImportedAnswerIsFoundByTheChatSearch() {
    UUID chatId =
        chatImportService.importChat(
            spaceId,
            author,
            new ChatImport(
                "Suche",
                "Suche",
                List.of(
                    new ChatImport.Turn(
                        "Was gilt beim Wunschkennzeichen?",
                        "Die Reservierung eines Wunschkennzeichens kostet 14,70 Euro.",
                        FIRST_ASKED,
                        FIRST_ASKED.plusSeconds(10),
                        List.of()))));

    assertThat(chatService.searchChats(spaceId, author, "Reservierung", null, null).matches())
        .extracting(ChatSearchMatch::chatId)
        .containsExactly(chatId);
  }

  @Test
  void aSourceInALibraryTheAuthorCannotReadIsRefusedAndNothingIsWritten() {
    ChatImport transcript =
        new ChatImport(
            "Fremd",
            "Fremd",
            List.of(
                new ChatImport.Turn(
                    "Frage",
                    "Antwort",
                    FIRST_ASKED,
                    FIRST_ASKED.plusSeconds(10),
                    List.of(
                        new ChatImport.Source(satzung, true),
                        new ChatImport.Source(foreignDocument, true)))));

    assertThatThrownBy(() -> chatImportService.importChat(spaceId, author, transcript))
        .isInstanceOf(ValidationException.class)
        .hasMessage(ChatImportService.UNREADABLE_DOCUMENT);
    assertThat(chatCountOf(author)).isZero();
  }

  @Test
  void anUnknownDocumentIsRefusedWithTheSameMessage() {
    ChatImport transcript =
        new ChatImport(
            "Unbekannt",
            "Unbekannt",
            List.of(
                new ChatImport.Turn(
                    "Frage",
                    "Antwort",
                    FIRST_ASKED,
                    FIRST_ASKED.plusSeconds(10),
                    List.of(new ChatImport.Source(UUID.randomUUID(), true)))));

    assertThatThrownBy(() -> chatImportService.importChat(spaceId, author, transcript))
        .isInstanceOf(ValidationException.class)
        .hasMessage(ChatImportService.UNREADABLE_DOCUMENT);
  }

  @Test
  void onlyAMemberOfAWritableSpaceCanImport() {
    UUID outsider = createUser();
    ChatImport transcript =
        new ChatImport(
            "Chat", "Chat", List.of(turn("Frage", FIRST_ASKED, FIRST_ASKED.plusSeconds(5))));

    assertThatThrownBy(() -> chatImportService.importChat(spaceId, outsider, transcript))
        .isInstanceOf(AccessDeniedException.class);

    Space space = spaceRepository.findById(spaceId).orElseThrow();
    space.archive();
    spaceRepository.save(space);
    assertThatThrownBy(() -> chatImportService.importChat(spaceId, author, transcript))
        .isInstanceOf(ConflictException.class);
    assertThat(chatCountOf(author)).isZero();
  }

  @Test
  void instantsMustRiseAndLieInThePast() {
    ChatImport backwards =
        new ChatImport(
            "Rückwärts",
            "Rückwärts",
            List.of(
                turn("Erste", FIRST_ASKED, FIRST_ASKED.plusSeconds(60)),
                turn("Zweite", FIRST_ASKED.plusSeconds(30), FIRST_ASKED.plusSeconds(90))));
    Instant tomorrow = Instant.now().plus(Duration.ofDays(1));
    ChatImport future =
        new ChatImport(
            "Zukunft", "Zukunft", List.of(turn("Frage", tomorrow, tomorrow.plusSeconds(5))));

    assertThatThrownBy(() -> chatImportService.importChat(spaceId, author, backwards))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> chatImportService.importChat(spaceId, author, future))
        .isInstanceOf(ValidationException.class);
    assertThat(chatCountOf(author)).isZero();
  }

  @Test
  void aBlankQuestionOrAnswerIsRefused() {
    ChatImport blankQuestion =
        new ChatImport(
            "Leer",
            "Leer",
            List.of(
                new ChatImport.Turn(
                    "   ", "Antwort", FIRST_ASKED, FIRST_ASKED.plusSeconds(5), List.of())));
    ChatImport blankAnswer =
        new ChatImport(
            "Leer",
            "Leer",
            List.of(
                new ChatImport.Turn(
                    "Frage", "\t \n", FIRST_ASKED, FIRST_ASKED.plusSeconds(5), List.of())));

    assertThatThrownBy(() -> chatImportService.importChat(spaceId, author, blankQuestion))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> chatImportService.importChat(spaceId, author, blankAnswer))
        .isInstanceOf(ValidationException.class);
    assertThat(chatCountOf(author)).isZero();
  }

  @Test
  void aMarkerThatNamesNoSourceOfItsOwnTurnIsRefusedAndNothingIsWritten() {
    String satzungMarker = marker(satzung, "01_verwaltungsgebuehrensatzung.pdf");
    ChatImport otherTurnsSource =
        new ChatImport(
            "fremde-runde",
            "Fremde Runde",
            List.of(
                citingTurn("Erste", "Laut Satzung" + satzungMarker + ".", FIRST_ASKED, satzung),
                citingTurn(
                    "Zweite",
                    "Siehe" + satzungMarker + ".",
                    FIRST_ASKED.plusSeconds(60),
                    leistung)));
    ChatImport unknownId =
        new ChatImport(
            "unbekannte-id",
            "Unbekannte ID",
            List.of(
                citingTurn(
                    "Frage",
                    "Antwort" + marker(UUID.randomUUID(), "01_verwaltungsgebuehrensatzung.pdf"),
                    FIRST_ASKED,
                    satzung)));

    for (ChatImport transcript : List.of(otherTurnsSource, unknownId)) {
      assertThatThrownBy(() -> chatImportService.importChat(spaceId, author, transcript))
          .isInstanceOf(ValidationException.class)
          .hasMessage(ChatImportService.UNMATCHED_MARKER);
    }
    assertThat(chatCountOf(author)).isZero();
  }

  @Test
  void aMarkerWhoseFileNameDiffersFromItsDocumentIsRefused() {
    // The claimed name is that of another source of the turn: the Belegfenster's file-name
    // fallback would attach such a marker to that source.
    ChatImport transcript =
        new ChatImport(
            "falscher-name",
            "Falscher Name",
            List.of(
                citingTurn(
                    "Frage",
                    "Antwort" + marker(satzung, "001_personalausweis.md"),
                    FIRST_ASKED,
                    satzung,
                    leistung)));

    assertThatThrownBy(() -> chatImportService.importChat(spaceId, author, transcript))
        .isInstanceOf(ValidationException.class)
        .hasMessage(ChatImportService.UNMATCHED_MARKER);
    assertThat(chatCountOf(author)).isZero();
  }

  @Test
  void aMarkerTheBelegfensterReadsButTheImportCannotParseIsRefused() {
    // The Belegfenster's JavaScript \s and . accept characters Java's do not: a no-break space
    // after "source:" and U+0085 inside the file name. Both markers point at no source of the turn.
    String noBreakSpace =
        "【source: " + UUID.randomUUID() + "#0 | 01_verwaltungsgebuehrensatzung.pdf】";
    String nextLineInName =
        "【source: " + UUID.randomUUID() + "#0 | 01_verwaltungs\u0085satzung.pdf】";

    for (String foreignMarker : List.of(noBreakSpace, nextLineInName)) {
      ChatImport transcript =
          new ChatImport(
              "unlesbare-marke",
              "Unlesbare Marke",
              List.of(citingTurn("Frage", "Antwort" + foreignMarker, FIRST_ASKED, satzung)));

      assertThatThrownBy(() -> chatImportService.importChat(spaceId, author, transcript))
          .isInstanceOf(ValidationException.class)
          .hasMessage(ChatImportService.UNMATCHED_MARKER);
    }
    assertThat(chatCountOf(author)).isZero();
  }

  @Test
  void aMarkerNamingASectionTheDocumentDoesNotHaveIsRefused() {
    ChatImport transcript =
        new ChatImport(
            "fremder-abschnitt",
            "Fremder Abschnitt",
            List.of(
                citingTurn(
                    "Frage",
                    "Antwort【source: " + satzung + "#2 | 01_verwaltungsgebuehrensatzung.pdf】",
                    FIRST_ASKED,
                    satzung)));

    assertThatThrownBy(() -> chatImportService.importChat(spaceId, author, transcript))
        .isInstanceOf(ValidationException.class)
        .hasMessage(ChatImportService.UNMATCHED_MARKER);
    assertThat(chatCountOf(author)).isZero();
  }

  @Test
  void aMarkerOfItsTurnsSourceIsKeptAsWritten() {
    String answer =
        "Laut Satzung 42,60 Euro" + marker(satzung, "01_Verwaltungsgebuehrensatzung.PDF");

    UUID chatId =
        chatImportService.importChat(
            spaceId,
            author,
            new ChatImport(
                "gueltige-marke",
                "Gültige Marke",
                List.of(citingTurn("Frage", answer, FIRST_ASKED, satzung))));

    assertThat(chatService.getChat(chatId, author).getMessages().get(1).getContent())
        .isEqualTo(answer);
  }

  @Test
  void anImportedSourceCarriesTheSameMetadataFieldsAsAGeneratedOne() {
    insertValue(satzung, "title", "Verwaltungsgebührensatzung", "MANUAL", null);
    insertValue(satzung, "fmt:mail_subject", "Neufassung der Satzung", "DETERMINISTIC", null);
    UUID field = insertCitationField(readable, "aktenzeichen", "Aktenzeichen");
    insertValue(satzung, "lib:aktenzeichen", "AZ 22-01", "MANUAL", field);

    UUID chatId =
        chatImportService.importChat(
            spaceId,
            author,
            new ChatImport(
                "metadaten",
                "Metadaten",
                List.of(
                    citingTurn(
                        "Frage",
                        "Antwort" + marker(satzung, "01_verwaltungsgebuehrensatzung.pdf"),
                        FIRST_ASKED,
                        satzung))));
    ChatSource imported =
        chatService.getChat(chatId, author).getMessages().get(1).getSources().getFirst();
    ChatSource generated =
        chatSourceAssembler
            .assemble(
                List.of(
                    new org.springframework.ai.document.Document(
                        "Text",
                        Map.of(
                            "document_id",
                            satzung.toString(),
                            "file_name",
                            "01_verwaltungsgebuehrensatzung.pdf",
                            "chunk_index",
                            0))),
                List.of(),
                null)
            .getFirst();

    assertThat(imported.getMetadata())
        .extracting(ChatSourceMetadataEntry::fieldKey)
        .containsExactly("title", "fmt:mail_subject", "lib:aktenzeichen");
    assertThat(imported.getMetadata()).isEqualTo(generated.getMetadata());
  }

  @Test
  void aSecondImportUnderAKeyAlreadyUsedInTheSpaceIsAConflictAndWritesNothing() {
    chatImportService.importChat(
        spaceId,
        author,
        new ChatImport("schluessel", "Erster", List.of(turn("Frage", FIRST_ASKED, FIRST_ASKED))));
    ChatImport again =
        new ChatImport("schluessel", "Zweiter", List.of(turn("Frage", FIRST_ASKED, FIRST_ASKED)));

    assertThatThrownBy(() -> chatImportService.importChat(spaceId, author, again))
        .isInstanceOf(ConflictException.class);
    assertThat(chatCountOf(author)).isOne();
  }

  @Test
  void theImportedChatsAreListedByKeyAfterARenameWithTheirMarksAndNoOtherChat() {
    UUID renamed =
        chatImportService.importChat(
            spaceId,
            author,
            new ChatImport(
                "gebuehren", "Gebühren", List.of(turn("Frage", FIRST_ASKED, FIRST_ASKED))));
    UUID archived =
        chatImportService.importChat(
            spaceId,
            author,
            new ChatImport("archiv", "Archiv", List.of(turn("Frage", FIRST_ASKED, FIRST_ASKED))));
    chatService.updateChat(renamed, author, new ChatPatch().title("Umbenannt"));
    chatService.archiveChat(archived, author);
    chatService.createChat(spaceId, author, new ChatCreation().title("Gebühren"));

    List<ImportedChatEntry> listed = chatImportService.listImportedChats(spaceId, author);

    assertThat(listed)
        .extracting(ImportedChatEntry::importKey, entry -> entry.chat().chat().getId())
        .containsExactlyInAnyOrder(tuple("gebuehren", renamed), tuple("archiv", archived));
    assertThat(entryOf(listed, "gebuehren").chat().chat().getTitle()).isEqualTo("Umbenannt");
    assertThat(entryOf(listed, "gebuehren").chat().archivedAt()).isNull();
    assertThat(entryOf(listed, "archiv").chat().archivedAt()).isNotNull();
  }

  @Test
  void onlyAMemberListsTheImportedChatsOfAnExistingSpace() {
    UUID outsider = createUser();

    assertThatThrownBy(() -> chatImportService.listImportedChats(spaceId, outsider))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> chatImportService.listImportedChats(UUID.randomUUID(), author))
        .isInstanceOf(NotFoundException.class);
  }

  private static ImportedChatEntry entryOf(List<ImportedChatEntry> entries, String importKey) {
    return entries.stream()
        .filter(entry -> entry.importKey().equals(importKey))
        .findFirst()
        .orElseThrow();
  }

  private static String marker(UUID documentId, String fileName) {
    return "【source: " + documentId + "#0 | " + fileName + "】";
  }

  private static ChatImport.Turn citingTurn(
      String question, String answer, Instant askedAt, UUID... cited) {
    return new ChatImport.Turn(
        question,
        answer,
        askedAt,
        askedAt.plusSeconds(20),
        Arrays.stream(cited).map(id -> new ChatImport.Source(id, true)).toList());
  }

  private UUID insertCitationField(UUID library, String fieldKey, String label) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO library_metadata_fields (id, library_id, field_key, label, field_type,"
            + " value_pattern, filter_enabled, citation_enabled, citation_position, sort_order,"
            + " created_at, updated_at) VALUES (?, ?, ?, ?, 'PATTERN', '.*', true, true, 1, 10,"
            + " now(), now())",
        id,
        library,
        fieldKey,
        label);
    return id;
  }

  private void insertValue(
      UUID document, String fieldKey, String text, String origin, UUID libraryField) {
    jdbcTemplate.update(
        "INSERT INTO document_metadata_values (id, document_id, field_key, text_value, origin,"
            + " extraction_version, library_field_id, created_at, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, now(), now())",
        UUID.randomUUID(),
        document,
        fieldKey,
        text,
        origin,
        "MANUAL".equals(origin) ? null : 1,
        libraryField);
  }

  private static ChatImport.Turn turn(String question, Instant askedAt, Instant answeredAt) {
    return new ChatImport.Turn(question, "Antwort auf: " + question, askedAt, answeredAt, null);
  }

  private int chatCountOf(UUID user) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM chats WHERE author_id = ?", Integer.class, user);
  }

  private UUID createUser() {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "import@example.com", "Test User");
    user.setOrganizationId(organizationId);
    return userRepository.save(user).getId();
  }

  private UUID createSpace(UUID owner) {
    Space space = new Space("Amtsleitung", null, false, owner, organizationId);
    space.addMembership(SpaceMembership.ofUser(owner, SpaceRole.ADMIN, organizationId));
    return spaceRepository.save(space).getId();
  }

  private UUID insertLibrary(UUID owner, String name) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "WITH shell AS (INSERT INTO assets (id, asset_type, organization_id, name, owner_type,"
            + " owner_user_id) VALUES (?, 'KNOWLEDGE_LIBRARY', ?, ?, 'USER', ?)"
            + " RETURNING id, organization_id) INSERT INTO knowledge_libraries (id,"
            + " organization_id, source_type) SELECT id, organization_id, 'UPLOAD' FROM shell",
        id,
        organizationId,
        name,
        owner);
    jdbcTemplate.update(
        "INSERT INTO asset_grants (id, asset_type, asset_id, organization_id, subject_type,"
            + " subject_user_id, role, created_at, updated_at) VALUES (?, 'KNOWLEDGE_LIBRARY', ?,"
            + " ?, 'USER', ?, 'OWNER', now(), now())",
        UUID.randomUUID(),
        id,
        organizationId,
        owner);
    return id;
  }

  private UUID insertDocument(UUID library, String fileName) {
    Document document =
        new Document(fileName, "/" + fileName, "text/markdown", 100L, SourceType.UPLOAD);
    document.setLibraryId(library);
    document.setOrganizationId(organizationId);
    document.setStatus(DocumentStatus.INDEXED);
    document.setIndexedAt(Instant.now());
    document.setChunkCount(2);
    return documentRepository.save(document).getId();
  }
}
