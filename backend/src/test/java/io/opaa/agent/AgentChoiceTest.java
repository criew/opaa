package io.opaa.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.AgentPlanStep;
import io.opaa.api.types.SystemRole;
import io.opaa.asset.AssetResponsibilityService;
import io.opaa.auth.CurrentUser;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import io.opaa.permission.AssetAccessService;
import io.opaa.permission.SpaceAssetDirectory;
import io.opaa.query.toolloop.AgentRunSpec;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The @ menu and the check of a chosen agent apply one rule: in the chat's space, readable by the
 * formula, of the caller's organization, while agents are switched on. Every other case refuses
 * alike.
 */
class AgentChoiceTest {

  private final UUID organization = UUID.randomUUID();
  private final UUID user = UUID.randomUUID();
  private final UUID space = UUID.randomUUID();
  private final UUID agentId = UUID.randomUUID();
  private final CurrentUser caller = CurrentUser.of(user, organization, SystemRole.USER, "Person");

  private final AgentRepository repository = mock(AgentRepository.class);
  private final AgentSwitch agentSwitch = mock(AgentSwitch.class);
  private final AssetAccessService access = mock(AssetAccessService.class);
  private final SpaceAssetDirectory spaceAssets = mock(SpaceAssetDirectory.class);
  private final AgentChoice choice =
      new AgentChoice(
          repository,
          agentSwitch,
          access,
          spaceAssets,
          mock(AssetResponsibilityService.class),
          mock(AgentService.class),
          mock(PlatformTransactionManager.class));
  private Agent agent;

  @BeforeEach
  void setUp() {
    agent = agent(agentId, "Recherche", organization);
    when(agentSwitch.isEnabled()).thenReturn(true);
    when(spaceAssets.assetIdsInSpace(eq(space), eq(Agent.ASSET_TYPE), any()))
        .thenReturn(Set.of(agentId));
    when(access.readableAssetIds(Agent.ASSET_TYPE, user, organization)).thenReturn(Set.of(agentId));
    when(repository.findById(agentId)).thenReturn(Optional.of(agent));
    when(repository.findAllById(any())).thenReturn(List.of(agent));
  }

  @Test
  void anAgentOfTheSpaceTheCallerMayReadIsOfferedAndResolved() {
    assertThat(choice.available(caller, space)).containsExactly(agent);

    Optional<AgentRunSpec> spec = choice.resolve(agentId, organization, user, space, Set.of());

    assertThat(spec).isPresent();
    assertThat(spec.get().name()).isEqualTo("Recherche");
    assertThat(spec.get().version()).isEqualTo(4);
    assertThat(spec.get().instruction()).isEqualTo("Antworte knapp.");
    assertThat(spec.get().toolNames()).containsExactly(AgentTools.SEARCH_KNOWLEDGE);
    assertThat(spec.get().limits().maxDuration()).isEqualTo(Duration.ofSeconds(90));
    assertThat(spec.get().limits().maxToolCalls()).isNull();
    assertThat(spec.get().confirmPlan()).isTrue();
    assertThat(spec.get().builtin()).isFalse();
  }

  @Test
  void anAgentTheCallerMayNotReadIsNeitherOfferedNorResolved() {
    when(access.readableAssetIds(Agent.ASSET_TYPE, user, organization)).thenReturn(Set.of());
    when(repository.findAllById(Set.of())).thenReturn(List.of());

    assertThat(choice.available(caller, space)).isEmpty();
    assertThat(choice.resolve(agentId, organization, user, space, Set.of())).isEmpty();
  }

  @Test
  void anAgentOutsideTheChatsSpaceIsRefused() {
    when(spaceAssets.assetIdsInSpace(eq(space), eq(Agent.ASSET_TYPE), any())).thenReturn(Set.of());

    assertThat(choice.resolve(agentId, organization, user, space, Set.of())).isEmpty();
  }

  @Test
  void aSpaceTheCallerCannotUseRefusesTheAgentButAnswersTheMenuLikeTheSpace() {
    when(spaceAssets.assetIdsInSpace(eq(space), eq(Agent.ASSET_TYPE), any()))
        .thenThrow(new AccessDeniedException("kein Mitglied"));

    assertThat(choice.resolve(agentId, organization, user, space, Set.of())).isEmpty();
    assertThatThrownBy(() -> choice.available(caller, space))
        .isInstanceOf(AccessDeniedException.class);

    when(spaceAssets.assetIdsInSpace(eq(space), eq(Agent.ASSET_TYPE), any()))
        .thenThrow(new NotFoundException("Space nicht gefunden"));
    assertThat(choice.resolve(agentId, organization, user, space, Set.of())).isEmpty();
  }

  @Test
  void anAgentOfAnotherOrganizationIsRefused() {
    Agent foreign = agent(agentId, "Fremd", UUID.randomUUID());
    when(repository.findById(agentId)).thenReturn(Optional.of(foreign));
    when(repository.findAllById(any())).thenReturn(List.of(foreign));

    assertThat(choice.available(caller, space)).isEmpty();
    assertThat(choice.resolve(agentId, organization, user, space, Set.of())).isEmpty();
  }

  @Test
  void switchedOffNoAgentIsOfferedOrResolved() {
    when(agentSwitch.isEnabled()).thenReturn(false);

    assertThat(choice.available(caller, space)).isEmpty();
    assertThat(choice.resolve(agentId, organization, user, space, Set.of())).isEmpty();
  }

  private static Agent agent(UUID id, String name, UUID organizationId) {
    Agent agent = mock(Agent.class);
    when(agent.getId()).thenReturn(id);
    when(agent.getName()).thenReturn(name);
    when(agent.getOrganizationId()).thenReturn(organizationId);
    when(agent.getRevision()).thenReturn(4);
    when(agent.isDelivered()).thenReturn(false);
    when(agent.configuration())
        .thenReturn(
            new AgentConfiguration(
                "Antworte knapp.",
                Set.of(),
                Set.of(AgentTools.SEARCH_KNOWLEDGE),
                AgentPlanStep.CONFIRM,
                new AgentLimits(null, null, 90, null)));
    return agent;
  }
}
