package io.opaa.connection.oauth;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.connection.profile.ClientRegistration;
import io.opaa.connection.profile.ProfileRegistrations;
import io.opaa.connection.token.SecretIssuer;
import io.opaa.connection.token.SecretRefusedException;
import io.opaa.indexing.source.ClientCredentialsAuth;
import io.opaa.indexing.source.Secret;
import io.opaa.indexing.source.ServiceAccountKeyAuth;
import io.opaa.indexing.source.ServiceAccountTokens;
import io.opaa.indexing.source.SignInRejectedException;
import io.opaa.indexing.source.SourceBlock.Reason;
import io.opaa.indexing.source.SourceCredentialsException;
import io.opaa.security.TargetAddressValidator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * A profile's own sign-in (client credentials, service account key): the access token for its
 * registration, held in the process until shortly before it expires (ADR-0021). A service account
 * key is signed only in the core ({@link ServiceAccountTokens}). A rejected registration marks the
 * profile; from then on no ask reaches the provider until a new secret or {@link #test} lifts it.
 */
@Component
public class ProfileSignIn implements SecretIssuer {

  static final Duration RENEWAL_MARGIN = Duration.ofMinutes(5);

  private final ProfileRegistrations registrations;
  private final ServiceAccountTokens serviceAccountTokens;
  private final ClientCredentialsGrant grant;
  private final Clock clock;
  private final Map<UUID, Held> held = new ConcurrentHashMap<>();

  /** The token last handed out for a profile, for the registration it was obtained with. */
  private record Held(String registration, Secret secret, Instant renewAt) {}

  public ProfileSignIn(
      ProfileRegistrations registrations,
      ServiceAccountTokens serviceAccountTokens,
      TargetAddressValidator targetAddressValidator,
      Clock clock) {
    this.registrations = registrations;
    this.serviceAccountTokens = serviceAccountTokens;
    this.grant = new ClientCredentialsGrant(targetAddressValidator);
    this.clock = clock;
  }

  @Override
  public Secret mint(UUID profileId) {
    ClientRegistration registration = registrations.registrationOf(profileId);
    if (registration.signInRejected()) {
      throw new SecretRefusedException(Reason.EXPIRED);
    }
    if (registration.secret() == null || registration.signIn() == null) {
      throw new SecretRefusedException(Reason.NOT_CONNECTED);
    }
    try {
      return signIn(registration);
    } catch (SignInRejectedException e) {
      held.remove(profileId);
      registrations.rejected(profileId);
      throw new SecretRefusedException(Reason.EXPIRED);
    }
  }

  @Override
  public void forgetMinted(UUID profileId) {
    Held dropped = held.remove(profileId);
    if (dropped != null) {
      serviceAccountTokens.discard(dropped.secret());
    }
  }

  /**
   * Signs in anew with the registration of {@code profileId}, past every held token: a success
   * lifts a rejection, a rejection marks it.
   */
  public SignInTest test(UUID profileId) {
    ClientRegistration registration = registrations.registrationOf(profileId);
    if (registration.secret() == null || registration.signIn() == null) {
      return new SignInTest(
          false, "Für den Zugang ist kein Client-Secret bzw. Dienstkonto-Schlüssel hinterlegt.");
    }
    forgetMinted(profileId);
    try {
      signIn(registration);
    } catch (SignInRejectedException e) {
      held.remove(profileId);
      registrations.rejected(profileId);
      return new SignInTest(false, e.getMessage());
    } catch (SourceCredentialsException e) {
      return new SignInTest(false, e.getMessage());
    }
    registrations.accepted(profileId);
    return new SignInTest(true, "Anmeldung erfolgreich.");
  }

  @Override
  public Secret renew(UUID profileId, String refreshToken, String issuedFor) {
    throw new IllegalStateException("No OAuth renewal is available");
  }

  @Override
  public Runnable revocation(UUID profileId, String refreshToken) {
    return null;
  }

  private Secret signIn(ClientRegistration registration) {
    UUID profileId = registration.profileId();
    String fingerprint = fingerprint(registration);
    Instant now = clock.instant();
    Held current = held.get(profileId);
    if (registration.method() == ConnectionAuthMethod.CLIENT_CREDENTIALS
        && current != null
        && current.registration().equals(fingerprint)
        && now.isBefore(current.renewAt())) {
      return current.secret();
    }
    Secret secret =
        switch (registration.signIn()) {
          case ServiceAccountKeyAuth auth ->
              serviceAccountTokens.accessSecret(
                  registration.secret(), registration.subject(), auth, registration.proxy());
          case ClientCredentialsAuth auth -> grant.token(registration, auth, now);
          default ->
              throw new IllegalStateException(
                  "Sign-in " + registration.method() + " is no profile's own sign-in");
        };
    Instant expiresAt = secret.expiresAt() == null ? now.plus(RENEWAL_MARGIN) : secret.expiresAt();
    held.put(profileId, new Held(fingerprint, secret, expiresAt.minus(RENEWAL_MARGIN)));
    return secret;
  }

  /** One value per registration: a changed secret, scope, tenant or proxy is another token. */
  private static String fingerprint(ClientRegistration registration) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String part :
          new String[] {
            registration.clientId(),
            registration.secret(),
            registration.tenant(),
            registration.scopes(),
            registration.subject(),
            registration.proxy()
          }) {
        digest.update((byte) 0);
        if (part != null) {
          digest.update(part.getBytes(StandardCharsets.UTF_8));
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The outcome of a sign-in test, with a German message that never carries the secret. */
  public record SignInTest(boolean success, String message) {}
}
