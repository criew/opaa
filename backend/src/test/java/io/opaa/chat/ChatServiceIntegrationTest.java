package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.TestcontainersConfiguration;
import io.opaa.api.dto.ChatCreateRequest;
import io.opaa.api.dto.ChatDetail;
import io.opaa.api.dto.ChatSummary;
import io.opaa.api.dto.ChatUpdateRequest;
import io.opaa.api.dto.SourceReference;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.group.GroupMembershipHistoryRepository;
import io.opaa.library.AssetGrantHistoryRepository;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.space.Space;
import io.opaa.space.SpaceMembership;
import io.opaa.space.SpaceMembershipRepository;
import io.opaa.space.SpaceRepository;
import io.opaa.space.SpaceRole;
import io.opaa.space.SpaceVisibility;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs against a real Postgres database with the real, versioned Liquibase schema applied ({@code
 * spring.liquibase.enabled=true}, {@code ddl-auto=none}), not Hibernate-generated DDL - see #288
 * and {@link io.opaa.space.SpaceServiceIntegrationTest}'s Javadoc, which this class follows the
 * same pattern from, including its cleanup order: {@code userRepository.deleteAll()} deletes every
 * user in the shared test database, not just this class's own, so every non-system library -
 * regardless of which organization or test class created it - must be gone first, or a leftover
 * library from another class sharing this Spring context blocks the delete with a {@code
 * fk_knowledge_libraries_owner_user} RESTRICT violation. {@code chats.author_id} and {@code
 * chats.space_id} are plain {@code UUID} columns without {@code @ManyToOne}; Hibernate does not
 * create foreign keys for those, Liquibase does ({@code fk_chats_space}, {@code fk_chats_author},
 * migration 030).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles({"local", "dev"})
@Testcontainers(disabledWithoutDocker = true)
class ChatServiceIntegrationTest {

  @Autowired private ChatService chatService;
  @Autowired private ChatRepository chatRepository;
  @Autowired private ChatMessageRepository chatMessageRepository;
  @Autowired private SpaceRepository spaceRepository;
  @Autowired private SpaceMembershipRepository spaceMembershipRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private AssetGrantHistoryRepository grantHistoryRepository;
  @Autowired private GroupMembershipHistoryRepository membershipHistoryRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationA;

  @BeforeEach
  void setUp() {
    chatMessageRepository.deleteAll();
    chatRepository.deleteAll();
    spaceMembershipRepository.deleteAll();
    spaceRepository.deleteAll();
    libraryRepository.deleteAll(
        libraryRepository.findAll().stream().filter(l -> !l.isSystemLibrary()).toList());
    grantHistoryRepository.deleteAll();
    membershipHistoryRepository.deleteAll();
    userRepository.deleteAll();
    organizationA =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Org A")).getId();
  }

  @AfterEach
  void tearDown() {
    chatMessageRepository.deleteAll();
    chatRepository.deleteAll();
    spaceMembershipRepository.deleteAll();
    spaceRepository.deleteAll();
    libraryRepository.deleteAll(
        libraryRepository.findAll().stream().filter(l -> !l.isSystemLibrary()).toList());
    grantHistoryRepository.deleteAll();
    membershipHistoryRepository.deleteAll();
    userRepository.deleteAll();
    organizationRepository.deleteById(organizationA);
  }

