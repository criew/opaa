package io.opaa.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.common.NotFoundException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The organization boundary of {@link KnowledgeLibraryRepository#requireInOrganization}: the lookup
 * asks for the caller's organization only, so a library of another one is as absent as a missing
 * one, with the same message.
 */
class KnowledgeLibraryRepositoryTest {

  private final KnowledgeLibraryRepository repository =
      mock(KnowledgeLibraryRepository.class, CALLS_REAL_METHODS);
  private final UUID organizationId = UUID.randomUUID();
  private final KnowledgeLibrary library =
      KnowledgeLibrary.ownedByUser(organizationId, "Eigene", null, UUID.randomUUID());

  @Test
  void aLibraryOfTheCallersOrganizationIsReturned() {
    when(repository.findByIdAndOrganizationId(library.getId(), organizationId))
        .thenReturn(Optional.of(library));

    assertThat(repository.requireInOrganization(library.getId(), organizationId)).isSameAs(library);
  }

  @Test
  void aLibraryOfAnotherOrganizationIsNotFoundLikeAMissingOne() {
    when(repository.findByIdAndOrganizationId(library.getId(), organizationId))
        .thenReturn(Optional.of(library));
    UUID otherOrganizationId = UUID.randomUUID();

    assertThatThrownBy(() -> repository.requireInOrganization(library.getId(), otherOrganizationId))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Bibliothek nicht gefunden");
    assertThatThrownBy(() -> repository.requireInOrganization(UUID.randomUUID(), organizationId))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Bibliothek nicht gefunden");
  }
}
