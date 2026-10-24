---
stand: 2026-10-24
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-23
---

# IT-Grundschutz

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

**Gilt für:** Einrichtungen der Bundesverwaltung verpflichtend; andere Behörden, wenn Landesrecht
oder das eigene Haus den IT-Grundschutz vorgibt oder sie ihn freiwillig anwenden.

Der IT-Grundschutz ist die Methode des BSI, mit der eine Behörde ihr Sicherheitskonzept aufstellt.
Die oder der Informationssicherheitsbeauftragte (ISB) nimmt OPAA darin auf und *modelliert* die
Installation, ordnet also jedem ihrer Teile die passenden *Bausteine* zu – Kapitel des
IT-Grundschutz-Kompendiums mit Anforderungen zu einem Thema wie Datenbanken oder Protokollierung.
Verantwortlich ist die Behörde; OPAA beschreibt, was das Produkt dazu beiträgt.

## Was OPAA liefert

- **Teile einer Installation** (Zielobjekte): Anwendung, Datenbank, Originalablage, Modelldienste,
  Container-Plattform, dazu Identitätsanbieter und Quellen des Hauses
  ([Betriebsübersicht](../../handbuch/betrieb/README.md#bausteine)).
- **Daten und Voreinstellungen:** {{faktenblatt:datenkatalog}},
  {{faktenblatt:voreinstellungen}}; Nachweise des Projekts in den
  [Sicherheitsnachweisen](sicherheitsnachweise.md).
- **Je Release ein [Update-Paket](../../handbuch/betrieb/releases-und-nachweise.md#das-update-paket)**, das bei jeder Änderung angibt,
  ob das Sicherheitskonzept fortzuschreiben ist.

Bausteine, zu denen das Produkt beiträgt, nach den Schichten des Kompendiums (Edition 2023):

| Schicht | Baustein | Beitrag von OPAA |
|---|---|---|
| ORP – Organisation und Personal | ORP.4 Identitäts- und Berechtigungsmanagement | [Berechtigungen](../../handbuch/konten-und-rechte/berechtigungen.md), {{faktenblatt:rollensicht}}, Zugangstokens ([Externe KI-Werkzeuge](../../handbuch/spaces/fremdzugaenge.md)) |
| CON – Konzepte und Vorgehensweisen | CON.1 Kryptokonzept | [Schlüssel und Geheimnisse](../../handbuch/betrieb/netz-und-sicherheit.md#schlüssel-und-geheimnisse) |
|  | CON.2 Datenschutz | [Pseudonymisierung im Protokoll](../../handbuch/betrieb/nachvollziehbarkeit.md#pseudonymisierung-im-protokoll), [Auskunft über die Datenerhebung](../../handbuch/betrieb/nachvollziehbarkeit.md#auskunft-über-die-datenerhebung-einer-installation), Vorlagen unter [Datenschutz](../04-datenschutz/README.md) |
|  | CON.3 Datensicherungskonzept | [Sicherung und Wiederherstellung](../../handbuch/betrieb/sicherung-und-wiederherstellung.md), [Originalablage sichern](../../handbuch/betrieb/originalablage.md#sicherung-und-wiederherstellung), [Wiederherstellungsprobe](../09-betrieb-update/wiederherstellungsprobe.md) |
|  | CON.6 Löschen und Vernichten | {{faktenblatt:fristen}}, [Löschkonzept](../04-datenschutz/loeschkonzept.md), [Löschen oder sperren](../../handbuch/konten-und-rechte/benutzerverwaltung.md#8-löschen-oder-sperren) |
|  | CON.9 Informationsaustausch (je nach Einsatz) | externe KI-Werkzeuge: [Freigabe je Bibliothek](../../handbuch/spaces/fremdzugaenge.md#4-die-freigabe-einer-wissensbibliothek), [Kontingent und Abflussalarm](../../handbuch/spaces/fremdzugaenge.md#10-kontingent-und-abflussalarm) |
| OPS – Betrieb | OPS.1.1.2 Ordnungsgemäße IT-Administration | [Systemverwalter-Konto beim Erststart](../../handbuch/betrieb/authentifizierung.md#erststart-und-systemverwalter-konto), [Rollen](../../handbuch/konten-und-rechte/benutzerverwaltung.md#7-rollen), [Notfallzugang](../../handbuch/betrieb/authentifizierung.md#notfallprozedur-wieder-hereinkommen) |
|  | OPS.1.1.3 Patch- und Änderungsmanagement | [Sicherheitsupdates](../../handbuch/betrieb/releases-und-nachweise.md#sicherheitsupdates), [Update-Paket](../../handbuch/betrieb/releases-und-nachweise.md#das-update-paket) |
|  | OPS.1.1.4 Schutz vor Schadprogrammen | nur zugelassene Formate, nach Inhalt geprüft ([Zugelassene Formate](../../handbuch/arbeitsmittel/wissensbibliotheken/formate/README.md#zugelassene-formate)); zuschaltbare Virenprüfung jeder aufgenommenen Datei über einen clamd-Dienst der Behörde, vor dem Lesen und vor der Ablage des Originals, Befund im Revisionsprotokoll, Verhalten bei Ausfall des Scanners einstellbar; ab Werk aus, Seiteninhalte einer Quelle ohne Datei werden nicht geprüft ([Virenprüfung](../../handbuch/betrieb/netz-und-sicherheit.md#virenprüfung-der-aufgenommenen-dateien), {{faktenblatt:datenfluesse}}) |
|  | OPS.1.1.5 Protokollierung | [Logs und Metriken](../../handbuch/betrieb/ueberwachung-und-fehlerbehebung.md#logs-und-metriken), {{faktenblatt:protokollereignisse}} |
|  | OPS.1.1.6 Software-Tests und -Freigaben | [Update-Paket](../../handbuch/betrieb/releases-und-nachweise.md#das-update-paket), [Aktualisierung und Rückweg](../../handbuch/betrieb/kubernetes.md#aktualisierung-und-rückweg), [Wiederherstellungsprobe](../../handbuch/betrieb/sicherung-und-wiederherstellung.md#wiederherstellungsprobe) |
|  | OPS.2.2 Cloud-Nutzung, OPS.2.3 Nutzung von Outsourcing | bei externem Modellanbieter oder Betrieb durch einen Dienstleister; Ziele in {{faktenblatt:datenfluesse}} |
| DER – Detektion und Reaktion | DER.1 Detektion von sicherheitsrelevanten Ereignissen | [Logs und Metriken](../../handbuch/betrieb/ueberwachung-und-fehlerbehebung.md#logs-und-metriken); eine Ausleitung an ein zentrales Sicherheitsmonitoring ist nicht gebaut |
|  | DER.2.1 Behandlung von Sicherheitsvorfällen | Meldeweg zum Projekt in [`SECURITY.md`](../../../SECURITY.md), [Schwachstellen](../../handbuch/betrieb/releases-und-nachweise.md#schwachstellen), [Sicherheitsupdates](../../handbuch/betrieb/releases-und-nachweise.md#sicherheitsupdates) |
|  | DER.4 Notfallmanagement | [Notfallkonzept](../09-betrieb-update/notfallkonzept.md), [Notfallzugang](../../handbuch/betrieb/authentifizierung.md#notfallprozedur-wieder-hereinkommen), [Ausfall einer Instanz](../../handbuch/betrieb/mehrere-instanzen.md#wenn-eine-instanz-ausfällt) |
| APP – Anwendungen | APP.2.1 Allgemeiner Verzeichnisdienst (nur mit mitgeliefertem Keycloak) | [Härtung des mitgelieferten Keycloak](../../handbuch/betrieb/authentifizierung.md#härtung-des-mitgelieferten-keycloak) |
|  | APP.3.1 Webanwendungen und Webservices | [Härtung](../../handbuch/betrieb/netz-und-sicherheit.md#härtung-für-erreichbare-deployments) |
|  | APP.3.2 Webserver | [Frontend-Laufzeitimage](../../handbuch/betrieb/images-und-laufzeit.md#frontend-laufzeitimage), [Sicherheits-Header](../../handbuch/betrieb/netz-und-sicherheit.md#sicherheits-header-und-strict-transport-security) |
|  | APP.4.3 Relationale Datenbanken | [Eigene PostgreSQL](../../handbuch/betrieb/datenbank.md#voraussetzungen-einer-eigenen-postgresql) |
|  | APP.4.4 Kubernetes | [Netz und Sicherheit](../../handbuch/betrieb/kubernetes.md#netz-und-sicherheit) |
|  | APP.5.3 Allgemeiner E-Mail-Client und -Server (nur mit Mailversand) | [Einrichtung](../../handbuch/betrieb/e-mail.md#einrichtung), [Zustellung über die Warteschlange](../../handbuch/betrieb/e-mail.md#zustellung-über-die-warteschlange) |
|  | APP.6 Allgemeine Software | [Provenance und Software-Stückliste](../../handbuch/betrieb/releases-und-nachweise.md#provenance-und-software-stückliste), [Lizenzen](../../handbuch/betrieb/releases-und-nachweise.md#lizenzen) |
| SYS – IT-Systeme | SYS.1.6 Containerisierung | [Laufzeitimage](../../handbuch/betrieb/images-und-laufzeit.md#backend-laufzeitimage), [Härtung des Compose-Stapels](../../handbuch/betrieb/docker-compose.md#härtung-des-compose-stapels) |
| NET – Netze und Kommunikation | NET.1.1 Netzarchitektur und -design, NET.3.2 Firewall | Regelvorlage aus {{faktenblatt:datenfluesse}}, [Ausgangsproxy](../../handbuch/betrieb/netz-und-sicherheit.md#ausgangsproxy) |

Allein beim Betrieb liegen unter anderem ISMS.1, ORP.1 bis ORP.3 und ORP.5, OPS.1.2.5 Fernwartung,
DER.2.2, DER.2.3 und DER.3.1, die Bausteine für Server, Speicher und Virtualisierung (SYS.1.1, SYS.1.3,
SYS.1.5, SYS.1.8), die Clients (SYS.2), Webbrowser (APP.1.2), Active Directory (APP.2.2), Fileserver der
angebundenen Quellen (APP.3.3, APP.3.4), DNS (APP.3.6), Netzbausteine (NET.1.2, NET.3.1, NET.3.3) und die
Räume (INF). Die Originalablage ist eine Kopie der Quellen, kein Archiv; OPS.1.2.2 Archivierung passt
nicht.

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
| Falsche oder erfundene Antworten | Antworten mit Fundstellen ([Suche](../../handbuch/spaces/suche.md#7-belege-prüfen-und-fundstellen-bilden)) | Regel „Antwort ist ein Entwurf“ in der [Nutzungsrichtlinie](../08-nutzung/nutzungsrichtlinie.md) |
| Prompt-Injection: in Dokumenten versteckte Anweisungen an das Modell | keine eigene Erkennung | nur geprüfte Quellen anbinden, Schreibrechte an Quellen begrenzen |
| Abfluss von Inhalten an einen Modellanbieter | Modelle im Haus betreibbar, Ziele in {{faktenblatt:datenfluesse}} | Wahl des Anbieters, Vertrag, Netzregeln |
| Antworten aus Dokumenten ohne Leserecht | Suche nur in Dokumenten, die die Person lesen darf ([Berechtigungen](../../handbuch/konten-und-rechte/berechtigungen.md)) | Rechtevergabe je Bibliothek |

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
- Die Bausteinnamen folgen dem Kompendium, Edition 2023, der aktuellen Fassung. Das BSI führt
  schrittweise den Nachfolger Grundschutz++ ein; die Behörde gleicht ab, welche Fassung sie anwendet.
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
- [Releases und Nachweise](../../handbuch/betrieb/releases-und-nachweise.md#was-ein-release-liefert): wie Release-Notizen
  datenrelevante Änderungen kennzeichnen.

**Rechtsgrundlagen:**

| Worum es geht | Fundstelle |
|---|---|
| IT-Grundschutz und Mindeststandards als Mindestanforderung der Bundesverwaltung | [§ 44 Abs. 1 BSIG](https://www.gesetze-im-internet.de/bsig_2025/__44.html) |
| Länder und Kommunen | Landesrecht, zu prüfen |
