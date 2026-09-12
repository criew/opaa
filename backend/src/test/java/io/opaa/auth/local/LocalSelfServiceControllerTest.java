package io.opaa.auth.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.api.dto.LocalForgotPasswordRequest;
import io.opaa.api.dto.LocalRegisterRequest;
import io.opaa.api.dto.LocalSetPasswordRequest;
import io.opaa.api.dto.LocalVerifyEmailRequest;
import io.opaa.auth.local.LocalAuthRateLimiter.AddressScope;
import io.opaa.common.TooManyRequestsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The order of the guards in {@link LocalSelfServiceController} (ADR-0033, Entscheidungen 9 and
 * 11): a switched-off flow is refused as an unknown route before the address is counted or touched,
 * an available flow spends the address's budget before anything is done with the address, and a
 * spent budget stops the request. The flows themselves are proved by the integration tests.
 */
class LocalSelfServiceControllerTest {

  private final LocalSelfServiceService service = mock(LocalSelfServiceService.class);
  private final LocalAuthRateLimiter rateLimiter = mock(LocalAuthRateLimiter.class);
  private final LocalSelfServiceController controller =
      new LocalSelfServiceController(service, rateLimiter);

  @BeforeEach
  void setUp() {
    when(service.isPasswordResetAvailable()).thenReturn(true);
    when(service.isSelfRegistrationAvailable()).thenReturn(true);
  }

  @Test
  void aSwitchedOffFlowIsAnUnknownRouteBeforeTheAddressIsCounted() {
    when(service.isPasswordResetAvailable()).thenReturn(false);
    when(service.isSelfRegistrationAvailable()).thenReturn(false);

    assertThatThrownBy(
            () -> controller.forgotPassword(new LocalForgotPasswordRequest("erika@stadt.example")))
        .isInstanceOf(NoResourceFoundException.class);
    assertThatThrownBy(
            () ->
                controller.register(
                    new LocalRegisterRequest("erika@stadt.example", "Erika", "passwort-2026-x")))
        .isInstanceOf(NoResourceFoundException.class);

    verifyNoInteractions(rateLimiter);
    verify(service, never()).requestPasswordReset(anyString());
    verify(service, never()).register(anyString(), anyString(), anyString());
  }

  @Test
  void theAddressBudgetIsSpentBeforeTheAddressIsProcessed() throws Exception {
    assertThat(
            controller
                .forgotPassword(new LocalForgotPasswordRequest("erika@stadt.example"))
                .getStatusCode()
                .value())
        .isEqualTo(204);
    assertThat(
            controller
                .register(new LocalRegisterRequest("erika@stadt.example", "Erika", "passwort-x"))
                .getStatusCode()
                .value())
        .isEqualTo(202);

    InOrder order = inOrder(rateLimiter, service);
    order
        .verify(rateLimiter)
        .requireAddressAllowance(AddressScope.FORGOT_PASSWORD, "erika@stadt.example");
    order.verify(service).requestPasswordReset("erika@stadt.example");
    order.verify(rateLimiter).requireAddressAllowance(AddressScope.REGISTER, "erika@stadt.example");
    order.verify(service).register("erika@stadt.example", "Erika", "passwort-x");
  }

  @Test
  void aSpentBudgetStopsTheRequestBeforeTheService() {
    doThrow(new TooManyRequestsException(42))
        .when(rateLimiter)
        .requireAddressAllowance(any(), anyString());

    assertThatThrownBy(
            () -> controller.forgotPassword(new LocalForgotPasswordRequest("erika@stadt.example")))
        .isInstanceOf(TooManyRequestsException.class);
    assertThatThrownBy(
            () ->
                controller.register(
                    new LocalRegisterRequest("erika@stadt.example", "Erika", "passwort-x")))
        .isInstanceOf(TooManyRequestsException.class);

    verify(service, never()).requestPasswordReset(anyString());
    verify(service, never()).register(anyString(), anyString(), anyString());
  }

  @Test
  void theLinkEndpointsNeedNoSwitchAndNoAddressBudget() {
    assertThat(
            controller
                .setPassword(new LocalSetPasswordRequest("roh-token", "neues-passwort-2026"))
                .getStatusCode()
                .value())
        .isEqualTo(204);
    assertThat(
            controller
                .verifyEmail(new LocalVerifyEmailRequest("roh-token"))
                .getStatusCode()
                .value())
        .isEqualTo(204);

    verify(service).setPassword("roh-token", "neues-passwort-2026");
    verify(service).verifyEmail("roh-token");
    verifyNoInteractions(rateLimiter);
  }
}
