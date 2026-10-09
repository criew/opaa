package io.opaa.connection.oauth;

import io.opaa.connection.profile.ClientRegistration;
import io.opaa.connection.profile.ProfileRegistrations;
import io.opaa.connection.token.SecretIssuer;
import io.opaa.indexing.source.OAuthAuth;
import io.opaa.indexing.source.Revocation;
import io.opaa.indexing.source.Secret;
import io.opaa.indexing.source.SignInRejectedException;
import io.opaa.indexing.source.SourceCredentialsException;
import io.opaa.security.TargetAddressValidator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The token store's way to the providers: renewal and revocation of an OAuth grant through {@link
 * OAuthClient} with the profile's registration, a profile's own sign-in through {@link
 * ProfileSignIn}.
 */
@Component
public class ProviderTokens implements SecretIssuer {

  private static final Logger log = LoggerFactory.getLogger(ProviderTokens.class);

  /** How long an access token must still be valid to be sent as bearer for its revocation. */
  static final Duration BEARER_RENEWAL_MARGIN = Duration.ofMinutes(5);

  private final ProfileRegistrations registrations;
  private final ProfileSignIn profileSignIn;
  private final OAuthClient client;
  private final Clock clock;

  public ProviderTokens(
      ProfileRegistrations registrations,
      ProfileSignIn profileSignIn,
      TargetAddressValidator targetAddressValidator,
      Clock clock) {
    this.registrations = registrations;
    this.profileSignIn = profileSignIn;
    this.client = new OAuthClient(targetAddressValidator);
    this.clock = clock;
  }

  /**
   * {@inheritDoc} A grant issued for another resource than the profile names now is refused as no
   * longer taken: renewing it would carry a token of one server to another.
   */
  @Override
  public Issued renew(UUID profileId, String refreshToken, String issuedFor) {
    ClientRegistration registration = registrations.registrationOf(profileId);
    if (registration.resource() != null && !registration.resource().equals(issuedFor)) {
      throw new SignInRejectedException(
          "Die Zustimmung gilt für einen anderen MCP-Server; das Konto muss neu verbunden werden.");
    }
    OAuthClient.Grant grant =
        client.refresh(registration, oauthOf(registration), refreshToken, clock.instant());
    return new Issued(
        grant.accessToken(),
        grant.accessTokenExpiresAt(),
        grant.refreshToken(),
        grant.refreshTokenExpiresAt());
  }

  @Override
  public Runnable revocation(UUID profileId, StoredTokens tokens) {
    ClientRegistration registration;
    try {
      registration = registrations.registrationOf(profileId);
    } catch (RuntimeException e) {
      log.warn("No revocation for a token of profile {}: its registration is gone", profileId);
      return null;
    }
    if (!(registration.signIn() instanceof OAuthAuth auth)
        || auth.revocation().endpoint() == null) {
      return null;
    }
    if (auth.revocation() instanceof Revocation.BearerPost) {
      return () -> {
        String access = bearerFor(registration, auth, tokens);
        if (access != null) {
          client.revoke(registration, auth, tokens.refreshToken(), access);
        }
      };
    }
    return () -> client.revoke(registration, auth, tokens.refreshToken(), tokens.accessToken());
  }

  /**
   * The access token a bearer revocation of {@code tokens} sends: the stored one while it is valid
   * beyond {@link #BEARER_RENEWAL_MARGIN}, else a renewed one, so the revocation reaches the
   * refresh token behind it; {@code null} where the provider no longer renews the grant, which then
   * has nothing left to revoke. Asked when the revocation runs, never within a transaction.
   */
  private String bearerFor(ClientRegistration registration, OAuthAuth auth, StoredTokens tokens) {
    Instant now = clock.instant();
    Instant expiresAt = tokens.accessTokenExpiresAt();
    boolean valid =
        tokens.accessToken() != null
            && expiresAt != null
            && expiresAt.isAfter(now.plus(BEARER_RENEWAL_MARGIN));
    if (valid || tokens.refreshToken() == null) {
      return tokens.accessToken();
    }
    try {
      return client.refresh(registration, auth, tokens.refreshToken(), now).accessToken();
    } catch (SignInRejectedException e) {
      log.info(
          "No revocation for a token of profile {}: the provider no longer renews it",
          registration.profileId());
      return null;
    } catch (RuntimeException e) {
      log.warn(
          "A token of profile {} could not be renewed before its revocation ({})",
          registration.profileId(),
          e.getClass().getSimpleName());
      return tokens.accessToken();
    }
  }

  @Override
  public Secret mint(UUID profileId) {
    return profileSignIn.mint(profileId);
  }

  @Override
  public void forgetMinted(UUID profileId) {
    profileSignIn.forgetMinted(profileId);
  }

  /** The declared OAuth sign-in of {@code registration}. */
  static OAuthAuth oauthOf(ClientRegistration registration) {
    if (registration.signIn() instanceof OAuthAuth auth) {
      return auth;
    }
    throw new SourceCredentialsException(
        "Der Zugang meldet sich nicht über OAuth an. Zuständig ist die Systemverwaltung.");
  }
}
