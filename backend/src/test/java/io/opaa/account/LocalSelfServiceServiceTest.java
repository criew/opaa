package io.opaa.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.account.LocalActionTokenService.IssuedActionToken;
import io.opaa.account.LocalAuthSettings.Values;
import io.opaa.account.LocalSelfServiceAccountService.Registered;
import io.opaa.account.LocalSelfServiceAccountService.ResetLink;
import io.opaa.auth.OidcProviderRegistry;
import io.opaa.auth.User;
import io.opaa.common.ConflictException;
import io.opaa.common.FieldValidationException;
import io.opaa.common.PublicBaseUrl;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The anti-enumeration mechanics of {@link LocalSelfServiceService} (ADR-0033, Entscheidungen 9 and
 * 11) in isolation: after the field checks, "forgot password" and a registration do nothing on the
 * caller's thread but hand one task to the executor and answer after the response floor - a
 * registration carries no password, the verification link sets it; the task treats a taken address
 * (found or lost in a race) as silently no account, and the link mail is queued by the account
 * service in the transaction that issues the link; the availability of the two flows is the
 * conjunction of switch, setting, domain list and public base URL, and a switch that cannot be read
 * at all means "not served".
 */
class LocalSelfServiceServiceTest {

  private static final String PASSWORD = "sicheres-passwort-2026";
  private static final long FLOOR = LocalSelfServiceService.RESPONSE_FLOOR.toMillis();
  private static final long EPSILON = 50;
  private static final long CEILING = 4 * FLOOR;

  private final LocalSelfServiceAccountService accounts =
      mock(LocalSelfServiceAccountService.class);
  private final LocalPasswordService passwords = mock(LocalPasswordService.class);
  private final LocalAuthSettingsRepository settings = mock(LocalAuthSettingsRepository.class);
  private final OidcProviderRegistry registry = mock(OidcProviderRegistry.class);
  private final PublicBaseUrl publicBaseUrl = mock(PublicBaseUrl.class);
  private final List<Runnable> queued = new ArrayList<>();
  private final Executor executor = queued::add;
  private LocalSelfServiceService service;

  @BeforeEach
  void setUp() {
    when(registry.localAccountsEnabled()).thenReturn(true);
    when(publicBaseUrl.isConfigured()).thenReturn(true);
    settings(new Values(true, List.of("stadt.example"), true, 12, 72, 30, 90, 90, null, null));
    service =
        new LocalSelfServiceService(
            accounts, passwords, settings, registry, publicBaseUrl, executor);
  }

  @Test
  void theAvailabilityIsTheConjunctionOfSwitchSettingDomainListAndBaseUrl() {
    assertThat(service.isPasswordResetAvailable()).isTrue();
    assertThat(service.isSelfRegistrationAvailable()).isTrue();

    settings(new Values(true, List.of(), true, 12, 72, 30, 90, 90, null, null));
    assertThat(service.isSelfRegistrationAvailable()).isFalse();
    settings(new Values(false, List.of("stadt.example"), false, 12, 72, 30, 90, 90, null, null));
    assertThat(service.isSelfRegistrationAvailable()).isFalse();
    assertThat(service.isPasswordResetAvailable()).isFalse();

    settings(new Values(true, List.of("stadt.example"), true, 12, 72, 30, 90, 90, null, null));
    when(publicBaseUrl.isConfigured()).thenReturn(false);
    assertThat(service.isPasswordResetAvailable()).isFalse();
    assertThat(service.isSelfRegistrationAvailable()).isFalse();
    when(publicBaseUrl.isConfigured()).thenReturn(true);
    when(registry.localAccountsEnabled()).thenReturn(false);
    assertThat(service.isPasswordResetAvailable()).isFalse();
    assertThat(service.isSelfRegistrationAvailable()).isFalse();
  }

  /**
   * The authorization rule and the rate limiter ask these two before any handler runs. An exception
   * escaping there would answer 500 where an unknown route answers 401 - so an unreadable switch
   * means "not served", not "blew up".
   */
  @Test
  void aSwitchThatCannotBeReadMeansTheFlowIsNotServed() {
    when(settings.findSingleton()).thenThrow(new DataAccessResourceFailureException("no database"));

    assertThat(service.isPasswordResetAvailable()).isFalse();
    assertThat(service.isSelfRegistrationAvailable()).isFalse();
  }

