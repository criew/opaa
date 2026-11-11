package io.opaa.chat;

import io.opaa.compliance.InstanceDisclosure.DataSetDescription;
import io.opaa.compliance.InstanceDisclosure.ProtocolField;
import io.opaa.compliance.InstanceDisclosure.QueryPath;
import io.opaa.compliance.InstanceStateSource;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The version stamp of an agent answer ({@link AgentRunStamp}) described field by field for the
 * staff council (ADR-0070, Entscheidung 4, Bestand a). A new part of the stamp, or a new way to
 * read it, changes this list in the same change.
 */
@Component
class AgentRunStampDescription implements InstanceStateSource {

  static final String ID = "agent-answer-stamp";

  private static final String RETENTION =
      "Wie der Chat: bis die Person ihn löscht, die Bereinigung des Space oder die Löschung nach"
          + " Sperre des Kontos greift";

  @Override
  public List<DataSetDescription> dataSetDescriptions() {
    return List.of(
        new DataSetDescription(
            ID,
            "Fassungsstempel an der Agentenantwort",
            "Die Person erkennt, womit eine Agentenantwort entstanden ist, und dass sie ein"
                + " ungeprüfter Entwurf ist. Gespeichert an der Antwort im Chat der Person.",
            List.of(
                new ProtocolField(
                    "Agent",
                    "Kennung und Name des gewählten Agenten zum Zeitpunkt der Antwort",
                    "Welcher Agent geantwortet hat",
                    RETENTION),
                new ProtocolField(
                    "Fassung des Agenten",
                    "Nummer der Fassung, mit der der Lauf arbeitete",
                    "Vergleich mit der aktuellen Fassung des Agenten",
                    RETENTION),
                new ProtocolField(
                    "Bibliotheken",
                    "Kennung und Name der durchsuchbaren Bibliotheken zum Zeitpunkt, nur solche,"
                        + " die die Person lesen durfte",
                    "Worauf die Antwort sich stützen konnte",
                    RETENTION),
                new ProtocolField(
                    "Vorlagen",
                    "Name und Fassung jeder ausgefüllten Vorlage",
                    "Nach welcher Vorlage eine Datei entstand",
                    RETENTION),
                new ProtocolField(
                    "Modell", "Kennung des Chat-Modells", "Mit welchem Modell", RETENTION)),
            List.of(
                new QueryPath(
                    "eigene Antwort",
                    "Die Person an ihrer Antwort im Chat und in einer kopierten oder exportierten"
                        + " Antwort; eine Datei nach Vorlage trägt den Stempel in Fußzeile und"
                        + " Dokumenteigenschaften"),
                new QueryPath("Selbstauskunft", "Die Person in ihrer eigenen Selbstauskunft")),
            List.of(
                "Keine Ansicht für Verwaltung, Revision oder Eigentümer eines Agenten",
                "Keine Auswertung, wer wann welchen Agenten, welche Fassung, welche Bibliothek,"
                    + " welche Vorlage oder welches Modell genutzt hat",
                "Keine Zählung der Nutzung je Agent oder je Person",
                "Kein Eintrag im Revisionsprotokoll für den Aufruf eines Agenten")));
  }
}
