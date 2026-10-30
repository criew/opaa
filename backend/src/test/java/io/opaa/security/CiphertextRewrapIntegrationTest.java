package io.opaa.security;

import static io.opaa.security.CredentialsEncryptorTest.KEY;
import static io.opaa.security.CredentialsEncryptorTest.NEW_KEY;
import static io.opaa.security.CredentialsEncryptorTest.OTHER_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.test.OpaaIntegrationTest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * {@link CiphertextRewrap} against PostgreSQL after a key rotation (ADR-0064): every readable value
 * under an old key or in the {@code enc:v1:} format moves to the current key, a value it cannot
 * read stays as stored and is counted, cleartext is encrypted where it is used. The probe tables
 * are temporary tables of one held connection, so no other test sees them; the registered columns
 * of the application are checked against the schema.
 */
@OpaaIntegrationTest
class CiphertextRewrapIntegrationTest {

  private static final EncryptedColumn CREDENTIAL =
      EncryptedColumn.credentials("rewrap_probe", "credential");
  private static final EncryptedColumn SETTING =
      EncryptedColumn.settings("rewrap_probe", "setting");
  private static final EncryptedColumn STEP =
      EncryptedColumn.settings("rewrap_probe_steps", "secret").keyedBy("step");
  private static final EncryptedColumn TOKEN =
      EncryptedColumn.credentials("rewrap_probe_tokens", "token").refusingCleartext();
  private static final EncryptedColumns PROBE_COLUMNS =
      () -> List.of(CREDENTIAL, SETTING, STEP, TOKEN);

  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private List<EncryptedColumns> registered;

  private Connection connection;
  private SingleConnectionDataSource probe;
  private JdbcTemplate jdbc;

  @BeforeEach
  void createProbeTables() throws SQLException {
    connection = dataSource.getConnection();
    probe = new SingleConnectionDataSource(connection, true);
    jdbc = new JdbcTemplate(probe);
    jdbc.execute(
        "CREATE TEMPORARY TABLE rewrap_probe (id uuid PRIMARY KEY, credential text, setting text)");
    jdbc.execute("CREATE TEMPORARY TABLE rewrap_probe_steps (step text PRIMARY KEY, secret text)");
    jdbc.execute("CREATE TEMPORARY TABLE rewrap_probe_tokens (id uuid PRIMARY KEY, token text)");
  }

  @AfterEach
  void dropProbeTables() throws SQLException {
    jdbc.execute("DROP TABLE IF EXISTS rewrap_probe, rewrap_probe_steps, rewrap_probe_tokens");
    connection.close();
  }

  private static CredentialsEncryptor credentials(String key, String... previous) {
    return credentials(CipherFormatGate.KEYED, key, previous);
  }

  private static CredentialsEncryptor credentials(
      CipherFormatGate gate, String key, String... previous) {
    return new CredentialsEncryptor(
        new CredentialsEncryptionProperties(key, List.of(previous)), gate);
  }

  private static SettingsEncryptor settings(String key, String... previous) {
    return new SettingsEncryptor(
        new SettingsEncryptionProperties(key, List.of(previous)), CipherFormatGate.KEYED);
  }

  private CiphertextRewrap rewrap(
      CredentialsEncryptor credentials, SettingsEncryptor settings, CipherFormatGate gate) {
    return rewrap(credentials, settings, gate, LegacyCipherRetirement.NEVER);
  }

  private CiphertextRewrap rewrap(
      CredentialsEncryptor credentials,
      SettingsEncryptor settings,
      CipherFormatGate gate,
      LegacyCipherRetirement retirement) {
    return new CiphertextRewrap(
        jdbc,
        new DataSourceTransactionManager(probe),
        List.of(PROBE_COLUMNS),
        credentials,
        settings,
        gate,
        retirement);
  }

  /** Records what each run measured; refuses the purposes in {@link #retired}. */
  private static final class RecordedRetirement implements LegacyCipherRetirement {

    private final Set<EncryptionPurpose> retired = EnumSet.noneOf(EncryptionPurpose.class);
    private final Map<EncryptionPurpose, List<Integer>> measured =
        new EnumMap<>(EncryptionPurpose.class);

    @Override
    public boolean refusesLegacy(EncryptionPurpose purpose) {
      return retired.contains(purpose);
    }

    @Override
    public void measured(EncryptionPurpose purpose, int unbound) {
      measured.computeIfAbsent(purpose, p -> new ArrayList<>()).add(unbound);
    }
  }

