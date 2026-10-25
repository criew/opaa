package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.common.Feature;
import io.opaa.common.FeatureFlags;
import io.opaa.common.TestFeatureFlags;
import io.opaa.knowledge.LibraryAccessService;
import io.opaa.metadata.DocumentTypeVocabularyEntry;
import io.opaa.metadata.DocumentTypeVocabularyRepository;
import io.opaa.metadata.LibraryMetadataFieldRepository;
import io.opaa.metadata.LibraryMetadataFieldValueRepository;
import io.opaa.metadata.MetadataFilter;
import io.opaa.metadata.MetadataFilterValidator;
import io.opaa.observability.ChatMetrics;
import io.opaa.space.ChatAutoCleanupSettings;
import io.opaa.space.Space;
import io.opaa.space.SpaceAccessPolicy;
import io.opaa.space.SpaceAssetAssociationRepository;
import io.opaa.space.SpaceAssetAssociationService;
import io.opaa.space.SpaceRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/**
 * A chat's stored metadata filter with the metadata feature switched off (ADR-0044): neither the
 * creation nor a change of the chat writes a filter, so a filter stored before the switch stays as
 * it is and is in effect again once the feature is back on.
 */
class ChatServiceMetadataFeatureTest {

  private static final MetadataFilter STORED =
      MetadataFilter.ofDateWindow(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));
  private static final MetadataFilter SENT =
      MetadataFilter.ofDateWindow(LocalDate.of(2025, 1, 1), null);

  private final UUID spaceId = UUID.randomUUID();
  private final UUID authorId = UUID.randomUUID();
  private final UUID organizationId = UUID.randomUUID();

  private ChatRepository chatRepository;
  private SpaceRepository spaceRepository;
  private SpaceAccessPolicy spaceAccessPolicy;
  private DocumentTypeVocabularyRepository vocabularyRepository;

  @BeforeEach
  void setUp() {
    chatRepository = mock(ChatRepository.class);
    spaceRepository = mock(SpaceRepository.class);
    spaceAccessPolicy = mock(SpaceAccessPolicy.class);
    vocabularyRepository = mock(DocumentTypeVocabularyRepository.class);
    Space space = mock(Space.class);
    when(space.getOrganizationId()).thenReturn(organizationId);
    when(spaceRepository.findById(spaceId)).thenReturn(Optional.of(space));
    when(spaceAccessPolicy.isMember(spaceId, authorId)).thenReturn(true);
    when(chatRepository.save(any(Chat.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(vocabularyRepository.snapshot())
        .thenReturn(
            io.opaa.metadata.DocumentTypeVocabulary.of(List.<DocumentTypeVocabularyEntry>of()));
  }

  @Test
  void switchedOffANewChatStoresNoFilter() {
    service(TestFeatureFlags.without(Feature.METADATA))
        .createChat(spaceId, authorId, new ChatCreation().metadataFilter(SENT));

    ArgumentCaptor<Chat> saved = ArgumentCaptor.forClass(Chat.class);
    org.mockito.Mockito.verify(chatRepository).save(saved.capture());
    assertThat(saved.getValue().getMetadataFilter()).isEqualTo(MetadataFilter.NONE);
  }

  @Test
  void switchedOffAChangeKeepsTheStoredFilter() {
    Chat chat = storedChat();

    service(TestFeatureFlags.without(Feature.METADATA))
        .updateChat(chat.getId(), authorId, new ChatPatch().metadataFilter(SENT));

    assertThat(chat.getMetadataFilter()).isEqualTo(STORED);
  }

  @Test
  void switchedOffClearingTheFilterKeepsTheStoredFilterToo() {
    Chat chat = storedChat();

    service(TestFeatureFlags.without(Feature.METADATA))
        .updateChat(chat.getId(), authorId, new ChatPatch().metadataFilter(MetadataFilter.NONE));

    assertThat(chat.getMetadataFilter()).isEqualTo(STORED);
  }

  @Test
  void switchedOnAChangeReplacesTheStoredFilter() {
    Chat chat = storedChat();

    service(TestFeatureFlags.allOn())
        .updateChat(chat.getId(), authorId, new ChatPatch().metadataFilter(SENT));

    assertThat(chat.getMetadataFilter()).isEqualTo(SENT);
  }

  private Chat storedChat() {
    Chat chat = new Chat(spaceId, authorId, organizationId, "Chat", true, Set.of());
    chat.applyMetadataFilter(STORED);
    when(chatRepository.findByIdAndAuthorId(chat.getId(), authorId)).thenReturn(Optional.of(chat));
    return chat;
  }

  private ChatService service(FeatureFlags flags) {
    return new ChatService(
        chatRepository,
        mock(ChatMessageRepository.class),
        spaceRepository,
        spaceAccessPolicy,
        mock(SpaceAssetAssociationRepository.class),
        mock(SpaceAssetAssociationService.class),
        mock(LibraryAccessService.class),
        mock(ObjectMapper.class),
        mock(ChatMessageWriter.class),
        mock(ChatTitleGenerationService.class),
        new MetadataFilterValidator(
            vocabularyRepository,
            mock(LibraryMetadataFieldRepository.class),
            mock(LibraryMetadataFieldValueRepository.class),
            flags),
        mock(ChatNoteService.class),
        mock(ChatPersonalMarkRepository.class),
        mock(ChatFullTextSearch.class),
        mock(ChatMetrics.class),
        mock(ChatAutoCleanupSettings.class),
        Clock.systemUTC());
  }
}
