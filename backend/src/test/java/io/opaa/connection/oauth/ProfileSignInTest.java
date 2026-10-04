package io.opaa.connection.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionOwnership;
import io.opaa.connection.oauth.ProfileSignIn.SignInTest;
import io.opaa.connection.profile.ConnectionProfile;
import io.opaa.connection.profile.ConnectionProfileRepository;
import io.opaa.connection.profile.ProfileRegistrations;
import io.opaa.connection.token.SecretRefusedException;
import io.opaa.indexing.source.ClientAuthentication;
import io.opaa.indexing.source.Endpoint;
import io.opaa.indexing.source.Secret;
import io.opaa.indexing.source.SecretKind;
import io.opaa.indexing.source.ServiceAccountKey;
import io.opaa.indexing.source.ServiceAccountKeyFixture;
import io.opaa.indexing.source.ServiceAccountTokens;
import io.opaa.indexing.source.SourceBlock.Reason;
import io.opaa.indexing.source.SourceConnector;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.indexing.source.TestSourceConnectors;
import io.opaa.indexing.source.probe.ProbeKeySourceConnector;
import io.opaa.indexing.source.profileprobe.ClientCredentialsProbeSourceConnector;
import io.opaa.knowledge.SourceType;
import io.opaa.security.CredentialsEncryptor;
import io.opaa.security.TargetAddressValidator;
import io.opaa.test.FakeAuthorizationServer;
import io.opaa.test.MutableClock;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * A profile's own sign-in against a fake authorization server, from the stored profile through
 * {@link ProfileRegistrations} to the token: client credentials as the test connector declares
 * them, held until shortly before they expire; a service account key signed by the core; a rejected
 * registration marked and asked no more; the profile's proxy used, its TLS switch never.
 */
class ProfileSignInTest {

  private static final String CLIENT_ID = "opaa-client";
  private static final String CLIENT_SECRET = "geheim:mit&zeichen";

  private final FakeAuthorizationServer server = new FakeAuthorizationServer();
  private final ConnectionProfileRepository profiles = mock(ConnectionProfileRepository.class);
  private final CredentialsEncryptor encryptor = mock(CredentialsEncryptor.class);
  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-04T12:00:00Z"));
  private ProfileSignIn signIn;
  private ProfileRegistrations registrations;
  private ConnectionProfile profile;

  @AfterEach
  void stop() {
    server.close();
  }

