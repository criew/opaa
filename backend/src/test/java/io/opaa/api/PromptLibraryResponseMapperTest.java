package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.PromptLibraryRequest;
import io.opaa.api.dto.PromptLibraryResponse;
import io.opaa.api.dto.PromptLibraryUpdateRequest;
import io.opaa.api.dto.PromptRequest;
import io.opaa.api.types.AssetOwnerType;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.AssetVisibility;
import io.opaa.api.types.PromptVariableType;
import io.opaa.prompt.PromptContent;
import io.opaa.prompt.PromptLibrary;
import io.opaa.prompt.PromptLibraryCreation;
import io.opaa.prompt.PromptLibraryUpdate;
import io.opaa.prompt.PromptLibraryView;
import io.opaa.prompt.PromptVariable;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Every field of the prompt library's requests and response is carried, and none is swapped. */
class PromptLibraryResponseMapperTest {

  private static final UUID ORGANIZATION = UUID.randomUUID();
  private static final UUID GROUP = UUID.randomUUID();

  @Test
  void theResponseCarriesShellFieldsRoleCountAndOwnerName() {
    PromptLibrary library =
        PromptLibrary.ownedByGroup(
            ORGANIZATION, "Vorlagen", "Beschreibung", GROUP, AssetVisibility.SHARED, true);

    PromptLibraryResponse response =
        PromptLibraryResponseMapper.toResponse(
            new PromptLibraryView(library, AssetRole.EDITOR, 7, "Referat 50", null));

    assertThat(response.getId()).isEqualTo(library.getId());
    assertThat(response.getName()).isEqualTo("Vorlagen");
    assertThat(response.getDescription()).isEqualTo("Beschreibung");
    assertThat(response.getOwnerType()).isEqualTo(AssetOwnerType.GROUP);
    assertThat(response.getOwnerId()).isEqualTo(GROUP);
    assertThat(response.getOwnerName()).isEqualTo("Referat 50");
    assertThat(response.getVisibility()).isEqualTo(AssetVisibility.SHARED);
    assertThat(response.getListed()).isTrue();
    assertThat(response.getMyRole()).isEqualTo(AssetRole.EDITOR);
    assertThat(response.getPromptCount()).isEqualTo(7L);
    assertThat(response.getSuccession()).isNull();
  }

  @Test
  void theRequestsBecomeTheirDomainRecords() {
    PromptLibraryRequest request =
        new PromptLibraryRequest("Vorlagen")
            .description("Beschreibung")
            .ownerType(AssetOwnerType.GROUP)
            .ownerId(GROUP)
            .visibility(AssetVisibility.ORGANIZATION)
            .listed(true);

    assertThat(PromptLibraryResponseMapper.toCreation(request))
        .isEqualTo(
            new PromptLibraryCreation(
                "Vorlagen",
                "Beschreibung",
                AssetOwnerType.GROUP,
                GROUP,
                AssetVisibility.ORGANIZATION,
                true));
    assertThat(
            PromptLibraryResponseMapper.toUpdate(
                new PromptLibraryUpdateRequest("Neu", AssetVisibility.PRIVATE, false)
                    .description("Text")))
        .isEqualTo(new PromptLibraryUpdate("Neu", "Text", AssetVisibility.PRIVATE, false));
  }

  @Test
  void aPromptRequestKeepsEveryVariableFieldAndDefaultsTheSortOrder() {
    PromptRequest request =
        new PromptRequest("anhoerung", "Anhörung", "Zu {{art}}")
            .description("Beschreibung")
            .variables(
                List.of(
                    new io.opaa.api.dto.PromptVariable(
                            "art", "Art", PromptVariableType.SELECT, false)
                        .defaultValue("schriftlich")
                        .options(List.of("schriftlich", "mündlich"))));

    assertThat(PromptLibraryResponseMapper.toContent(request))
        .isEqualTo(
            new PromptContent(
                "anhoerung",
                "Anhörung",
                "Beschreibung",
                "Zu {{art}}",
                List.of(
                    new PromptVariable(
                        "art",
                        "Art",
                        PromptVariableType.SELECT,
                        false,
                        "schriftlich",
                        List.of("schriftlich", "mündlich"))),
                0));
    assertThat(PromptLibraryResponseMapper.toContent(request.sortOrder(4)).sortOrder())
        .isEqualTo(4);
  }
}
