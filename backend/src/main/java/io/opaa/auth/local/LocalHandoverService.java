package io.opaa.auth.local;

import com.nimbusds.jwt.JWTParser;
import io.opaa.auth.local.LocalHandoverAccountService.HandedOver;
import io.opaa.auth.local.LocalHandoverAccountService.HandoverPreview;
import io.opaa.auth.oidc.OidcIssuerUris;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRegistry;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.common.ConflictException;
import io.opaa.common.UnauthorizedException;
import java.text.ParseException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.stereotype.Service;

/**
 * The handover as the API sees it (ADR-0033, Entscheidung 12): the preview the person reads before
 * the irreversible step, and the redemption - provider token verified here, the account write one
 * transaction in {@link LocalHandoverAccountService}, the confirmation mail after its commit.
 *
 * <p><b>Why the provider token travels in the body.</b> {@code UserProvisioningFilter} runs behind
 * {@code BearerTokenAuthenticationFilter} for every request with a {@code Jwt} principal and
 * <em>creates</em> an account for an unknown {@code (issuer, subject)}. A provider token in the
 * {@code Authorization} header would therefore create the very account this call must find absent,
 * before any controller runs. The endpoint is {@code permitAll} and does the verification itself -
 * through {@link OidcProviderRegistry}, so it is the same decoder with the same signature, issuer,
 * expiry and {@code azp} validation the resource server applies to any other token of that
 * provider. The subject comes from that verified token and from nowhere else.
 *
 * <p>Deliberately not {@code @Transactional}: the verification may have to fetch a provider's JWK
 * set, and the send afterwards runs into SMTP timeouts - neither may hold a database connection.
 */
@Service
public class LocalHandoverService {

  static final String PROVIDER_TOKEN_INVALID =
      "Die Anmeldung beim Identitätsanbieter konnte nicht geprüft werden. Bitte melden Sie sich"
          + " erneut an und öffnen Sie den Link aus der E-Mail noch einmal.";

  private final LocalHandoverAccountService accounts;
  private final LocalActionTokenService actionTokens;
  private final OidcProviderRepository providers;
  private final OidcProviderRegistry registry;
  private final LocalAccountMailer mailer;

  public LocalHandoverService(
      LocalHandoverAccountService accounts,
      LocalActionTokenService actionTokens,
      OidcProviderRepository providers,
      OidcProviderRegistry registry,
      LocalAccountMailer mailer) {
    this.accounts = accounts;
    this.actionTokens = actionTokens;
    this.providers = providers;
    this.registry = registry;
    this.mailer = mailer;
  }

  /** What the code stands for; it stays redeemable. */
  public HandoverPreview preview(String rawToken) {
    return accounts.preview(rawToken);
  }

  /**
   * Redeems the handover. The code decides which provider is asked - never the caller - so a person
   * following the link cannot end up bound to an identity of some other provider.
   */
  public HandedOver redeem(String rawToken, String rawProviderToken) {
    LocalActionToken token =
        actionTokens
            .findRedeemable(rawToken, ActionTokenPurpose.HANDOVER)
            .orElseThrow(LocalActionTokenService::invalidToken);
    OidcProvider provider =
        providers
            .findById(token.getProviderId())
            .orElseThrow(LocalActionTokenService::invalidToken);
    Jwt verified = verifyProviderToken(rawProviderToken, provider);
    String subject = verified.getSubject();
    if (subject == null || subject.isBlank()) {
      throw new UnauthorizedException(PROVIDER_TOKEN_INVALID);
    }
    HandedOver handedOver = accounts.redeem(rawToken, provider, subject);
    mailer.sendHandedOver(handedOver.user());
    return handedOver;
  }

  /**
   * The provider's own verification, plus one check the decoder cannot make: that the token was
   * issued by the provider this handover names. Reading the unverified {@code iss} for that is safe
   * - it only decides which refusal the person reads; the verification below is what accepts
   * anything.
   */
  private Jwt verifyProviderToken(String rawProviderToken, OidcProvider provider) {
    String presented = unverifiedIssuerOf(rawProviderToken);
    if (!OidcIssuerUris.normalize(provider.getIssuerUri())
        .equals(OidcIssuerUris.normalize(presented))) {
      throw new ConflictException(
          LocalHandoverAccountService.PROVIDER_MISMATCH_MESSAGE,
          LocalHandoverAccountService.PROVIDER_MISMATCH);
    }
    AuthenticationManager manager = registry.resolve(provider.getIssuerUri());
    try {
      Authentication authenticated =
          manager.authenticate(new BearerTokenAuthenticationToken(rawProviderToken));
      if (authenticated.getPrincipal() instanceof Jwt jwt) {
        return jwt;
      }
    } catch (AuthenticationException refused) {
      throw new UnauthorizedException(PROVIDER_TOKEN_INVALID);
    }
    throw new UnauthorizedException(PROVIDER_TOKEN_INVALID);
  }

  private static String unverifiedIssuerOf(String rawProviderToken) {
    try {
      String issuer = JWTParser.parse(rawProviderToken).getJWTClaimsSet().getIssuer();
      if (issuer == null || issuer.isBlank()) {
        throw new UnauthorizedException(PROVIDER_TOKEN_INVALID);
      }
      return issuer;
    } catch (ParseException notAToken) {
      throw new UnauthorizedException(PROVIDER_TOKEN_INVALID);
    }
  }
}