  @Test
  void clientCredentialsGoAsBasicAuthenticationAndTheTokenIsHeld() {
    wire(clientProbe(server.tokenEndpoint(), ClientAuthentication.CLIENT_SECRET_BASIC));
    profile = clientProfile(null);

    Secret first = signIn.mint(profile.getId());
    Secret again = signIn.mint(profile.getId());

    assertThat(first.kind()).isEqualTo(SecretKind.ACCESS_TOKEN);
    assertThat(first.value()).isEqualTo(server.lastToken());
    assertThat(first.expiresAt()).isEqualTo(clock.instant().plusSeconds(3600));
    assertThat(again).isEqualTo(first);
    assertThat(server.requests())
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.authorization())
                  .isEqualTo(
                      "Basic "
                          + Base64.getEncoder()
                              .encodeToString(
                                  "opaa-client:geheim%3Amit%26zeichen"
                                      .getBytes(StandardCharsets.UTF_8)));
              assertThat(request.form())
                  .containsEntry("grant_type", "client_credentials")
                  .containsEntry("scope", ClientCredentialsProbeSourceConnector.DEFAULT_SCOPE)
                  .doesNotContainKeys("client_id", "client_secret");
            });
  }

  @Test
  void clientCredentialsGoInTheFormAndTheProfileScopeWins() {
    wire(clientProbe(server.tokenEndpoint(), ClientAuthentication.CLIENT_SECRET_POST));
    profile = clientProfile(null);
    ReflectionTestUtils.setField(profile, "scopes", "Files.Read");

    signIn.mint(profile.getId());

    assertThat(server.requests())
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.authorization()).isNull();
              assertThat(request.form())
                  .containsEntry("client_id", CLIENT_ID)
                  .containsEntry("client_secret", CLIENT_SECRET)
                  .containsEntry("scope", "Files.Read");
            });
  }

  @Test
  void aTokenIsObtainedAnewShortlyBeforeItExpiresAndAfterItWasForgotten() {
    wire(clientProbe(server.tokenEndpoint(), ClientAuthentication.CLIENT_SECRET_BASIC));
    profile = clientProfile(null);

    Secret first = signIn.mint(profile.getId());
    clock.advance(Duration.ofMinutes(56));
    Secret renewed = signIn.mint(profile.getId());
    signIn.forgetMinted(profile.getId());
    Secret afterForgetting = signIn.mint(profile.getId());

    assertThat(renewed.value()).isNotEqualTo(first.value());
    assertThat(afterForgetting.value()).isNotEqualTo(renewed.value());
    assertThat(server.requests()).hasSize(3);
  }

  /** {@code invalid_client} marks the profile; from then on no ask reaches the provider. */
  @Test
  void aRejectedRegistrationIsMarkedAndAskedNoMoreUntilATestLiftsIt() {
    wire(clientProbe(server.tokenEndpoint(), ClientAuthentication.CLIENT_SECRET_BASIC));
    profile = clientProfile(null);
    server.rejectWith(401, "invalid_client");

    assertThatThrownBy(() -> signIn.mint(profile.getId()))
        .isInstanceOfSatisfying(
            SecretRefusedException.class,
            refused -> assertThat(refused.reason()).isEqualTo(Reason.EXPIRED));
    assertThatThrownBy(() -> signIn.mint(profile.getId()))
        .isInstanceOfSatisfying(
            SecretRefusedException.class,
            refused -> assertThat(refused.reason()).isEqualTo(Reason.EXPIRED));
    assertThat(profile.isSignInRejected()).isTrue();
    assertThat(server.requests()).hasSize(1);

    SignInTest stillRejected = signIn.test(profile.getId());
    server.accept();
    SignInTest accepted = signIn.test(profile.getId());

    assertThat(stillRejected.success()).isFalse();
    assertThat(stillRejected.message()).contains("abgewiesen").doesNotContain(CLIENT_SECRET);
    assertThat(accepted.success()).isTrue();
    assertThat(profile.isSignInRejected()).isFalse();
    assertThat(signIn.mint(profile.getId()).value()).isEqualTo(server.lastToken());
  }

  /** Client secret and token reach no log line, no message and no {@code toString}. */
  @Test
  void theClientSecretAndTheTokenLeakNowhere() {
    wire(clientProbe(server.tokenEndpoint(), ClientAuthentication.CLIENT_SECRET_POST));
    profile = clientProfile(null);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    root.addAppender(appender);
    try {
      Secret token = signIn.mint(profile.getId());
      server.rejectWith(401, "invalid_client");
      SignInTest refused = signIn.test(profile.getId());

      assertThat(refused.message()).doesNotContain(CLIENT_SECRET, token.value());
      assertThat(token.toString()).doesNotContain(token.value());
      assertThat(registrations.registrationOf(profile.getId()).toString())
          .doesNotContain(CLIENT_SECRET);
      assertThat(appender.list)
          .isNotEmpty()
          .allSatisfy(
              event ->
                  assertThat(event.getFormattedMessage())
                      .doesNotContain(CLIENT_SECRET, token.value()));
    } finally {
      root.detachAppender(appender);
    }
  }

  @Test
  void aProfileWithoutSecretHandsOutNothingAndAsksNoOne() {
    wire(clientProbe(server.tokenEndpoint(), ClientAuthentication.CLIENT_SECRET_BASIC));
    profile = clientProfile(null);
    ReflectionTestUtils.setField(profile, "clientSecretCiphertext", null);

    assertThatThrownBy(() -> signIn.mint(profile.getId()))
        .isInstanceOfSatisfying(
            SecretRefusedException.class,
            refused -> assertThat(refused.reason()).isEqualTo(Reason.NOT_CONNECTED));
    assertThat(signIn.test(profile.getId()).success()).isFalse();
    assertThat(server.requests()).isEmpty();
  }

  /** The request reaches the fake as a proxy, naming the declared endpoint's host as target. */
  @Test
  void theTokenIsFetchedThroughTheProfilesProxy() {
    wire(
        clientProbe(
            URI.create("http://token.example.invalid/token"),
            ClientAuthentication.CLIENT_SECRET_BASIC));
    profile = clientProfile(server.address());

    signIn.mint(profile.getId());

    assertThat(server.requests())
        .singleElement()
        .satisfies(
            request -> assertThat(request.target().getHost()).isEqualTo("token.example.invalid"));
  }

  /** The profile skips the certificate check for its server address, never for the endpoint. */
  @Test
  void theTlsSwitchOfTheProfileDoesNotReachTheTokenEndpoint() throws Exception {
    Path keystore = Files.createTempFile("opaa-sign-in-tls-", ".p12");
    HttpsServer https = selfSignedServer(keystore);
    try {
      https.createContext(
          "/token",
          exchange -> {
            byte[] body = "{\"access_token\": \"unsicher\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      https.start();
      wire(
          clientProbe(
              URI.create("https://127.0.0.1:" + https.getAddress().getPort() + "/token"),
              ClientAuthentication.CLIENT_SECRET_BASIC));
      profile = clientProfile(null);
      ReflectionTestUtils.setField(profile, "sourceInsecureSsl", true);

      SignInTest test = signIn.test(profile.getId());

      assertThat(test.success()).isFalse();
      assertThat(test.message()).contains("nicht erreichbar");
      assertThat(profile.isSignInRejected()).isFalse();
    } finally {
      https.stop(0);
      Files.deleteIfExists(keystore);
    }
  }

  /** The key of the profile signs in the core, imitating the profile's account. */
  @Test
  void aServiceAccountKeyOfTheProfileIsSignedByTheCore() throws Exception {
    ServiceAccountKeyFixture key = new ServiceAccountKeyFixture();
    wire(new ProbeKeySourceConnector(server.tokenEndpoint()));
    profile = profile(ProbeKeySourceConnector.TYPE, ConnectionAuthMethod.SERVICE_ACCOUNT_KEY);
    ReflectionTestUtils.setField(profile, "clientId", ServiceAccountKeyFixture.CLIENT_EMAIL);
    secret(profile, ServiceAccountKey.parse(key.json()).storedForm());
    ReflectionTestUtils.setField(
        profile, "connectorSettings", "{\"subject\": \"fach@example.org\"}");

    Secret token = signIn.mint(profile.getId());

    assertThat(token.value()).isEqualTo(server.lastToken());
    SignedJWT assertion = SignedJWT.parse(server.requests().getFirst().form().get("assertion"));
    assertThat(assertion.verify(new RSASSAVerifier(key.publicKey()))).isTrue();
    assertThat(assertion.getJWTClaimsSet().getIssuer())
        .isEqualTo(ServiceAccountKeyFixture.CLIENT_EMAIL);
    assertThat(assertion.getJWTClaimsSet().getSubject()).isEqualTo("fach@example.org");
    assertThat(assertion.getJWTClaimsSet().getAudience())
        .containsExactly(server.tokenEndpoint().toString());
  }

  private void wire(SourceConnector connector) {
    SourceConnectorRegistry registry = TestSourceConnectors.connectors().with(connector).registry();
    @SuppressWarnings("unchecked")
    ObjectProvider<SourceConnectorRegistry> connectors = mock(ObjectProvider.class);
    when(connectors.getObject()).thenReturn(registry);
    when(profiles.findById(any())).thenAnswer(call -> java.util.Optional.ofNullable(profile));
    when(profiles.markSignInRejected(any(), any()))
        .thenAnswer(
            call -> {
              ReflectionTestUtils.setField(profile, "signInRejectedAt", call.getArgument(1));
              return 1;
            });
    registrations =
        new ProfileRegistrations(
            profiles, encryptor, mock(PlatformTransactionManager.class), connectors, clock);
    signIn =
        new ProfileSignIn(
            registrations,
            new ServiceAccountTokens(TargetAddressValidator.disabled(), clock),
            TargetAddressValidator.disabled(),
            clock);
  }

  private static ClientCredentialsProbeSourceConnector clientProbe(
      URI endpoint, ClientAuthentication clientAuth) {
    return new ClientCredentialsProbeSourceConnector(new Endpoint.Fixed(endpoint), clientAuth);
  }

  private ConnectionProfile clientProfile(String proxy) {
    ConnectionProfile created =
        profile(
            ClientCredentialsProbeSourceConnector.TYPE, ConnectionAuthMethod.CLIENT_CREDENTIALS);
    ReflectionTestUtils.setField(created, "clientId", CLIENT_ID);
    ReflectionTestUtils.setField(created, "sourceProxy", proxy);
    secret(created, CLIENT_SECRET);
    return created;
  }

  private ConnectionProfile profile(SourceType type, ConnectionAuthMethod method) {
    ConnectionProfile created = new ConnectionProfile(type, clock.instant());
    ReflectionTestUtils.setField(created, "name", "Zugang " + UUID.randomUUID());
    ReflectionTestUtils.setField(created, "serverUrl", "https://quelle.example.org");
    ReflectionTestUtils.setField(created, "authMethod", method);
    ReflectionTestUtils.setField(created, "ownership", ConnectionOwnership.LIBRARY);
    return created;
  }

  private void secret(ConnectionProfile target, String plain) {
    String ciphertext = "enc:v1:" + UUID.randomUUID();
    ReflectionTestUtils.setField(target, "clientSecretCiphertext", ciphertext);
    when(encryptor.decrypt(ciphertext)).thenReturn(plain);
  }

  /** An HTTPS server with a freshly generated certificate no trust store knows. */
  private static HttpsServer selfSignedServer(Path keystore) throws Exception {
    Files.delete(keystore);
    String keytool =
        System.getProperty("java.home") + File.separator + "bin" + File.separator + "keytool";
    Process process =
        new ProcessBuilder(
                keytool,
                "-genkeypair",
                "-alias",
                "sign-in",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-validity",
                "2",
                "-dname",
                "CN=127.0.0.1",
                "-ext",
                "SAN=ip:127.0.0.1",
                "-storetype",
                "PKCS12",
                "-keystore",
                keystore.toString(),
                "-storepass",
                "changeit",
                "-keypass",
                "changeit")
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!process.waitFor(30, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IllegalStateException("keytool did not finish: " + output);
    }
    if (process.exitValue() != 0) {
      throw new IllegalStateException("keytool failed: " + output);
    }
    KeyStore store = KeyStore.getInstance("PKCS12");
    try (var in = Files.newInputStream(keystore)) {
      store.load(in, "changeit".toCharArray());
    }
    KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keys.init(store, "changeit".toCharArray());
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(keys.getKeyManagers(), null, null);
    HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(context));
    return server;
  }
}
