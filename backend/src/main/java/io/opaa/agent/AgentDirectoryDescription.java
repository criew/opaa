package io.opaa.agent;

import io.opaa.compliance.InstanceDisclosure.DataSetDescription;
import io.opaa.compliance.InstanceDisclosure.ProtocolField;
import io.opaa.compliance.InstanceDisclosure.QueryPath;
import io.opaa.compliance.InstanceStateSource;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The agent directory ({@link AgentDirectoryService}) described field by field for the staff
 * council: what it shows, who reads it and what it deliberately does not show. A new field of the
 * directory, or a new way to read it, changes this list in the same change.
 */
@Component
class AgentDirectoryDescription implements InstanceStateSource {

  static final String ID = "agent-directory";

  private static final String RETENTION = "Solange der Agent oder die Vorlage besteht";

  @Override
  public List<DataSetDescription> dataSetDescriptions() {
    return List.of(
        new DataSetDescription(
            ID,
            "Agentenverzeichnis",
            "Die Personalvertretung erkennt, welche Agenten und Vorlagen es gibt und was sie tun,"
                + " ohne dass jemand dafür Listen pflegt. Es beschreibt Objekte, keine Personen.",
            List.of(
                new ProtocolField(
                    "Agent",
                    "Name, Zweck und Anweisung jedes Agenten der Organisation",
                    "Was der Agent tun soll",
                    RETENTION),
                new ProtocolField(
                    "Wissen und Werkzeuge",
                    "Namen der zugeordneten Bibliotheken, nicht ihr Inhalt; Werkzeuge, Ablauf,"
                        + " Grenzen und die Namen der zugeordneten Vorlagen",
                    "Worauf der Agent zugreifen kann",
                    RETENTION),
                new ProtocolField(
                    "Vorlage",
                    "Name, Zweck, Dateiname und Platzhalter jeder Vorlage",
                    "Welche Dateien Agenten erstellen",
                    RETENTION),
                new ProtocolField(
                    "Verantwortung und Stand",
                    "Verantwortliche Stelle, Fassung und seit wann gesperrt; nicht der Grund der"
                        + " Sperre",
                    "Wer für das Objekt einsteht und welche Fassung gilt",
                    RETENTION)),
            List.of(
                new QueryPath(
                    "Agentenverzeichnis",
                    "Wer das Recht „Agentenverzeichnis einsehen“ hat, ab Werk nur die"
                        + " Systemverwaltung; ansehen und als CSV exportieren, jeder Zugriff im"
                        + " Revisionsprotokoll")),
            List.of(
                "Keine Angaben zur Nutzung: keine Zählung, keine Liste der Nutzenden, keine"
                    + " letzte Nutzung",
                "Kein Eigentümer und keine Person außer dem Namen einer verantwortlichen Gruppe",
                "Kein Inhalt einer Bibliothek",
                "Keine Änderungshistorie je Agent über die Fassungsnummer hinaus")));
  }
}
