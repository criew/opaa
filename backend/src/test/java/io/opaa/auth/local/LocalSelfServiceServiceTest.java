package io.opaa.auth.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.auth.User;
import io.opaa.auth.local.LocalActionTokenService.IssuedActionToken;
import io.opaa.auth.local.LocalAuthSettings.Values;
import io.opaa.auth.local.LocalSelfServiceAccountService.Registered;
import io.opaa.auth.local.LocalSelfServiceAccountService.ResetLink;
import io.opaa.auth.oidc.OidcProviderRegistry;
import io.opaa.common.ConflictException;
import io.opaa.common.FieldValidationException;
import io.opaa.common.PublicBaseUrl;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The anti-enumeration mechanics of {@link LocalSelfServiceService} (ADR-0033, Entscheidungen 9 and
 * 11) in isolation: every answer of "forgot password" and of a registration takes at least the
 * response floor, a registration pays the hashing cost whether or not the address is free or its
 * domain allowed, a taken address (found or lost in a race) is silently no account, mail leaves on
 * the executor and never on the caller's thread, and the availability of the two flows is the
 * conjunction of switch, setting, domain list and public base URL.
 */
class LocalSelfServiceServiceTest {

  private static final String PASSWORD = "sicheres-passwort-2026";
  private static final String HASH = "{bcrypt}hash";

  private final LocalSelfServiceAccountService accounts =
      mock(LocalSelfServiceAccountService.class);
  private final LocalPasswordService passwords = mock(LocalPasswordService.class);
  private final LocalAccountMailer mailer = mock(LocalAccountMailer.class);
  private final LocalAuthSettingsRepository settings = mock(LocalAuthSettingsRepository.class);
  private final OidcProviderRegistry registry = mock(OidcProviderRegistry.class);
  private final PublicBaseUrl publicBaseUrl = mock(PublicBaseUrl.class);
  private final PasswordEncoder encoder = mock(PasswordEncoder.class);
  private final List<Runnable> queued = new java.util.ArrayList<>();
  private final Executor executor = queued::add;
  private LocalSelfServiceService service;

  @BeforeEach
  void setUp() {
    when(encoder.encode(anyString())).thenReturn(HASH);
    when(registry.localAccountsEnabled()).thenReturn(true);
    when(publicBaseUrl.isConfigured()).thenReturn(true);
    settings(new Values(true, List.of("stadt.example"), true, 12, 72, 30, 90, 90));
    service =
        new LocalSelfServiceService(
            accounts,
            passwords,
            mailer,
            settings,
            registry,
            publicBaseUrl,
            encoder,
            new PasswordPolicy(() -> 12),
            executor);
  }

  @Test
  void theAvailabilityIsTheConjunctionOfSwitchSettingDomainListAndBaseUrl() {
    assertThat(service.isPasswordResetAvailable()).isTrue();
    assertThat(service.isSelfRegistrationAvailable()).isTrue();

    settings(new Values(true, List.of(), true, 12, 72, 30, 90, 90));
    assertThat(service.isSelfRegistrationAvailable()).isFalse();
    settings(new Values(false, List.of("stadt.example"), false, 12, 72, 30, 90, 90));
    assertThat(service.isSelfRegistrationAvailable()).isFalse();
    assertThat(service.isPasswordResetAvailable()).isFalse();

    settings(new Values(true, List.of("stadt.example"), true, 12, 72, 30, 90, 90));
    when(publicBaseUrl.isConfigured()).thenReturn(false);
    assertThat(service.isPasswordResetAvailable()).isFalse();
    assertThat(service.isSelfRegistrationAvailable()).isFalse();
    when(publicBaseUrl.isConfigured()).thenReturn(true);
    when(registry.localAccountsEnabled()).thenReturn(false);
    assertThat(service.isPasswordResetAvailable()).isFalse();
    assertThat(service.isSelfRegistrationAvailable()).isFalse();
  }

  @Test
  void forgotPasswordTakesAtLeastTheFloorWhetherOrNotAMailLeaves() {
    when(accounts.issueResetLinkFor("niemand@stadt.example")).thenReturn(Optional.empty());
    User user = User.localAccount("erika@stadt.example", "Erika");
    IssuedActionToken token = new IssuedActionToken("roh", Instant.now().plusSeconds(60));
    when(accounts.issueResetLinkFor("erika@stadt.example"))
        .thenReturn(Optional.of(new ResetLink(user, token)));

    long unknown = millis(() -> service.requestPasswordReset("niemand@stadt.example"));
    long known = millis(() -> service.requestPasswordReset("erika@stadt.example"));

    assertThat(unknown).isGreaterThanOrEqualTo(LocalSelfServiceService.RESPONSE_FLOOR.toMillis());
    assertThat(known).isGreaterThanOrEqualTo(LocalSelfServiceService.RESPONSE_FLOOR.toMillis());
    // the mail is queued, not sent on the caller's thread
    verifyNoInteractions(mailer);
    assertThat(queued).hasSize(1);
    queued.getFirst().run();
    verify(mailer).sendPasswordReset(user, token);
  }

