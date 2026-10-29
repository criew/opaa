package io.opaa.externalaccess.token;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.account.LocalTokenRevocationService;
import io.opaa.asset.AssetCatalogService;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ValidationException;
import io.opaa.externalaccess.ExternalAccessSettings;
import io.opaa.externalaccess.ExternalAccessSettingsService;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.LibraryAccessService;
import io.opaa.security.LocalAuthKeyService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Every issued access token is announced to its owner by mail - the trace a briefly taken-over
 * session leaves with the person - and a refused issuance announces nothing.
 */
class ExternalAccessTokenIssuanceNoticeTest {

  private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");

  private final ExternalAccessTokenRepository tokens = mock(ExternalAccessTokenRepository.class);
  private final LibraryAccessService libraryAccess = mock(LibraryAccessService.class);
  private final ExternalAccessLibraryRelease release = mock(ExternalAccessLibraryRelease.class);
  private final ExternalAccessSettingsService settings = mock(ExternalAccessSettingsService.class);
  private final LocalAuthKeyService keys = mock(LocalAuthKeyService.class);
  private final UserRepository users = mock(UserRepository.class);
  private final ExternalAccessTokenMailer mailer = mock(ExternalAccessTokenMailer.class);
  private final LocalTokenRevocationService sessions = mock(LocalTokenRevocationService.class);

  private final ExternalAccessTokenService service =
      new ExternalAccessTokenService(
          tokens,
          mock(KnowledgeLibraryRepository.class),
          libraryAccess,
          release,
          settings,
          mock(AssetCatalogService.class),
          keys,
          mock(AuditEventRecorder.class),
          users,
          mailer,
          sessions,
          Clock.fixed(NOW, ZoneOffset.UTC));

  private final User owner = new User("sub", "https://idp.example", "anna@amt.example", "Anna");
  private final UUID libraryId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    when(settings.isEnabled()).thenReturn(true);
    when(settings.current())
        .thenReturn(
            new ExternalAccessSettingsService.View(
                ExternalAccessSettings.Values.defaults(), 364, null, null));
    when(libraryAccess.readableLibraryIds(any(), any())).thenReturn(Set.of(libraryId));
    when(release.releasedAmong(any())).thenReturn(Set.of(libraryId));
    when(keys.lookupHash(any(), anyString())).thenReturn("hash");
    when(tokens.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(users.findById(owner.getId())).thenReturn(Optional.of(owner));
  }

  @Test
  void anIssuedTokenIsAnnouncedToItsOwnerByName() {
    service.issue(
        owner.getId(),
        UUID.randomUUID(),
        "  Claude Code  ",
        List.of(libraryId),
        NOW.plus(Duration.ofDays(10)));

    verify(mailer).sendIssued(owner, "Claude Code", NOW);
  }

  @Test
  void aSessionThatEndedMeanwhileIssuesNothing() {
    Jwt session = Jwt.withTokenValue("t").header("alg", "none").subject("s").build();
    when(sessions.sessionEndedSince(owner.getId(), session)).thenReturn(true);

    assertThatThrownBy(
            () ->
                service.issue(
                    owner.getId(),
                    UUID.randomUUID(),
                    "Claude Code",
                    List.of(libraryId),
                    NOW.plus(Duration.ofDays(10)),
                    session))
        .isInstanceOf(AccessDeniedException.class);

    verify(tokens, never()).save(any());
    verifyNoInteractions(mailer);
  }

  @Test
  void aRefusedIssuanceAnnouncesNothing() {
    assertThatThrownBy(
            () ->
                service.issue(
                    owner.getId(),
                    UUID.randomUUID(),
                    "Claude Code",
                    List.of(libraryId),
                    NOW.plus(Duration.ofDays(400))))
        .isInstanceOf(ValidationException.class);

    verifyNoInteractions(mailer);
  }
}
