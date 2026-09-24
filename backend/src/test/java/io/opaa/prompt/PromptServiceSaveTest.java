package io.opaa.prompt;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.SystemRole;
import io.opaa.asset.AssetAuthorization;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ConflictException;
import io.opaa.permission.AssetAccessService;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Only the violated unique name of a prompt reads as "name taken"; any other violated constraint -
 * a library deleted meanwhile, a check the application no longer anticipates - stays what it is.
 */
class PromptServiceSaveTest {

  private final PromptLibraryService libraryService = mock(PromptLibraryService.class);
  private final PromptRepository promptRepository = mock(PromptRepository.class);
  private final PromptService promptService =
      new PromptService(
          libraryService,
          promptRepository,
          mock(PromptLibraryRepository.class),
          mock(AssetAuthorization.class),
          mock(AssetAccessService.class),
          mock(AuditEventRecorder.class));

  private final UUID organization = UUID.randomUUID();
  private final CurrentUser caller =
      CurrentUser.of(UUID.randomUUID(), organization, SystemRole.USER, "Sachbearbeitung");
  private PromptLibrary library;

  @BeforeEach
  void setUp() {
    library = PromptLibrary.ownedByUser(organization, "Vorlagen", null, caller.id(), false);
    when(libraryService.load(library.getId(), caller)).thenReturn(library);
  }

  @Test
  void aViolatedUniqueNameIsAConflict() {
    when(promptRepository.saveAndFlush(any())).thenThrow(violation("uk_prompts_library_name"));

    assertThatThrownBy(() -> promptService.create(library.getId(), content(), caller))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("„vermerk“");
  }

  @Test
  void anyOtherViolatedConstraintIsRethrownUnchanged() {
    DataIntegrityViolationException foreignKey = violation("fk_prompts_library_organization");
    when(promptRepository.saveAndFlush(any())).thenThrow(foreignKey);

    assertThatThrownBy(() -> promptService.create(library.getId(), content(), caller))
        .isSameAs(foreignKey);
  }

  private static DataIntegrityViolationException violation(String constraint) {
    return new DataIntegrityViolationException(
        "could not execute statement",
        new ConstraintViolationException(
            "violation", new SQLException("violates " + constraint), constraint));
  }

  private static PromptContent content() {
    return new PromptContent("vermerk", "Vermerk", null, "Bitte formulieren.", List.of(), 0);
  }
}