  @Test
  void aRegistrationHashesFirstAndCreatesNothingForAForeignDomainOrATakenAddress() {
    when(accounts.register(eq("belegt@stadt.example"), anyString(), anyString()))
        .thenThrow(new ConflictException("belegt", LocalUserService.EMAIL_TAKEN));
    when(accounts.register(eq("verloren@stadt.example"), anyString(), anyString()))
        .thenThrow(new DataIntegrityViolationException("ux_users_local_email"));

    long foreign = millis(() -> service.register("wer@anderswo.example", "Wer", PASSWORD));
    long taken = millis(() -> service.register("belegt@stadt.example", "Wer", PASSWORD));
    long lost = millis(() -> service.register("verloren@stadt.example", "Wer", PASSWORD));

    verify(encoder, org.mockito.Mockito.times(3)).encode(PASSWORD);
    verify(accounts, never()).register(eq("wer@anderswo.example"), anyString(), anyString());
    assertThat(queued).isEmpty();
    verifyNoInteractions(mailer);
    for (long ms : List.of(foreign, taken, lost)) {
      assertThat(ms).isGreaterThanOrEqualTo(LocalSelfServiceService.RESPONSE_FLOOR.toMillis());
    }
  }

  @Test
  void aRegistrationOfAFreeAddressCreatesTheAccountWithTheHashAndQueuesTheMail() {
    User user = User.localAccount("neu@stadt.example", "Neu");
    IssuedActionToken token =
        new IssuedActionToken("roh", Instant.now().plus(Duration.ofHours(24)));
    when(accounts.register("neu@stadt.example", "Neu", HASH))
        .thenReturn(new Registered(user, null, token));

    service.register("neu@stadt.example", "Neu", PASSWORD);

    verify(accounts).register("neu@stadt.example", "Neu", HASH);
    verifyNoInteractions(mailer);
    assertThat(queued).hasSize(1);
    queued.getFirst().run();
    verify(mailer).sendRegistrationVerification(user, token);
  }

  @Test
  void aRegistrationRefusesBadInputWithFieldErrorsBeforeAnyCost() {
    assertThatThrownBy(() -> service.register("keine-adresse", "Wer", PASSWORD))
        .isInstanceOf(FieldValidationException.class)
        .satisfies(
            e ->
                assertThat(((FieldValidationException) e).fieldErrors())
                    .extracting(FieldValidationException.FieldError::field)
                    .containsExactly("email"));
    assertThatThrownBy(() -> service.register("wer@stadt.example", " ", PASSWORD))
        .isInstanceOf(FieldValidationException.class)
        .satisfies(
            e ->
                assertThat(((FieldValidationException) e).fieldErrors())
                    .extracting(FieldValidationException.FieldError::field)
                    .containsExactly("displayName"));
    assertThatThrownBy(() -> service.register("wer@stadt.example", "Wer", "kurz"))
        .isInstanceOf(FieldValidationException.class)
        .satisfies(
            e ->
                assertThat(((FieldValidationException) e).fieldErrors())
                    .extracting(FieldValidationException.FieldError::field)
                    .containsExactly("password"));

    verify(encoder, never()).encode(anyString());
    verifyNoInteractions(accounts);
  }

  @Test
  void theLinkEndpointsDelegateToTheTransactionalServices() {
    service.setPassword("roh", PASSWORD);
    service.verifyEmail("roh");

    verify(passwords).setPasswordByLink("roh", PASSWORD);
    verify(accounts).verifyEmail("roh");
    verifyNoInteractions(mailer);
  }

  private void settings(Values values) {
    LocalAuthSettings row = new LocalAuthSettings();
    row.replace(values, null, Instant.now());
    when(settings.findSingleton()).thenReturn(Optional.of(row));
  }

  private static long millis(Runnable action) {
    long start = System.nanoTime();
    action.run();
    return (System.nanoTime() - start) / 1_000_000;
  }
}
