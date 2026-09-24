package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.dto.AvailablePrompt;
import io.opaa.api.types.PromptVariableType;
import io.opaa.prompt.Prompt;
import io.opaa.prompt.PromptLibrary;
import io.opaa.prompt.PromptVariable;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pins every field {@link AvailablePromptResponseMapper} copies. */
class AvailablePromptResponseMapperTest {

  @Test
  void copiesEveryFieldAndDerivesWhetherTheFormAsks() {
    UUID promptId = UUID.randomUUID();
    UUID libraryId = UUID.randomUUID();
    Prompt prompt = mock(Prompt.class);
    when(prompt.getId()).thenReturn(promptId);
    when(prompt.getName()).thenReturn("zusammenfassung");
    when(prompt.getTitle()).thenReturn("Zusammenfassung");
    when(prompt.getDescription()).thenReturn("Stand zu einem Stichtag");
    when(prompt.getVariables())
        .thenReturn(
            List.of(
                new PromptVariable(
                    "stichtag", "Stichtag", PromptVariableType.DATE, true, null, List.of())));
    PromptLibrary library = mock(PromptLibrary.class);
    when(library.getId()).thenReturn(libraryId);
    when(library.getName()).thenReturn("Formulierungshilfen");

    AvailablePrompt response =
        AvailablePromptResponseMapper.toResponse(
            new io.opaa.prompt.AvailablePrompt(prompt, library, true));

    assertThat(response.getId()).isEqualTo(promptId);
    assertThat(response.getLibraryId()).isEqualTo(libraryId);
    assertThat(response.getLibraryName()).isEqualTo("Formulierungshilfen");
    assertThat(response.getName()).isEqualTo("zusammenfassung");
    assertThat(response.getTitle()).isEqualTo("Zusammenfassung");
    assertThat(response.getDescription()).isEqualTo("Stand zu einem Stichtag");
    assertThat(response.getHasVariables()).isTrue();
    assertThat(response.getAssociatedWithSpace()).isTrue();
  }

  @Test
  void aPromptWithoutVariablesIsInsertedWithoutAForm() {
    Prompt prompt = mock(Prompt.class);
    when(prompt.getVariables()).thenReturn(List.of());
    PromptLibrary library = mock(PromptLibrary.class);

    AvailablePrompt response =
        AvailablePromptResponseMapper.toResponse(
            new io.opaa.prompt.AvailablePrompt(prompt, library, false));

    assertThat(response.getHasVariables()).isFalse();
    assertThat(response.getAssociatedWithSpace()).isFalse();
  }
}
