# Sicherheit, Nachweis & Prüfbarkeit

> **Status: Entwurf — die Leitplanken stehen, der Schnitt der ersten Protokollstufe ist entschieden.**
>
> **Phasenlage:** Phase 1. Das revisionssichere Protokoll, die Vollständigkeit nach DSGVO, sichere
> Voreinstellungen und die Mitbestimmungsfähigkeit gehören zum Fundament — ohne sie ergibt ein Start in
> einer Behörde keinen Sinn. Der geordnete Entwicklungsprozess mit Stückliste und signierten Builds läuft
> parallel und begleitend; die unabhängige Prüfung als Nachweisstufe ist eine Reifegradfrage, keine
> Funktion.

> **Abgrenzung:** Das Rechtemodell steht in
> [Spaces, Assets & Zugangskontrolle](./spaces-and-assets.md), Anmeldung, Kontenlebenszyklus und
> Systemverwaltung in [Identität, Rechte & Mandanten](./access-control.md), die aggregierte Auswertung in
> [Monitoring, Kosten & Governance](./monitoring-and-governance.md). Dieses Dokument beschreibt, wie
> belegt wird, dass all das gewirkt hat — und wo die Grenzen dieses Belegens liegen.

## Motivation

In der Verwaltung genügt es nicht, das Richtige zu tun. Es muss Jahre später **belegbar** sein, und zwar
gegenüber jemandem, der weder beim Vorgang dabei war noch dem Haus wohlgesonnen sein muss: der internen
Revision, dem Rechnungshof, der Aufsichtsbehörde, der Datenschutzaufsicht, einer Betroffenen mit einem
Auskunftsersuchen.

Daraus folgen drei Anforderungen, die einander gegenläufig sind und deshalb ausdrücklich austariert
werden müssen:

1. **Es muss genug protokolliert werden**, um die Prüferfrage zu beantworten — auch die schwierige
   Negativfrage, worauf jemand gerade **keinen** Zugriff hatte.
2. **Es darf nicht so viel erhoben werden**, dass daraus ein Tätigkeits- und Leistungsprofil der
   Beschäftigten entsteht. Sonst scheitert die Einführung an der Mitbestimmung, und zwar zu Recht.
3. **Es muss löschbar sein**, ohne dass die Unveränderlichkeit des Protokolls Schaden nimmt.

Ein Produkt, das nur die erste Anforderung erfüllt, ist prüfbar und nicht einführbar. Eines, das nur die
zweite erfüllt, ist einführbar und nicht betreibbar. OPAA muss beides zugleich sein.

---

## Überblick

1. **Revisionssicheres Protokoll** — wer, wann, was; alles, was Zugriff verändert oder
   Verwaltungshandeln ist; der Zugriff auf Protokolldaten erzeugt selbst einen Eintrag; anbindbar an ein
   zentrales Sicherheitsmonitoring.
2. **Historisierung der Rechte** — die Rechtemenge zu einem beliebigen Stichtag ist rekonstruierbar,
   statt sie bei jeder Abfrage mitzuschreiben.
3. **Vollständigkeit nach DSGVO** — Löschung und Export, beides vollständig und beides ohne
   Sonderweg.
4. **Sichere Voreinstellungen und Produkthärtung** — der Auslieferungszustand ist der sichere Zustand.
5. **Geordneter Entwicklungsprozess** — Software-Stückliste, signierte und reproduzierbare Builds,
   unabhängige Prüfung als Nachweisstufe.
6. **C5-Fähigkeit statt Zertifizierung** — OPAA wird nie selbst zertifiziert; das Ziel ist, dass ein
   Betreiber die Prüfung mit OPAA im Prüfumfang besteht.
7. **Mitbestimmungsfähigkeit** — die Dienstvereinbarung wird zu einer Konfigurationsaufgabe statt zu
   einem Projektrisiko.

Die Punkte 1 bis 5 sind Bauaufgaben. Punkt 6 ist eine Dokumentationsaufgabe, die aus ihnen folgt.
Punkt 7 ist eine **Entwurfsentscheidung**, die begrenzt, was die Punkte 1 bis 5 tun dürfen — und die
deshalb im Zweifel gewinnt.

---

## Revisionssicheres Protokoll

### Der Schnitt der ersten Stufe

Die erste Stufe protokolliert **alles, was Zugriff verändert oder Verwaltungshandeln ist** — und sonst
nichts. Abfragen bleiben vollständig draußen: weder wer was gefragt hat noch welche Antwort erzeugt
wurde.

Diese Grenze ist keine Bequemlichkeit, sondern folgt dem Zielkonflikt, der dieses Kapitel trägt.
Rechteereignisse fallen **selten** an, betreffen ganz überwiegend **Objekte** und beantworten genau die
Prüferfrage. Abfrageereignisse fallen **ständig** an, betreffen ausschließlich **Verhalten** und ergeben
in der Menge das Tätigkeitsprofil, das die Mitbestimmung ausschließt. Wer beides in dieselbe Ablage
schreibt, hat den Nachweis für die Prüfung und zugleich das Material für die Auswertung, die es nicht
geben soll — und danach entscheidet nur noch die Zugriffsregel, nicht mehr der Datenbestand.

