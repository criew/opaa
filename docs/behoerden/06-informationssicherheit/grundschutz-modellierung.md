---
stand: 2026-10-23
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-23
---

# IT-Grundschutz

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

**Gilt für:** Bundesministerien verpflichtend; andere Behörden, wenn Landesrecht oder das eigene Haus
den IT-Grundschutz vorgibt oder sie ihn freiwillig anwenden.

Der IT-Grundschutz ist die Methode des BSI, mit der eine Behörde ihr Sicherheitskonzept aufstellt.
Die oder der Informationssicherheitsbeauftragte (ISB) nimmt OPAA darin auf und *modelliert* die
Installation, ordnet also jedem ihrer Teile die passenden *Bausteine* zu – Kapitel des
IT-Grundschutz-Kompendiums mit Anforderungen zu einem Thema wie Datenbanken oder Protokollierung.
Verantwortlich ist die Behörde; OPAA beschreibt, was das Produkt dazu beiträgt.

## Was OPAA liefert

- **Teile einer Installation** (Zielobjekte): Anwendung, Datenbank, Originalablage, Modelldienste,
  Container-Plattform, dazu Identitätsanbieter und Quellen des Hauses
  ([Betriebsübersicht](../../handbuch/betrieb.md#bausteine)).
- **Daten und Voreinstellungen** der eingesetzten Version: {{faktenblatt:datenkatalog}},
  {{faktenblatt:voreinstellungen}}; Nachweise des Projekts in den
  [Sicherheitsnachweisen](sicherheitsnachweise.md).
- **Je Release ein [Update-Paket](../../handbuch/releases-und-nachweise.md#das-update-paket)**, das bei jeder Änderung angibt,
  ob das Sicherheitskonzept fortzuschreiben ist.

Bausteine, zu denen das Produkt beiträgt, nach den Schichten des Kompendiums:

| Schicht | Baustein | Beitrag von OPAA |
|---|---|---|
| ORP – Organisation und Personal | ORP.4 Identitäts- und Berechtigungsmanagement | [Berechtigungen](../../handbuch/berechtigungen.md), {{faktenblatt:rollensicht}} |
| CON – Konzepte und Vorgehensweisen | CON.1 Kryptokonzept | [Schlüssel und Geheimnisse](../../handbuch/betrieb.md#schlüssel-und-geheimnisse) |
| | CON.3 Datensicherungskonzept | [Sicherung und Wiederherstellung](../../handbuch/betrieb.md#sicherung-und-wiederherstellung) |
| | CON.6 Löschen und Vernichten | {{faktenblatt:fristen}} |
| OPS – Betrieb | OPS.1.1.3 Patch- und Änderungsmanagement | [Sicherheitsupdates](../../handbuch/releases-und-nachweise.md#sicherheitsupdates), Update-Paket |
| | OPS.1.1.4 Schutz vor Schadprogrammen | nur zugelassene Formate, nach Inhalt geprüft ([Zugelassene Formate](../../handbuch/formate/README.md#zugelassene-formate)); keine eigene Virenprüfung |
| | OPS.1.1.5 Protokollierung | [Logs und Metriken](../../handbuch/betrieb.md#logs-und-metriken) |
| | OPS.2.2 Cloud-Nutzung, OPS.2.3 Nutzung von Outsourcing | bei externem Modellanbieter oder Betrieb durch einen Dienstleister; Ziele in {{faktenblatt:datenfluesse}} |
| DER – Detektion und Reaktion | DER.1 Detektion von sicherheitsrelevanten Ereignissen | [Logs und Metriken](../../handbuch/betrieb.md#logs-und-metriken) |
| | DER.2.1 Behandlung von Sicherheitsvorfällen | Meldeweg in [`SECURITY.md`](../../../SECURITY.md) |
| | DER.4 Notfallmanagement | [Notfallkonzept](../09-betrieb-update/notfallkonzept.md) |
| APP – Anwendungen | APP.3.1 Webanwendungen und Webservices | [Härtung](../../handbuch/betrieb.md#härtung-für-erreichbare-deployments) |
| | APP.4.3 Relationale Datenbanken | [Eigene PostgreSQL](../../handbuch/datenbank.md#voraussetzungen-einer-eigenen-postgresql) |
| | APP.4.4 Kubernetes | [Netz und Sicherheit](../../handbuch/kubernetes.md#netz-und-sicherheit) |
| SYS – IT-Systeme | SYS.1.6 Containerisierung | [Laufzeitimage](../../handbuch/betrieb.md#backend-laufzeitimage) |
| NET – Netze und Kommunikation | NET.1.1 Netzarchitektur und -design, NET.3.2 Firewall | {{faktenblatt:datenfluesse}}, [Ausgangsproxy](../../handbuch/betrieb.md#ausgangsproxy) |

Ein Baustein ist nicht erfüllt, nur weil OPAA seinen Anteil liefert; der Grundschutz-Check bewertet
das laufende System.

## Vorschlag zum Schutzbedarf

Der Schutzbedarf wird je Schutzziel festgelegt und von den Daten auf die Systeme vererbt. Für einen
typischen Einsatz als Wissensassistent:

| Schutzziel | Vorschlag | Begründung |
|---|---|---|
| Vertraulichkeit | hoch | folgt den sensibelsten angebundenen Dokumenten; Chats zeigen, wer was gefragt hat |
| Integrität | normal bis hoch | falsche oder veränderte Antworten können in Entscheidungen einfließen |
| Verfügbarkeit | normal | Hilfsmittel, kein zeitkritisches Fachverfahren; die Arbeit geht ohne OPAA weiter |

Ab „hoch“ verlangt der IT-Grundschutz zusätzlich eine Risikoanalyse.

## KI-Risiken

Für KI-Risiken gibt es keinen Baustein; sie gehören in die Risikoanalyse.

| Risiko | Was OPAA mitbringt | Was die Behörde festlegt |
|---|---|---|
| Falsche oder erfundene Antworten | Antworten mit Fundstellen ([Suche](../../handbuch/suche.md#7-belege-prüfen-und-fundstellen-bilden)) | Regel „Antwort ist ein Entwurf“ in der [Nutzungsrichtlinie](../08-nutzung/nutzungsrichtlinie.md) |
| Prompt-Injection: in Dokumenten versteckte Anweisungen an das Modell | keine eigene Erkennung | nur geprüfte Quellen anbinden, Schreibrechte an Quellen begrenzen |
| Abfluss von Inhalten an einen Modellanbieter | Modelle im Haus betreibbar, Ziele in {{faktenblatt:datenfluesse}} | Wahl des Anbieters, Vertrag, Netzregeln |
| Antworten aus Dokumenten ohne Leserecht | Suche nur in Dokumenten, die die Person lesen darf ([Berechtigungen](../../handbuch/berechtigungen.md)) | Rechtevergabe je Bibliothek |

## Aufgaben der Behörde

1. **Zielobjekte und Schutzbedarf festlegen**, etwa nach dem Vorschlag oben.
2. **Bausteine zuordnen** und den Anteil des Betriebs im Grundschutz-Check prüfen, etwa jede Zeile
   der Härtungstabelle im Handbuch.
3. **Risikoanalyse durchführen**, mindestens für die KI-Risiken oben.
4. **Bei jedem Update fortschreiben:** Release-Notizen und Update-Paket lesen, ein neues Ziel in den
   Datenflüssen als neue Verbindung aufnehmen, vorher sichern.

## Ergebnis festhalten

```text
Anwendung:           [Name der Installation], OPAA-Version [X.Y.Z], eingespielt am [Datum]
Betrieb:             [Docker Compose / Kubernetes], durch [Stelle oder Dienstleister]
Modelldienste:       [im Haus / extern bei Anbieter]
Schutzbedarf:        [normal / hoch / sehr hoch], begründet mit [ ]
Bausteine:           [Liste aus der Tabelle, angepasst]
KI-Risikoanalyse:    Stand [Datum]
Nächste Fortschreibung: mit dem nächsten Update, spätestens [Datum]
```

## Gut zu wissen

- OPAA hat keine Zulassung für Verschlusssachen, auch nicht für VS-NfD.
- Die Bausteinnamen folgen dem Kompendium, Edition 2023. Das BSI hat einen Nachfolger unter dem Namen
  Grundschutz++ angekündigt; die Behörde gleicht ab, welche Fassung sie anwendet.
- Wer OPAA in einer Cloud betreiben lässt, braucht zusätzlich [C5](c5-cloud-betrieb.md).

## Zum Nachlesen

**Arbeitshilfen** für die oder den ISB:

- [IT-Grundschutz-Kompendium](https://www.bsi.bund.de/DE/Themen/Unternehmen-und-Organisationen/Standards-und-Zertifizierung/IT-Grundschutz/IT-Grundschutz-Kompendium/it-grundschutz-kompendium_node.html):
  die Bausteine mit ihren Anforderungen.
- [BSI-Standards 200-2 bis 200-4](https://www.bsi.bund.de/DE/Themen/Unternehmen-und-Organisationen/Standards-und-Zertifizierung/IT-Grundschutz/BSI-Standards/bsi-standards_node.html):
  Vorgehen bei der Modellierung (200-2), Risikoanalyse für Themen ohne Baustein (200-3) und
  Notfallmanagement (200-4).
- [AIC4](https://www.bsi.bund.de/DE/Themen/Unternehmen-und-Organisationen/Informationen-und-Empfehlungen/Kuenstliche-Intelligenz/AIC4/aic4.html):
  Prüfkatalog des BSI für KI-Cloud-Dienste, als Ideengeber für die KI-Risikoanalyse; OPAA ist danach
  nicht geprüft, ebenso wenig nach dem neueren Prüfkatalog A5.
- [Releases und Nachweise](../../handbuch/releases-und-nachweise.md#was-ein-release-liefert): wie Release-Notizen
  datenrelevante Änderungen kennzeichnen.

**Rechtsgrundlagen:**

| Worum es geht | Fundstelle |
|---|---|
| IT-Grundschutz für Bundesministerien, Mindeststandards für die übrige Bundesverwaltung | BSI-Gesetz in der Fassung des [NIS2UmsuCG](https://www.recht.bund.de/bgbl/1/2025/301/VO.html), Paragraf zu prüfen |
| Länder und Kommunen | Landesrecht, zu prüfen |
