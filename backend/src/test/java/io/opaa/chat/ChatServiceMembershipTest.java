package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.common.NotFoundException;
import io.opaa.common.TestFeatureFlags;
import io.opaa.knowledge.LibraryAccessService;
import io.opaa.metadata.DocumentTypeVocabularyRepository;
import io.opaa.metadata.LibraryMetadataFieldRepository;
import io.opaa.metadata.LibraryMetadataFieldValueRepository;
import io.opaa.metadata.MetadataFilterValidator;
import io.opaa.observability.ChatMetrics;
import io.opaa.space.ChatAutoCleanupSettings;
import io.opaa.space.Space;
import io.opaa.space.SpaceAccessPolicy;
import io.opaa.space.SpaceAssetAssociationRepository;
import io.opaa.space.SpaceAssetAssociationService;
import io.opaa.space.SpaceRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** A space the caller is not a member of answers exactly like one that does not exist. */
class ChatServiceMembershipTest {

  private final UUID spaceId = UUID.randomUUID();
  private final UUID outsider = UUID.randomUUID();

  private SpaceRepository spaceRepository;
  private SpaceAccessPolicy spaceAccessPolicy;
  private ChatService service;

  @BeforeEach
  void setUp() {
    spaceRepository = mock(SpaceRepository.class);
    spaceAccessPolicy = mock(SpaceAccessPolicy.class);
    when(spaceRepository.findById(spaceId)).thenReturn(Optional.of(mock(Space.class)));
    when(spaceAccessPolicy.isMember(spaceId, outsider)).thenReturn(false);
    service =
        new ChatService(
            mock(ChatRepository.class),
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
                mock(DocumentTypeVocabularyRepository.class),
                mock(LibraryMetadataFieldRepository.class),
                mock(LibraryMetadataFieldValueRepository.class),
                TestFeatureFlags.allOn()),
            mock(ChatNoteService.class),
            mock(ChatPersonalMarkRepository.class),
            mock(ChatFullTextSearch.class),
            mock(ChatMetrics.class),
            mock(ChatAutoCleanupSettings.class),
            Clock.systemUTC());
  }

  // regression guard for #3358 (4): no 403 tells an outsider that the space exists
  @Test
  void anOutsiderGetsTheAnswerOfAnUnknownSpace() {
    assertThatThrownBy(() -> service.listChats(spaceId, outsider))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Space nicht gefunden");
    assertThatThrownBy(() -> service.createChat(spaceId, outsider, new ChatCreation()))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Space nicht gefunden");
    assertThatThrownBy(() -> service.searchChats(spaceId, outsider, "Bescheid", 0, 20))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Space nicht gefunden");
    assertThatThrownBy(() -> service.listChats(UUID.randomUUID(), outsider))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Space nicht gefunden");
  }
}
