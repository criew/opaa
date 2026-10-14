package io.opaa.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.connection.profile.ConnectionProfile;
import io.opaa.connection.profile.ConnectionProfileRepository;
import io.opaa.connection.profile.ConnectorTypePolicyRepository;
import io.opaa.connection.profile.LibraryConnection;
import io.opaa.connection.profile.LibraryConnectionRepository;
import io.opaa.connection.token.TestSecrets;
import io.opaa.indexing.spi.RunCredentials;
import io.opaa.indexing.spi.Secret;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.test.MutableClock;
import io.opaa.test.SourceTypes;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * How often a long run asks the port for the secret of a library under a profile and how often the
 * stored secret is decrypted: 2,000 documents with two accesses each over 500 seconds. The secret
 * lies in the token store, and every ask decrypts its row once.
 */
class RunSecretAskCountTest {

  private static final int DOCUMENTS = 2_000;
  private static final int ACCESSES_PER_DOCUMENT = 2;
  private static final Duration PER_ACCESS = Duration.ofMillis(125);

  private final Map<UUID, KnowledgeLibrary> rows = new HashMap<>();
  private final TestSecrets.Store store = new TestSecrets.Store();
  private final LibraryConnectionRepository connections = mock(LibraryConnectionRepository.class);
  private final ConnectionProfileRepository profiles = mock(ConnectionProfileRepository.class);
  private final ProfileSourceConnectionResolver resolver =
      TestProfileResolvers.resolver(
          connections,
          profiles,
          TestProfileResolvers.blocks(
              mock(ConnectorTypePolicyRepository.class), connections, profiles, rows, store),
          rows,
          store);
  private final AtomicInteger asks = new AtomicInteger();
  private static final Instant START = Instant.parse("2026-10-04T12:00:00Z");

  private final MutableClock clock = new MutableClock(START);

  private KnowledgeLibrary library;
  private Supplier<Secret> port;

  @BeforeEach
  void connectThroughAProfile() {
    library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Akten",
            null,
            UUID.randomUUID(),
            SourceTypes.RSS_FEED,
            null,
            "https://ablage.example.org/akten",
            "nutzer:geheim");
    rows.put(library.getId(), library);
    ConnectionProfile profile = mock(ConnectionProfile.class);
    UUID profileId = UUID.randomUUID();
    when(profile.getId()).thenReturn(profileId);
    when(profile.getName()).thenReturn("Ablage");
    when(profile.getSourceType()).thenReturn(SourceTypes.RSS_FEED);
    when(profile.getServerUrl()).thenReturn("https://ablage.example.org");
    when(profile.getAuthMethod()).thenReturn(ConnectionAuthMethod.PERSONAL_SECRET);
    LibraryConnection connection = new LibraryConnection(library.getId(), profileId, Instant.EPOCH);
    when(connections.findById(library.getId())).thenReturn(Optional.of(connection));
    when(connections.findAllById(any())).thenReturn(List.of(connection));
    when(profiles.findById(profileId)).thenReturn(Optional.of(profile));
    when(profiles.findAllById(any())).thenReturn(List.of(profile));
    store.moveIn(profileId, library, "https://ablage.example.org");
    port =
        () -> {
          asks.incrementAndGet();
          return resolver.currentSecret(library);
        };
  }

  @Test
  void theRunStartDecryptsOnce() {
    resolver.resolve(library);

    assertThat(store.decryptions()).isOne();
  }

  /** Before: the executor asked once at the start of its body and kept the secret. */
  @Test
  void onceARunAsBeforeAsksOnceAndDecryptsOnce() {
    Secret kept = port.get();
    for (int access = 0; access < DOCUMENTS * ACCESSES_PER_DOCUMENT; access++) {
      assertThat(kept).isNotNull();
    }

    assertThat(asks).hasValue(1);
    assertThat(store.decryptions()).isOne();
  }

  /** Asking the port before every access, without reuse, would decrypt once per access. */
  @Test
  void askingThePortBeforeEveryAccessWouldDecryptPerDocument() {
    RunCredentials credentials = new RunCredentials(port, Duration.ZERO, clock);

    accessAll(credentials);

    assertThat(asks).hasValue(4_000);
    assertThat(store.decryptions()).isEqualTo(4_000);
  }

  /** After: one ask per validity, 500 seconds at ten seconds each. */
  @Test
  void theRunCredentialsAskOncePerValidityAndDecryptOnceEachTime() {
    RunCredentials credentials = new RunCredentials(port, RunCredentials.VALIDITY, clock);

    accessAll(credentials);

    assertThat(Duration.between(START, clock.instant())).isEqualTo(Duration.ofSeconds(500));
    assertThat(asks).hasValue(50);
    assertThat(store.decryptions()).isEqualTo(50);
  }

  private void accessAll(RunCredentials credentials) {
    for (int access = 0; access < DOCUMENTS * ACCESSES_PER_DOCUMENT; access++) {
      credentials.check();
      clock.advance(PER_ACCESS);
    }
  }
}
