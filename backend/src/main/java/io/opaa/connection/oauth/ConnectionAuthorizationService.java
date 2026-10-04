package io.opaa.connection.oauth;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionAuthorizationPurpose;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.PublicBaseUrl;
import io.opaa.common.TooManyRequestsException;
import io.opaa.common.ValidationException;
import io.opaa.connection.account.AccountOverview;
import io.opaa.connection.account.ConnectedAccountService;
import io.opaa.connection.profile.ClientRegistration;
import io.opaa.connection.profile.ConnectionProfile;
import io.opaa.connection.profile.ConnectionProfileRepository;
import io.opaa.connection.profile.ProfileRegistrations;
import io.opaa.connection.token.NewSecret;
import io.opaa.indexing.source.OAuthAuth;
import io.opaa.indexing.source.SourceCredentialsException;
import io.opaa.security.CredentialsEncryptionKeyMissingException;
import io.opaa.security.CredentialsEncryptor;
import io.opaa.security.TargetAddressValidator;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The OAuth consent without a server session (ADR-0025, Nachtrag 03.10.2026): {@link #start} keeps
 * state and PKCE verifier, bound to the caller and the profile version, and returns the
 * authorization request; {@link #complete} takes what the provider sent back through the
 * application, uses the state up before it asks the provider, exchanges the code and stores the
 * grant. Where the caller returns to follows from the purpose alone.
 */
@Service
public class ConnectionAuthorizationService {

  public static final String PURPOSE_UNAVAILABLE = "CONNECTION_AUTHORIZATION_PURPOSE_UNAVAILABLE";
  public static final String PROFILE_CHANGED = "CONNECTION_AUTHORIZATION_PROFILE_CHANGED";
  public static final String PUBLIC_BASE_URL_MISSING = "PUBLIC_BASE_URL_MISSING";

  /** The page of the application the provider returns to. */
  public static final String CALLBACK_PATH = "/connections/callback";

  /** How long a started consent can be completed. */
  static final Duration LIFETIME = Duration.ofMinutes(10);

  /** How many consents a person starts within {@link #LIFETIME}. */
  static final int MAX_STARTS = 10;

  private static final String ACCOUNTS_PAGE = "/settings/accounts";
  private static final Pattern PROVIDER_ERROR = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
  private static final SecureRandom RANDOM = new SecureRandom();

  private final ConnectionAuthorizationRepository authorizations;
  private final ConnectionProfileRepository profiles;
  private final ProfileRegistrations registrations;
  private final ConnectedAccountService accounts;
  private final PublicBaseUrl publicBaseUrl;
  private final CredentialsEncryptor encryptor;
  private final OAuthClient client;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public ConnectionAuthorizationService(
      ConnectionAuthorizationRepository authorizations,
      ConnectionProfileRepository profiles,
      ProfileRegistrations registrations,
      ConnectedAccountService accounts,
      PublicBaseUrl publicBaseUrl,
      CredentialsEncryptor encryptor,
      TargetAddressValidator targetAddressValidator,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.authorizations = authorizations;
    this.profiles = profiles;
    this.registrations = registrations;
    this.accounts = accounts;
    this.publicBaseUrl = publicBaseUrl;
    this.encryptor = encryptor;
    this.client = new OAuthClient(targetAddressValidator);
    this.transactions = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  /**
   * Starts a consent of the caller on profile {@code profileId} for {@code purpose}: {@code 400}
   * for a profile without OAuth and for a purpose not served yet, what connecting an account
   * refuses, {@code 409} without a public address and {@code 429} beyond {@link #MAX_STARTS}.
   */
  public Started start(CurrentUser caller, UUID profileId, ConnectionAuthorizationPurpose purpose) {
    if (purpose != ConnectionAuthorizationPurpose.ACCOUNT) {
      throw new ValidationException(
          "Bibliotheken lassen sich noch nicht über eine Anmeldung beim Anbieter verbinden.",
          PURPOSE_UNAVAILABLE);
    }
    ConnectionProfile profile =
        profiles
            .findById(profileId)
            .orElseThrow(() -> new NotFoundException("Zugang nicht gefunden"));
    if (profile.getAuthMethod() != ConnectionAuthMethod.OAUTH) {
      throw new ValidationException(
          "Der Zugang „" + profile.getName() + "“ meldet sich nicht beim Anbieter an.");
    }
    accounts.requireConnectable(caller, profile);
    URI redirect =
        publicBaseUrl
            .link(CALLBACK_PATH)
            .map(URI::create)
            .orElseThrow(
                () ->
                    new ConflictException(
                        "Für die Anmeldung beim Anbieter fehlt die öffentliche Adresse von OPAA"
                            + " (OPAA_PUBLIC_BASE_URL). Zuständig ist die Systemverwaltung.",
                        PUBLIC_BASE_URL_MISSING));
    ClientRegistration registration = registrations.registrationOf(profileId);
    OAuthAuth auth = ProviderTokens.oauthOf(registration);
    String state = randomToken();
    String verifier = randomToken();
    Instant now = clock.instant();
    Instant expiresAt = now.plus(LIFETIME);
    String verifierCiphertext = encryptor.encrypt(verifier);
    transactions.executeWithoutResult(
        status -> {
          authorizations.lockStartsOf(caller.id());
          authorizations.deleteEndedBefore(now);
          requireBudget(caller, now);
          authorizations.save(
              new ConnectionAuthorization(
                  hash(state),
                  caller.id(),
                  profileId,
                  profile.getVersion(),
                  purpose,
                  verifierCiphertext,
                  redirect.toString(),
                  now,
                  expiresAt));
        });
    URI url;
    try {
      url = client.authorizationUrl(registration, auth, state, challengeOf(verifier), redirect);
    } catch (SourceCredentialsException e) {
      throw new ValidationException(e.getMessage());
    }
    return new Started(url, expiresAt);
  }

  /**
   * Completes the caller's consent named by {@code state} with the provider's {@code code}, or ends
   * it with the provider's {@code error}. The state is used up first, whatever follows: {@code 404}
   * for one unknown, another person's, used up or expired; {@code 409} when the profile changed
   * since the start, also while the code was exchanged; {@code 400} when the provider refused or
   * the exchange failed. A grant that cannot be stored is revoked with the registration it came
   * from.
   */
  public Completed complete(CurrentUser caller, String state, String code, String error) {
    ConnectionAuthorization authorization = useUp(caller, state);
    if (error != null && !error.isBlank()) {
      throw new ValidationException(refusal(error));
    }
    if (code == null || code.isBlank()) {
      throw new ValidationException("Der Anbieter hat keinen Code zurückgegeben.");
    }
    ConnectionProfile profile =
        profiles
            .findById(authorization.getProfileId())
            .orElseThrow(() -> new NotFoundException("Zugang nicht gefunden"));
    ClientRegistration registration = registrations.registrationOf(profile.getId());
    if (registration.version() != authorization.getProfileVersion()) {
      throw profileChanged(profile);
    }
    accounts.requireConnectable(caller, profile);
    OAuthAuth auth = ProviderTokens.oauthOf(registration);
    OAuthClient.Grant grant;
    try {
      grant =
          client.exchange(
              registration,
              auth,
              code,
              verifierOf(authorization),
              URI.create(authorization.getRedirectUri()),
              clock.instant());
    } catch (SourceCredentialsException e) {
      throw new ValidationException(e.getMessage());
    }
    if (grant.refreshToken() == null) {
      throw new ValidationException(
          "Der Anbieter hat keine dauerhafte Zustimmung erteilt (kein Refresh-Token). Der Zugang"
              + " braucht dafür den passenden Scope, etwa offline_access. Zuständig ist die"
              + " Systemverwaltung.");
    }
    AccountOverview.Account account;
    try {
      account = transactions.execute(status -> store(caller, profile, registration, grant));
    } catch (RuntimeException e) {
      client.revoke(registration, auth, grant.refreshToken(), grant.accessToken());
      throw e;
    }
    return new Completed(authorization.getPurpose(), profile.getId(), ACCOUNTS_PAGE, account);
  }

  /**
   * Stores {@code grant} while the profile row is held against a change, only if the profile still
   * stands at the version of {@code registration}, which the grant was obtained with: a change
   * either went first and refuses it, or comes after and discards it.
   */
  private AccountOverview.Account store(
      CurrentUser caller,
      ConnectionProfile profile,
      ClientRegistration registration,
      OAuthClient.Grant grant) {
    Long version = profiles.lockedVersion(profile.getId());
    if (version == null || version != registration.version()) {
      throw profileChanged(profile);
    }
    return accounts.established(
        caller,
        profile,
        new NewSecret.OAuthGrant(
            grant.refreshToken(),
            grant.accessToken(),
            grant.accessTokenExpiresAt(),
            grant.refreshTokenExpiresAt()),
        null);
  }

  private static ConflictException profileChanged(ConnectionProfile profile) {
    return new ConflictException(
        "Der Zugang „"
            + profile.getName()
            + "“ wurde geändert, während Sie beim Anbieter waren. Bitte verbinden Sie erneut.",
        PROFILE_CHANGED);
  }

  /**
   * The caller's open authorization named by {@code state}, used up in a transaction of its own so
   * no second completion reaches the provider.
   */
  private ConnectionAuthorization useUp(CurrentUser caller, String state) {
    if (state == null || state.isBlank()) {
      throw unknownState();
    }
    String stateHash = hash(state);
    return transactions.execute(
        status -> {
          Instant now = clock.instant();
          ConnectionAuthorization found =
              authorizations.findByStateHash(stateHash).orElseThrow(this::unknownState);
          if (!found.getUserId().equals(caller.id())
              || found.getConsumedAt() != null
              || !found.getExpiresAt().isAfter(now)
              || authorizations.consume(found.getId(), now) != 1) {
            throw unknownState();
          }
          return found;
        });
  }

  private NotFoundException unknownState() {
    return new NotFoundException(
        "Diese Anmeldung beim Anbieter ist unbekannt, abgelaufen oder schon abgeschlossen. Bitte"
            + " verbinden Sie erneut.");
  }

  private void requireBudget(CurrentUser caller, Instant now) {
    List<ConnectionAuthorization> recent =
        authorizations.findByUserIdAndCreatedAtAfterOrderByCreatedAtAsc(
            caller.id(), now.minus(LIFETIME));
    if (recent.size() < MAX_STARTS) {
      return;
    }
    Instant free = recent.get(recent.size() - MAX_STARTS).getCreatedAt().plus(LIFETIME);
    throw new TooManyRequestsException(
        "Sie haben in den letzten Minuten bereits "
            + MAX_STARTS
            + " Anmeldungen beim Anbieter begonnen. Bitte versuchen Sie es später erneut.",
        Math.max(1, Duration.between(now, free).toSeconds()));
  }

  private String verifierOf(ConnectionAuthorization authorization) {
    try {
      return encryptor.decrypt(authorization.getCodeVerifierCiphertext());
    } catch (CredentialsEncryptionKeyMissingException e) {
      throw new ValidationException(
          "Die Anmeldung beim Anbieter lässt sich nicht mehr abschließen. Bitte verbinden Sie"
              + " erneut.");
    }
  }

  /** The German refusal for the provider's error code; an unexpected code is not repeated. */
  private static String refusal(String error) {
    if ("access_denied".equals(error)) {
      return "Die Zustimmung beim Anbieter wurde abgelehnt oder abgebrochen. Es wurde nichts"
          + " verbunden.";
    }
    return "Der Anbieter hat die Anmeldung nicht abgeschlossen"
        + (PROVIDER_ERROR.matcher(error).matches() ? " (" + error + ")" : "")
        + ". Es wurde nichts verbunden.";
  }

  /** 32 random bytes, base64url without padding: a state or a PKCE verifier (RFC 7636, 4.1). */
  private static String randomToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** The S256 challenge of {@code verifier} (RFC 7636, 4.2). */
  static String challengeOf(String verifier) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
  }

  /** The stored form of a state: only its hash, so the table alone completes nothing. */
  static String hash(String state) {
    return HexFormat.of().formatHex(sha256(state.getBytes(StandardCharsets.UTF_8)));
  }

  private static byte[] sha256(byte[] input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A started consent: where the browser goes, and until when it can be completed. */
  public record Started(URI authorizationUrl, Instant expiresAt) {}

  /**
   * A completed consent: what it was for, on which profile, where the caller goes on to and, for
   * {@code ACCOUNT}, the connection as it stands now.
   */
  public record Completed(
      ConnectionAuthorizationPurpose purpose,
      UUID profileId,
      String returnTo,
      AccountOverview.Account account) {}
}
