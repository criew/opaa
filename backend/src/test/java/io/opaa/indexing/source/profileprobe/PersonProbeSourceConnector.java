package io.opaa.indexing.source.profileprobe;

import io.opaa.api.types.ConnectionOwnership;
import io.opaa.api.types.ConnectionProfileSupport;
import io.opaa.api.types.PersonalSecretForm;
import io.opaa.common.ValidationException;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.ProfileDeclaration;
import io.opaa.indexing.source.ServerAddressRule;
import io.opaa.indexing.source.SignIn;
import io.opaa.indexing.source.SourceBrowser;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceConnector;
import io.opaa.indexing.source.SourceConnectorDescriptor;
import io.opaa.indexing.source.SourceListing;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.SourceType;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * A connector that exists only in test code and lets persons connect their own account: its
 * personal secret is a user name and a password, owned by a library or a person. Its sign-in takes
 * only the password {@link #ACCEPTED_PASSWORD}; any other is rejected as a provider would. A {@code
 * share} setting binds its secret, as a file server does. Its listing names one folder for the
 * accepted password, and it refuses a change onto {@link #REFUSED_HOST} with a reason naming a
 * folder.
 */
@Component
public class PersonProbeSourceConnector implements SourceConnector, SourceBrowser {

  public static final SourceType TYPE = SourceType.of("PERSON_PROBE");

  /** The one password the probe's sign-in accepts, for any user name. */
  public static final String ACCEPTED_PASSWORD = "richtig-2163";

  /** A host the probe refuses a change onto. */
  public static final String REFUSED_HOST = "abgelehnt.example.org";

  /** The folder its refusal names, which no administration may read. */
  public static final String REFUSED_FOLDER = "Gehaltsabrechnungen Vogt";

  /** The one folder its listing names. */
  public static final String LISTED_FOLDER = "ablage";

  @Override
  public SourceConnectorDescriptor descriptor() {
    return SourceConnectorDescriptor.remoteRun(TYPE, "Testquelle für Personen")
        .withProfiles(
            ProfileDeclaration.of(
                    ConnectionProfileSupport.OPTIONAL,
                    SignIn.personalSecret(
                        PersonalSecretForm.USERNAME_AND_PASSWORD,
                        ConnectionOwnership.LIBRARY,
                        ConnectionOwnership.PERSON))
                .withAddress(ServerAddressRule.schemes("https")));
  }

  @Override
  public java.util.Set<String> settingsKeys() {
    return java.util.Set.of("share");
  }

  @Override
  public ConnectorData readSettings(ConnectorData requested) {
    requested.requireOnly(settingsKeys());
    return requested.isEmpty() ? null : requested;
  }

  /** The probe binds a secret to its {@code share}, as a file server does. */
  @Override
  public String credentialBinding(SourceSettings settings) {
    Object share =
        settings.connectorSettings() == null ? null : settings.connectorSettings().get("share");
    return share == null ? null : share.toString();
  }

  @Override
  public SourceSettings validate(SourceSettings requested) {
    return requested;
  }

  @Override
  public SourceSettings validateChange(
      SourceSettings stored, SourceSettings requested, boolean replacesConnection) {
    if (requested.sourceUrl() != null && requested.sourceUrl().contains(REFUSED_HOST)) {
      throw new ValidationException(
          "Der Ordner „" + REFUSED_FOLDER + "“ ist auf diesem Server nicht erreichbar.");
    }
    return requested;
  }

  @Override
  public void configureNew(KnowledgeLibrary library, SourceSettings validated) {
    if (validated.connectorSettings() != null) {
      library.updateSourceSettings(validated.connectorSettings().toJson());
    }
  }

  @Override
  public void applyChange(KnowledgeLibrary library, ConnectorData own, SourceSettings validated) {
    if (validated.connectorSettings() != null) {
      library.updateSourceSettings(validated.connectorSettings().toJson());
    }
  }

  @Override
  public String otherTypeMessage() {
    return "sourceType passt nicht zur Testquelle für Personen";
  }

  @Override
  public SourceListing browse(Query query) {
    String credentials = query.settings().sourceCredentials();
    if (credentials != null && credentials.endsWith(":" + ACCEPTED_PASSWORD)) {
      return new SourceListing(
          true, List.of(new SourceListing.Entry(LISTED_FOLDER, LISTED_FOLDER)), null);
    }
    return new SourceListing(false, List.of(), "Die Anmeldung bei der Testquelle fehlt.");
  }

  @Override
  public SourceConnectionTestResult testConnection(SourceSettings settings, ConnectorData stored) {
    String credentials = settings.sourceCredentials();
    if (credentials != null && credentials.endsWith(":" + ACCEPTED_PASSWORD)) {
      return new SourceConnectionTestResult(true, "Testquelle für Personen erreichbar.", 0L);
    }
    return new SourceConnectionTestResult(
        false, "Die Anmeldung bei der Testquelle wurde abgelehnt.", null, false, null);
  }
}