  private CredentialsEncryptor credentials(LegacyCipherRetirement retirement) {
    return new CredentialsEncryptor(
        new CredentialsEncryptionProperties(KEY, List.of()), CipherFormatGate.KEYED, retirement);
  }

  @Test
  void aKeyedRunCountsTheUnboundValuesBeforeItMovesThemAndEncryptsCleartextThatIsUsed() {
    UUID row = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO rewrap_probe VALUES (?, ?, ?)",
        row,
        credentials(() -> false, KEY).encrypt("a-secret", CREDENTIAL.row(row)),
        new SettingsEncryptor(new SettingsEncryptionProperties(KEY, List.of()), () -> false)
            .encrypt("a-setting", SETTING.row(row)));
    UUID cleartext = insert("legacy-cleartext", null);
    insert("  \t", null);
    RecordedRetirement retirement = new RecordedRetirement();
    CiphertextRewrap rewrap =
        rewrap(credentials(retirement), settings(KEY), CipherFormatGate.KEYED, retirement);

    assertThat(rewrap.rewrap()).isEqualTo(new CiphertextRewrap.Outcome(3, 0, 0, false));
    rewrap.rewrap();

    assertThat(retirement.measured)
        .containsEntry(EncryptionPurpose.CREDENTIALS, List.of(2, 0))
        .containsEntry(EncryptionPurpose.SETTINGS, List.of(1, 0));
    assertThat(credentialOf(row)).startsWith("enc:v2:");
    assertThat(credentialOf(cleartext)).startsWith("enc:v2:");
    assertThat(credentials(KEY).decrypt(credentialOf(cleartext), CREDENTIAL.row(cleartext)))
        .isEqualTo("legacy-cleartext");
  }

  @Test
  void cleartextOfAColumnThatRefusesItIsNeitherEncryptedNorWaitedFor() {
    UUID row = UUID.randomUUID();
    jdbc.update("INSERT INTO rewrap_probe_tokens VALUES (?, 'injected-token')", row);
    RecordedRetirement retirement = new RecordedRetirement();

    rewrap(credentials(retirement), settings(KEY), CipherFormatGate.KEYED, retirement).rewrap();

    assertThat(retirement.measured).containsEntry(EncryptionPurpose.CREDENTIALS, List.of(0));
    assertThat(
            jdbc.queryForObject(
                "SELECT token FROM rewrap_probe_tokens WHERE id = ?", String.class, row))
        .isEqualTo("injected-token");
  }

  @Test
  void onceRetiredAnUnboundValueIsNeitherMovedNorMeasuredAndAHeldBackRunMeasuresNothing() {
    UUID source = UUID.randomUUID();
    UUID copied =
        insert(credentials(() -> false, KEY).encrypt("a-secret", CREDENTIAL.row(source)), null);
    UUID cleartext = insert("injected-cleartext", null);
    RecordedRetirement retirement = new RecordedRetirement();
    retirement.retired.add(EncryptionPurpose.CREDENTIALS);

    CiphertextRewrap.Outcome outcome =
        rewrap(credentials(retirement), settings(KEY), CipherFormatGate.KEYED, retirement).rewrap();

    assertThat(outcome.unreadable()).as("the copied enc:v1 value is not even selected").isZero();
    assertThat(credentialOf(copied)).startsWith("enc:v1:");
    assertThat(credentialOf(cleartext)).isEqualTo("injected-cleartext");
    assertThat(retirement.measured).doesNotContainKey(EncryptionPurpose.CREDENTIALS);

    RecordedRetirement heldBack = new RecordedRetirement();
    rewrap(credentials(KEY), settings(KEY), () -> false, heldBack).rewrap();
    assertThat(heldBack.measured).isEmpty();
  }

  @Test
  void theStoredRetirementNeedsTwoMeasurementsApartIsClearedByTheWayBackAndThenRefuses()
      throws SQLException {
    UUID keyed = UUID.randomUUID();
    String keyedValue = credentials(KEY).encrypt("a-secret", CREDENTIAL.row(keyed));
    UUID legacy = UUID.randomUUID();
    String legacyValue = credentials(() -> false, KEY).encrypt("b-secret", CREDENTIAL.row(legacy));
    String legacySetting =
        new SettingsEncryptor(new SettingsEncryptionProperties(KEY, List.of()), () -> false)
            .encrypt("sk-secret", SETTING.row(legacy));
    try (Connection held = dataSource.getConnection()) {
      held.setAutoCommit(false);
      JdbcTemplate inTransaction = new JdbcTemplate(new SingleConnectionDataSource(held, true));
      try {
        StoredLegacyCipherRetirement retirement = stored(true, inTransaction);
        retirement.measured(EncryptionPurpose.CREDENTIALS, 0);
        retirement.measured(EncryptionPurpose.CREDENTIALS, 0);
        assertThat(stored(true, inTransaction).refusesLegacy(EncryptionPurpose.CREDENTIALS))
            .as("not yet confirmed")
            .isFalse();

        backdate(inTransaction);
        retirement.measured(EncryptionPurpose.CREDENTIALS, 1);
        retirement.measured(EncryptionPurpose.CREDENTIALS, 0);
        assertThat(stored(true, inTransaction).refusesLegacy(EncryptionPurpose.CREDENTIALS))
            .as("a value in between starts the wait anew")
            .isFalse();

        backdate(inTransaction);
        retirement.measured(EncryptionPurpose.CREDENTIALS, 0);
        retirement.measured(EncryptionPurpose.SETTINGS, 0);
        backdate(inTransaction);
        retirement.measured(EncryptionPurpose.SETTINGS, 0);

        StoredLegacyCipherRetirement elsewhere = stored(true, inTransaction);
        CredentialsEncryptor reader =
            new CredentialsEncryptor(
                new CredentialsEncryptionProperties(KEY, List.of()), () -> false, elsewhere);
        assertThat(reader.decrypt(keyedValue, CREDENTIAL.row(keyed))).isEqualTo("a-secret");
        assertThatThrownBy(() -> reader.decrypt(legacyValue, CREDENTIAL.row(legacy)))
            .isInstanceOf(CredentialsEncryptionKeyMissingException.class)
            .hasMessageContaining("nicht mehr zugelassenen Format");
        assertThatThrownBy(() -> reader.decrypt("admin:cleartext", CREDENTIAL.row(legacy)))
            .isInstanceOf(CredentialsEncryptionKeyMissingException.class);
        assertThat(reader.encrypt("c-secret", CREDENTIAL.row(keyed)))
            .as("keyed although the gate is closed")
            .startsWith("enc:v2:");
        SettingsEncryptor settingsReader =
            new SettingsEncryptor(
                new SettingsEncryptionProperties(KEY, List.of()), () -> false, elsewhere);
        assertThatThrownBy(() -> settingsReader.decrypt(legacySetting, SETTING.row(legacy)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("enc:v1");

        assertThat(stored(true, inTransaction, true).refusesLegacy(EncryptionPurpose.CREDENTIALS))
            .as("an instance of another version ran since")
            .isFalse();
        stored(true, inTransaction, true).measured(EncryptionPurpose.SETTINGS, 0);
        assertThat(stored(true, inTransaction).refusesLegacy(EncryptionPurpose.SETTINGS))
            .as("the lapsed retirement is deleted and measured anew")
            .isFalse();
        assertThat(stored(true, inTransaction).refusesLegacy(EncryptionPurpose.CREDENTIALS))
            .isTrue();

        StoredLegacyCipherRetirement wayBack = stored(false, inTransaction);
        assertThat(wayBack.refusesLegacy(EncryptionPurpose.CREDENTIALS)).isFalse();
        wayBack.run(null);
        assertThat(stored(true, inTransaction).refusesLegacy(EncryptionPurpose.CREDENTIALS))
            .as("OPAA_ENCRYPTION_KEYED_FORMAT=false clears the retirement at start")
            .isFalse();
      } finally {
        held.rollback();
      }
    }
  }

  private static StoredLegacyCipherRetirement stored(boolean keyed, JdbcTemplate jdbc) {
    return stored(keyed, jdbc, false);
  }

  private static StoredLegacyCipherRetirement stored(
      boolean keyed, JdbcTemplate jdbc, boolean anotherVersionRan) {
    return new StoredLegacyCipherRetirement(
        keyed, () -> jdbc, since -> anotherVersionRan, Duration.ofMinutes(1), System::nanoTime);
  }

  /**
   * Moves every pending measurement back beyond {@link
   * StoredLegacyCipherRetirement#MIN_CONFIRMATION}.
   */
  private static void backdate(JdbcTemplate jdbc) {
    jdbc.update(
        "UPDATE cipher_legacy_retirements SET measured_since = measured_since - interval '6 minutes'"
            + " WHERE retired_at IS NULL");
  }

  private UUID insert(String credential, String setting) {
    UUID id = UUID.randomUUID();
    jdbc.update("INSERT INTO rewrap_probe VALUES (?, ?, ?)", id, credential, setting);
    return id;
  }

  private String credentialOf(UUID id) {
    return jdbc.queryForObject(
        "SELECT credential FROM rewrap_probe WHERE id = ?", String.class, id);
  }

  @Test
  void afterARotationEveryReadableValueMovesToTheNewKeyAndTheRestStaysAsStored() {
    CredentialsEncryptor before = credentials(KEY);
    SettingsEncryptor settingsBefore = settings(KEY);
    CredentialsEncryptor rotated = credentials(NEW_KEY, KEY);
    CredentialsEncryptor newOnly = credentials(NEW_KEY);
    SettingsEncryptor settingsNewOnly = settings(NEW_KEY);

    UUID keyed = UUID.randomUUID();
    String keyedValue = before.encrypt("a-secret", CREDENTIAL.row(keyed));
    jdbc.update(
        "INSERT INTO rewrap_probe VALUES (?, ?, ?)",
        keyed,
        keyedValue,
        settingsBefore.encrypt("a-setting", SETTING.row(keyed)));
    UUID legacyFormat = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO rewrap_probe VALUES (?, ?, NULL)",
        legacyFormat,
        credentials(() -> false, KEY).encrypt("b-secret", CREDENTIAL.row(legacyFormat)));
    UUID cleartext = insert("legacy-cleartext", null);
    UUID empty = insert(null, null);
    UUID lostKey = UUID.randomUUID();
    String lostKeyValue = credentials(OTHER_KEY).encrypt("lost", CREDENTIAL.row(lostKey));
    jdbc.update("INSERT INTO rewrap_probe VALUES (?, ?, NULL)", lostKey, lostKeyValue);
    UUID copied = insert(keyedValue, null);
    UUID current = UUID.randomUUID();
    String currentValue = rotated.encrypt("already", CREDENTIAL.row(current));
    jdbc.update("INSERT INTO rewrap_probe VALUES (?, ?, NULL)", current, currentValue);
    jdbc.update(
        "INSERT INTO rewrap_probe_steps VALUES ('CHAT_MODEL', ?)",
        settingsBefore.encrypt("sk-step", STEP.row("CHAT_MODEL")));

    CiphertextRewrap.Outcome outcome =
        rewrap(rotated, settings(NEW_KEY, KEY), CipherFormatGate.KEYED).rewrap();

    assertThat(outcome).isEqualTo(new CiphertextRewrap.Outcome(5, 2, 0, false));
    assertThat(newOnly.decrypt(credentialOf(keyed), CREDENTIAL.row(keyed))).isEqualTo("a-secret");
    assertThat(
            settingsNewOnly.decrypt(
                jdbc.queryForObject(
                    "SELECT setting FROM rewrap_probe WHERE id = ?", String.class, keyed),
                SETTING.row(keyed)))
        .isEqualTo("a-setting");
    assertThat(newOnly.decrypt(credentialOf(legacyFormat), CREDENTIAL.row(legacyFormat)))
        .isEqualTo("b-secret");
    assertThat(
            settingsNewOnly.decrypt(
                jdbc.queryForObject(
                    "SELECT secret FROM rewrap_probe_steps WHERE step = 'CHAT_MODEL'",
                    String.class),
                STEP.row("CHAT_MODEL")))
        .isEqualTo("sk-step");
    assertThat(newOnly.decrypt(credentialOf(cleartext), CREDENTIAL.row(cleartext)))
        .isEqualTo("legacy-cleartext");
    assertThat(credentialOf(empty)).isNull();
    assertThat(credentialOf(lostKey)).as("no key reads it").isEqualTo(lostKeyValue);
    assertThat(credentialOf(copied)).as("bound to another row").isEqualTo(keyedValue);
    assertThat(credentialOf(current)).isEqualTo(currentValue);
  }

  @Test
  void aSecondRunFindsNothingLeftButTheUnreadable() {
    CredentialsEncryptor rotated = credentials(NEW_KEY, KEY);
    UUID row = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO rewrap_probe VALUES (?, ?, NULL)",
        row,
        credentials(KEY).encrypt("a-secret", CREDENTIAL.row(row)));
    UUID lostKey = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO rewrap_probe VALUES (?, ?, NULL)",
        lostKey,
        credentials(OTHER_KEY).encrypt("lost", CREDENTIAL.row(lostKey)));
    CiphertextRewrap rewrap = rewrap(rotated, settings(NEW_KEY, KEY), CipherFormatGate.KEYED);

    assertThat(rewrap.rewrap()).isEqualTo(new CiphertextRewrap.Outcome(1, 1, 0, false));
    assertThat(rewrap.rewrap()).isEqualTo(new CiphertextRewrap.Outcome(0, 1, 0, false));
  }

  @Test
  void moreRowsThanOnePageAreAllMovedInOneRun() {
    CredentialsEncryptor before = credentials(KEY);
    int rows = CiphertextRewrap.PAGE_SIZE * 2 + 50;
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < rows; i++) {
      UUID id = UUID.randomUUID();
      ids.add(id);
      jdbc.update(
          "INSERT INTO rewrap_probe VALUES (?, ?, NULL)",
          id,
          before.encrypt("secret-" + i, CREDENTIAL.row(id)));
    }

    CiphertextRewrap.Outcome outcome =
        rewrap(credentials(NEW_KEY, KEY), settings(NEW_KEY, KEY), CipherFormatGate.KEYED).rewrap();

    assertThat(outcome).isEqualTo(new CiphertextRewrap.Outcome(rows, 0, 0, false));
    CredentialsEncryptor newOnly = credentials(NEW_KEY);
    for (int i = 0; i < rows; i++) {
      UUID id = ids.get(i);
      assertThat(newOnly.decrypt(credentialOf(id), CREDENTIAL.row(id))).isEqualTo("secret-" + i);
    }
  }

  @Test
  void unreadableValuesDoNotUseUpTheBudgetOfTheReadableOnesBehindThem() {
    UUID lostRow = UUID.randomUUID();
    String lost = credentials(OTHER_KEY).encrypt("lost", CREDENTIAL.row(lostRow));
    int unreadable = CiphertextRewrap.RUN_BUDGET + 10;
    jdbc.update(
        "INSERT INTO rewrap_probe SELECT gen_random_uuid(), ?, NULL FROM generate_series(1, ?)",
        lost,
        unreadable);
    UUID last = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
    jdbc.update(
        "INSERT INTO rewrap_probe VALUES (?, ?, NULL)",
        last,
        credentials(KEY).encrypt("hinten", CREDENTIAL.row(last)));

    CiphertextRewrap.Outcome outcome =
        rewrap(credentials(NEW_KEY, KEY), settings(NEW_KEY, KEY), CipherFormatGate.KEYED).rewrap();

    assertThat(outcome).isEqualTo(new CiphertextRewrap.Outcome(1, unreadable, 0, false));
    assertThat(credentials(NEW_KEY).decrypt(credentialOf(last), CREDENTIAL.row(last)))
        .isEqualTo("hinten");
  }

  /**
   * With the keyed format held back ({@code OPAA_ENCRYPTION_KEYED_FORMAT=false}, or an older
   * instance still running) a rotation still completes: every value moves to the current key and
   * keeps its format, so an {@code enc:v1:} value stays readable for an older version given the
   * current key alone.
   */
  @Test
  void whileTheKeyedFormatIsHeldBackARotationKeepsTheFormatAndMovesToTheCurrentKey() {
    CipherFormatGate heldBack = () -> false;
    UUID oldKey = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO rewrap_probe VALUES (?, ?, NULL)",
        oldKey,
        credentials(heldBack, KEY).encrypt("a-secret", CREDENTIAL.row(oldKey)));
    UUID keyedOldKey = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO rewrap_probe VALUES (?, ?, NULL)",
        keyedOldKey,
        credentials(KEY).encrypt("b-secret", CREDENTIAL.row(keyedOldKey)));
    UUID alreadyCurrent = UUID.randomUUID();
    String currentValue =
        credentials(heldBack, NEW_KEY).encrypt("c-secret", CREDENTIAL.row(alreadyCurrent));
    jdbc.update("INSERT INTO rewrap_probe VALUES (?, ?, NULL)", alreadyCurrent, currentValue);
    UUID lostKey = UUID.randomUUID();
    String lostValue = credentials(heldBack, OTHER_KEY).encrypt("lost", CREDENTIAL.row(lostKey));
    jdbc.update("INSERT INTO rewrap_probe VALUES (?, ?, NULL)", lostKey, lostValue);
    CiphertextRewrap rewrap =
        rewrap(credentials(heldBack, NEW_KEY, KEY), settings(NEW_KEY, KEY), heldBack);

    assertThat(rewrap.rewrap()).isEqualTo(new CiphertextRewrap.Outcome(2, 1, 0, true));
    assertThat(rewrap.rewrap()).isEqualTo(new CiphertextRewrap.Outcome(0, 1, 0, true));

    CredentialsEncryptor newOnly = credentials(heldBack, NEW_KEY);
    assertThat(credentialOf(oldKey)).startsWith("enc:v1:");
    assertThat(newOnly.decrypt(credentialOf(oldKey), CREDENTIAL.row(oldKey))).isEqualTo("a-secret");
    assertThat(credentialOf(keyedOldKey)).startsWith("enc:v2:" + newOnly.currentKeyId() + ":");
    assertThat(newOnly.decrypt(credentialOf(keyedOldKey), CREDENTIAL.row(keyedOldKey)))
        .isEqualTo("b-secret");
    assertThat(credentialOf(alreadyCurrent)).isEqualTo(currentValue);
    assertThat(credentialOf(lostKey)).isEqualTo(lostValue);
  }

  /**
   * Held back without a decrypt-only key, a run selects - and so reads and locks - no {@code
   * enc:v1:} value: a corrupt one is not even counted, and only keyed values under another key are
   * looked at.
   */
  @Test
  void heldBackWithoutAPreviousKeyTheRunLeavesEveryUnkeyedValueAlone() {
    CipherFormatGate heldBack = () -> false;
    UUID current = UUID.randomUUID();
    String currentValue =
        credentials(heldBack, NEW_KEY).encrypt("a-secret", CREDENTIAL.row(current));
    jdbc.update("INSERT INTO rewrap_probe VALUES (?, ?, NULL)", current, currentValue);
    UUID corrupt = insert("enc:v1:kein-geheimtext", null);
    UUID foreign = UUID.randomUUID();
    String foreignValue = credentials(OTHER_KEY).encrypt("lost", CREDENTIAL.row(foreign));
    jdbc.update("INSERT INTO rewrap_probe VALUES (?, ?, NULL)", foreign, foreignValue);

    CiphertextRewrap.Outcome outcome =
        rewrap(credentials(heldBack, NEW_KEY), settings(NEW_KEY), heldBack).rewrap();

    assertThat(outcome)
        .as(
            "only the keyed value under another key is selected; a selected corrupt one would count")
        .isEqualTo(new CiphertextRewrap.Outcome(0, 1, 0, true));
    assertThat(credentialOf(current)).isEqualTo(currentValue);
    assertThat(credentialOf(corrupt)).isEqualTo("enc:v1:kein-geheimtext");
    assertThat(credentialOf(foreign)).isEqualTo(foreignValue);
  }

  @Test
  void withoutAKeyForAPurposeItsColumnsAreLeftAlone() {
    UUID row = UUID.randomUUID();
    String stored = credentials(KEY).encrypt("a-secret", CREDENTIAL.row(row));
    jdbc.update("INSERT INTO rewrap_probe VALUES (?, ?, NULL)", row, stored);

    CiphertextRewrap.Outcome outcome =
        rewrap(credentials(null), settings(NEW_KEY, KEY), CipherFormatGate.KEYED).rewrap();

    assertThat(outcome).isEqualTo(new CiphertextRewrap.Outcome(0, 0, 0, false));
    assertThat(credentialOf(row)).isEqualTo(stored);
  }

  /**
   * Every registered column exists with its row key, and every column of the schema named like a
   * ciphertext is registered - a new encrypted column arrives here before it can be left out of the
   * re-encryption.
   */
  @Test
  void theRegisteredColumnsAreTheCiphertextColumnsOfTheSchema() {
    Set<String> columns = new HashSet<>();
    for (EncryptedColumns source : registered) {
      for (EncryptedColumn column : source.encryptedColumns()) {
        jdbcTemplate.queryForList(
            "SELECT "
                + column.idColumn()
                + ", "
                + column.column()
                + " FROM "
                + column.table()
                + " LIMIT 0");
        assertThat(columns.add(column.table() + "." + column.column()))
            .as("registered once: %s", column)
            .isTrue();
      }
    }

    List<String> ciphertextColumns =
        jdbcTemplate.queryForList(
            "SELECT table_name || '.' || column_name FROM information_schema.columns"
                + " WHERE table_schema = current_schema() AND column_name LIKE '%\\_ciphertext'",
            String.class);
    assertThat(columns)
        .containsAll(ciphertextColumns)
        .contains(
            "knowledge_libraries.source_credentials",
            "knowledge_libraries.source_webhook_secret",
            "installation_steps.secret");
  }
}