  private UUID createLibrary(UUID ownerId) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type, owner_user_id,"
            + " visibility, listed, personal, source_type, created_at, updated_at)"
            + " VALUES (?, ?, 'Testbibliothek', 'USER', ?, 'PRIVATE', false, false, 'UPLOAD',"
            + " now(), now())",
        id,
        organizationA,
        ownerId);
    return id;
  }

  private UUID createUser() {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "user@example.com", "Test User");
    user.setOrganizationId(organizationA);
    return userRepository.save(user).getId();
  }

  private UUID createSpaceWithMember(UUID memberId) {
    Space space =
        new Space("Fachbereich", null, false, SpaceVisibility.PRIVATE, memberId, organizationA);
    space.addMembership(new SpaceMembership(memberId, SpaceRole.MEMBER, organizationA));
    return spaceRepository.save(space).getId();
  }

  @Test
  void memberCanCreateAndRetrieveTheirOwnChat() {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMember(author);

    ChatDetail created =
        chatService.createChat(spaceId, author, new ChatCreateRequest().title("Frage zur Frist"));

    assertThat(created.getSpaceId()).isEqualTo(spaceId);
    assertThat(created.getAuthorId()).isEqualTo(author);
    assertThat(created.getTitle()).isEqualTo("Frage zur Frist");
    assertThat(created.getUseKnowledge()).isTrue();
    assertThat(created.getMessages()).isEmpty();

    ChatDetail fetched = chatService.getChat(created.getId(), author);
    assertThat(fetched.getId()).isEqualTo(created.getId());
  }

  @Test
  void chattingRequiresSpaceMembership() {
    UUID outsider = createUser();
    UUID member = createUser();
    UUID spaceId = createSpaceWithMember(member);

    assertThatThrownBy(() -> chatService.createChat(spaceId, outsider, new ChatCreateRequest()))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void creatingAChatInANonExistentSpaceReturnsNotFound() {
    UUID author = createUser();

    assertThatThrownBy(
            () -> chatService.createChat(UUID.randomUUID(), author, new ChatCreateRequest()))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void aChatIsInvisibleToEveryoneButItsAuthorIncludingASpaceMember() {
    UUID author = createUser();
    UUID otherMember = createUser();
    UUID spaceId = createSpaceWithMember(author);
    Space space = spaceRepository.findByIdWithMemberships(spaceId).orElseThrow();
    space.addMembership(new SpaceMembership(otherMember, SpaceRole.ADMIN, organizationA));
    spaceRepository.save(space);

    ChatDetail chat = chatService.createChat(spaceId, author, new ChatCreateRequest());

    // #525 acceptance criterion: a foreign user - even a fellow space admin - gets 404, not 403,
    // which would confirm the chat's existence.
    assertThatThrownBy(() -> chatService.getChat(chat.getId(), otherMember))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void listChatsOnlyReturnsTheCallersOwnChatsInThatSpace() {
    UUID author = createUser();
    UUID otherMember = createUser();
    UUID spaceId = createSpaceWithMember(author);
    Space space = spaceRepository.findByIdWithMemberships(spaceId).orElseThrow();
    space.addMembership(new SpaceMembership(otherMember, SpaceRole.MEMBER, organizationA));
    spaceRepository.save(space);

    chatService.createChat(spaceId, author, new ChatCreateRequest().title("Meine Frage"));
    chatService.createChat(spaceId, otherMember, new ChatCreateRequest().title("Fremde Frage"));

    List<ChatSummary> authorsChats = chatService.listChats(spaceId, author);

    assertThat(authorsChats).hasSize(1);
    assertThat(authorsChats.getFirst().getTitle()).isEqualTo("Meine Frage");
  }

  @Test
  void updateChatOnlyOverwritesFieldsThatWereProvided() {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMember(author);
    UUID libraryId = createLibrary(author);
    ChatDetail created =
        chatService.createChat(
            spaceId, author, new ChatCreateRequest().title("Ursprünglich").useKnowledge(false));

    ChatDetail updated =
        chatService.updateChat(
            created.getId(),
            author,
            new ChatUpdateRequest().referencedLibraryIds(List.of(libraryId)));

    assertThat(updated.getTitle()).isEqualTo("Ursprünglich");
    assertThat(updated.getUseKnowledge()).isFalse();
    assertThat(updated.getReferencedLibraryIds()).containsExactly(libraryId);
  }

  @Test
  void deleteChatRemovesItForItsAuthor() {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMember(author);
    ChatDetail created = chatService.createChat(spaceId, author, new ChatCreateRequest());

    chatService.deleteChat(created.getId(), author);

    assertThatThrownBy(() -> chatService.getChat(created.getId(), author))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void appendTurnPersistsBothMessagesAndDerivesTheTitleFromTheFirstQuestion() {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMember(author);
    Chat chat = chatRepository.save(new Chat(spaceId, author, organizationA, null, true, Set.of()));

    chatService.appendTurn(
        chat,
        "Wie hoch ist die Rückstellung für Altlastensanierung?",
        "Die Rückstellung beträgt 42.000 EUR.",
        List.of(new SourceReference("bericht.pdf", 0.9, 1, true)));

    ChatDetail detail = chatService.getChat(chat.getId(), author);
    assertThat(detail.getTitle())
        .isEqualTo("Wie hoch ist die Rückstellung für Altlastensanierung?");
    assertThat(detail.getMessages()).hasSize(2);
    assertThat(detail.getMessages().get(0).getRole()).isEqualTo(ChatRole.USER);
    assertThat(detail.getMessages().get(1).getRole()).isEqualTo(ChatRole.ASSISTANT);
    assertThat(detail.getMessages().get(1).getSources()).hasSize(1);
    assertThat(detail.getMessages().get(1).getSources().getFirst().getFileName())
        .isEqualTo("bericht.pdf");
  }

  @Test
  void appendTurnDoesNotOverwriteAnExplicitlySetTitle() {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMember(author);
    ChatDetail created =
        chatService.createChat(spaceId, author, new ChatCreateRequest().title("Mein Titel"));
    Chat chat = chatRepository.findById(created.getId()).orElseThrow();

    chatService.appendTurn(chat, "Irgendeine Frage?", "Antwort.", List.of());

    ChatDetail detail = chatService.getChat(created.getId(), author);
    assertThat(detail.getTitle()).isEqualTo("Mein Titel");
  }

  @Test
  void findOwnedChatReturnsEmptyForAForeignChat() {
    UUID author = createUser();
    UUID stranger = createUser();
    UUID spaceId = createSpaceWithMember(author);
    ChatDetail created = chatService.createChat(spaceId, author, new ChatCreateRequest());

    assertThat(chatService.findOwnedChat(created.getId(), stranger)).isEmpty();
    assertThat(chatService.findOwnedChat(created.getId(), author)).isPresent();
    assertThat(chatService.findOwnedChat(null, author)).isEmpty();
  }

  @Test
  void effectiveLibraryScopeIntersectsReferencesWithReadableLibrariesWhenUseKnowledgeIsOff() {
    UUID readable = UUID.randomUUID();
    UUID notReadable = UUID.randomUUID();
    Chat chat =
        new Chat(
            UUID.randomUUID(),
            UUID.randomUUID(),
            organizationA,
            null,
            false,
            Set.of(readable, notReadable));

    Set<UUID> scope = chatService.effectiveLibraryScope(chat, Set.of(readable));

    assertThat(scope).containsExactly(readable);
  }

  @Test
  void effectiveLibraryScopeIsEveryReadableLibraryWhenUseKnowledgeIsOn() {
    UUID readableA = UUID.randomUUID();
    UUID readableB = UUID.randomUUID();
    Chat chat = new Chat(UUID.randomUUID(), UUID.randomUUID(), organizationA, null, true, Set.of());

    Set<UUID> scope = chatService.effectiveLibraryScope(chat, Set.of(readableA, readableB));

    assertThat(scope).containsExactlyInAnyOrder(readableA, readableB);
  }
}
