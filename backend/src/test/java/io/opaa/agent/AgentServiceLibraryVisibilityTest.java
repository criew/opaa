package io.opaa.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.SystemRole;
import io.opaa.asset.AssetAuthorization;
import io.opaa.asset.AssetOwnerNames;
import io.opaa.asset.AssetResponsibilityService;
import io.opaa.asset.AssetSuccessionSource;
import io.opaa.auth.CurrentUser;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.LibraryAccessService;
import io.opaa.knowledge.SourceType;
import io.opaa.permission.AssetAccessService;
import io.opaa.template.DocumentTemplateSettingsService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Somebody else's private library does not exist for the caller (ADR-0041, decision 6): an agent
 * assigned to it shows neither its name nor a count of hidden libraries, exactly as with a deleted
 * one.
 */
class AgentServiceLibraryVisibilityTest {

  private final UUID organization = UUID.randomUUID();
  private final CurrentUser caller =
      CurrentUser.of(UUID.randomUUID(), organization, SystemRole.USER, "Person");
  private final AssetAuthorization authorization = mock(AssetAuthorization.class);
  private final LibraryAccessService libraryAccess = mock(LibraryAccessService.class);
  private final KnowledgeLibraryRepository libraries = mock(KnowledgeLibraryRepository.class);
  private final AgentSwitch agentSwitch = mock(AgentSwitch.class);
  private final AgentService service =
      new AgentService(
          mock(AgentRepository.class),
          agentSwitch,
          null,
          authorization,
          mock(AssetAccessService.class),
          null,
          null,
          mock(AssetOwnerNames.class),
          mock(AssetSuccessionSource.class),
          libraryAccess,
          libraries,
          null,
          mock(DocumentTemplateSettingsService.class),
          null,
          mock(AssetResponsibilityService.class));

  private AgentView viewOfAgentAssignedTo(KnowledgeLibrary library, Set<UUID> readable) {
    Agent agent = Agent.ownedByUser(organization, "Prüfung", null, UUID.randomUUID());
    agent.configure(
        new AgentConfiguration("", Set.of(library.getId()), Set.of(), null, null, Set.of()));
    when(agentSwitch.isEnabled()).thenReturn(true);
    when(authorization.load(any(), any(), any())).thenReturn(agent);
    when(authorization.requireRole(any(), any(), anyBoolean(), any())).thenReturn(AssetRole.VIEWER);
    when(libraryAccess.readableLibraryIds(any(), any())).thenReturn(readable);
    when(libraries.findAllById(any())).thenReturn(List.of(library));
    return service.get(agent.getId(), caller);
  }

  @Test
  void aPrivateLibraryOfSomebodyElseIsNeitherNamedNorCountedAsHidden() {
    KnowledgeLibrary foreignPrivate =
        KnowledgeLibrary.ownerOnly(
            organization,
            "Fremde Ablage",
            null,
            UUID.randomUUID(),
            SourceType.UPLOAD,
            null,
            null,
            null);

    AgentView view = viewOfAgentAssignedTo(foreignPrivate, Set.of());

    assertThat(view.libraries()).isEmpty();
    assertThat(view.hiddenLibraryCount()).isZero();
  }

  @Test
  void aSharedLibraryTheCallerMayNotReadStaysCountedAsHidden() {
    KnowledgeLibrary shared =
        KnowledgeLibrary.ownedByUser(organization, "Personalakten", null, UUID.randomUUID());

    AgentView view = viewOfAgentAssignedTo(shared, Set.of());

    assertThat(view.libraries()).isEmpty();
    assertThat(view.hiddenLibraryCount()).isOne();
  }
}
