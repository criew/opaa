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
import io.opaa.connection.consent.SourceConsentService;
import io.opaa.connection.profile.ClientRegistration;
import io.opaa.connection.profile.ConnectionProfile;
import io.opaa.connection.profile.ConnectionProfileRepository;
import io.opaa.connection.profile.EffectiveSourceSettings;
import io.opaa.connection.profile.LibraryConnection.Responsible;
import io.opaa.connection.profile.ProfileRegistrations;
import io.opaa.connection.token.NewSecret;
import io.opaa.connection.token.SecretOwner.PendingConsent;
import io.opaa.indexing.source.OAuthAuth;
import io.opaa.indexing.source.Secret;
import io.opaa.indexing.source.SecretKind;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.indexing.source.SourceCredentialsException;
import io.opaa.knowledge.KnowledgeLibrary;
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
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The OAuth consent without a server session (ADR-0025, Nachtrag 03.10.2026): {@link #start} keeps
 * state and PKCE verifier, bound to the caller and the profile version, and returns the
 * authorization request; {@link #complete} takes what the provider sent back through the
 * application, uses the state up before it asks the provider, exchanges the code and stores the
 * grant. Where the caller returns to follows from the purpose alone. A consent for a library's
 * source is bound to the library or, for a new one, to a pending consent of the caller; whoever may
 * connect it is checked at the start and again at the completion.
 */
@Service
public class ConnectionAuthorizationService {

  public static final String PROFILE_CHANGED = "CONNECTION_AUTHORIZATION_PROFILE_CHANGED";
  public static final String PUBLIC_BASE_URL_MISSING = "PUBLIC_BASE_URL_MISSING";

  /** Refusal of a response whose {@code iss} is not the issuer the consent started with. */
  public static final String ISSUER_MISMATCH = "CONNECTION_AUTHORIZATION_ISSUER_MISMATCH";

  /** Refusal of a consent for a library's source without the confirmed service account. */
  public static final String SERVICE_ACCOUNT_CONFIRMATION_REQUIRED =
      "SERVICE_ACCOUNT_CONFIRMATION_REQUIRED";

  /** The page of the application the provider returns to. */
  public static final String CALLBACK_PATH = "/connections/callback";

  /** How long a started consent can be completed. */
  static final Duration LIFETIME = Duration.ofMinutes(10);

  /** How many consents a person starts within {@link #LIFETIME}. */
  static final int MAX_STARTS = 10;

  private static final String ACCOUNTS_PAGE = "/settings/accounts";
  private static final String NEW_LIBRARY_PAGE = "/libraries/new";
  private static final String LIBRARY_PAGE = "/libraries/";
  private static final Logger log = LoggerFactory.getLogger(ConnectionAuthorizationService.class);
  private static final Pattern PROVIDER_ERROR = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
  private static final SecureRandom RANDOM = new SecureRandom();

  private final ConnectionAuthorizationRepository authorizations;
  private final ConnectionProfileRepository profiles;
  private final ProfileRegistrations registrations;
  private final ConnectedAccountService accounts;
  private final SourceConsentService consents;
  private final EffectiveSourceSettings effective;
  private final ObjectProvider<SourceConnectorRegistry> connectors;
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
      SourceConsentService consents,
      EffectiveSourceSettings effective,
      ObjectProvider<SourceConnectorRegistry> connectors,
      PublicBaseUrl publicBaseUrl,
      CredentialsEncryptor encryptor,
      TargetAddressValidator targetAddressValidator,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.authorizations = authorizations;
    this.profiles = profiles;
    this.registrations = registrations;
    this.accounts = accounts;
    this.consents = consents;
    this.effective = effective;
    this.connectors = connectors;
    this.publicBaseUrl = publicBaseUrl;
    this.encryptor = encryptor;
    this.client = new OAuthClient(targetAddressValidator);
    this.transactions = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  /**
   * The address every provider returns to after a consent, {@code {public
   * base}/connections/callback}; empty while no public address is configured.
   */
  public Optional<String> redirectUri() {
    return publicBaseUrl.link(CALLBACK_PATH);
  }

  /**
   * {@link #start(CurrentUser, UUID, ConnectionAuthorizationPurpose, LibraryConsent)} for a purpose
   * without a library.
   */
  public Started start(CurrentUser caller, UUID profileId, ConnectionAuthorizationPurpose purpose) {
    return start(caller, profileId, purpose, LibraryConsent.NONE);
  }

  /**
   * Starts a consent of the caller on profile {@code profileId} for {@code purpose}: {@code 400}
   * for a profile without OAuth, what connecting an account or a library's source refuses, {@code
   * 400} {@value #SERVICE_ACCOUNT_CONFIRMATION_REQUIRED} for a library's source without {@link
   * LibraryConsent#serviceAccountConfirmed}, {@code 409} without a public address and {@code 429}
   * beyond {@link #MAX_STARTS}.
   */
  public Started start(
      CurrentUser caller,
      UUID profileId,
      ConnectionAuthorizationPurpose purpose,
      LibraryConsent library) {
    ConnectionProfile profile =
        profiles
            .findById(profileId)
            .orElseThrow(() -> new NotFoundException("Zugang nicht gefunden"));
    if (profile.getAuthMethod() != ConnectionAuthMethod.OAUTH) {
      throw new ValidationException(
          "Der Zugang „" + profile.getName() + "“ meldet sich nicht beim Anbieter an.");
    }
    KnowledgeLibrary reconnected = requireMayConnect(caller, profile, purpose, library.libraryId());
    if (purpose != ConnectionAuthorizationPurpose.ACCOUNT && !library.serviceAccountConfirmed()) {
      throw new ValidationException(
          "Bitte bestätigen Sie, dass Sie ein Dienstkonto verbinden und kein persönliches Konto.",
          SERVICE_ACCOUNT_CONFIRMATION_REQUIRED);
    }
    if (reconnected != null && library.responsible() != null) {
      consents.requireResponsible(reconnected, library.responsible());
    }
    URI redirect =
        redirectUri()
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
          ConnectionAuthorization authorization =
              new ConnectionAuthorization(
                  hash(state),
                  caller.id(),
                  profileId,
                  profile.getVersion(),
                  purpose,
                  verifierCiphertext,
                  redirect.toString(),
                  now,
                  expiresAt);
          authorization.expectIssuer(profile.getIssuer(), profile.isIssuerParameterSupported());
          if (purpose != ConnectionAuthorizationPurpose.ACCOUNT) {
            authorization.forLibrary(
                now,
                purpose == ConnectionAuthorizationPurpose.LIBRARY_RECONNECT
                    ? library.libraryId()
                    : null,
                purpose == ConnectionAuthorizationPurpose.LIBRARY_RECONNECT
                    ? library.responsible()
                    : null,
                library.acceptsAccountChange());
          }
          authorizations.save(authorization);
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
   * it with the provider's {@code error}, as the provider's {@code iss} names it ({@code null}
   * where absent). The state is used up first, whatever follows: {@code 404} for one unknown,
   * another person's, used up or expired; {@code 400} {@value #ISSUER_MISMATCH} when {@code iss} is
   * not exactly the issuer the consent started with, or missing where that issuer announced it (RFC
   * 9207), before anything reaches the provider; {@code 409} when the profile changed since the
   * start, also while the code was exchanged, and for another account than a library's source was
   * connected as ({@value SourceConsentService#ACCOUNT_CHANGED}); {@code 400} when the provider
   * refused or the exchange failed. Whoever may connect is checked as at the start. A grant that
   * cannot be stored is revoked with the registration it came from.
   */
  public Completed complete(
      CurrentUser caller, String state, String code, String error, String iss) {
    ConnectionAuthorization authorization = useUp(caller, state);
    requireIssuer(authorization, iss);
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
    ConnectionAuthorizationPurpose purpose = authorization.getPurpose();
    KnowledgeLibrary library =
        requireMayConnect(caller, profile, purpose, authorization.getLibraryId());
    if (purpose != ConnectionAuthorizationPurpose.ACCOUNT
        && authorization.getServiceAccountConfirmedAt() == null) {
      throw new ValidationException(
          "Die Bestätigung des Dienstkontos fehlt. Bitte verbinden Sie erneut.",
          SERVICE_ACCOUNT_CONFIRMATION_REQUIRED);
    }
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
    Completed completed;
    try {
      String accountLabel = connectedAccount(profile, library, grant);
      completed =
          transactions.execute(
              status ->
                  store(
                      caller, profile, registration, authorization, library, grant, accountLabel));
    } catch (RuntimeException e) {
      client.revoke(registration, auth, grant.refreshToken(), grant.accessToken());
      throw e;
    }
    return completed;
  }

  /**
   * Refuses {@code caller} connecting through {@code profile} for {@code purpose}, at the start and
   * again at the completion: an account as {@link ConnectedAccountService#requireConnectable}, a
   * new library's source with the profile's release, an existing library's source with {@code
   * MANAGER} on {@code libraryId}. Returns that library, {@code null} for every other purpose.
   */
  private KnowledgeLibrary requireMayConnect(
      CurrentUser caller,
      ConnectionProfile profile,
      ConnectionAuthorizationPurpose purpose,
      UUID libraryId) {
    return switch (purpose) {
      case ACCOUNT -> {
        accounts.requireConnectable(caller, profile);
        yield null;
      }
      case LIBRARY_NEW -> {
        consents.requireStartableForNew(caller, profile);
        yield null;
      }
      case LIBRARY_RECONNECT -> {
        if (libraryId == null) {
          throw new ValidationException(
              "libraryId ist erforderlich, um eine Quelle neu zu verbinden");
        }
        yield consents.requireReconnectable(caller, profile, libraryId);
      }
    };
  }

  /**
   * The account the provider names for {@code grant}, asked through the connector with its access
   * token outside any transaction; {@code null} where it names none or cannot be asked, and for an
   * MCP server, which has no connector to ask.
   *
   * @throws ValidationException (German 400) for any other failure of the lookup; the caller
   *     revokes the grant
   */
  private String connectedAccount(
      ConnectionProfile profile, KnowledgeLibrary library, OAuthClient.Grant grant) {
    if (profile.isMcpServer()) {
      return null;
    }
    try {
      return connectors
          .getObject()
          .connector(profile.getSourceType())
          .connectedAccount(
              effective.withAccessToken(
                  profile,
                  library,
                  new Secret(
                      SecretKind.ACCESS_TOKEN, grant.accessToken(), grant.accessTokenExpiresAt())))
          .filter(label -> !label.isBlank())
          .map(String::strip)
          .orElse(null);
    } catch (SourceCredentialsException e) {
      log.info(
          "The provider named no account for a consent under profile {} ({})",
          profile.getId(),
          e.getClass().getSimpleName());
      return null;
    } catch (RuntimeException e) {
      log.warn(
          "The account lookup for a consent under profile {} failed ({})",
          profile.getId(),
          e.getClass().getSimpleName());
      throw new ValidationException(
          "Das Konto beim Anbieter ließ sich nicht ermitteln; die Zustimmung wurde zurückgenommen"
              + " und es wurde nichts verbunden. Bitte versuchen Sie es erneut.");
    }
  }

  /**
   * Stores {@code grant} for the purpose of {@code authorization} while the profile row is held
   * against a change, only if the profile still stands at the version of {@code registration},
   * which the grant was obtained with: a change either went first and refuses it, or comes after
   * and discards it.
   */
  private Completed store(
      CurrentUser caller,
      ConnectionProfile profile,
      ClientRegistration registration,
      ConnectionAuthorization authorization,
      KnowledgeLibrary library,
      OAuthClient.Grant grant,
      String accountLabel) {
    Long version = profiles.lockedVersion(profile.getId());
    if (version == null || version != registration.version()) {
      throw profileChanged(profile);
    }
    NewSecret.OAuthGrant secret =
        new NewSecret.OAuthGrant(
            grant.refreshToken(),
            grant.accessToken(),
            grant.accessTokenExpiresAt(),
            grant.refreshTokenExpiresAt());
    ConnectionAuthorizationPurpose purpose = authorization.getPurpose();
    return switch (purpose) {
      case ACCOUNT ->
          new Completed(
              purpose,
              profile.getId(),
              ACCOUNTS_PAGE,
              accounts.established(caller, profile, secret, accountLabel),
              null,
              null);
      case LIBRARY_NEW -> {
        PendingConsent pending = consents.holdPending(caller, profile, accountLabel, secret);
        yield new Completed(
            purpose,
            profile.getId(),
            NEW_LIBRARY_PAGE,
            null,
            null,
            new PendingConnection(
                pending.tokenId(),
                accountLabel,
                consents
                    .pending(caller, pending.tokenId())
                    .map(found -> found.expiresAt())
                    .orElse(null)));
      }
      case LIBRARY_RECONNECT -> {
        consents.establish(
            caller,
            library,
            profile,
            secret,
            accountLabel,
            authorization.getResponsible(),
            authorization.acceptsAccountChange());
        yield new Completed(
            purpose, profile.getId(), LIBRARY_PAGE + library.getId(), null, library.getId(), null);
      }
    };
  }

  /**
   * Refuses a response from another authorization server than the one the consent started with
   * (mix-up, RFC 9207, 2.4): {@code iss} must equal the expected issuer character for character,
   * and is required where that server announced it. Without a known issuer nothing is compared.
   */
  private static void requireIssuer(ConnectionAuthorization authorization, String iss) {
    String expected = authorization.getExpectedIssuer();
    if (expected == null || expected.equals(iss)) {
      return;
    }
    if (iss == null && !authorization.isIssuerRequired()) {
      return;
    }
    log.warn(
        "A consent under profile {} was refused: the provider's response {} the expected issuer",
        authorization.getProfileId(),
        iss == null ? "did not name" : "named another than");
    throw new ValidationException(
        "Die Antwort kam nicht vom Anmeldedienst dieses Zugangs. Aus Sicherheitsgründen wurde"
            + " nichts verbunden. Bitte verbinden Sie erneut; tritt das wieder auf, ist die"
            + " Systemverwaltung zuständig.",
        ISSUER_MISMATCH);
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
   * What a consent for a library's source binds: the library to connect anew ({@code null} for a
   * new one), the caller's confirmation of a service account, who answers for the connection after
   * a reconnection ({@code null} to keep it) and whether another account than before is accepted.
   */
  public record LibraryConsent(
      UUID libraryId,
      boolean serviceAccountConfirmed,
      Responsible responsible,
      boolean acceptsAccountChange) {

    static final LibraryConsent NONE = new LibraryConsent(null, false, null, false);
  }

  /**
   * A completed consent: what it was for, on which profile, where the caller goes on to and, for
   * {@code ACCOUNT}, the connection as it stands now; for {@code LIBRARY_RECONNECT} the library,
   * for {@code LIBRARY_NEW} the pending connection a new library takes over.
   */
  public record Completed(
      ConnectionAuthorizationPurpose purpose,
      UUID profileId,
      String returnTo,
      AccountOverview.Account account,
      UUID libraryId,
      PendingConnection pending) {}

  /** A consent waiting for its library: its id, the account it was given as and its end. */
  public record PendingConnection(UUID id, String accountLabel, Instant expiresAt) {}
}
