package io.opaa.connection.oauth;

import io.opaa.connection.profile.ClientRegistration;
import io.opaa.connection.profile.ProfileRegistrations;
import io.opaa.connection.token.SecretIssuer;
import io.opaa.indexing.source.OAuthAuth;
import io.opaa.indexing.source.Secret;
import io.opaa.indexing.source.SourceCredentialsException;
import io.opaa.security.TargetAddressValidator;
import java.time.Clock;
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

  @Override
  public Issued renew(UUID profileId, String refreshToken, String issuedFor) {
    ClientRegistration registration = registrations.registrationOf(profileId);
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
    return () -> client.revoke(registration, auth, tokens.refreshToken(), tokens.accessToken());
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
