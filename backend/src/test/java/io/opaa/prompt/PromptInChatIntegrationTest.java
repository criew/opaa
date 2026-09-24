package io.opaa.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.opaa.api.AvailablePromptController;
import io.opaa.api.dto.AvailablePrompt;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.AssetVisibility;
import io.opaa.api.types.PermissionSubjectType;
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
import io.opaa.llm.ActiveChatModelResolver;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
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
 * next question, and the chat's selection offers exactly the readable prompt libraries of the
 * person's organization, the ones associated with the space first.
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
            () ->
                queryService.query(
                    QUESTION,
                    null,
                    callerOf(administrator, true),
                    true,
                    List.of(),
                    null,
                    prompt.getId()))
        .as("administering is not reading: the system administration without a grant may not")
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
    libraryService.update(
        foreignLibrary,
        new PromptLibraryUpdate("Fremd", null, AssetVisibility.ORGANIZATION, false),
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

  @Test
  void theSelectionRespectsTheReadRightAndTheOrganizationAndPutsTheSpaceFirst() {
    UUID associated = libraryOf(owner, "Zeta Referat");
    zusammenfassung(associated);
    grantViewer(associated, reader);
    associationService.associate(space, PromptLibrary.ASSET_TYPE, associated, callerOf(reader));
    UUID organizationWide = libraryOf(owner, "Alpha Haus");
    libraryService.update(
        organizationWide,
        new PromptLibraryUpdate("Alpha Haus", null, AssetVisibility.ORGANIZATION, false),
        callerOf(owner));
    promptService.create(
        organizationWide,
        new PromptContent("vermerk", "Vermerk", "Kurzer Vermerk", "Bitte.", List.of(), 0),
        callerOf(owner));
    UUID unreadable = libraryOf(owner, "Beta Privat");
    promptService.create(
        unreadable,
        new PromptContent("privat", "Privat", null, "Bitte.", List.of(), 0),
        callerOf(owner));
    UUID foreignLibrary = libraryOf(foreigner, "Fremd");
    libraryService.update(
        foreignLibrary,
        new PromptLibraryUpdate("Fremd", null, AssetVisibility.ORGANIZATION, false),
        callerOf(foreigner));
    promptService.create(
        foreignLibrary,
        new PromptContent("fremd", "Fremd", null, "Bitte.", List.of(), 0),
        callerOf(foreigner));

    List<AvailablePrompt> inSpace =
        availablePromptController.listAvailablePrompts(space, callerOf(reader));
    assertThat(inSpace)
        .extracting(AvailablePrompt::getName, AvailablePrompt::getAssociatedWithSpace)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("zusammenfassung", true),
            org.assertj.core.groups.Tuple.tuple("vermerk", false));
    assertThat(inSpace.get(0).getLibraryName()).isEqualTo("Zeta Referat");
    assertThat(inSpace.get(0).getHasVariables()).isTrue();
    assertThat(inSpace.get(1).getHasVariables()).isFalse();
    assertThat(inSpace.get(1).getDescription()).isEqualTo("Kurzer Vermerk");

    assertThat(availablePromptController.listAvailablePrompts(null, callerOf(reader)))
        .as("without a space: by library name, nothing marked")
        .extracting(AvailablePrompt::getName, AvailablePrompt::getAssociatedWithSpace)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("vermerk", false),
            org.assertj.core.groups.Tuple.tuple("zusammenfassung", false));
    assertThat(availablePromptController.listAvailablePrompts(null, callerOf(outsider)))
        .extracting(AvailablePrompt::getName)
        .containsExactly("vermerk");
    assertThat(availablePromptController.listAvailablePrompts(null, callerOf(administrator, true)))
        .as("administering is not reading: the system administration sees the formula's set")
        .extracting(AvailablePrompt::getName)
        .containsExactly("vermerk");
    assertThat(availablePromptController.listAvailablePrompts(null, callerOf(foreigner)))
        .as("the organization boundary")
        .extracting(AvailablePrompt::getName)
        .containsExactly("fremd");
    assertThatThrownBy(
            () -> availablePromptController.listAvailablePrompts(space, callerOf(outsider)))
        .as("the space orders only for its members")
        .isInstanceOf(AccessDeniedException.class);
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
            new AssetGrantUpsert(PermissionSubjectType.USER, person, AssetRole.VIEWER),
            callerOf(owner))
        .grant()
        .getId();
  }

  private UUID libraryOf(UUID person, String name) {
    return libraryService
        .create(new PromptLibraryCreation(name, null, null, null, null, null), callerOf(person))
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