  @Test
  void forgotPasswordAnswersAfterTheFloorAndDoesItsWorkOnTheExecutor() {
    when(accounts.issueResetLinkFor("niemand@stadt.example")).thenReturn(Optional.empty());
    User user = User.localAccount("erika@stadt.example", "Erika");
    IssuedActionToken token = new IssuedActionToken("roh", Instant.now().plusSeconds(60));
    when(accounts.issueResetLinkFor("erika@stadt.example"))
        .thenReturn(Optional.of(new ResetLink(user, token)));

    long unknown = millis(() -> service.requestPasswordReset("niemand@stadt.example"));
    long known = millis(() -> service.requestPasswordReset("erika@stadt.example"));

    assertThat(unknown).isBetween(FLOOR, CEILING);
    assertThat(known).isBetween(FLOOR, CEILING);
    assertThat(Math.abs(known - unknown)).isLessThanOrEqualTo(EPSILON);
    // nothing happened on the caller's thread: one task each, the lookup inside it
    verifyNoInteractions(accounts);
    assertThat(queued).hasSize(2);
    queued.forEach(Runnable::run);
    verify(accounts).issueResetLinkFor("niemand@stadt.example");
    verify(accounts).issueResetLinkFor("erika@stadt.example");
  }

  @Test
  void aRegistrationCreatesNothingForAForeignDomainOrATakenAddress() {
    when(accounts.register(eq("belegt@stadt.example")))
        .thenThrow(new ConflictException("belegt", LocalUserService.EMAIL_TAKEN));
    when(accounts.register(eq("verloren@stadt.example")))
        .thenThrow(new DataIntegrityViolationException("ux_users_local_email"));

    long foreign = millis(() -> service.register("wer@anderswo.example"));
    long taken = millis(() -> service.register("belegt@stadt.example"));
    long lost = millis(() -> service.register("verloren@stadt.example"));

    for (long ms : List.of(foreign, taken, lost)) {
      assertThat(ms).isBetween(FLOOR, CEILING);
    }
    assertThat(Math.max(foreign, Math.max(taken, lost)) - Math.min(foreign, Math.min(taken, lost)))
        .isLessThanOrEqualTo(EPSILON);
    // nothing happens on the caller's thread; everything waits in the queue
    verifyNoInteractions(accounts);
    assertThat(queued).hasSize(3);
    queued.forEach(Runnable::run);
    verify(accounts, never()).register(eq("wer@anderswo.example"));
    verify(accounts).register("belegt@stadt.example");
    verify(accounts).register("verloren@stadt.example");
  }

  @Test
  void aRegistrationOfAFreeAddressCreatesTheAccountWithoutAPassword() {
    User user = User.localAccount("neu@stadt.example", "Neu");
    IssuedActionToken token =
        new IssuedActionToken("roh", Instant.now().plus(Duration.ofHours(24)));
    when(accounts.register("neu@stadt.example")).thenReturn(new Registered(user, null, token));

    service.register("neu@stadt.example");

    verifyNoInteractions(accounts);
    assertThat(queued).hasSize(1);
    queued.getFirst().run();
    verify(accounts).register("neu@stadt.example");
  }

  @Test
  void aRegistrationRefusesBadInputWithFieldErrorsBeforeAnyCost() {
    assertThatThrownBy(() -> service.register("keine-adresse"))
        .isInstanceOf(FieldValidationException.class)
        .satisfies(e -> assertThat(fields(e)).containsExactly("email"));
    assertThatThrownBy(() -> service.requestPasswordReset("keine-adresse"))
        .isInstanceOf(FieldValidationException.class)
        .satisfies(e -> assertThat(fields(e)).containsExactly("email"));

    assertThat(queued).isEmpty();
    verifyNoInteractions(accounts);
  }

  @Test
  void theLinkEndpointsDelegateToTheTransactionalServices() {
    service.setPassword("roh", PASSWORD);
    service.confirmRegistration("roh", "Erika", PASSWORD);

    verify(passwords).setPasswordByLink("roh", PASSWORD);
    verify(passwords).confirmRegistration("roh", "Erika", PASSWORD);
    assertThat(queued).isEmpty();
  }

  private static List<String> fields(Throwable e) {
    return ((FieldValidationException) e)
        .fieldErrors().stream().map(FieldValidationException.FieldError::field).toList();
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