Was aus der Prüfbarkeit dennoch gebraucht wird und trotzdem nicht ins Protokoll wandert, liefert die
[Rechtehistorie](#nachweisbarkeit-historisierung-von-rechten): Sie beantwortet die Negativfrage aus dem
Rechtestand, nicht aus einer Ereigniskette je Abfrage.

### Der Zielkonflikt: Speicherung und Auswertbarkeit sind zu trennen

Zwei verbindliche Anforderungen stehen gegeneinander:

- **Prüfbarkeit verlangt Zurechenbarkeit.** Ein Prüfer will wissen, wer wann was getan hat. Ein
  Protokoll ohne handelnde Person beantwortet keine einzige Prüffrage; „jemand hat den Zugriff auf die
  Personalvorgänge erweitert" ist kein Nachweis, sondern ein Befund.
- **Mitbestimmungsfähigkeit verlangt, dass es keinen personenbezogenen Auswertungspfad gibt** — nicht
  abgeschaltet, sondern nicht gebaut (siehe [unten](#2-einen-personenbezogenen-auswertungspfad-gibt-es-nicht)).

Beides zugleich geht nur, wenn **Speicherung und Auswertbarkeit getrennt** werden:

> **Der Eintrag trägt die handelnde Person. Es gibt keine Oberfläche und keine Schnittstelle, die
> Protokolldaten nach Person filtert, gruppiert oder sortiert.**

Der Personenbezug wird also erhoben, aber er ist **kein Einstiegspunkt**. Jede Abfrage beginnt an einem
Objekt, einem Zeitraum oder einer Ereignisart; die handelnde Person ist immer nur **Ergebnis**, nie
Suchkriterium. Welche Wege das konkret bedeutet, steht unter
[Zugriffswege](#zugriffswege-was-es-gibt-und-was-es-nicht-gibt).

### Der Protokollsatz

Ein Eintrag beantwortet fünf Fragen: **wer, wann, was, an welchem Objekt, mit welchem Ergebnis.**

```json
{
  "event_id": "01J9…",
  "recorded_at": "2026-02-16T14:30:15Z",
  "organization_id": "org-1",
  "actor_kind": "USER",
  "actor_ref": "pseud-7f3a…",
  "event_type": "library.grant.revoked",
  "object_type": "KNOWLEDGE_LIBRARY",
  "object_id": "lib-personalvorgaenge",
  "object_label": "Personalvorgänge",
  "subject_kind": "GROUP",
  "subject_ref": "grp-referat-z2",
  "before": { "role": "READER", "expires_at": null },
  "after": null,
  "outcome": "SUCCESS",
  "reason": null,
  "correlation_ref": "sync-2026-02-16-06"
}
```

| Feld | Warum es dabei ist |
|---|---|
| `event_id` | eindeutige Kennung; macht einen Eintrag zitierbar, ohne ihn über Zeit und Person zu beschreiben |
| `recorded_at` | Zeitpunkt der Aufzeichnung, in UTC; der Zeitpunkt ist die Achse jeder Prüfung |
| `organization_id` | die Mandantengrenze gilt auch im Protokoll |
| `actor_kind` | Person, Dienstkonto oder Systemvorgang (z. B. Verzeichnisabgleich) — trennt Handeln von Automatik |
| `actor_ref` | **Pseudonymkennung** der handelnden Person; die Zuordnung liegt getrennt (siehe [Unveränderlichkeit und Löschrecht](#unveränderlichkeit-und-löschrecht)) |
| `event_type` | Ereignisart aus einer **geschlossenen Liste**; eine offene Liste wäre nicht prüfbar und nicht abgrenzbar gegenüber der Personalvertretung |
| `object_type`, `object_id` | das betroffene Objekt — der einzige zulässige Einstieg in die Auswertung |
| `object_label` | die Bezeichnung **zum Zeitpunkt des Ereignisses**; ohne sie ist ein später umbenanntes oder gelöschtes Objekt in der Prüfung nicht mehr benennbar |
| `subject_kind`, `subject_ref` | das betroffene Rechtesubjekt — Person (pseudonymisiert) oder Gruppe; ohne dieses Feld ist eine Rechtevergabe inhaltsleer |
| `before`, `after` | die geänderten Werte, **eng begrenzt** auf das rechtlich Erhebliche (Rolle, Frist, Sichtbarkeit) — kein vollständiger Objektabzug |
| `outcome` | erfolgreich, abgelehnt oder fehlgeschlagen; die **abgelehnte** Verwaltungsaktion ist für eine Prüfung oft die interessantere |
| `reason` | Anlass, verpflichtend dort, wo ein Anlass verlangt ist — Zugriff auf Protokolldaten, anlassbezogene Klärung, bestätigter Verzeichnislauf |
| `correlation_ref` | verbindet die Einträge **eines** Vorgangs, etwa die 412 Einzeländerungen eines Verzeichnislaufs; ersetzt den Sammeleintrag, den eine Prüfung nicht gebrauchen kann |

**Die Netzadresse ist nicht Teil des Standardsatzes.** Sie unterscheidet Dienststelle von Heimarbeit und
ist damit ein Anwesenheitsmerkmal — sie beantwortet keine Frage nach dem Recht, sondern eine nach dem
Aufenthalt. Dieselbe Begründung schließt **Geräte- und Browserkennung** sowie **Standortangaben** aus.
Die Netzadresse kann für Sicherheitszwecke ausdrücklich eingeschaltet werden; dann ist die Einschaltung
zu begründen, und das Feld bleibt aus Berichten und Exporten ausgeschlossen. Ob eine C5-Prüfung das Feld
zwingend verlangt, ist offen; sollte das so sein, ist es schriftlich zu begründen.

### Die Ereignisse der ersten Stufe

Alles Folgende ist protokollpflichtig. Die Liste ist **geschlossen**: Was hier nicht steht, wird in der
ersten Stufe nicht geschrieben.

**Rechte an Assets**

- Rechtevergabe, Rechteänderung und Rechteentzug an Wissensbibliotheken und weiteren Assets,
  einschließlich Mitfreigaben aus der Freigabekette
- Ablauf einer Befristung, sobald sie wirkt — ein Recht, das ohne Eintrag endet, ist im Nachweis eine
  Lücke wie eines, das ohne Eintrag beginnt
- Änderung von Freigabestufe oder Auffindbarkeit eines Assets (`visibility`, `listed`)
- Aussetzen von Grants durch eine nachträglich gesenkte Freigabe-Obergrenze

**Spaces, Bibliotheken und Gruppen**

- Anlegen, Ändern und Löschen von Spaces, Wissensbibliotheken und Gruppen
- Aufnahme, Rollenänderung und Entfernen von Space-Mitgliedern; die Aufnahme **externer** Personen in
  einen Space mit geteilten Inhalten zusätzlich mit der ausdrücklichen Bestätigung im Eintrag
- Aufnahme und Entfernen von Gruppenmitgliedern; Auflösung einer Gruppe
- Bereitstellung einer Bibliothek in einem Space, dessen Mitglieder nicht sämtlich Lesezugriff haben
- Eigentümerwechsel, Übernahme von Assets ohne Zuständigkeit und der Übergang in „Nachfolge offen"

**Konten, Rollen und Verzeichnisabgleich**

- Erteilung und Entzug der System-Admin-Rolle
- Deaktivierung eines Kontos, erzwungene Neuanmeldung, Ausstellung und Widerruf von API-Tokens
- **Lokale Konten** ([ADR-0033](../decisions/0033-lokale-benutzerverwaltung.md), Epic #1529): Anlage
  und Einladung (mit Zustellweg: Mail zugestellt, Mail gescheitert, Link angezeigt),
  Selbstregistrierung, Änderung von
  Ablaufdatum (mit Vorher/Nachher) und der Tatsache einer Änderung von Adresse, Anzeigename oder
  Anlagegrund (nur der Feldname, nie der Wert), Sperre und Entsperrung mit Grund (Verwalter oder
  Inaktivität), die **Sperre eines Kontos nach mehreren Fehlversuchen** als eigenes Ereignis
  (die daraus folgende Zustandsänderung — nie der einzelne Fehlversuch, nie der Zähler),
  administratives Zurücksetzen und Erzeugen eines Passworts, Setzen und
  Ändern des eigenen Passworts, fremdveranlasster Widerruf von Sitzungen mit Grund, Löschung, Anlage
  und Wiederanlauf des Notanker-Kontos der Systemverwaltung sowie **jede erfolgreiche Anmeldung dieses
  einen Notanker-Kontos** (es ist ein privilegiertes Notfallzugangsmittel, keine Person), Anstoß und
  Abschluss der Übergabe eines lokalen Kontos an eine Anbieteridentität (mit Anbieter-Kennung, nie mit
  dem Subject), Ein- und Ausschalten der lokalen Verwaltung, der Selbstregistrierung und des
  Passwort-vergessen-Wegs sowie jede Änderung ihrer Einstellungen. **Nicht** dabei: die eigene
  Abmeldung, der routinemäßige Ablauf einer Sitzung und der einzelne Fehlversuch (siehe unten). In
  keinem dieser Ereignisse stehen E-Mail-Adressen, Namen, Anbieter-Subjects oder Freitext; die
  betroffene Person ist das Pseudonym in `subject_ref`
- **Jede bewirkte** Rechteänderung aus einem Verzeichnisabgleich — je Änderung, nicht je Lauf, verbunden
  über `correlation_ref`; dazu ein Kopfeintrag des Laufs mit Ergebnis und, oberhalb der Schwelle, mit
  der bestätigenden Person und ihrem Anlass

**Systemeinstellungen**

- Governance-Einstellungen: Aufbewahrungsfristen, Mindestgruppengröße, Aggregation, Statistik
- **Die Protokollkonfiguration selbst** — einschließlich des Einschaltens der Netzadresse. Ohne diesen
  Punkt bleibt eine spätere Abweichung von der Dienstvereinbarung unbemerkt; die Änderung wird
  zusätzlich angezeigt
- Modellvorgaben und die Freigabe externer Modelle
- Die Freigabe-Obergrenze konnektor-gespeister Bibliotheken
- **Mail-Einstellungen** ([ADR-0033](../decisions/0033-lokale-benutzerverwaltung.md)): Änderung der
  SMTP-Einstellungen (ohne den Passwortwert), Änderung und Zurücksetzen einer Mail-Vorlage, ein
  ausgelöster Testversand

**Zugriff auf die Protokolldaten selbst**

- Jedes Lesen, jede Auswertung und jeder Export von Protokolldaten, einschließlich der **abgelehnten**
  Versuche — siehe [unten](#der-zugriff-auf-protokolldaten-erzeugt-selbst-einen-eintrag)

### Umsetzungsstand der geschlossenen Liste (#392)

Die Liste oben beschreibt das **Zielverhalten**; welche Arten heute tatsächlich einen Eintrag
schreiben, hängt davon ab, ob die zugrunde liegende Funktion im Code schon existiert. Verdrahtet
sind: Rechte an Assets (Vergabe/Änderung/Entzug von Grants, Änderung von `visibility`/`listed`),
Anlegen/Ändern/Löschen von Bibliotheken, Spaces und Gruppen, Aufnahme/Rollenänderung/Entfernen von
Mitgliedern, Eigentümerübergang eines Space, jede bewirkte Änderung eines Verzeichnisabgleichs samt
Kopfeintrag, die Erteilung/der Entzug der System-Admin-Rolle sowie — seit #1533 — der
fremdveranlasste Widerruf lokaler Sitzungen (`LOCAL_SESSION_REVOKED`, zunächst nur bei
Wiederverwendung eines Refresh-Tokens; seit #1534 auch beim Abschalten der lokalen Verwaltung), das
Ändern des eigenen Passworts (`LOCAL_PASSWORD_CHANGED`) sowie — seit #1534 — die Anlage und der
Wiederanlauf des Notanker-Kontos (`LOCAL_ADMIN_SEEDED`, `LOCAL_ADMIN_RESET`), jede Anmeldung mit ihm
(`LOCAL_BOOTSTRAP_ACCOUNT_LOGIN`) und der Schalter der lokalen Verwaltung
(`LOCAL_ACCOUNTS_ENABLED`/`_DISABLED`), sowie — seit #1537 — die Verwaltung lokaler Konten
(`LOCAL_USER_CREATED`, `LOCAL_USER_INVITED` und `LOCAL_USER_PASSWORD_RESET_REQUESTED` je mit
Zustellweg, `LOCAL_USER_CHANGED` mit Vorher/Nachher nur für das Ablaufdatum, `LOCAL_USER_LOCKED`
mit Grund `ADMIN` oder `INACTIVITY`, `LOCAL_USER_UNLOCKED`, `LOCAL_USER_PASSWORD_GENERATED`,
`LOCAL_USER_DELETED`, `LOCAL_ACCOUNTS_SETTINGS_CHANGED`; Subjekt als Pseudonym, nie Adresse, Name
oder Anlagegrund als Wert), sowie — seit #1538 — das Setzen des eigenen Passworts über einen
Einladungs- oder Rücksetzlink (`LOCAL_PASSWORD_SET` mit dem Zweck des Links) und die
Selbstregistrierung (`LOCAL_USER_REGISTERED` unter dem Systemprozess `local-auth`), sowie — seit
#1535 — die Sperre eines Kontos nach mehreren Fehlversuchen
(`LOCAL_ACCOUNT_LOCKED_AFTER_FAILED_LOGINS`, einmal je Sperre, ohne Zähler) und — seit #1542 — die
Mail-Einstellungen (`MAIL_SETTINGS_CHANGED` ohne den Passwortwert, `MAIL_TEMPLATE_CHANGED`,
`MAIL_TEMPLATE_RESET`, `MAIL_TEST_SENT`). Die Anfrage
„Passwort vergessen" ändert keinen Zustand; die Bestätigung der eigenen Adresse ist eine Handlung
der Person am eigenen Konto und nach ADR-0033, Entscheidung 13, kein Verwaltungsakt — beide
erzeugen kein Ereignis. Noch **nicht** verdrahtet — weil
die jeweilige Funktion selbst noch fehlt, nicht weil sie ausgenommen wäre: Ablauf einer Befristung
(kein Scheduler), Aussetzen von Grants durch eine gesenkte Freigabe-Obergrenze, Bereitstellung einer
Bibliothek in einem Space, Eigentümerübernahme ohne Zuständigkeit und der Übergang in „Nachfolge
offen", Deaktivierung eines Kontos, erzwungene Neuanmeldung, API-Tokens, sämtliche
Systemeinstellungen (Governance, Protokollkonfiguration, Modellvorgaben, Freigabe-Obergrenze
konnektor-gespeister Bibliotheken) sowie Anstoß und Abschluss der Übergabe eines lokalen Kontos an
eine Anbieteridentität (#1594). Jede dieser Lücken schließt das jeweilige Folge-Issue, sobald
die zugehörige Funktion existiert — die Liste selbst bleibt geschlossen und ändert sich nicht.

### Was ausdrücklich nicht protokolliert wird

| Nicht protokolliert | Begründung |
|---|---|
| **Abfragen** — Frage, Suchbegriffe, angewandter Suchbereich, Trefferzahl | Das ist Verhalten, nicht Zugriffsänderung. In der Menge ergibt es das Tätigkeitsprofil, das die Mitbestimmung ausschließt. Die Prüfbarkeit hängt nicht daran: Die Negativfrage beantwortet die Rechtehistorie |
| **Antwortinhalte, Zitate, Modellaufrufe** | dasselbe, zusätzlich mit Inhalten aus dem Fachverfahren |
| **Erfolgreiche Anmeldungen und Sitzungsverläufe** | reines Anwesenheitsmerkmal, ohne Aussage über Rechte |
| **Fehlgeschlagene Anmeldungen und abgewiesene Verbindungsversuche** | Sicherheitsereignisse, die in das zentrale Sicherheitsmonitoring gehören und nicht in das Nachweisprotokoll. Sie kommen mit der [SIEM-Anbindung](#anbindung-an-ein-zentrales-sicherheitsmonitoring), nicht mit dieser Stufe. Das gilt unverändert für die lokale Anmeldung ([ADR-0033](../decisions/0033-lokale-benutzerverwaltung.md)): Der einzelne Fehlversuch steht nur im technischen Anwendungslog (kurze Frist, keine Auswertungsoberfläche, Konto-Kennung statt Adresse); in das Nachweisprotokoll gelangt allein die daraus folgende **Kontosperre** als Zustandsänderung. Der Fehlversuchszähler eines Kontos wird weder ausgegeben noch historisiert und geht bei jeder erfolgreichen Anmeldung, jedem Zurücksetzen und jeder Entsperrung auf null |
| **Lesezugriffe auf Dokumente und Chats** | Verhalten; wer worauf zugreifen **durfte**, belegt die Rechtehistorie |

**Spätere Stufen** — nicht verworfen, nur nicht hier: das Teilen und Zurückziehen von Chats und
Artefakten (es ändert Reichweite und gehört dazu, sobald die Funktion existiert), Agentenaktionen mit
aufrufender Person, Agentenversion und Freigabeschritt, sowie die Ausleitung an ein zentrales
Sicherheitsmonitoring.

### Der Sicherheitsgrad der ersten Stufe: einfaches Anfügen

Die Ablage wird **nur beschrieben**. Das Anwendungskonto der Datenbank besitzt auf ihr das Recht zum
Einfügen und zum begrenzten Lesen — **kein** `UPDATE`, **kein** `DELETE`, **kein** `TRUNCATE` und keine
Rechte am Schema der Tabelle. Ein einmal geschriebener Satz kann durch die Anwendung nicht mehr geändert
und nicht mehr entfernt werden, auch nicht durch einen Fehler in ihr und auch nicht durch eine über sie
eingeschleuste Anweisung.

Die [automatische Löschung](#aufbewahrung) braucht dennoch einen Weg, Sätze verschwinden zu lassen. Sie
läuft deshalb **nicht über das Anwendungskonto**, sondern über ein getrenntes Wartungskonto, und sie
entfernt **nie einen einzelnen Satz**, sondern immer eine vollständige abgelaufene Zeitscheibe (die
Ablage ist nach Monaten unterteilt). Damit ist „löschen" eine Mengenoperation nach Fristablauf und kein
Griff in einen einzelnen Vorgang.

**Eine Prüfsummenverkettung gibt es in dieser Stufe nicht.** Das ist eine bewusste Abwägung, und ihre
Grenze gehört ausgesprochen:

> **Eine Manipulation durch jemanden mit direktem Datenbankzugang fällt nicht auf.** Wer als
> Datenbankadministrator an der Anwendung vorbei schreibt, kann Sätze ändern oder entfernen, ohne dass
> OPAA das erkennt oder belegen kann.

Der Schutz der ersten Stufe stützt sich damit auf zwei Dinge, und nur auf sie: darauf, dass die
**Anwendung** selbst kein Änderungsrecht hat — das ist der häufige und der wahrscheinliche Fall —, und
darauf, dass der **direkte Datenbankzugang betrieblich beschränkt** ist. Dieser zweite Teil ist keine
Produkteigenschaft: Er wird außerhalb von OPAA durch den Betreiber geregelt und nachgewiesen, über die
Vergabe der Datenbankrechte, das Vier-Augen-Prinzip beim administrativen Zugang und die Protokollierung
der Datenbank selbst. Genau danach wird eine Prüfung fragen, und die ehrliche Antwort lautet: OPAA
liefert dafür keinen Nachweis, sondern setzt ihn voraus. Die
[Verantwortungsmatrix](#zwei-wege--die-behörde-wählt) führt diesen Punkt auf der Betreiberseite.

Die Verkettung der Einträge über Prüfsummen ist die naheliegende nächste Stufe und unter
[Offene Fragen](#offene-fragen) geführt.

### Zugriffswege: was es gibt und was es nicht gibt

Hier erweist sich später, ob die Zusage gehalten wurde. Deshalb steht sie hier als Liste und nicht als
Absicht.

**Es gibt genau diese Abfragen:**

| Einstieg | Beispiel | Warum zulässig |
|---|---|---|
| **nach Objekt** | „alle Ereignisse an der Bibliothek `Personalvorgänge`" | Das Objekt ist der Prüfgegenstand; die genannten Personen sind Ergebnis, nicht Filter |
| **nach Zeitraum** | „alle Rechteänderungen zwischen dem 1. und dem 31. März" | beantwortet die Prüferfrage entlang der Zeitachse, ohne Personeneinstieg |
| **nach Ereignisart** | „alle Änderungen an Systemeinstellungen im letzten Quartal" | prüft eine Kategorie von Verwaltungshandeln, nicht eine Person |
| **nach Vorgang** | „alle Einträge des Verzeichnislaufs vom 16. Februar" | über `correlation_ref`; hält einen technischen Vorgang zusammen |

Jede dieser Abfragen verlangt einen **Zeitraum** und ist in ihrer Ergebnismenge begrenzt. Eine Abfrage
ohne Zeitgrenze ist ein Vollabzug und damit ein Auswertungspfad mit anderem Namen. Konkret (#393,
Code-Review-Befund 3): der Zeitraum ist auf **92 Tage** je Abfrage begrenzt — grob ein Quartal, breit
genug für die Beispiele oben, nicht breit genug für einen verkappten Voll-Extrakt in wenigen Aufrufen —
und die Seitenzahl je Abfrage ist auf **50 Seiten** begrenzt — eine Anfrage über die 50. Seite hinaus
wird abgewiesen, nicht auf die letzte nutzbare Seite gekappt (#393, Nachbesserung 3), damit die
Seitengröße allein keine Vollabzug-in-Scheiben-Lücke offenlässt; ein größerer Bedarf über mehrere
Jahre wird durch mehrere aufeinanderfolgende, je für sich nachvollziehbare Abfragen abgedeckt, nicht
durch eine einzelne.

Der Weg **nach Objekt** weist `objectType = Konto` ausdrücklich ab (#393, Code-Review-Befund 2): Das
`object_id` eines Kontoereignisses ist dieselbe Pseudonymkennung, die `actor_ref` für dieselbe Person in
all ihren eigenen Ereignissen trägt. Ohne diese Abweisung ließe sich über `nach Zeitraum` eine
Pseudonymkennung aus `actor_ref` ablesen und anschließend über `nach Objekt` als `object_id`
zurückspielen — und damit genau die weiter unten ausgeschlossene Sicht „alle Ereignisse, bei denen
Person X betroffen war" rekonstruieren, ohne die anlassbezogene Klärung zu durchlaufen. Der berechtigte
Bedarf hinter einer kontobezogenen Frage läuft über `nach Zeitraum`/`nach Ereignisart` (ohne
Personenbezug), die freigegebene anlassbezogene Klärung oder die Rechtehistorie (#238) — nie über
diesen Weg.

**Es gibt diese Abfragen nicht — und zwar nicht abschaltbar, sondern nicht gebaut:**

- **kein Filter nach handelnder Person.** Weder als Parameter, noch als Spaltenfilter, noch über eine
  Sortierung. `actor_ref` ist ein Ausgabefeld, kein Eingabefeld
- **keine Gruppierung und keine Zählung je Person** — kein „Aktionen je Beschäftigtem", auch nicht
  aggregiert, auch nicht als Nebenprodukt einer anderen Auswertung
- **keine Zeitreihe je Person**, in keiner Auflösung
- **keine Freitextsuche über das Protokoll.** Sie wäre der bequemste Weg, einen Personenfilter durch die
  Hintertür herzustellen — die Pseudonymkennung ist ein Text wie jeder andere
- **kein Vollabzug ohne Objekt-, Zeitraum- oder Ereignisbezug**, auch nicht als Export
- **keine Sicht „alle Ereignisse, bei denen Person X betroffen war"** über das Protokoll. Der berechtigte
  Bedarf dahinter — welche Rechte hatte X wann — wird über die
  [Rechtehistorie](#nachweisbarkeit-historisierung-von-rechten) beantwortet, die den Rechtestand
  abbildet und nicht das Handeln einzelner Beschäftigter

**Die eine Ausnahme, und sie ist eng:** Der Personenfilter existiert genau einmal, im freigegebenen
Vorgang der [anlassbezogenen Klärung](#2-einen-personenbezogenen-auswertungspfad-gibt-es-nicht) — im
Vier-Augen-Prinzip unter Beteiligung der Personalvertretung, mit vorab festgelegter Person, vorab
festgelegtem Zeitraum und dokumentiertem Zweck, die die Abfrage **technisch** begrenzen. Ohne
freigegebenen Vorgang ist er nicht aufrufbar; mit ihm reicht er nicht weiter als die Freigabe. Der
Zugriff erzeugt seinen eigenen Eintrag, die betroffene Person wird unterrichtet, und die Zahl der Fälle
geht in den Jahresbericht an die Personalvertretung.

**Stand #393, technisch bereits umgesetzt:** zwei verschiedene Personen (Vier-Augen-Prinzip, auch als
Datenbank-Constraint, nicht nur anwendungsseitig), vorab festgelegte Person/Zeitraum/Zweck, technische
Begrenzung der Abfrage auf genau diese Grenzen, und eine Befristung der Freigabe (30 Tage ab Freigabe;
danach ist eine erneute Freigabe mit erneutem Vier-Augen-Prinzip nötig, nicht eine Verlängerung
derselben). **Noch nicht technisch durchgesetzt, sondern organisatorisch abzugrenzen:** dass die zwei
freigebenden Personen tatsächlich unterschiedliche Rollen im Sinne der Mitbestimmung besetzen (heute
genügen zwei beliebige AUDITOR-Konten — die **Beteiligung der Personalvertretung** an der Freigabe
selbst ist ein organisatorischer Prozessschritt, kein technisches Gate), die **Unterrichtung der
betroffenen Person** nach Abschluss und die Aufnahme in den Jahresbericht. Diese drei bleiben
Dienstvereinbarung/Prozess, bis ein eigenes Ticket sie technisch abbildet.

Wer Protokolldaten liest, exportiert oder auswertet, erzeugt damit einen eigenen Protokolleintrag — mit
Person, Zeitpunkt, Anlass und Umfang der Abfrage. Der Eintrag ist für die auswertende Stelle nicht
unterdrückbar. **Auch der abgewiesene Versuch** erzeugt einen Eintrag; ein Zugriffsversuch, der nur bei
Erfolg festgehalten wird, verschweigt genau den Fall, um dessentwillen protokolliert wird.

Diese Einträge sind kein Sonderbestand: Sie liegen in derselben Ablage, unterliegen denselben Regeln und
sind über dieselben — und nur über dieselben — Wege abfragbar. Wer ein Protokoll führen will, das den
Blick ins Protokoll ausnimmt, führt keines.

**Protokollierter Zugriff ist aber kein begrenzter Zugriff; beides ist nötig.** Deshalb zusätzlich:
benannter Personenkreis, dokumentierter Anlass und die technisch durchgesetzte Trennung der
Auswertungswege für Revision und Dienststellenleitung.

### Verwaltungsaktionen und Agentenaktionen

Zwei Kategorien, die über die gewöhnliche Nutzerhandlung hinausgehen und deshalb eigens genannt werden:

- **Verwaltungsaktionen.** Alles, was die Systemverwaltung tut, ist protokollpflichtig — gerade weil
  System-Admins nicht automatisch leseberechtigt sind, muss jeder Übernahme- und Verwaltungsakt sichtbar
  sein. Ein Admin, der ein Asset einer neuen Zuständigkeit zuweist, hinterlässt eine Spur, die er selbst
  nicht entfernen kann.
- **Agentenaktionen** — Teil einer späteren Stufe, weil es heute keine Agenten gibt; die Festlegung
  steht hier, damit sie nicht später neu verhandelt wird. Ein Agent handelt **immer mit den Rechten der
  aufrufenden Person**. Der Protokolleintrag hält deshalb beides fest: die aufrufende Person und den
  ausführenden Agenten in seiner konkreten Version. Bei schreibenden Aktionen kommt der Freigabeschritt
  hinzu — wer freigegeben hat, wann, und was genau freigegeben wurde. Ein automatisierter Vorgang ohne
  benennbaren Menschen dahinter ist in der Verwaltung nicht zurechenbar und damit nicht zulässig.

### Aufbewahrung

Hier stehen zwei berechtigte Anliegen gegeneinander: Eine Prüfung greift **über Jahre** zurück — ein
Rechnungshofverfahren zu einer Vergabe von 2026 wird 2029 geführt, und ein Testat wird jährlich
erneuert. Die Datensparsamkeit verlangt umgekehrt, personenbeziehbare Daten so kurz wie möglich zu
halten. Beides lässt sich nicht auflösen, nur begrenzen — und die Begrenzung gehört ins Produkt und
nicht in die Auslegung des Einzelfalls.

**Der Vorschlag:**

| | Wert | Begründung |
|---|---|---|
| **Untergrenze** | 1 Jahr | Kürzer ist keine Nachweisfähigkeit: Ein jährlicher Prüfzyklus fände dann bereits Lücken im eigenen Zeitraum. Die Untergrenze ist durch die Konfiguration **nicht unterschreitbar** |
| **Voreinstellung** | 3 Jahre | deckt den üblichen Abstand zwischen Vorgang und Prüfung, ohne in die Größenordnung der Aktenaufbewahrung zu geraten |
| **Obergrenze** | 10 Jahre | Was länger liegt, dient keiner Prüfung mehr, sondern nur noch der Möglichkeit, es später doch auszuwerten. Die Obergrenze ist **nicht überschreitbar**, auch nicht durch Konfiguration |

- **Automatische Löschung nach Ablauf**, monatsweise und ohne Zutun (siehe
  [Sicherheitsgrad](#der-sicherheitsgrad-der-ersten-stufe-einfaches-anfügen)). Eine reine Untergrenze
  („mindestens ein Jahr") ist keine Regelung, sondern eine unbefristete Speicherung mit Mindestdauer.
- Die Protokollfrist muss **mindestens so lang** gewählt werden wie die Aufbewahrung der Inhalte, auf die
  sie sich bezieht. Sonst existiert ein Chatverlauf noch, aber es ist nicht mehr belegbar, wer ihn wann
  gelesen hat. Das Produkt warnt bei einer inkonsistenten Einstellung. Die konkrete Dauer folgt aus
  Fachrecht und Aktenordnung der einführenden Stelle.
- Eine **Verkürzung** der Frist wirkt nur nach vorn und ist selbst protokollpflichtig; sie darf nicht das
  Werkzeug sein, mit dem ein unbequemer Zeitraum verschwindet.

**Stand #395, technisch umgesetzt:** Die Frist ist eine **einzige, systemweite** Einstellung, keine je
Organisation — die Partitionierung der Ablage (`audit_log`, #391) läuft ausschließlich über die Zeit
(`PARTITION BY RANGE (recorded_at)`), nicht über die Organisation, und eine Frist je Organisation ließe
sich nicht als „vollständige Zeitscheibe löschen" umsetzen, sobald zwei Organisationen in derselben
Ablage unterschiedliche Fristen hätten. Das passt zum Betriebsmodell: eine OPAA-Installation je Behörde.

Die Löschung selbst läuft über `opaa_audit_delete_expired_partitions()` — eine parameterlose,
`SECURITY DEFINER`-Funktion im Besitz von `opaa_audit_owner` (derselben Rolle, die bereits `audit_log`
gehört, siehe [Sicherheitsgrad](#der-sicherheitsgrad-der-ersten-stufe-einfaches-anfügen) und
[ADR-0015](../decisions/0015-eigentuemertrennung-protokollablage.md)). Das Anwendungskonto erhält
ausschließlich das Recht, diese eine Funktion aufzurufen (`EXECUTE`) — kein `DROP`, kein `DELETE`, keine
Parameter, mit denen sich eine bestimmte Partition, Organisation oder ein Zeitraum benennen ließe. Die
Funktion selbst prüft nur die eine gespeicherte Konfigurationszeile und entfernt ausschließlich
vollständige, abgelaufene Monatspartitionen; ein einzelner Protokollsatz lässt sich über sie nicht
entfernen. Ein Scheduler im Anwendungskonto ruft sie monatlich auf — „läuft nicht über das
Anwendungskonto" heißt hier konkret: Die eigentliche `DROP TABLE`-Anweisung führt technisch immer
`opaa_audit_owner` aus, unabhängig davon, wer den Aufruf ausgelöst hat.

„Wirkt nur nach vorn" ist technisch erzwungen, nicht nur Konvention: Die Funktion merkt sich, bis zu
welchem Zeitpunkt sie zuletzt gelöscht hat, und lässt diesen Zeitpunkt pro tatsächlich vergangenem
Kalendermonat um höchstens einen Monat weiterrücken — unabhängig davon, wie oft die Funktion
aufgerufen wird oder wie stark die Frist verkürzt wurde. Eine drastische Verkürzung löscht damit nicht
rückwirkend einen großen, bereits „überfälligen" Bestand in einem einzigen Aufruf; das Anwendungskonto
kann diesen Fortschritt auch nicht direkt manipulieren, da es auf die dafür genutzten Spalten keinen
Schreibzugriff hat. Jede Friständerung erzeugt über `AuditEventRecorder` einen eigenen Eintrag vom Typ
`AUDIT_LOG_CONFIGURATION_CHANGED` (kein neuer Ereignistyp nötig — dieser deckt „die
Protokollkonfiguration selbst" bereits seit #391 ab).

Die Warnung bei einer Protokollfrist unterhalb der Inhaltsaufbewahrung liefert aktuell immer `false`:
Eine konfigurierbare Inhaltsaufbewahrung existiert nicht (der dafür vorgesehene Umfang, #216, wurde
als nicht geplant geschlossen). Der zuvor als Erweiterungspunkt vorbereitete `ContentRetentionProvider`
wurde mit ihm entfernt (#817) und müsste bei Wiederaufnahme des Umfangs neu eingeführt werden.

Die Löschung eines Kontos entfernt bereits seit #391 automatisch die Pseudonymzuordnung
(`fk_audit_actor_pseudonyms_user_organization`, `ON DELETE CASCADE`, zusammengesetzt seit Migration 047) und lässt das Protokoll selbst unverändert — das
war keine neue Arbeit für #395, nur ein zusätzlicher Nachweis auf Anwendungsebene (siehe
`AuditActorPseudonymServiceIntegrationTest`, zusätzlich zum bereits bestehenden Nachweis auf
Datenbankebene in `Migration017AuditLogTest`).

### Der Auszug für die Personalvertretung

Der Auszug ist kein Protokollbericht, sondern die **Beschreibung des Protokolls**. Er ist ohne Anlass,
jederzeit und vollständig exportierbar — vor dem Rollout einmal als Grundlage der Dienstvereinbarung,
danach jährlich als Nachweis, dass sich nichts stillschweigend verschoben hat.

**Er enthält:**

- die vollständige Liste der Ereignisarten mit Zweck je Art
- die vollständige Feldliste des Protokollsatzes, je Feld mit Granularität, Zweck und Frist
- die geltenden Aufbewahrungsfristen und den Zeitpunkt ihrer letzten Änderung
- die Liste der vorhandenen Abfragewege **und** die Liste der ausdrücklich nicht vorhandenen, jeweils mit
  dem Ergebnis der automatisierten Prüfung darauf (siehe [Erfolgs-Metriken](#erfolgs-metriken))
- ob die Netzadresse eingeschaltet ist, seit wann und mit welcher Begründung
- Zahl und Anlasskategorien der anlassbezogenen Klärungen im Berichtszeitraum

**Er enthält nicht:** keine Protokollsätze, keine Namen, keine Pseudonymkennungen, keine Zahlen je Person
oder je Organisationseinheit und keine Objektlisten. Ein Auszug, der belegen soll, dass es keine
personenbezogene Auswertung gibt, darf nicht selbst eine sein.

### Unveränderlichkeit und Löschrecht

Ein nur anfügendes Protokoll und ein nachträgliches Schwärzen schließen einander aus. Der Widerspruch wird
zugunsten der Unveränderlichkeit aufgelöst:

**Der Personenbezug wird ab dem Schreibzeitpunkt pseudonymisiert.** Das Protokoll enthält eine Kennung,
die Zuordnung zur Person liegt in einer getrennt gehaltenen Tabelle. Beim Löschen eines Kontos entfällt
dieser Eintrag — das Protokoll bleibt unverändert und ist danach nicht mehr auf eine Person zurückführbar.
Es wird nichts nachträglich verändert und nichts überschrieben.

### Anbindung an ein zentrales Sicherheitsmonitoring

Behörden betreiben ihr Sicherheitsmonitoring in aller Regel zentral, oft für viele Fachverfahren
zusammen. OPAA liefert seine sicherheitsrelevanten Ereignisse deshalb in einem gängigen Format an ein
solches System aus — fehlgeschlagene Anmeldungen, abgewiesene Verbindungsversuche aus unzulässigen
Netzbereichen, Rechteänderungen, Verwaltungsaktionen, Zugriffe auf Protokolldaten.

**Der Export ist keine Umgehung.** Was hinausgeht, unterliegt denselben Zweck-, Zugriffs- und
Sparsamkeitsregeln wie das Protokoll selbst. Insbesondere führt der Weg über das Sicherheitsmonitoring
nicht an der Festlegung vorbei, dass es keinen personenbezogenen Auswertungspfad gibt: Was OPAA nicht
nach Person gruppiert, liefert es auch nicht so aus. Welche Ereignisklassen ausgeleitet werden, ist Teil
der dokumentierten Zweckbindung und damit Gegenstand der Dienstvereinbarung.

---

## Nachweisbarkeit: Historisierung von Rechten

Die Rechtemenge eines Nutzers ist eine **berechnete Größe** aus drei Quellen — direkte Grants,
Gruppengrants und organisationsweite Freigaben —, von denen sich eine, die Gruppenmitgliedschaft, auch
ohne Zutun der Verwaltung ändert: durch den Verzeichnisabgleich und, seit [ADR-0025](../decisions/0025-mehrere-oidc-anbieter.md),
durch den Gruppenanspruch im Anmeldetoken. Rechte, die aus mehreren Quellen zusammengerechnet werden,
muss man erklären können.

Die Prüferfrage lautet nicht „was hat Frau K. getan", sondern: *„Worauf hatte Frau K. am 3. März Zugriff,
und belegen Sie, dass die Bibliothek `Personalvorgänge` nicht dazugehörte."* Die **Negativfrage** ist die
schwierigere, und ein Ereignisprotokoll kann sie nicht beantworten, solange es Lücken haben kann.

Deshalb werden **alle drei Quellen historisiert**: Grants, Gruppenmitgliedschaften **und die
Reichweitenfelder am Asset** (`visibility`, `listed`). Zu jedem Zeitpunkt ist rekonstruierbar, wer welche
Rechte hatte, seit wann und aufgrund welchen Vorgangs.

Die dritte Quelle mitzunehmen ist nicht optional: Eine Bibliothek, die vom 1. bis zum 10. März
organisationsweit freigegeben war, verschaffte in dieser Zeit Zugriff, ohne dass je ein Grant existierte.
Wäre nur protokolliert statt historisiert, ruhte ein Drittel der Rekonstruktion auf genau der
lückenanfälligen Quelle, die dieses Kapitel verwirft — und die Antwort auf die Prüferfrage fiele falsch
aus, und zwar in die gefährliche Richtung. Es sind zwei Felder an wenigen hundert Objekten.

**Aufbewahrung und Löschschicksal der Historie** folgen derselben Logik wie das Protokoll: Sie unterliegt
einer Höchstdauer, und der Personenbezug ist ab dem Schreibzeitpunkt pseudonymisiert. Beim Löschen eines
Kontos entfällt die Zuordnung; die Historie selbst bleibt unverändert bestehen. Ohne diese Festlegung
entstünden zwei unvereinbare Aussagen — entweder wäre die Zusage „danach nicht mehr auf eine Person
zurückführbar" nicht haltbar, oder für ausgeschiedene Personen wäre nichts mehr belegbar, obwohl
Prüfungen gerade sie häufig betreffen.

**Regressionsprüfung gegen Filterfehler:** Wendet eine Abfrage einen Suchbereich an, der eine nach der
Historie zu diesem Zeitpunkt nicht lesbare Bibliothek enthält, ist das ein beweisbarer
Durchsetzungsfehler. Der Nachweis führt über die **Rechtehistorie** und ihre Rekonstruktion zum
Stichtag, nicht über eine Protokollzeile je Abfrage: Die erste Protokollstufe schreibt Abfragen bewusst
nicht mit (siehe
[Was ausdrücklich nicht protokolliert wird](#was-ausdrücklich-nicht-protokolliert-wird)).

Ein laufender Abgleich der live berechneten Rechte gegen die Historie findet je Abfrage **nicht** statt:
Er beeinflusste weder Suchergebnis noch Zugriffsentscheidung und kostete drei Zusatz-Queries gegen
anhaltend wachsende Tabellen. Dass beide Rechenwege dieselbe Bibliotheksmenge ergeben, sichert
stattdessen ein Integrationstest gegen eine echte Datenbank ab — für jede Operation, die die lesbare
Menge verändert: Berechtigung erteilen, ändern und entziehen, Gruppenmitglied hinzufügen und entfernen,
Gruppe löschen, Verzeichnisabgleich (Mitgliedschaft hinzugefügt, entfernt, Gruppe samt Mitglied neu
angelegt), Gruppenmitgliedschaft aus dem Anmeldetoken (hinzugefügt und entfernt), Bibliothek anlegen, in
der Sichtbarkeit ändern und löschen. Ein Schreibpfad, der Leserechte ändert, ohne seine Historienzeile zu
schreiben, fällt dort auf, bevor er in Betrieb geht. Welche Klassen dabei überhaupt in Frage kommen, hält
derselbe Test gegen den Anwendungskontext, damit eine neue Klasse an den Rechtetabellen nicht unbemerkt
hinzukommt.

Für die **Reichweitenfelder** (`visibility`, `listed`) trägt diese Einschränkung zusätzlich der Compiler:
Über die Bibliothek selbst sind sie nur aus dem Paket heraus veränderbar, das die Historienzeile schreibt
— ein Schreibpfad außerhalb dieses Pakets lässt sich gar nicht erst übersetzen. Am Compiler vorbei ginge
es weiterhin über direktes SQL, eine Datenbankmigration oder Reflection; diese Wege sieht keine der
Prüfungen.

Für den **Bestand** einer Bibliothek — das Anlegen und Löschen der Zeile selbst — gilt weder das eine noch
das andere: Der Test gegen den Anwendungskontext lässt das Bibliotheks-Repository bewusst aus, weil es
überwiegend von lesenden Klassen gehalten wird, und die Prüfung je Operation kennt nur die dort benannten
Dienste. Dass heute keine weitere Klasse Bibliothekszeilen anlegt oder löscht, ist eine Beobachtung, keine
Prüfung. Für Mitgliedschaften bleibt es bei der Absicherung über den Test.

Das ist bewusst **anders gelöst als über eine Protokollzeile je Abfrage**: Die Rechtemenge bei jeder Suche
mitzuschreiben würde das Protokoll um eine erhebliche Menge personenbezogener Daten erweitern — genau das,
was die Datensparsamkeit vermeiden soll — und wäre trotzdem lückenanfällig. Die Historie liefert dieselbe
Aussage mit weniger Daten.

**Folge für die Berichte:** Einen Bericht „abgelehnte Zugriffe" kann es nicht geben. Weil der Filter Teil
der Vektorsuche ist, existiert kein abgelehnter Zugriff, den man protokollieren könnte — unberechtigte
Chunks werden nie geladen. Was es gibt, ist der Nachweis über die **Rechtehistorie** — und er ist der
stärkere, weil er den Zustand belegt und nicht das Ausbleiben eines Ereignisses.

**Umsetzungsstand (#238):** Grants, Gruppenmitgliedschaften und die Reichweitenfelder einer Bibliothek
sind als Intervalle mit auslösendem Vorgang historisiert, einschließlich eines Backfills für den
Altbestand (Ursache `BACKFILL`, `valid_from` aus dem jeweiligen Erstellungszeitpunkt der Fachzeile) —
ohne ihn wäre die Rekonstruktion für jeden Stichtag vor der Migration und für jedes seither unveränderte
Recht falsch, nicht bloß lückenhaft. Die Historie überlebt die Löschung einer Bibliothek oder Gruppe
(siehe [ADR-0016](../decisions/0016-loeschschicksal-rechtehistorie.md)): Die Fachobjekt-Spalten tragen
bewusst keinen Fremdschlüssel, damit eine reguläre Lösch-Operation die Beweislage nicht mit sich reißt.

**Auflösung der Intervallgrenzen (#1497, [ADR-0032](../decisions/0032-zeitquelle-rechtehistorie.md)):**
Aufeinanderfolgende Zustandsintervalle desselben Objekts haben streng aufsteigende Grenzen — auch dann,
wenn beide Änderungen in denselben Tick der Systemuhr fallen. Die Zusage gilt **für Zeilen, die ab
dieser Korrektur geschrieben wurden**; vorher entstandene leere Intervalle bleiben bestehen und werden
nicht nachbearbeitet. Für einen Stichtag in einem solchen Altzeitraum kann die Rekonstruktion also
weiterhin fälschlich „kein Zugriff" melden — dieselbe Einschränkung, die weiter unten unter „Beginn der
belegbaren Historie" für die Zeit vor dem Backfill gilt. Ohne diese Zusage hinterlässt ein Zustand,
der kürzer bestand als die Uhr fortschreitet, ein leeres Intervall, und ein leeres Intervall enthält
keinen Stichtag: Die Rekonstruktion antwortete für diesen Zeitraum „kein Zugriff", obwohl Zugriff
bestand. Die Grenzen stammen deshalb aus einer streng monotonen Quelle mit Mikrosekunden-Auflösung
statt unmittelbar aus der Systemuhr. Lückenlos bleiben die Intervalle dabei: Das Schließen eines
Intervalls und das Öffnen des folgenden teilen sich einen Grenzwert. Zeilen der Länge null gibt es
weiterhin, aber ausschließlich als **Ereignismarkierung** für einen Widerruf oder eine Löschung — sie
halten den auslösenden Vorgang fest und werden von der Rekonstruktion nie als Zustand ausgewählt.

**Noch offen, bewusst nicht Teil dieser Ausbaustufe:**

- **Aufbewahrungshöchstdauer und Pseudonymisierung der Historie selbst.** Die oben zugesagte
  Pseudonymisierung ab Schreibzeitpunkt ist noch nicht umgesetzt; die Subjektspalten der
  Rechtehistorie sind stattdessen `ON DELETE RESTRICT` gegen die Nutzertabelle — eine Kontolöschung
  ist damit blockiert, solange Rechtehistorie zu diesem Konto existiert, bis #391/#395 die
  Pseudonymisierung liefern (siehe ADR-0016).
- **Verzeichnislauf ohne Laufbezug.** Ein historisierter Eintrag mit Ursache `DIRECTORY_SYNC_ADDED`/
  `DIRECTORY_SYNC_REMOVED` lässt sich nicht auf den konkreten Synchronisationslauf zurückführen, der
  ihn verursacht hat — `DirectorySyncStatus` hält nur den jeweils letzten Lauf je Organisation.

Beides ist als Follow-up vorgesehen, nicht als Lücke im Rekonstruktionsergebnis selbst.

**Beginn der belegbaren Historie:** Der Backfill sieht ausschließlich die zum Migrationszeitpunkt noch
lebenden Fach­zeilen. Ein Recht, das vor der Migration erteilt **und vor der Migration bereits wieder
entzogen** wurde, hinterlässt keine Spur — die Fachzeile existiert dann nicht mehr, es gibt nichts, was
der Backfill lesen könnte. Für einen Stichtag **vor** dem Migrationszeitpunkt kann die Rekonstruktion
deshalb weiterhin fälschlich „kein Zugriff" antworten, wenn zwischen Erteilung und Entzug kein
Datenbestand mehr existierte, der das Gegenteil belegt. Das ist eine Dateneigenschaft, keine Lücke im
Code: Die Migration kann nur historisieren, was zu ihrem Zeitpunkt noch da ist. Ab dem
Migrationszeitpunkt — und für jedes seither unverändert bestehende Recht rückwirkend bis zu seiner
Entstehung, weil der Backfill dessen `created_at` übernimmt — sind die Rechtemenge zu einem Stichtag und
die Negativfrage korrekt beantwortbar. Für einen Prüfzeitraum, der vor die Migration zurückreicht und
Rechte betrifft, die dort bereits beendet waren, bleibt die Rechtehistorie ohne Aussage.

---

## Vollständigkeit nach DSGVO: Löschung und Export

### Löschung eines Benutzerkontos

```
1. Zugang sofort deaktivieren — nie durch offene Eigentumsfragen aufgehalten
2. Assets in den Zustand "Nachfolge offen" versetzen: nutzbar, aber Reichweite eingefroren
3. Nutzer aus allen Spaces und Gruppen entfernen
4. Konto, Sitzungen und Tokens löschen
5. Pseudonymzuordnung entfernen — das Protokoll bleibt unverändert bestehen
```

Private Chats und Artefakte des Nutzers folgen den
[Offboarding-Regeln für den Standard-Space](./access-control.md#der-lebenszyklus-eines-kontos): Der
Standard-Space wird deaktiviert statt gelöscht, private Inhalte bleiben darin unzugänglich — für
niemanden, auch nicht für einen Nachfolger. Das ist der Sperr-Pfad, der Regelweg für jedes Konto,
das je benutzt wurde. Die **echte Löschung** eines lokalen Kontos (#1537) ist auf Konten beschränkt,
die nichts referenziert — keine Bibliothek, kein Chat, kein Eintrag in Rechte- oder
Nachweisbeständen; praktisch ein nie benutztes Konto —, und löscht dessen persönlichen Space mit
(als `SPACE_DELETED` protokolliert), weil das Schema einen Space ohne Eigentümer nicht zulässt. Geteilte Chats und Artefakte sind Arbeitsergebnisse der
Organisation und verschwinden nicht mit dem Konto ihres Erstellers, werden aber nach Ablauf der
Aufbewahrungsfrist gelöscht.

Dokumente werden über ihre Wissensbibliothek gelöscht. Für konnektor-indizierte Dokumente gilt weiterhin
der Ausschluss-Mechanismus, weil sie beim nächsten Lauf sonst erneut aufgenommen würden.

**Löschung heißt Löschung in allen abgeleiteten Beständen.** Ein gelöschtes Dokument verschwindet mit
seinen Chunks, Einbettungen und Zwischenständen; ein Rest im Index wäre der Unterschied zwischen einer
erfüllten und einer behaupteten Löschpflicht.

### Export und Auskunft

**Personenbeziehbare Felder lokaler Konten** ([ADR-0033](../decisions/0033-lokale-benutzerverwaltung.md)):
Mit der lokalen Benutzerverwaltung kommen zu einem Konto der Anlagegrund (dienstlicher Anlass und
Befristungsgrund, höchstens 200 Zeichen, für die betroffene Person in ihren Einstellungen einsehbar),
Ablaufdatum, Sperrzustand mit Grund, der Zeitpunkt der E-Mail-Bestätigung und die Kennzeichnung
„Passwortwechsel ausstehend" mit Anlass hinzu — alle Teil der Selbstauskunft. Die Sitzungs- und
Aktionstoken (`local_refresh_tokens`, `local_revoked_tokens`, `local_action_tokens`) sind Betriebsdaten
mit harter Frist (Lebensdauer begrenzt, Löschung spätestens sieben Tage nach Ablauf oder Widerruf);
es gibt keine Oberfläche und keine Schnittstelle, die sie je Person ausgibt — die in
[Zugangskontrolle](./access-control.md#sitzungsverwaltung) zugesagte Übersicht der **eigenen**
Sitzungen ist die einzige vorgesehene Ausnahme und nur für die Person selbst. Die Kontenliste der
Verwaltung zeigt Aktivität nur als Klasse („nie", „länger als 90 Tage nicht", „aktiv"), lässt sich
nicht danach sortieren und kennt keinen Export und keinen Massenabruf. Seit dem 12.09.2026 (#1601)
führt sie **alle Konten der Organisation**, lokale wie die der Identitätsanbieter; die
Aktivitätsklasse erscheint dabei **nur an lokalen Zeilen** — für ein Anbieterkonto gibt OPAA keine
aus, sein Lebenszyklus liegt beim Anbieter. Die drei Zusagen dieses Absatzes — Klasse statt
Zeitstempel, keine Sortierung danach, kein Export — gelten unverändert und sind der Gegenstand der
Auflage, nicht die Frage, welche Konten die Liste führt. Der [Auszug für die
Personalvertretung](#der-auszug-für-die-personalvertretung) führt diese Felder und die neuen
Ereignisarten, bevor die lokale Verwaltung erstmals eingeschaltet wird.

Drei Exporte, die auseinanderzuhalten sind, weil sie verschiedene Adressaten haben:

| Export | Für wen | Inhalt |
|---|---|---|
| **Selbstauskunft** | die betroffene Person selbst | alle zu ihr gespeicherten Daten, vollständig und maschinenlesbar — **nicht delegierbar**, für niemanden sonst auslösbar |
| **Auskunft über die Datenerhebung** | Datenschutzbeauftragte und Personalvertretung | welche personenbeziehbaren Felder erhoben werden, in welcher Granularität, zu welchem Zweck und wie lange sie liegen — vor dem Rollout einmal vollständig vorlegbar |
| **Bestandsexport** | die Organisation | Wissensbestände, Assets und Konfiguration in offenen Formaten, damit ein Wechsel des Betreibers oder des Produkts möglich bleibt |

Die Selbstauskunft ist der einzige Weg, auf dem personenbezogene Daten gebündelt herausgehen — und sie
geht ausschließlich an die betroffene Person, weder über eine Vertretungsfunktion noch durch einen Admin
„im Auftrag" noch in ein fremdes Postfach.

### Berichte

- **Rechteänderungen an einem Objekt:** wer hat wem was freigegeben, mit Rechtestand zum Stichtag aus der
  Historie — Einstieg über das Objekt und einen Zeitraum, nie über eine Person
- **Verwaltungshandeln nach Ereignisart** in einem Zeitraum, etwa alle Änderungen an
  Systemeinstellungen
- **Auszug für die Personalvertretung** — die Beschreibung des Protokolls, nicht sein Inhalt
  ([oben](#der-auszug-für-die-personalvertretung))
- **Testzugang für die Personalvertretung** vor dem Rollout, damit sie die Zusagen selbst nachvollziehen
  kann statt sie zu glauben

Einen **Zugangsbericht** „wer hat wann worauf zugegriffen" gibt es nicht. Er setzte voraus, dass
Lesezugriffe und Abfragen mitgeschrieben werden — genau das, was die erste Stufe ausschließt. Was er
beantworten sollte, beantwortet die Rechtehistorie: nicht wer gelesen **hat**, sondern wer lesen
**durfte**.

---

## Sichere Voreinstellungen und Produkthärtung

Der Auslieferungszustand ist der sichere Zustand. Wer OPAA aufsetzt, soll nichts abschalten müssen, um
sicher zu sein — er soll etwas einschalten müssen, um es nicht zu sein, und das begründen.

- **Keine Vorgabekennwörter, keine Vorgabekonten.** Der erste Verwaltungszugang entsteht bei der
  Einrichtung, nicht im Auslieferungszustand. Das eine Konto, das OPAA sich beim allerersten Start
  selbst anlegt — das Notanker-Konto der Systemverwaltung — ist kein Vorgabekonto in diesem Sinn: Seine
  Adresse muss der Betrieb vorher benennen (der ausgelieferte Vorgabewert wird abgelehnt), sein
  Passwort ist zufällig erzeugt, steht einmalig im Log und muss bei der ersten Anmeldung gewechselt
  werden.
- **Verschlüsselung auf dem Transportweg durchgehend**, auch zwischen den Bestandteilen des Systems.
  Ruhende Daten liegen im verschlüsselten Speicher des Betreibers; Schlüsselverwaltung und -wechsel sind
  dokumentiert und liegen beim Betreiber.
- **Geschlossene Voreinstellung nach außen.** Ohne ausdrückliche Freigabe geht keine Anfrage an ein
  externes Modell und an keinen externen Dienst. Der Betrieb ohne Netzanbindung ist das vorgesehene
  Szenario, nicht die Ausnahme.
- **Getrennte Ausführung.** Was Dokumente verarbeitet oder Werkzeuge ausführt, läuft in einer eigenen,
  eingeschränkten Umgebung ohne Zugriff auf Netz und Datenbestand außerhalb des Auftrags.
- **Fehlermeldungen verraten nichts.** Nutzerseitige Meldungen nennen keine internen Pfade, Kennungen
  oder Bestandteile; die Einzelheiten gehen ins Protokoll.
- **Grenzen sind voreingestellt**, nicht optional — Größen von Uploads, Anzahl gleichzeitiger Anfragen,
  Verbrauchsgrenzen je Nutzer (siehe [Monitoring, Kosten & Governance](./monitoring-and-governance.md)).
- **Passwörter liegen nie im Klartext und nie umkehrbar.** Ein Passwort eines lokalen Kontos wird
  ausschließlich als Prüfwert eines bewusst langsamen Verfahrens mit Arbeitsfaktor gespeichert, mit
  einem Präfix versehen, das das Verfahren benennt — damit ein späterer Wechsel des Verfahrens eine
  Konfigurationsänderung ist und nicht ein Datenbankeingriff. Verglichen wird nur über dieses
  Verfahren, auch bei unbekannter Kennung (gegen einen festen Vergleichswert), damit die Antwortzeit
  nicht verrät, ob eine Adresse existiert. Weder das Anwendungslog noch das Nachweisprotokoll noch
  eine API-Antwort enthalten jemals ein Passwort, ein Roh-Token oder einen Einladungslink; ein Test
  hält das maschinell fest. Die Richtlinie verlangt eine Mindestlänge und schließt die häufigsten
  Passwörter aus, verzichtet aber bewusst auf Komplexitätsregeln und auf einen erzwungenen
  periodischen Wechsel — beides verschiebt Passwörter erfahrungsgemäß auf Zettel.
- **Der Anmeldeweg ist begrenzt, bevor er antwortet.** Anmeldung, Erneuerung, Passwortwechsel,
  Registrierung und die beiden Link-Flüsse haben je eigene Grenzen — je Client-Adresse, je Konto
  beziehungsweise je E-Mail-Adresse und zusätzlich eine globale Grenze je Zeitfenster gegen verteiltes
  Ausprobieren gestohlener Zugangsdaten. Eine überschrittene Grenze antwortet mit einer Wartezeit; das
  Überschreiten der globalen Grenze erzeugt eine Warnung und eine Metrik und ist damit ein
  Frühwarnsignal für den Betrieb. Mehrere falsche Passwörter in Folge sperren zusätzlich das Konto für
  eine feste Frist. Die Client-Adresse dafür wird nur aus einem Weiterleitungs-Header übernommen, wenn
  die Verbindung selbst aus einem als vertrauenswürdig benannten Netz kommt; die Vorgabe ist, dem
  Header nicht zu trauen. Die konkreten Werte stehen im Produkthandbuch, nicht hier.
- **Härtungs- und Konfigurationsleitfäden** sind Teil des Produkts, nicht Beratungsleistung: eine
  dokumentierte Referenzkonfiguration, eine Liste der sicherheitsrelevanten Einstellungen mit ihrer
  Voreinstellung und eine Prüfliste für die Inbetriebnahme.
- **Das Produkt prüft sich selbst.** Eine Betriebsansicht zeigt an, wo die laufende Konfiguration von der
  gehärteten Referenz abweicht. Eine Abweichung ist erlaubt; unbemerkt zu bleiben ist sie nicht.

---

## Geordneter Entwicklungsprozess: Stückliste und signierte Builds

Ein Betreiber kann für eine Software nur einstehen, wenn er weiß, woraus sie besteht und dass das
Ausgelieferte dem Geprüften entspricht.

- **Software-Stückliste.** Für jede Veröffentlichung wird maschinenlesbar mitgeliefert, aus welchen
  Bestandteilen und Versionen sie besteht. Das ist die Voraussetzung dafür, dass eine neu bekannt
  gewordene Schwachstelle im Haus binnen Stunden bewertet werden kann statt binnen Wochen.
- **Signierte Artefakte.** Veröffentlichte Stände und Container-Abbilder sind signiert und ihre Herkunft
  ist überprüfbar. Der Betreiber kann feststellen, dass er das ausführt, was veröffentlicht wurde.
- **Reproduzierbare Builds.** Derselbe Quellstand ergibt dasselbe Artefakt. Damit ist die Signatur mehr
  als eine Zusage: Sie ist unabhängig nachvollziehbar — und für einen quelloffenen Kern ist genau das das
  Argument, warum Offenheit hier Sicherheit erzeugt und nicht Angriffsfläche.
- **Geordneter Änderungsprozess.** Änderungen laufen über nachvollziehbare Vorgänge mit Überprüfung durch
  eine zweite Person, automatisierte Tests und ein festes Vorgehen bei Sicherheitskorrekturen. Für die
  Meldung von Schwachstellen gibt es einen benannten Weg und eine zugesagte Reaktion.
- **Unabhängige Prüfung als Nachweisstufe.** Eine externe Sicherheitsuntersuchung des Produkts und seines
  Quelltextes ist die Stufe, die aus einer Selbstauskunft einen Nachweis macht. Sie ist keine
  Produktfunktion, sondern ein Reifegrad; ihr Bericht gehört in das Nachweispaket. Wann und in welchem
  Umfang sie erfolgt, entscheidet das Projekt gesondert.

---

## C5-Fähigkeit statt Zertifizierung

**OPAA wird nie selbst „C5-zertifiziert".** Das ist keine Zurückhaltung, sondern eine Eigenschaft des
Kriterienkatalogs: Der C5-Katalog des BSI prüft den **Betrieb** eines Dienstes — Organisation, Personal,
Räumlichkeiten, Verfahren —, nicht ein Stück Software. Ein Softwarehersteller kann diese Prüfung schon
deshalb nicht bestehen, weil er den geprüften Gegenstand gar nicht betreibt. Wer mit einem
„C5-zertifizierten Produkt" wirbt, beschreibt etwas, das es nicht gibt.

Das Produktziel ist deshalb die **C5-Fähigkeit**: OPAA ist so gebaut und dokumentiert, dass ein Betreiber
die Prüfung **mit OPAA im Prüfumfang** besteht. Was dafür nötig ist, sind die vier Blöcke dieses
Dokuments — revisionssicheres Protokoll, Vollständigkeit nach DSGVO mit Löschung und Export, sichere
Voreinstellungen und Produkthärtung, geordneter Entwicklungsprozess mit Stückliste und signierten Builds.

### Welche Prüfbereiche das Produkt betreffen

| | Prüfbereiche |
|---|---|
| **Softwarerelevant** — OPAA muss liefern, was der Betreiber hier vorlegt | Identitäts- und Berechtigungsmanagement (IDM) · Regelbetrieb (OPS) · Produktsicherheit (PSS) · Kryptographie und Schlüsselmanagement (CRY) · Portabilität und Interoperabilität (PI) · Compliance (COM) · Beschaffung, Entwicklung und Änderung von Informationssystemen (DEV) · Kommunikationssicherheit (COS) · Umgang mit Sicherheitsvorfällen (SIM) · Kontinuität des Betriebs (BCM) |
| **Rein betreiberseitig** — OPAA kann dazu nichts beitragen | Physische Sicherheit (PS) · Personal (HR) · Organisation der Informationssicherheit (OIS) · Sicherheitsrichtlinien und Arbeitsanweisungen (SP) · Steuerung und Überwachung von Dienstleistern (SSO) · Umgang mit Ermittlungsanfragen staatlicher Stellen (INQ) · Verwaltung der Werte (AM) |

Diese Zuordnung ist eine Einschätzung aus Produktsicht und ersetzt keine Prüfungsplanung; maßgeblich ist
der Prüfumfang, den Betreiber und Prüfstelle vereinbaren.

### Zwei Wege — die Behörde wählt

Beide Wege werden **gleichwertig** unterstützt. Die Nachweise sind dieselben, nur der Adressat
unterscheidet sich.

**Selbst betreiben.** Das Haus betreibt OPAA im eigenen Rechenzentrum und bringt es in seine eigene
Prüfung ein. Dafür stellt das Projekt ein **Nachweispaket** bereit:

- **Verantwortungsmatrix** — welche Anforderung erfüllt das Produkt, welche der Betreiber, welche beide
  gemeinsam. Ohne sie beginnt jede Prüfung mit derselben Klärungsrunde.
- **Härtungs- und Konfigurationsleitfäden** mit Referenzkonfiguration und Prüfliste
- **Software-Stückliste** je Veröffentlichung
- **Signierte und reproduzierbare Builds** mit überprüfbarer Herkunft
- **Bericht der unabhängigen Prüfung**, sobald vorhanden

**Über einen bereits testierten Betreiber betreiben lassen.** Ein Betreiber, der ein C5-Testat bereits
hält — etwa ein Landes- oder Bundesrechenzentrum —, nimmt OPAA in seinen Prüfumfang auf. Die Behörde
bezieht dann das Testat und muss selbst nichts nachweisen. Für Häuser ohne eigenes testiertes
Rechenzentrum ist das der kürzere Weg; er setzt voraus, dass OPAA mandantenfähig betrieben werden kann,
und ist damit an die Mandantengrenze der Organisation gebunden.

Welcher Weg der richtige ist, hängt allein am vorhandenen Betrieb, nicht an einer Produktentscheidung.
OPAA verhält sich in beiden Fällen gleich.

---

## Mitbestimmungsfähigkeit

OPAA erzeugt Daten mit Personenbezug: geteilte Chatverläufe in gemeinsamen Räumen, Nutzungsstatistiken
je Asset, Protokolldaten. In einer Behörde ist das mitbestimmungsrelevant — **ohne Dienstvereinbarung
beginnt in aller Regel kein Rollout**, und die Personalvertretung spricht genau diese Punkte an. Das ist
das am häufigsten unterschätzte Einführungshindernis: kein technisches, sondern eines, das ein Projekt vor
der ersten Zeile Nutzung anhalten kann.

Die ausführliche Begründung, die drei Datenquellen mit Personenbezug und die vollständige Liste der
Stellschrauben stehen in
[Spaces, Assets & Zugangskontrolle](./spaces-and-assets.md#mitbestimmung-und-personalvertretung). Hier
stehen die fünf Eigenschaften, die das Produkt dafür tragen muss.

### 1. Sichtbarkeit ist eine Handlung, keine Automatik

Chats und Artefakte entstehen zunächst privat und werden erst sichtbar, wenn die Person sie in den Space
teilt. Wer teilt, tut es bewusst. Die dreimal gestellte Rückfrage zu einer Rechtsgrundlage, bei der jemand
unsicher ist, wird damit nicht zur dauerhaft sichtbaren Wissenslücke in Schriftform.

**Private Inhalte sind verbindlich unbeobachtet** — in jedem Space, nicht nur in einem dafür vorgesehenen,
und auch gegenüber Systemverwaltung, Revision und Dienststellenleitung — und **fachlich gleichwertig**:
Ein privater Chat verfügt über dasselbe Wissen und denselben Suchbereich wie ein geteilter im selben
Space. Ohne diese Gleichwertigkeit wäre die Ausweichmöglichkeit nur formal und der Zwang zum sichtbaren
Raum faktisch.

Geschützt ist der **Inhalt**, nicht die Tatsache der Nutzung: Dass jemand arbeitet, wird protokolliert;
was er schreibt, nicht. Das gehört ausgesprochen, damit die Auskunft an die Beschäftigten stimmt.

### 2. Einen personenbezogenen Auswertungspfad gibt es nicht

> **Es gibt keine Schnittstelle und keine Oberfläche, die Nutzungs-, Chat- oder Herkunftsdaten nach Person
> filtert, gruppiert oder sortiert. Diese Funktion ist nicht abschaltbar vorhanden — sie existiert
> nicht.**

**Nicht abgeschaltet, sondern nicht gebaut.** Eine Funktion, die es nicht gibt, kann niemand einschalten.
Für eine Dienstvereinbarung ist das der Unterschied zwischen einer Zusage und einer Tatsache — und
zwischen „heute ist es aus" und „morgen ist es an, mit rückwirkend auswertbaren Daten von gestern".

Zwei Wege bleiben notwendigerweise offen, und beide sind kein Auswertungspfad:

- **Selbstauskunft** der betroffenen Person — ihr eigenes Auskunftsrecht, nicht delegierbar.
- **Anlassbezogene Klärung eines Sicherheitsvorfalls** — im **Vier-Augen-Prinzip unter Beteiligung der
  Personalvertretung**, mit dokumentiertem Anlass und eigenem Protokolleintrag über den Zugriff. Ein
  Produkt ohne jede Möglichkeit, einen Vorfall aufzuklären, wäre nicht betreibbar; eines, in dem diese
  Aufklärung der Normalweg ist, wäre nicht zustimmungsfähig.

Diese Ausnahme ist **inhaltlich begrenzt, nicht nur formal** — sie ist der einzige verbliebene Weg von
den Daten zu einer Person, und alles, was jemand wissen will, drückt künftig durch dieses eine Nadelöhr:

1. **Zweckausschluss.** Nicht verfügbar für arbeitsrechtliche, disziplinarische und leistungsbezogene
   Fragen — auch nicht bei Mischsachverhalten.
2. **Umfangsbegrenzung vorab.** Person, Zeitraum und Zweck werden vor der Freigabe festgelegt und
   begrenzen die Abfrage **technisch**. Sonst klärt man einen Vorfall vom Mai und liest dabei zwei Jahre.
3. **Unterrichtung der betroffenen Person** nach Abschluss, mit Anlass und Umfang — außer die Klärung
   richtet sich gegen einen Dritten.
4. **Jahresbericht an die Personalvertretung** über Zahl der Fälle und Anlässe in Kategorien, ohne Namen.

### 3. Keine Ranglisten

Kein Vergleich einzelner Beschäftigter, keine Bestenlisten, keine Aktivitätsbewertung — **auch nicht als
spielerisches Element**. Der spielerische Rahmen ändert nichts an der Datengrundlage; er macht die
Auswertung nur geselliger.

Statistiken sind **aggregiert** je Organisationseinheit. Unterhalb einer Mindestgruppengröße wird der Wert
**unterdrückt statt angezeigt** — nicht gerundet, nicht anonymisiert dargestellt, sondern nicht
ausgegeben. Das Produkt setzt eine Voreinstellung und erzwingt eine Untergrenze; die angemessene Zahl
folgt aus dem tatsächlichen Zuschnitt der Einheiten und gehört in die Dienstvereinbarung.

### 4. Aufbewahrung mit Ober- und Untergrenze

Für Chats, Artefakte, Herkunftsdaten **und Protokolldaten**: konfigurierbare Frist mit einer
**Höchstdauer**, nicht nur einer Mindestdauer, und automatischer Löschung nach Ablauf.

Die **Netzadresse gehört nicht zum Standard-Protokollsatz**, weil sie Dienststelle und Heimarbeit
unterscheidbar macht und damit ein Anwesenheitsmerkmal ist. Wird sie für Sicherheitszwecke benötigt, wird
sie ausdrücklich eingeschaltet, begründet und aus Berichten und Exporten ausgeschlossen.

### 5. Getrennte Zugriffswege, technisch durchgesetzt

Revision und Dienststellenleitung haben **verschiedene** Auswertungswege, und die Trennung ist technisch
durchgesetzt statt organisatorisch zugesagt. Für **jede erhobene Kennzahl ist der Zweck dokumentiert** —
auch für die Protokolldaten und die Herkunftsverfolgung, nicht nur für die Kennzahlen des Cockpits. Ein
**Auszug für die Personalvertretung** ist exportierbar: welche personenbeziehbaren Daten erhoben werden,
in welcher Granularität, wozu und wie lange sie liegen.

### Die Wirkung

**Die Dienstvereinbarung wird damit zu einer Konfigurationsaufgabe statt zu einem Projektrisiko.** Die
Punkte, die die Personalvertretung ansprechen wird, sind im Produkt bereits entschieden; verhandelt werden
Fristen, Schwellen und Zuständigkeiten — nicht die Frage, ob eine Überwachungsfunktion existiert.

### Was das Produkt nicht regeln kann

- **Freiwilligkeit.** Ob die Nutzung verpflichtend wird und ob Beschäftigten ein Nachteil entsteht, die
  den Assistenten nicht oder nur für sich nutzen, entscheidet die Dienststelle.
- **Die Höhe der Mindestgruppengröße.** In einem Referat mit vier Beschäftigten ist auch ein Aggregatwert
  personenbeziehbar, sobald zwei im Urlaub sind.
- **Die rechtliche Bewertung.** Ob und in welchem Umfang der Mitbestimmungstatbestand greift, entscheidet
  die einführende Stelle; die Darstellung hier ist Produktsicht und keine Rechtsberatung.

---

## Integrationspunkte

- **Identität und Kontenlebenszyklus:** jede Rechte- und Kontenänderung erzeugt einen Protokolleintrag →
  [access-control.md](./access-control.md)
- **Rechtemodell und Mitbestimmung:** die Datenquellen mit Personenbezug und die vollständige Liste der
  Stellschrauben → [spaces-and-assets.md](./spaces-and-assets.md)
- **Monitoring und Governance:** die Grenze dieses Dokuments ist dort die verbindliche Vorgabe →
  [monitoring-and-governance.md](./monitoring-and-governance.md)
- **RAG-Engine:** der angewandte Suchbereich je Abfrage ist die Grundlage der Regressionsprüfung →
  [data-indexing-rag.md](./data-indexing-rag.md)
- **Modelle:** die Freigabe externer Modelle ist eine sicherheitsrelevante Einstellung →
  [llm-integration.md](./llm-integration.md)
- **Betrieb:** Verschlüsselung ruhender Daten, Schlüsselverwaltung, Sicherung und Wiederanlauf liegen
  beim Betreiber → [deployment-infrastructure.md](./deployment-infrastructure.md)

---

## Offene Fragen

- **Prüfsummenverkettung der Protokolleinträge** — die naheliegende nächste Stufe des Sicherheitsgrads
  und die Antwort auf die benannte Grenze der ersten: Erst sie macht eine Manipulation mit direktem
  Datenbankzugang erkennbar. Offen ist der Zuschnitt: Verkettung je Satz oder je Zeitabschnitt,
  Veröffentlichung der Ankerwerte, und wie sich das Schwärzen des Personenbezugs beim Löschen eines
  Kontos mit einer Kette verträgt (heute betrifft es nur die getrennte Zuordnungstabelle und lässt das
  Protokoll unberührt — das bleibt auch mit Kette die richtige Auflösung, muss aber nachgewiesen werden).
- **Ausleitung an ein zentrales Sicherheitsmonitoring** — welche Ereignisklassen, in welchem Format und
  ab welcher Stufe. Der Umfang der ersten Stufe schreibt Anmeldeereignisse bewusst nicht mit; sie kommen
  über diesen Weg oder gar nicht.
- Verlangt eine C5-Prüfung die Netzadresse im Protokollsatz zwingend? Falls ja, ist die Ausnahme
  schriftlich zu begründen.
- Wie wird die technische Trennung der Auswertungswege für Revision und Leitung konkret durchgesetzt —
  über getrennte Rollen, getrennte Endpunkte oder getrennte Bestände?
- Wie wird die vorab festgelegte Umfangsbegrenzung der anlassbezogenen Klärung technisch erzwungen?
- Ab wann und in welchem Umfang erfolgt die unabhängige Sicherheitsprüfung?
- Wie weit reicht der Bestandsexport für einen Betreiberwechsel — Rohbestände, Assets, Konfiguration,
  Protokolldaten?

---

## Erfolgs-Metriken

- **Prüfbarkeit:** Die Negativfrage („belegen Sie, dass X nicht zugänglich war") ist für einen beliebigen
  Stichtag ohne Nacharbeit beantwortbar.
- **Vollständigkeit:** Kein Zugriff auf Protokolldaten ohne eigenen Eintrag; nachgewiesen durch Prüfung
  gegen alle Auswertungswege.
- **Nachweis der Nichtexistenz:** Ein Test gegen sämtliche Auswertungsendpunkte belegt, dass keiner nach
  Person filtert, gruppiert oder sortiert — und dass jede Abfrage einen Objekt-, Zeitraum- oder
  Ereignisbezug erzwingt.
- **Unveränderlichkeit auf Datenbankebene:** Ein Test mit dem Anwendungskonto belegt, dass `UPDATE`,
  `DELETE` und `TRUNCATE` auf der Protokollablage scheitern. Die Grenze dieses Nachweises ist benannt:
  Er sagt nichts über einen Zugang an der Anwendung vorbei.
- **Löschtreue:** Nach Ablauf der Höchstfrist existieren keine Sätze mehr; nach einer Kontolöschung ist
  kein Protokollsatz auf eine Person zurückführbar, und kein Satz wurde verändert.
- **Einführungsreife:** Das Nachweispaket ist vor der ersten Prüfung vollständig vorlegbar — gemessen
  daran, wie viele Rückfragen einer Prüfstelle es nicht schon beantwortet.

---

## Verwandte Dokumente

- [Identität, Rechte & Mandanten](./access-control.md) — Anmeldung, Kontenlebenszyklus, Systemverwaltung
- [Spaces, Assets & Zugangskontrolle](./spaces-and-assets.md) — Rechtemodell und Mitbestimmung im Detail
- [Monitoring, Kosten & Governance](./monitoring-and-governance.md) — was ausgewertet werden darf
- [Produktvision](../VISION.md) — Einordnung in die Themenbereiche und Phasen
