package io.opaa.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.opaa.api.dto.AvailablePrompt;
import io.opaa.api.types.AssetGrantSubjectType;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.PromptVariableType;
import io.opaa.api.types.SpaceRole;
import io.opaa.api.types.SpaceVisibility;
import io.opaa.api.types.SystemRole;
import io.opaa.asset.AssetGrantService;
import io.opaa.asset.AssetGrantUpsert;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.chat.ChatCreation;
import io.opaa.chat.ChatService;
import io.opaa.chat.ChatTurn;
import io.opaa.chat.UsedPrompt;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import io.opaa.llm.ActiveChatModelResolver;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.prompt.web.AvailablePromptController;
import io.opaa.query.QueryService;
import io.opaa.space.Space;
import io.opaa.space.SpaceAssetAssociationService;
import io.opaa.space.SpaceMembership;
import io.opaa.space.SpaceRepository;
import io.opaa.test.OpaaMockedChatModelIntegrationTest;
import io.opaa.test.OwnOrganizationFixtures;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A prompt inserted in the chat (#1903), end to end against the Liquibase schema: the question
 * keeps the prompt as a snapshot on its message, a prompt the person may no longer read refuses the
 * next question, and the chat's selection offers exactly the prompt libraries associated with the
 * space that the person may read - the space is a hard boundary for offer and use alike.
 */
@OpaaMockedChatModelIntegrationTest
class PromptInChatIntegrationTest {

  private static final String QUESTION = "Fasse den Stand zum 24.09.2026 zusammen.";

  @Autowired private QueryService queryService;
  @Autowired private ChatService chatService;
  @Autowired private PromptLibraryService libraryService;
  @Autowired private PromptService promptService;
  @Autowired private AssetGrantService grantService;
  @Autowired private SpaceAssetAssociationService associationService;
  @Autowired private AvailablePromptController availablePromptController;
  @Autowired private SpaceRepository spaceRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private OwnOrganizationFixtures ownOrganizationFixtures;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ChatModel chatModel;
  @Autowired private ActiveChatModelResolver activeChatModelResolver;

  private UUID organization;
  private UUID foreignOrganization;
  private UUID owner;
  private UUID reader;
  private UUID outsider;
  private UUID administrator;
  private UUID foreigner;
  private UUID space;

  @BeforeEach
  void setUp() {
    when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
    when(activeChatModelResolver.resolveChatClient())
        .thenReturn(ChatClient.builder(chatModel).build());
    when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("Antwort")))));

    organization = createOrganization("Prompt im Chat");
    foreignOrganization = createOrganization("Fremde Organisation");
    owner = createUser(organization, "Eigentümerin");
    reader = createUser(organization, "Sachbearbeiterin");
    outsider = createUser(organization, "Außenstehende");
    administrator = createUser(organization, "Systemverwaltung");
    foreigner = createUser(foreignOrganization, "Fremde");
    space = createSpace(reader, organization);
  }

  @AfterEach
  void tearDown() {
    for (UUID id : List.of(organization, foreignOrganization)) {
      jdbcTemplate.update("DELETE FROM chats WHERE organization_id = ?", id);
      jdbcTemplate.update("DELETE FROM space_asset_associations WHERE organization_id = ?", id);
      jdbcTemplate.update("DELETE FROM assets WHERE organization_id = ?", id);
      jdbcTemplate.update("DELETE FROM asset_grant_history WHERE organization_id = ?", id);
      jdbcTemplate.update("DELETE FROM asset_ownership_history WHERE organization_id = ?", id);
    }
    ownOrganizationFixtures.removeOrganizations(organization, foreignOrganization);
  }

  @Test
  void theQuestionKeepsThePromptAsASnapshotAndARevokedPromptRefusesTheNextOne() {
    UUID library = libraryOf(owner, "Formulierungshilfen");
    Prompt prompt = zusammenfassung(library);
    UUID grant = grantViewer(library, reader);
    associationService.associate(space, PromptLibrary.ASSET_TYPE, library, callerOf(reader));
    UUID chat = chatService.createChat(space, reader, new ChatCreation()).getId();
    ArgumentCaptor<org.springframework.ai.chat.prompt.Prompt> modelCall =
        ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);

    queryService.query(QUESTION, chat, callerOf(reader), true, List.of(), null, prompt.getId());

    List<ChatTurn> turns = chatService.getChat(chat, reader).getMessages();
    assertThat(turns).hasSize(2);
    assertThat(turns.get(0).getContent()).isEqualTo(QUESTION);
    assertThat(turns.get(0).getUsedPrompt())
        .isEqualTo(new UsedPrompt(prompt.getId(), "Zusammenfassung"));
    assertThat(turns.get(1).getUsedPrompt()).as("the answer carries no prompt").isNull();
    org.mockito.Mockito.verify(chatModel, org.mockito.Mockito.atLeastOnce())
        .call(modelCall.capture());
    assertThat(modelCall.getAllValues())
        .as("the prompt changes nothing about the model call - its title never reaches the model")
        .noneMatch(call -> call.getContents().contains("Zusammenfassung"));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE object_id = ?",
                Long.class,
                prompt.getId().toString()))
        .as("inserting a prompt writes no audit entry - only PROMPT_CREATED stands there")
        .isEqualTo(1L);
    assertThatThrownBy(
            () -> promptService.requireUsable(prompt.getId(), space, callerOf(administrator, true)))
        .as("administering is not reading: the system administration without a grant may not")
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage(PromptService.NOT_USABLE);
    assertThatThrownBy(
            () ->
                queryService.query(
                    QUESTION, null, callerOf(reader), true, List.of(), null, prompt.getId()))
        .as("an ephemeral query has no space and therefore offers no prompt")
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage(PromptService.NOT_USABLE);

    promptService.update(
        library,
        prompt.getId(),
        new PromptContent(
            "zusammenfassung",
            "Lagebericht",
            null,
            "Fasse den Stand zum {{stichtag}} zusammen.",
            prompt.getVariables(),
            0),
        callerOf(owner));
    grantService.revokeGrant(PromptLibrary.ASSET_TYPE, library, grant, callerOf(owner));

    assertThatThrownBy(
            () ->
                queryService.query(
                    QUESTION, chat, callerOf(reader), true, List.of(), null, prompt.getId()))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage(PromptService.NOT_USABLE)
        .hasFieldOrPropertyWithValue("code", PromptService.NOT_USABLE_CODE);
    List<ChatTurn> after = chatService.getChat(chat, reader).getMessages();
    assertThat(after).as("the refused question is not persisted").hasSize(2);
    assertThat(after.get(0).getUsedPrompt().title())
        .as("the snapshot stands, renamed and revoked alike")
        .isEqualTo("Zusammenfassung");
  }

  @Test
  void anUnknownPromptAndOneOfAnotherOrganizationAreRefusedAlike() {
    UUID foreignLibrary = libraryOf(foreigner, "Fremd");
    grantService.upsertGrant(
        PromptLibrary.ASSET_TYPE,
        foreignLibrary,
        AssetGrantUpsert.forAllAccounts(AssetRole.VIEWER),
        callerOf(foreigner));
    Prompt foreignPrompt =
        promptService.create(
            foreignLibrary,
            new PromptContent("fremd", "Fremd", null, "Bitte.", List.of(), 0),
            callerOf(foreigner));
    UUID chat = chatService.createChat(space, reader, new ChatCreation()).getId();

    for (UUID promptId : List.of(foreignPrompt.getId(), UUID.randomUUID())) {
      assertThatThrownBy(
              () ->
                  queryService.query(
                      "Frage", chat, callerOf(reader), true, List.of(), null, promptId))
          .isInstanceOf(AccessDeniedException.class)
          .hasMessage(PromptService.NOT_USABLE);
    }
    assertThat(chatService.getChat(chat, reader).getMessages()).isEmpty();
  }

  /**
   * The test matrix of the hard boundary for prompts: associated and readable is offered and
   * usable; associated but not readable, and readable but not associated, are neither.
   */
  @Test
  void theSpaceIsAHardBoundaryForThePromptOfferAndItsUse() {
    UUID associatedReadable = libraryOf(owner, "Zeta Referat");
    Prompt offered = zusammenfassung(associatedReadable);
    grantViewer(associatedReadable, reader);
    associationService.associate(
        space, PromptLibrary.ASSET_TYPE, associatedReadable, callerOf(reader));

    UUID readableNotAssociated = libraryOf(owner, "Alpha Haus");
    grantService.upsertGrant(
        PromptLibrary.ASSET_TYPE,
        readableNotAssociated,
        AssetGrantUpsert.forAllAccounts(AssetRole.VIEWER),
        callerOf(owner));
    Prompt notAssociated =
        promptService.create(
            readableNotAssociated,
            new PromptContent("vermerk", "Vermerk", "Kurzer Vermerk", "Bitte.", List.of(), 0),
            callerOf(owner));

    UUID associatedUnreadable = libraryOf(owner, "Beta Privat");
    Prompt unreadable =
        promptService.create(
            associatedUnreadable,
            new PromptContent("privat", "Privat", null, "Bitte.", List.of(), 0),
            callerOf(owner));
    jdbcTemplate.update(
        "INSERT INTO space_asset_associations (id, space_id, asset_id, organization_id,"
            + " created_by_user_id, created_at) VALUES (?, ?, ?, ?, ?, now())",
        UUID.randomUUID(),
        space,
        associatedUnreadable,
        organization,
        owner);

    List<AvailablePrompt> inSpace =
        availablePromptController.listAvailablePrompts(space, callerOf(reader));
    assertThat(inSpace)
        .as("only what is associated and readable")
        .extracting(AvailablePrompt::getName)
        .containsExactly("zusammenfassung");
    assertThat(inSpace.get(0).getLibraryName()).isEqualTo("Zeta Referat");
    assertThat(inSpace.get(0).getHasVariables()).isTrue();
    assertThat(inSpace.get(0).getDescription()).isEqualTo("Stand eines Vorgangs zu einem Stichtag");

    UUID chat = chatService.createChat(space, reader, new ChatCreation()).getId();
    queryService.query(QUESTION, chat, callerOf(reader), true, List.of(), null, offered.getId());
    for (Prompt refused : List.of(notAssociated, unreadable)) {
      assertThatThrownBy(
              () ->
                  queryService.query(
                      QUESTION, chat, callerOf(reader), true, List.of(), null, refused.getId()))
          .as("a prompt outside the space, or one not readable, is refused by the server")
          .isInstanceOf(AccessDeniedException.class)
          .hasMessage(PromptService.NOT_USABLE)
          .hasFieldOrPropertyWithValue("code", PromptService.NOT_USABLE_CODE);
    }
    assertThat(chatService.getChat(chat, reader).getMessages())
        .as("only the turn with the usable prompt is persisted")
        .hasSize(2);
  }

  @Test
  void theSelectionRespectsTheMembershipAndTheOrganization() {
    UUID organizationWide = libraryOf(owner, "Alpha Haus");
    grantService.upsertGrant(
        PromptLibrary.ASSET_TYPE,
        organizationWide,
        AssetGrantUpsert.forAllAccounts(AssetRole.VIEWER),
        callerOf(owner));
    promptService.create(
        organizationWide,
        new PromptContent("vermerk", "Vermerk", null, "Bitte.", List.of(), 0),
        callerOf(owner));
    associationService.associate(
        space, PromptLibrary.ASSET_TYPE, organizationWide, callerOf(reader));

    assertThat(availablePromptController.listAvailablePrompts(space, callerOf(reader)))
        .extracting(AvailablePrompt::getName)
        .containsExactly("vermerk");
    assertThatThrownBy(
            () -> availablePromptController.listAvailablePrompts(space, callerOf(outsider)))
        .as("the space offers its prompts only to its members")
        .isInstanceOf(AccessDeniedException.class);
  }

  /** A space that does not exist, or one of another organization, orders nothing: it is a 404. */
  @Test
  void anUnknownSpaceOrAForeignOneIsNotFound() {
    assertThatThrownBy(
            () ->
                availablePromptController.listAvailablePrompts(UUID.randomUUID(), callerOf(reader)))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(
            () -> availablePromptController.listAvailablePrompts(space, callerOf(foreigner)))
        .isInstanceOf(NotFoundException.class);
  }

  private Prompt zusammenfassung(UUID library) {
    return promptService.create(
        library,
        new PromptContent(
            "zusammenfassung",
            "Zusammenfassung",
            "Stand eines Vorgangs zu einem Stichtag",
            "Fasse den Stand zum {{stichtag}} zusammen.",
            List.of(
                new PromptVariable(
                    "stichtag", "Stichtag", PromptVariableType.DATE, true, null, List.of())),
            0),
        callerOf(owner));
  }

  private UUID grantViewer(UUID library, UUID person) {
    return grantService
        .upsertGrant(
            PromptLibrary.ASSET_TYPE,
            library,
            new AssetGrantUpsert(AssetGrantSubjectType.USER, person, AssetRole.VIEWER),
            callerOf(owner))
        .grant()
        .getId();
  }

  private UUID libraryOf(UUID person, String name) {
    return libraryService
        .create(new PromptLibraryCreation(name, null, null, null, null), callerOf(person))
        .library()
        .getId();
  }

  private UUID createOrganization(String name) {
    return organizationRepository
        .save(new Organization(UUID.randomUUID(), name + " " + UUID.randomUUID()))
        .getId();
  }

  private UUID createUser(UUID organizationId, String displayName) {
    User user =
        new User(
            "prompt-chat-" + UUID.randomUUID(),
            "https://issuer.example",
            UUID.randomUUID() + "@example.com",
            displayName);
    user.setOrganizationId(organizationId);
    return userRepository.save(user).getId();
  }

  private UUID createSpace(UUID admin, UUID organizationId) {
    Space created =
        new Space("Referat 50", null, false, SpaceVisibility.PRIVATE, admin, organizationId);
    created.addMembership(SpaceMembership.ofUser(admin, SpaceRole.ADMIN, organizationId));
    return spaceRepository.save(created).getId();
  }

  private CurrentUser callerOf(UUID userId) {
    return callerOf(userId, false);
  }

  private CurrentUser callerOf(UUID userId, boolean systemAdmin) {
    UUID organizationId = userId.equals(foreigner) ? foreignOrganization : organization;
    return CurrentUser.of(
        userId,
        organizationId,
        systemAdmin ? SystemRole.SYSTEM_ADMIN : SystemRole.USER,
        "Sachbearbeitung");
  }
}
