package io.opaa.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.llm.OptionalRoleState;
import io.opaa.llm.OptionalRoleStatus;
import io.opaa.llm.RerankModelRole;
import io.opaa.metadata.MetadataFilter;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

class RetrievalContextFactoryTest {

  private static final QueryProperties PROPERTIES =
      new QueryProperties(8, 25, 1.0, 0.3, false, 3, 1, false, 20, 20, 2);

  private final RerankModelRole rerankModelRole = mock(RerankModelRole.class);
  private final RetrievalContextFactory factory =
      new RetrievalContextFactory(PROPERTIES, rerankModelRole);

  private RetrievalContext contextWith(OptionalRoleStatus status) {
    when(rerankModelRole.currentStatus()).thenReturn(status);
    return factory.contextFor("Frage", List.of(), Set.of(UUID.randomUUID()), MetadataFilter.NONE);
  }

  /** Everything a stage reads travels in the context, taken as given. */
  @Test
  void carriesQuestionHistoryScopeFilterAndPropertiesAsGiven() {
    when(rerankModelRole.currentStatus()).thenReturn(OptionalRoleStatus.disabled());
    UUID libraryId = UUID.randomUUID();
    List<Message> history = List.of(new UserMessage("Vorherige Frage"));
    MetadataFilter filter = MetadataFilter.parse(List.of("SATZUNG"), null, null);

    RetrievalContext context = factory.contextFor("Frage", history, Set.of(libraryId), filter);

    assertThat(context.question()).isEqualTo("Frage");
    assertThat(context.conversationHistory()).isEqualTo(history);
    assertThat(context.searchScope()).containsExactly(libraryId);
    assertThat(context.metadataFilter()).isEqualTo(filter);
    assertThat(context.queryProperties()).isSameAs(PROPERTIES);
  }

  @Test
  void aReadyRoleMakesRerankingUsable() {
    RetrievalContext context =
        contextWith(
            new OptionalRoleStatus(OptionalRoleState.READY, "http://reranker/v1", "bge", null));

    assertThat(context.rerankAvailability()).isEqualTo(RerankAvailability.USABLE);
  }

  @Test
  void aDisabledRoleIsSwitchedOff() {
    assertThat(contextWith(OptionalRoleStatus.disabled()).rerankAvailability())
        .isEqualTo(RerankAvailability.SWITCHED_OFF);
  }

  /** Switched on but broken is a Störung, told apart from an operator's "aus". */
  @Test
  void anUnconfiguredOrUnreachableRoleIsNotUsable() {
    assertThat(
            contextWith(new OptionalRoleStatus(OptionalRoleState.UNCONFIGURED, null, null, null))
                .rerankAvailability())
        .isEqualTo(RerankAvailability.NOT_USABLE);
    assertThat(
            contextWith(
                    new OptionalRoleStatus(
                        OptionalRoleState.UNREACHABLE, "http://reranker/v1", "bge", "refused"))
                .rerankAvailability())
        .isEqualTo(RerankAvailability.NOT_USABLE);
  }
}
