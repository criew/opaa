package io.opaa.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.SystemRole;
import io.opaa.asset.AssetResponsibilityService;
import io.opaa.auth.CurrentUser;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.SourceType;
import io.opaa.template.DocumentTemplateRepository;
import io.opaa.template.DocumentTemplateSettingsService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A private library does not exist for the reader of the directory (ADR-0041, decision 6). */
class AgentDirectoryAssemblerTest {

  private final UUID organization = UUID.randomUUID();
  private final AgentRepository agents = mock(AgentRepository.class);
  private final KnowledgeLibraryRepository libraries = mock(KnowledgeLibraryRepository.class);
  private final AssetResponsibilityService responsibilities =
      mock(AssetResponsibilityService.class);
  private final AgentDirectoryAssembler assembler =
      new AgentDirectoryAssembler(
          agents,
          mock(DocumentTemplateRepository.class),
          mock(DocumentTemplateSettingsService.class),
          libraries,
          responsibilities);

  @Test
  void theNameOfAPrivateLibraryNeverReachesTheDirectory() {
    KnowledgeLibrary shared =
        KnowledgeLibrary.ownedByUser(organization, "Vergaberecht", null, UUID.randomUUID());
    KnowledgeLibrary privateOne =
        KnowledgeLibrary.ownerOnly(
            organization,
            "Meine Geheimablage",
            null,
            UUID.randomUUID(),
            SourceType.UPLOAD,
            null,
            null,
            null);
    Agent agent = Agent.ownedByUser(organization, "Prüfung", null, UUID.randomUUID());
    agent.configure(
        new AgentConfiguration(
            "", Set.of(shared.getId(), privateOne.getId()), Set.of(), null, null, Set.of()));
    when(agents.findByOrganizationId(organization)).thenReturn(List.of(agent));
    when(libraries.findAllById(any())).thenReturn(List.of(shared, privateOne));
    when(responsibilities.viewsOf(any(), any(), any())).thenReturn(Map.of());

    AgentDirectory directory =
        assembler.assemble(
            CurrentUser.of(UUID.randomUUID(), organization, SystemRole.SYSTEM_ADMIN, "Admin"));

    assertThat(directory.agents().getFirst().libraryNames()).containsExactly("Vergaberecht");
    assertThat(directory.agents().getFirst().allReadableLibraries()).isFalse();
  }
}
