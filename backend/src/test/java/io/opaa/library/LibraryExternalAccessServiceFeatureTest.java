package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.opaa.asset.OwnerOnlyRule;
import io.opaa.auth.UserRepository;
import io.opaa.common.Feature;
import io.opaa.common.TestFeatureFlags;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.LibraryAccessService;
import io.opaa.permission.SuccessionReachGuard;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * With the external-access feature switched off (ADR-0044) the library detail names no
 * Fremdzugangsfreigabe: {@link LibraryExternalAccessService#describe} has nothing to describe and
 * reads nothing, while the stored release stays as it is.
 */
class LibraryExternalAccessServiceFeatureTest {

  @Test
  void aSwitchedOffFeatureDescribesNoRelease() {
    LibraryExternalAccessTokenCounter tokenCounter = mock(LibraryExternalAccessTokenCounter.class);
    UserRepository users = mock(UserRepository.class);
    LibraryExternalAccessService service =
        new LibraryExternalAccessService(
            mock(KnowledgeLibraryRepository.class),
            mock(LibraryAccessService.class),
            users,
            mock(ApplicationEventPublisher.class),
            new ExternalAccessProperties(365, 14),
            tokenCounter,
            mock(SuccessionReachGuard.class),
            mock(OwnerOnlyRule.class),
            () -> Instant.parse("2026-10-14T08:00:00Z"),
            TestFeatureFlags.without(Feature.EXTERNAL_ACCESS));

    assertThat(service.describe(mock(KnowledgeLibrary.class))).isNull();
    verifyNoInteractions(tokenCounter, users);
  }
}
