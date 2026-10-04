package io.opaa.connection.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionOwnership;
import io.opaa.indexing.source.SourceBlock;
import io.opaa.indexing.source.SourceBlock.Reason;
import io.opaa.indexing.source.SourceConnectionBlockedException;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.indexing.source.TestSourceConnectors;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.test.SourceTypes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.ObjectProvider;

/** Which reason blocks a library, which one wins, and the notice each one carries. */
class SourceBlocksTest {

  /** One fact about a library that can block it. */
  enum Fact {
    TYPE_LOCK,
    CONNECTED,
    PROFILE_REMOVED,
    PROFILE_LOCK,
    OUTSIDE,
    NO_SECRET
  }

  private static final Instant NOW = Instant.parse("2026-10-04T08:00:00Z");
  private static final String SERVER = "https://feeds.example.org";

  private final List<ConnectorTypePolicy> policyRows = new ArrayList<>();
  private final List<LibraryConnection> connectionRows = new ArrayList<>();
  private final List<ConnectionProfile> profileRows = new ArrayList<>();
  private final ConnectorTypePolicyRepository policies = mock(ConnectorTypePolicyRepository.class);
  private final Map<UUID, KnowledgeLibrary> rows = new HashMap<>();
  private SourceBlocks blocks;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    LibraryConnectionRepository connections = mock(LibraryConnectionRepository.class);
    ConnectionProfileRepository profiles = mock(ConnectionProfileRepository.class);
    when(policies.findAll()).thenAnswer(call -> List.copyOf(policyRows));
    when(connections.findAllById(any()))
        .thenAnswer(
            call -> {
              Set<UUID> ids = idsOf(call.getArgument(0));
              return connectionRows.stream().filter(c -> ids.contains(c.getLibraryId())).toList();
            });
    when(profiles.findAllById(any()))
        .thenAnswer(
            call -> {
              Set<UUID> ids = idsOf(call.getArgument(0));
              return profileRows.stream().filter(p -> ids.contains(p.getId())).toList();
            });
    ObjectProvider<SourceConnectorRegistry> registry = mock(ObjectProvider.class);
    when(registry.getObject()).thenReturn(TestSourceConnectors.connectors().registry());
    blocks =
        new SourceBlocks(
            policies,
            connections,
            profiles,
            new ConnectionSecrets(connections, LibraryRows.over(rows)),
            registry);
  }

  static Stream<Arguments> precedence() {
    return Stream.of(
        Arguments.of(
            EnumSet.of(
                Fact.TYPE_LOCK, Fact.CONNECTED, Fact.PROFILE_LOCK, Fact.OUTSIDE, Fact.NO_SECRET),
            Reason.TYPE_LOCKED),
        Arguments.of(
            EnumSet.of(Fact.TYPE_LOCK, Fact.CONNECTED, Fact.PROFILE_REMOVED), Reason.TYPE_LOCKED),
        Arguments.of(EnumSet.of(Fact.TYPE_LOCK, Fact.NO_SECRET), Reason.TYPE_LOCKED),
        Arguments.of(
            EnumSet.of(Fact.CONNECTED, Fact.PROFILE_LOCK, Fact.OUTSIDE, Fact.NO_SECRET),
            Reason.PROFILE_LOCKED),
        Arguments.of(
            EnumSet.of(Fact.CONNECTED, Fact.PROFILE_REMOVED, Fact.OUTSIDE, Fact.NO_SECRET),
            Reason.ACCESS_REMOVED),
        Arguments.of(
            EnumSet.of(Fact.CONNECTED, Fact.OUTSIDE, Fact.NO_SECRET),
            Reason.TARGET_OUTSIDE_PROFILE),
        Arguments.of(EnumSet.of(Fact.CONNECTED, Fact.NO_SECRET), Reason.NOT_CONNECTED),
        Arguments.of(EnumSet.of(Fact.CONNECTED), null),
        Arguments.of(EnumSet.of(Fact.OUTSIDE, Fact.NO_SECRET), null));
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @MethodSource("precedence")
  void theFirstApplicableReasonWins(Set<Fact> facts, Reason expected) {
    KnowledgeLibrary library = arrange(facts, ConnectionAuthMethod.PERSONAL_SECRET);

    assertThat(blocks.blockOf(library, SourceBlocks.ALL).map(SourceBlock::reason))
        .isEqualTo(Optional.ofNullable(expected));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("precedence")
  void theDeclarationOrderOfTheReasonsIsThePrecedence(Set<Fact> facts, Reason expected) {
    KnowledgeLibrary library = arrange(facts, ConnectionAuthMethod.PERSONAL_SECRET);

    Optional<Reason> firstDeclared =
        Arrays.stream(Reason.values())
            .filter(reason -> blocks.blockOf(library, EnumSet.of(reason)).isPresent())
            .min(Comparator.naturalOrder());

    assertThat(blocks.blockOf(library, SourceBlocks.ALL).map(SourceBlock::reason))
        .isEqualTo(firstDeclared);
  }

  @Test
  void theReasonsAreDeclaredInTheirPrecedence() {
    assertThat(Reason.values())
        .containsExactly(
            Reason.TYPE_LOCKED,
            Reason.PROFILE_LOCKED,
            Reason.ACCESS_REMOVED,
            Reason.TARGET_OUTSIDE_PROFILE,
            Reason.NOT_CONNECTED);
  }

  @Test
  void theSetsFollowFromThePropertiesOfTheReasons() {
    assertThat(SourceBlocks.ALL).containsExactlyInAnyOrder(Reason.values());
    assertThat(SourceBlocks.LOCKS)
        .containsExactlyInAnyOrder(Reason.TYPE_LOCKED, Reason.PROFILE_LOCKED);
    assertThat(SourceBlocks.ENDING_A_RUNNING_RUN)
        .containsExactlyInAnyOrder(
            Reason.ACCESS_REMOVED, Reason.TARGET_OUTSIDE_PROFILE, Reason.NOT_CONNECTED);
    assertThat(SourceBlocks.SHOWN_IN_ANSWER)
        .containsExactlyInAnyOrder(
            Reason.TYPE_LOCKED, Reason.PROFILE_LOCKED, Reason.ACCESS_REMOVED, Reason.NOT_CONNECTED);
    for (Reason reason : Reason.values()) {
      assertThat(new SourceBlock(reason, "x", "y").locked())
          .isEqualTo(SourceBlocks.LOCKS.contains(reason));
    }
  }

  @Test
  void anAddressOutsideIsNotShownInAnAnswerButTheMissingSecretBehindItIs() {
    KnowledgeLibrary outside =
        arrange(EnumSet.of(Fact.CONNECTED, Fact.OUTSIDE), ConnectionAuthMethod.PERSONAL_SECRET);
    KnowledgeLibrary outsideWithoutSecret =
        arrange(
            EnumSet.of(Fact.CONNECTED, Fact.OUTSIDE, Fact.NO_SECRET),
            ConnectionAuthMethod.PERSONAL_SECRET);

    assertThat(blocks.blockOf(outside, SourceBlocks.SHOWN_IN_ANSWER)).isEmpty();
    assertThat(blocks.blockOf(outsideWithoutSecret, SourceBlocks.ALL).map(SourceBlock::reason))
        .contains(Reason.TARGET_OUTSIDE_PROFILE);
    assertThat(
            blocks
                .blockOf(outsideWithoutSecret, SourceBlocks.SHOWN_IN_ANSWER)
                .map(SourceBlock::reason))
        .contains(Reason.NOT_CONNECTED);
  }

  @Test
  void theTypeLocksAreReadOnlyWhenATypeLockIsConsidered() {
    KnowledgeLibrary library =
        arrange(EnumSet.of(Fact.CONNECTED), ConnectionAuthMethod.PERSONAL_SECRET);

    blocks.requireUnblocked(library, SourceBlocks.ENDING_A_RUNNING_RUN);

    verify(policies, never()).findAll();
  }

  static Stream<Arguments> notices() {
    return Stream.of(
        Arguments.of(
            EnumSet.of(Fact.TYPE_LOCK),
            ConnectionAuthMethod.PERSONAL_SECRET,
            new SourceBlock(
                Reason.TYPE_LOCKED,
                "Systemverwaltung",
                "Gesperrt – Inhalt wird nicht mehr aktualisiert. Die Systemverwaltung hat die"
                    + " Quellart „RSS-Feed“ gesperrt; der vorhandene Inhalt bleibt durchsuchbar."
                    + " Zuständig ist die Systemverwaltung.")),
        Arguments.of(
            EnumSet.of(Fact.CONNECTED, Fact.PROFILE_LOCK),
            ConnectionAuthMethod.PERSONAL_SECRET,
            new SourceBlock(
                Reason.PROFILE_LOCKED,
                "Systemverwaltung",
                "Gesperrt – Inhalt wird nicht mehr aktualisiert. Die Systemverwaltung hat den"
                    + " Zugang „Feeds“ gesperrt; der vorhandene Inhalt bleibt durchsuchbar."
                    + " Zuständig ist die Systemverwaltung.")),
        Arguments.of(
            EnumSet.of(Fact.CONNECTED, Fact.PROFILE_REMOVED),
            ConnectionAuthMethod.PERSONAL_SECRET,
            new SourceBlock(
                Reason.ACCESS_REMOVED,
                "Verwaltende der Bibliothek",
                "Zugang entfernt: Der Zugang dieser Bibliothek wurde gelöscht. Die Verwaltenden"
                    + " der Bibliothek ordnen sie einem anderen Zugang zu. Der Inhalt bleibt"
                    + " durchsuchbar, wird aber nicht mehr aktualisiert.")),
        Arguments.of(
            EnumSet.of(Fact.CONNECTED, Fact.OUTSIDE),
            ConnectionAuthMethod.PERSONAL_SECRET,
            new SourceBlock(
                Reason.TARGET_OUTSIDE_PROFILE,
                "Verwaltende der Bibliothek",
                "Die Adresse der Bibliothek liegt nicht unter der Server-Adresse des Zugangs"
                    + " \"Feeds\". Die Verwaltenden der Bibliothek passen die Adresse an. Der"
                    + " Inhalt bleibt durchsuchbar, wird aber nicht mehr aktualisiert.")),
        Arguments.of(
            EnumSet.of(Fact.CONNECTED, Fact.NO_SECRET),
            ConnectionAuthMethod.PERSONAL_SECRET,
            new SourceBlock(
                Reason.NOT_CONNECTED,
                "Verwaltende der Bibliothek",
                "Verbindung getrennt: Für den Zugang \"Feeds\" sind keine Zugangsdaten"
                    + " hinterlegt. Die Verwaltenden der Bibliothek tragen sie neu ein. Der"
                    + " Inhalt bleibt durchsuchbar, wird aber nicht mehr aktualisiert.")),
        Arguments.of(
            EnumSet.of(Fact.CONNECTED),
            ConnectionAuthMethod.OAUTH,
            new SourceBlock(
                Reason.NOT_CONNECTED,
                "Systemverwaltung",
                "Nicht verbunden: Die Anmeldeart des Zugangs \"Feeds\" wird für Bibliotheken"
                    + " noch nicht unterstützt. Zuständig ist die Systemverwaltung. Der Inhalt"
                    + " bleibt durchsuchbar, wird aber nicht mehr aktualisiert.")));
  }

  @ParameterizedTest(name = "{2}")
  @MethodSource("notices")
  void eachReasonCarriesItsResponsibleAndNotice(
      Set<Fact> facts, ConnectionAuthMethod method, SourceBlock expected) {
    KnowledgeLibrary library = arrange(facts, method);

    assertThat(blocks.blockOf(library, SourceBlocks.ALL)).contains(expected);
  }

  @Test
  void aConnectionWithoutSignInIsNotBlockedForAMissingSecret() {
    KnowledgeLibrary library =
        arrange(EnumSet.of(Fact.CONNECTED, Fact.NO_SECRET), ConnectionAuthMethod.NONE);

    assertThat(blocks.blockOf(library, SourceBlocks.ALL)).isEmpty();
  }

  @Test
  void onlyTheConsideredReasonsCountAndTheNextOneFollows() {
    KnowledgeLibrary library =
        arrange(
            EnumSet.of(Fact.CONNECTED, Fact.PROFILE_LOCK, Fact.OUTSIDE, Fact.NO_SECRET),
            ConnectionAuthMethod.PERSONAL_SECRET);

    assertThat(blocks.blockOf(library, SourceBlocks.LOCKS).map(SourceBlock::reason))
        .contains(Reason.PROFILE_LOCKED);
    assertThat(blocks.blockOf(library, SourceBlocks.ENDING_A_RUNNING_RUN).map(SourceBlock::reason))
        .contains(Reason.TARGET_OUTSIDE_PROFILE);
    assertThat(
            blocks
                .blockOf(library, EnumSet.of(Reason.ACCESS_REMOVED, Reason.NOT_CONNECTED))
                .map(SourceBlock::reason))
        .contains(Reason.NOT_CONNECTED);
    assertThat(blocks.blockOf(library, EnumSet.of(Reason.TYPE_LOCKED))).isEmpty();
  }

  @Test
  void manyLibrariesAreJudgedAsEachOnItsOwn() {
    List<KnowledgeLibrary> libraries = new ArrayList<>();
    // a type lock holds for every library of the type, so it is left out here
    precedence()
        .filter(arguments -> !((Set<?>) arguments.get()[0]).contains(Fact.TYPE_LOCK))
        .forEach(
            arguments -> {
              @SuppressWarnings("unchecked")
              Set<Fact> facts = (Set<Fact>) arguments.get()[0];
              libraries.add(arrange(facts, ConnectionAuthMethod.PERSONAL_SECRET));
            });

    Map<UUID, SourceBlock> among = blocks.blocksAmong(libraries, SourceBlocks.ALL);

    for (KnowledgeLibrary library : libraries) {
      assertThat(Optional.ofNullable(among.get(library.getId())))
          .isEqualTo(blocks.blockOf(library, SourceBlocks.ALL));
    }
    assertThat(blocks.blocksAmong(List.of(), SourceBlocks.ALL)).isEmpty();
  }

  @Test
  void requireUnblockedThrowsTheBlockOrHandsOutTheProfile() {
    KnowledgeLibrary blocked =
        arrange(EnumSet.of(Fact.CONNECTED, Fact.NO_SECRET), ConnectionAuthMethod.PERSONAL_SECRET);
    KnowledgeLibrary free =
        arrange(EnumSet.of(Fact.CONNECTED), ConnectionAuthMethod.PERSONAL_SECRET);
    KnowledgeLibrary own = arrange(EnumSet.noneOf(Fact.class), ConnectionAuthMethod.NONE);

    assertThatThrownBy(() -> blocks.requireUnblocked(blocked, SourceBlocks.ALL))
        .isInstanceOf(SourceConnectionBlockedException.class)
        .hasMessageStartingWith("Verbindung getrennt")
        .satisfies(
            e ->
                assertThat(((SourceConnectionBlockedException) e).block().reason())
                    .isEqualTo(Reason.NOT_CONNECTED));
    assertThat(blocks.requireUnblocked(blocked, SourceBlocks.LOCKS)).isPresent();
    assertThat(blocks.requireUnblocked(free, SourceBlocks.ALL).map(ConnectionProfile::getName))
        .contains("Feeds");
    assertThat(blocks.requireUnblocked(own, SourceBlocks.ALL)).isEmpty();
  }

  /** A library of its own type with the given facts, and the rows that state them. */
  private KnowledgeLibrary arrange(Set<Fact> facts, ConnectionAuthMethod method) {
    var type = SourceTypes.RSS_FEED;
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Feed",
            null,
            UUID.randomUUID(),
            SourceTypes.RSS_FEED,
            null,
            facts.contains(Fact.OUTSIDE)
                ? "https://elsewhere.example.org/a.xml"
                : SERVER + "/a.xml",
            null,
            facts.contains(Fact.NO_SECRET) ? null : "nutzer:geheim",
            false);
    rows.put(library.getId(), library);
    if (facts.contains(Fact.TYPE_LOCK) && policyRows.isEmpty()) {
      ConnectorTypePolicy policy = new ConnectorTypePolicy(type, NOW);
      policy.lockedSince(NOW, NOW);
      policyRows.add(policy);
    }
    if (facts.contains(Fact.CONNECTED)) {
      ConnectionProfile profile = new ConnectionProfile(type, NOW);
      profile.replace(
          new ConnectionProfileValues(
              "Feeds", SERVER, method, ConnectionOwnership.LIBRARY, null, null, null, null, null),
          null,
          NOW);
      if (facts.contains(Fact.PROFILE_LOCK)) {
        profile.lockedSince(NOW, NOW);
      }
      if (!facts.contains(Fact.PROFILE_REMOVED)) {
        profileRows.add(profile);
      }
      connectionRows.add(
          new LibraryConnection(
              library.getId(), facts.contains(Fact.PROFILE_REMOVED) ? null : profile.getId(), NOW));
    }
    return library;
  }

  private static Set<UUID> idsOf(Iterable<UUID> ids) {
    Set<UUID> set = new HashSet<>();
    ids.forEach(set::add);
    return set;
  }
}
