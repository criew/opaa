# Sicherheit, Nachweis & Prüfbarkeit

> **Status: Entwurf — die Leitplanken stehen, der Schnitt der ersten Protokollstufe ist entschieden.**
>
> **Phasenlage:** Phase 1. Das revisionssichere Protokoll, die Vollständigkeit nach DSGVO, sichere
> Voreinstellungen und die Mitbestimmungsfähigkeit gehören zum Fundament — ohne sie ergibt ein Start in
> einer Behörde keinen Sinn. Der geordnete Entwicklungsprozess mit Stückliste und signierten Builds läuft
> parallel und begleitend; die unabhängige Prüfung als Nachweisstufe ist eine Reifegradfrage, keine
> Funktion.
>
> **Selbstauskunft (Stufe 1, Epic #2986): gebaut** — Ablauf, Grenzen, Protokoll, Oberfläche in den
> Einstellungen („Meine Daten“) und Administration („Datenschutz“, #2993) und die Ausgabe aller
> Kategorien der Tabelle „Inhalt“. Der Abschnitt
> [Selbstauskunft](#selbstauskunft-stufe-1) ist die verbindliche Vorlage für #2988–#2994 und #2996.
> Die Oberfläche löst den Download als Formular-POST in einen verborgenen Frame aus, den nur dieser
> Endpunkt zulässt (`X-Frame-Options: SAMEORIGIN`, `frame-ancestors 'self'`), und liest Ende oder
> Abbruch aus dem Status: alle paar Sekunden, solange der Tab sichtbar ist, und sofort bei der
> Rückkehr in den Tab.

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

**Bewusste Ausnahmen** sind wenige, benannte Leseereignisse: der Abruf von Mitgliederlisten und
Abgleichsberichten durch die Verwaltung, die Vorschau einer Rechteübertragung, jeder Zugriff auf
Protokolldaten und der Export der [Selbstauskunft](#protokoll-der-selbstauskunft). Letzterer ist
weder Zugriffsänderung noch Verwaltungshandeln; er wird festgehalten, weil er der Nachweis ist, dass
das Auskunftsrecht erfüllt wurde, und weil ein missbräuchlicher Abruf aller eigenen Daten sonst
spurlos bliebe (Entscheidung des Maintainers nach dem Stakeholder-Review, Epic #2986).

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
  "correlation_ref": "sync-2026-02-16-06",
  "schema_version": 1
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
| `schema_version` | Version des [Ereigniskatalogs](#der-ereignisvertrag-katalog-und-schemaversion), unter der der Eintrag geschrieben ist; sagt einer Prüfung nach Jahren, wie `before`/`after` zu lesen sind |

**Die Netzadresse ist nicht Teil des Standardsatzes.** Sie unterscheidet Dienststelle von Heimarbeit und
ist damit ein Anwesenheitsmerkmal — sie beantwortet keine Frage nach dem Recht, sondern eine nach dem
Aufenthalt. Dieselbe Begründung schließt **Geräte- und Browserkennung** sowie **Standortangaben** aus.
Die Netzadresse kann für Sicherheitszwecke ausdrücklich eingeschaltet werden; dann ist die Einschaltung
zu begründen, und das Feld bleibt aus Berichten und Exporten ausgeschlossen. Ob eine C5-Prüfung das Feld
zwingend verlangt, ist offen; sollte das so sein, ist es schriftlich zu begründen.

### Der Ereignisvertrag: Katalog und Schemaversion

„Eng begrenzt auf das rechtlich Erhebliche“ ist für `before`/`after` keine Konvention, sondern ein
geprüfter Vertrag. **Der Ereigniskatalog** (`AuditEventCatalog`) nennt für jede Ereignisart die
Schlüssel, die `before` und `after` tragen dürfen (oberste Ebene, beide Seiten zusammen); wo ein
Ereignis keine Nutzlast trägt, ist der Eintrag leer, ebenso für die Ereignisarten, die heute kein
Schreiber erzeugt. Ein Typ ohne Eintrag lässt den Build scheitern. Schlüssel, die ein Schreiber aus
seinem eigenen Code ableitet, nennt er dem Katalog aus derselben Quelle (`AuditPayloadKeys`): das
Branding je Bildart Typ und Version, der Löschnachweis einer privaten Bibliothek je Halter von
Verweisen, der im Nachweis steht. Ein neuer Enum-Wert oder Halter ist damit gelistet, sobald er
geschrieben wird. Die Positivliste der Selbstauskunft wiederholt nur Schlüssel des Katalogs.

**Geprüft wird beim Schreiben**, in `AuditLogService#record` und damit auf jedem Schreibweg. Ein
Schlüssel, den der Katalog für die Ereignisart nicht nennt, und eine Nutzlast, die kein JSON-Objekt
ist, werden im Betrieb verworfen: Geschrieben werden nur die gelisteten Schlüssel, das Log meldet
die verworfenen Schlüsselnamen (nie ihre Werte) als Fehler, und der Zähler
`opaa.audit.payload.dropped` (Tag `event_type`) steigt; die Warnregel `OpaaAuditPayloadDropped` des
Helm-Charts meldet das. Unter `opaa.audit.payload.reject-unlisted=true`, gesetzt im Profil `dev`
und für jeden Spring-Kontext der Testsuite in `backend/src/test/resources/config/application.yml`,
gleich welche Profile ein Test aktiviert, verweigert OPAA stattdessen das Schreiben, und die Transaktion des Vorgangs bricht ab. Im Betrieb
wäre das Verweigern die schlechtere Wahl: Es rollte den Vorgang zurück, den der Eintrag belegen
soll, wegen eines Programmierfehlers, den der Test schon hätte finden sollen; gespeichert wird die
unzulässige Angabe in keinem der beiden Fälle. Geprüft werden die Schlüssel, nicht die Werte.

**Die Schemaversion** (`schema_version`, `AuditEventCatalog.SCHEMA_VERSION`, derzeit 1) steht in
jedem neuen Eintrag und geht mit in die [SIEM-Ausleitung](#anbindung-an-ein-zentrales-sicherheitsmonitoring).
Einträge von vor dem Katalog tragen keine. Erhöht wird sie, wenn ein gelisteter Schlüssel seine
Bedeutung ändert oder entfällt; ein neuer Schlüssel oder eine neue Ereignisart lässt ältere
Einträge lesbar und erhöht sie nicht. Ein Schnappschuss je Version
(`backend/src/test/resources/audit/event-catalog/v<N>.json`, die abgeleiteten Schlüssel in `v<N>-derived.json`) hält das fest: Entfällt ein
Schlüssel der laufenden Version oder wird er umbenannt, scheitert der Build, bis die Version steigt
und ihr eigener Schnappschuss dazukommt; ein neuer Schlüssel wird im Schnappschuss der laufenden
Version nachgetragen. Bestehende Einträge bleiben, wie sie geschrieben wurden: Das
Protokoll ist nur anfügend, der Katalog wirkt nur auf neue Einträge.

### Die Ereignisse der ersten Stufe

Alles Folgende ist protokollpflichtig. Die Liste ist **geschlossen**: Was hier nicht steht, wird in der
ersten Stufe nicht geschrieben.

**Rechte an Assets**

- Rechtevergabe, Rechteänderung und Rechteentzug an Wissensbibliotheken und weiteren Assets,
  einschließlich Mitfreigaben aus der Freigabekette
- Ablauf einer Befristung, sobald sie wirkt — ein Recht, das ohne Eintrag endet, ist im Nachweis eine
  Lücke wie eines, das ohne Eintrag beginnt
- Die organisationsweite Reichweite eines Assets ist seit
  [ADR-0037](../decisions/0037-reichweite-als-freigabe-an-alle.md) ein Grant an „Alle Konten" und
  wird als solcher protokolliert; ein eigenes Auffindbarkeitsfeld (`listed`) gibt es seit
  [ADR-0039](../decisions/0039-ein-katalog-und-ausdrueckliche-space-zuordnung.md) nicht mehr
- Setzen, Zurücknehmen, Erlöschen und Aussetzen der **Freigabe einer Wissensbibliothek für
  externe KI-Werkzeuge** ([external-access.md](./external-access.md#die-freigabe-der-bibliothek)) — sie ist
  das verbliebene Reichweitenfeld neben den Grants und entscheidet zusätzlich über die Hausgrenze;
  beim Erlöschen mit dem Anlass (Fristablauf, gesenkte Freigabe-Obergrenze)
- Aussetzen von Grants durch eine nachträglich gesenkte Freigabe-Obergrenze

**Spaces, Bibliotheken und Gruppen**

- Anlegen, Ändern und Löschen von Spaces, Wissensbibliotheken und Gruppen
- Anlegen, Ändern und Löschen von **Prompt-Bibliotheken und Prompts** — beim Prompt nur die
  Namen der geänderten Felder, nie Titel oder Text; die **Verwendung** eines Prompts erzeugt keinen
  Eintrag ([spaces-and-assets.md](./spaces-and-assets.md#protokoll))
- Aufnahme, Rollenänderung und Entfernen von Space-Mitgliedern; die Aufnahme **externer** Personen in
  einen Space mit geteilten Inhalten zusätzlich mit der ausdrücklichen Bestätigung im Eintrag
- Aufnahme und Entfernen von Gruppenmitgliedern; Auflösung einer Gruppe
- **Benennung und Entlassung einer verantwortlichen Person einer internen Gruppe** sowie die Abgabe
  der Verantwortung — Protokoll, aber **keine** Rechtehistorie: Verantwortung trägt kein Leserecht
  und ist für „wer konnte am Tag X was lesen" ohne Bedeutung
  ([ADR-0036](../decisions/0036-berechtigungsmodell-gruppen-und-faehigkeiten.md), Entscheidungen 4
  und 8)
- **Freigabe einer internen Gruppe zur Verwendung** und deren Rücknahme — ein Reichweitenfeld an
  der Gruppe: sie entscheidet, wer die Gruppe überhaupt als Empfänger benennen kann
- **Setzen und Lösen des Schutzkennzeichens einer Gruppe** (Personalvertretung,
  Schwerbehindertenvertretung, Gleichstellung, Personalvorgänge) — allein durch die
  Systemverwaltung (ADR-0036, Entscheidung 9)
- Bereitstellung einer Bibliothek in einem Space, dessen Mitglieder nicht sämtlich Lesezugriff haben
- Eigentümerwechsel, Übernahme von Assets ohne Zuständigkeit und der Übergang in „Nachfolge offen"

**Konten, Rollen und Verzeichnisabgleich**

- Erteilung und Entzug der System-Admin-Rolle
- Deaktivierung eines Kontos, erzwungene Neuanmeldung, Ausstellung, Widerruf und
  **Außerkrafttreten** von API-Tokens (mit Anlass: abgelaufen, Kontenlebenszyklus) — ein Zugang, der
  ohne Eintrag endet, ist dieselbe Lücke wie einer, der ohne Eintrag beginnt
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
- **Löschung der Chats gesperrter Konten** ([chat-list.md](./chat-list.md#löschung-der-chats-gesperrter-konten),
  #2997): je Konto und Lauf die Löschung mit Fristbeginn und Sperrgrund, nie mit Zahl, Titel, Inhalt
  oder Space; Anhalten und Fortsetzen der Löschung je Konto durch die Systemverwaltung, ohne Begründung
- **Jede bewirkte** Rechteänderung aus einem Verzeichnisabgleich — je Änderung, nicht je Lauf, verbunden
  über `correlation_ref`; dazu ein Kopfeintrag des Laufs mit Ergebnis und, oberhalb der Schwelle, mit
  der bestätigenden Person und ihrem Anlass. **Das Verwerfen** eines vorgelegten Plans ist ein
  eigenes Ereignis mit derselben Angabe — eine Entscheidung, die keine Spur hinterlässt, ist keine
  Entscheidung
- **Die Auslieferung eines Abgleichsberichts mit Namen an die Systemverwaltung** — Abruf des
  ausstehenden Plans, Probelauf, Lauf von Hand und Bestätigung; ein zusammenfassender Eintrag mit
  Anbieter und der Zahl der genannten Gruppen und Personen, nie mit den Namen
  ([ADR-0036](../decisions/0036-berechtigungsmodell-gruppen-und-faehigkeiten.md), Entscheidung 9,
  Nachtrag vom 02.10.2026)

**Systemeinstellungen**

- Governance-Einstellungen: Aufbewahrungsfristen, Mindestgruppengröße, Aggregation, Statistik
- **Die Protokollkonfiguration selbst** — einschließlich des Einschaltens der Netzadresse. Ohne diesen
  Punkt bleibt eine spätere Abweichung von der Dienstvereinbarung unbemerkt; die Änderung wird
  zusätzlich angezeigt
- Modellvorgaben und die Freigabe externer Modelle
- Die Freigabe-Obergrenze konnektor-gespeister Bibliotheken
- **Die Einstellungen für externe KI-Werkzeuge** ([external-access.md](./external-access.md#der-schalter-der-installation)):
  Ein- und Ausschalten des Kanals, Ablauf-Obergrenze für Zugangstokens, Kontingent je Token,
  Netzbereiche des Kanals und die Schwelle des Abflussalarms. Sie stehen aus demselben Grund hier
  wie die Freigabe-Obergrenze: Sie entscheiden über die Reichweite eines Bestands, nicht über
  Darstellung. Ebenso der Einleitungstext für fremde Werkzeuge, als Prüfsumme und Länge, nie im
  Wortlaut: Er ist eine Einstellung der Verwaltung, und jede Änderung einer Einstellung steht im
  Protokoll
  ([ADR-0060](../decisions/0060-schlanke-konfiguration-und-einrichtungsassistent.md), Entscheidung 11)
- **Mail-Einstellungen** ([ADR-0033](../decisions/0033-lokale-benutzerverwaltung.md)): Änderung der
  SMTP-Einstellungen, auch wenn sich nur das Passwort ändert (nie mit dem Wert, nur „gesetzt“,
  „geändert“ oder „entfernt“), Änderung und Zurücksetzen einer Mail-Vorlage mit Prüfsumme (SHA-256) und
  Länge von Betreff und Text vorher und nachher, nie im Wortlaut, ein ausgelöster Testversand
- **Geheimnisse einer Einstellung** — Modellschlüssel, Passwort des Mailservers, Geheimnis eines
  Verzeichniszugangs oder eines Zugangs — erscheinen in jedem Ereignis nur als „gesetzt“,
  „geändert“ oder „entfernt“, nie mit ihrem Wert
- **Der Import einer Einstellungsdatei**
  ([installation-and-settings.md](./installation-and-settings.md#fachliche-einstellungen-in-der-datenbank)):
  ein Eintrag mit den geänderten Bereichen, unter derselben Vorgangskennung wie jede einzelne
  Änderung des Imports; ein Import ohne Änderung schreibt nichts
- **Die Einstellungen der Selbstauskunft** (Notabschaltung, Tagesgrenze), mit Vorher/Nachher der
  geänderten Schlüssel ([Selbstauskunft](#grenzen-und-betrieb))

**Selbstauskunft**

- Beginn und Ende jedes Exports der [Selbstauskunft](#protokoll-der-selbstauskunft), das Ende mit
  Ergebnis, Größe und Prüfsumme, nie mit Inhalt. Abgewiesene Versuche schreiben nichts

**Zugriff auf die Protokolldaten selbst**

- Jedes Lesen, jede Auswertung und jeder Export von Protokolldaten, einschließlich der **abgelehnten**
  Versuche — siehe [unten](#zugriffswege-was-es-gibt-und-was-es-nicht-gibt)
- **Ausnahme Selbstauskunft:** Sie liest Protokoll, Verbindungsprotokoll und Diagnoseprotokoll über
  die eigene Person, schreibt dafür aber kein `AUDIT_LOG_ACCESSED`; an seiner Stelle stehen die beiden
  Exportereignisse. Ein **abgewiesener** Versuch der Selbstauskunft (Tagesgrenze, Starts je Tag, Obergrenze,
  abgeschaltet, ohne frische Anmeldung, laufender Export) schreibt nichts — abweichend von der Zusage
  für abgelehnte Protokollzugriffe, weil er scheitert, bevor irgendetwas gelesen wird, und ein
  Eintrag je Fehlversuch ein Verhaltensmerkmal ohne Nachweiswert wäre

### Umsetzungsstand der geschlossenen Liste (#392)

Die Liste oben beschreibt das **Zielverhalten**; welche Arten heute tatsächlich einen Eintrag
schreiben, hängt davon ab, ob die zugrunde liegende Funktion im Code schon existiert. Verdrahtet
sind: Rechte an Assets (Vergabe/Änderung/Entzug von Grants — Empfänger „Alle Konten"
eingeschlossen —),
Anlegen/Ändern/Löschen von Bibliotheken, Spaces und Gruppen — seit #1901 auch von Prompt-Bibliotheken
und Prompts (`PROMPT_LIBRARY_CREATED`/`_CHANGED`/`_DELETED`, `PROMPT_CREATED`/`_CHANGED`/`_DELETED`,
Objekte `PROMPT_LIBRARY` und `PROMPT`) —, Aufnahme/Rollenänderung/Entfernen von
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
`MAIL_TEMPLATE_RESET`, `MAIL_TEST_SENT`) und — seit #1563 — Anstoß und Abschluss der Übergabe
eines lokalen Kontos an eine Anbieteridentität (`LOCAL_USER_HANDOVER_REQUESTED` mit
Anbieter-Kennung und Zustellweg, `LOCAL_USER_HANDED_OVER` mit Anbieter-Kennung und den Zahlen
dessen, was mitgegangen ist — nie mit dem Subject der Anbieteridentität; das Widerrufsereignis der
Sitzungen trägt den Grund `HANDED_OVER`) und — seit #1717 — die Einstellungen für
externe KI-Werkzeuge (`EXTERNAL_ACCESS_SETTINGS_CHANGED` mit Vorher/Nachher nur der geänderten Schlüssel:
Schalter, Ablauf-Obergrenze, Kontingent, Netzbereiche, Alarmschwelle und, seit #3193, der
Einleitungstext für fremde Werkzeuge als Prüfsumme und Länge) und — seit #1718 — die persönlichen Zugangstokens
desselben Kanals (`API_TOKEN_ISSUED` mit Bibliotheksauswahl und Ablauf, `API_TOKEN_REVOKED` mit
Anlass und der Angabe, ob die Person selbst oder die Systemverwaltung widerrufen hat, und
`API_TOKEN_EXPIRED` beim Außerkrafttreten, mit dem Anlass `EXPIRED` aus dem täglichen Lauf oder
`ACCOUNT_LIFECYCLE` beim Sperren eines Kontos und bei der abgeschlossenen Übergabe an eine
Anbieteridentität — alle drei mit der
Token-Kennung statt des frei formulierten Tokennamens, und die **Nutzung** eines Tokens erzeugt
keinen Eintrag) und — seit #1833 — die Aufbewahrungshöchstdauer der Rechtehistorie
(`PERMISSION_HISTORY_RETENTION_CHANGED` mit Vorher/Nachher der Monatszahl) und — seit #2163 — die
Aufbewahrungsfrist des Verbindungsprotokolls (`CONNECTION_LOG_RETENTION_CHANGED`, ebenfalls mit
Vorher/Nachher der Monatszahl) und — seit #2496 — der hausweite Ausgangsproxy
(`OUTBOUND_PROXY_CHANGED` mit Vorher/Nachher von Proxy-Adresse und Ausnahmeliste) und — seit #1814 — die
Verantwortlichen interner Gruppen samt Reichweitenfeldern (`GROUP_STEWARD_APPOINTED`,
`GROUP_STEWARD_DISMISSED`, `GROUP_RELEASE_CHANGED` und `GROUP_PROTECTION_CHANGED`, die beiden
letzten mit Vorher/Nachher des Kennzeichens; die betroffene Person steht als Pseudonym im Subjekt,
und keines der vier erzeugt eine Historienzeile) und — ebenfalls seit #1814 — der Abruf der
Mitgliederliste einer Gruppe durch die Systemverwaltung, die diese Gruppe nicht selbst verantwortet
(`GROUP_MEMBERS_READ` mit der Zahl der Mitglieder) und — seit #1991 — jede Auslieferung eines
Abgleichsberichts mit Namen an die Systemverwaltung (`DIRECTORY_SYNC_REPORT_READ` mit Anbieter,
Weg und der Zahl der genannten Gruppen und Personen; ein Bericht ohne Namen schreibt nichts) und —
seit #1834 — die Übertragung der Rechte
eines Subjekts auf ein anderes (`PERMISSION_TRANSFER_EXECUTED` mit Quelle, Ziel, Umfang und Zahl
der Zeilen, und `PERMISSION_TRANSFER_PREVIEWED` für **jeden** Abruf der Vorschau, auch den
abgebrochenen — die Vorschau liest alles, was ein Subjekt hält). Das sind, neben dem Abruf der
Mitgliederliste und des Abgleichsberichts, die einzigen **Leseereignisse der Verwaltung** in dieser
Liste (neben den Protokollzugriffen und dem Export der Selbstauskunft, siehe
[Schnitt](#der-schnitt-der-ersten-stufe)) und die bewusste Ausnahme von „Abfragen werden nicht protokolliert": ADR-0036,
Entscheidung 9, räumt der Verwaltung die volle Mitgliederliste ein — und hält dafür fest, dass sie
sie abgerufen hat. Wer die Gruppe selbst verantwortet, erzeugt beim Lesen nichts; es ist die
eigene Gruppe. Die Anfrage
„Passwort vergessen" ändert keinen Zustand; die Bestätigung der eigenen Adresse ist eine Handlung
der Person am eigenen Konto und nach ADR-0033, Entscheidung 13, kein Verwaltungsakt — beide
erzeugen kein Ereignis. Ebenfalls verdrahtet sind die Freigabe-Obergrenze konnektor-gespeister
Bibliotheken (`CONNECTOR_LIBRARY_SHARE_LIMIT_CHANGED`; eine gesenkte Obergrenze setzt den Grant an
„Alle Konten" nicht aus, sondern entzieht ihn über den gewöhnlichen Weg als `ASSET_GRANT_REVOKED`),
die Freigabe einer Wissensbibliothek für externe KI-Werkzeuge (`ASSET_EXTERNAL_ACCESS_CHANGED`, ihr Erlöschen als
`ASSET_EXTERNAL_ACCESS_EXPIRED`), die Bereitstellung eines Assets in einem Space
(`ASSET_SHARED_TO_SPACE`, `ASSET_DETACHED_FROM_SPACE`) und die Aufbewahrungsfrist des Protokolls als
Teil der Protokollkonfiguration (`AUDIT_LOG_CONFIGURATION_CHANGED`). Noch **nicht** verdrahtet — weil
die jeweilige Funktion selbst noch fehlt, nicht weil sie ausgenommen wäre: Ablauf einer
Grant-Befristung (kein Scheduler; der Ablauf eines Zugangstokens hat mit #1718 einen),
Eigentümerübernahme ohne Zuständigkeit und der Übergang in „Nachfolge offen", Deaktivierung eines
Kontos, erzwungene Neuanmeldung und die übrigen Systemeinstellungen (Governance außer der
Aufbewahrungshöchstdauer der Rechtehistorie, Protokollkonfiguration außer der Frist,
Modellvorgaben) sowie die Ereignisse der Selbstauskunft (#2989). Jede dieser Lücken schließt das jeweilige Folge-Issue, sobald
die zugehörige Funktion existiert — die Liste selbst bleibt geschlossen und ändert sich nicht.

### Was ausdrücklich nicht protokolliert wird

| Nicht protokolliert | Begründung |
|---|---|
| **Abfragen** — Frage, Suchbegriffe (auch die der [Chatsuche](./chat-list.md#chatsuche) über eigene Gespräche), angewandter Suchbereich, Trefferzahl | Das ist Verhalten, nicht Zugriffsänderung. In der Menge ergibt es das Tätigkeitsprofil, das die Mitbestimmung ausschließt. Die Prüfbarkeit hängt nicht daran: Die Negativfrage beantwortet die Rechtehistorie |
| **Antwortinhalte, Zitate, Modellaufrufe** | dasselbe, zusätzlich mit Inhalten aus dem Fachverfahren |
| **Erfolgreiche Anmeldungen und Sitzungsverläufe** | reines Anwesenheitsmerkmal, ohne Aussage über Rechte |
| **Fehlgeschlagene Anmeldungen und abgewiesene Verbindungsversuche** | Sicherheitsereignisse, die in das zentrale Sicherheitsmonitoring gehören und nicht in das Nachweisprotokoll. Sie bleiben im Anwendungslog und erreichen das Sicherheitsmonitoring über die Log-Sammlung der Behörde, nicht über die [SIEM-Anbindung](#anbindung-an-ein-zentrales-sicherheitsmonitoring) des Protokolls. Das gilt unverändert für die lokale Anmeldung ([ADR-0033](../decisions/0033-lokale-benutzerverwaltung.md)): Der einzelne Fehlversuch steht nur im technischen Anwendungslog (kurze Frist, keine Auswertungsoberfläche, Konto-Kennung statt Adresse); in das Nachweisprotokoll gelangt allein die daraus folgende **Kontosperre** als Zustandsänderung. Der Fehlversuchszähler eines Kontos wird weder ausgegeben noch historisiert und geht bei jeder erfolgreichen Anmeldung, jedem Zurücksetzen und jeder Entsperrung auf null |
| **Der Schwellenwertalarm des Zugriffswegs für externe KI-Werkzeuge** | Dasselbe Muster wie eine Zeile höher: Er ist ein Sicherheitsereignis, keine Zugriffsänderung. Die Überschreitung erzeugt eine Meldung an die Systemverwaltung und einen Eintrag im technischen Anwendungslog (kurze Frist, keine Auswertungsoberfläche); der Zähler lebt nur im Arbeitsspeicher und wird nicht historisiert. In das Nachweisprotokoll gelangt allein die daraus folgende **Sperre des Tokens** als Zustandsänderung. Einzelheiten: [external-access.md](./external-access.md#kontingente-und-der-abflussalarm) |
| **Lesezugriffe auf Dokumente und Chats** | Verhalten; wer worauf zugreifen **durfte**, belegt die Rechtehistorie. Der Export der Selbstauskunft ist kein Lesezugriff in diesem Sinn: Er wird als Ganzes festgehalten, nie je Chat oder Dokument |
| **Favoriten an Assets** — Setzen, Entfernen, Bestand | Persönliche Ordnung, keine Zugriffsänderung; in der Menge ein Interessenprofil. Favoriten werden weder protokolliert noch historisiert, nicht gezählt und sind für niemanden außer der Person selbst abrufbar; exportiert werden sie nur in ihrer Selbstauskunft, und als personenbeziehbares Feld gehören sie in die Auskunft über die Datenerhebung ([ADR-0039](../decisions/0039-ein-katalog-und-ausdrueckliche-space-zuordnung.md), Entscheidung 7) |

**Spätere Stufen** — nicht verworfen, nur nicht hier: das Teilen und Zurückziehen von Chats und
Artefakten (es ändert Reichweite und gehört dazu, sobald die Funktion existiert), Agentenaktionen mit
aufrufender Person, Agentenversion und Freigabeschritt.

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

**Die Prüfsummenkette macht eine Änderung am Bestand erkennbar.** Jeder Protokollmonat trägt eine
Kette von Prüfsummen über alle seine Sätze in der Reihenfolge ihres Eintrags, nachgezogen alle zehn
Minuten von einem Lauf außerhalb des Schreibwegs, mit Kontrollpunkten je Stapel. Ist ein Monat zu
Ende und vollständig verkettet, wird er **versiegelt**: Die Kopf-Prüfsumme steht mit der Anzahl der
Sätze als eigener Protokolleintrag `AUDIT_LOG_MONTH_SEALED` in einem späteren Monat, wo sie selbst
verkettet wird, und geht über die [Ausleitung](#anbindung-an-ein-zentrales-sicherheitsmonitoring) mit der
Prüfsumme als eigenem Feld an das Sicherheitsmonitoring. Kontrollpunkte und Siegel liegen in
Tabellen, die wie die Ablage selbst dem Wartungskonto gehören und von der Anwendung nur angefügt
und gelesen werden. Die Revision prüft einen Monat über einen eigenen Abfrageweg (`GET
/api/v1/audit/chain/<JJJJ-MM>`, Anlass pflichtig, selbst protokolliert): Die Antwort sagt, ob die
Kette zu Kontrollpunkten und Siegel passt, und nennt bei einem Bruch die erste Abweichung mit
Position, Art (geändert oder eingefügt/entfernt) und dem Zeitsegment zwischen den Kontrollpunkten,
ohne einen Inhalt herauszugeben
([ADR-0059](../decisions/0059-ereignisvertrag-aufbewahrung-und-integritaet-des-revisionsprotokolls.md)).

Die Grenze gehört weiter ausgesprochen:

> **Wer die Datenbank beherrscht, kann Sätze ändern und die Kette neu rechnen.** Erkennbar bleibt das
> an dem Siegel, das die Datenbank verlassen hat, im Sicherheitsmonitoring oder in einem anderen
> System, das die Siegel-Einträge aufbewahrt. Ohne einen solchen zweiten Ort belegt die Kette nur,
> dass seit dem letzten Kontrollpunkt nichts geändert wurde.

Der Schutz stützt sich damit auf drei Dinge: darauf, dass die **Anwendung** selbst kein
Änderungsrecht hat — das ist der häufige und der wahrscheinliche Fall —, auf die **Kette mit dem
Siegel außerhalb der Datenbank**, und darauf, dass der **direkte Datenbankzugang betrieblich
beschränkt** ist. Dieser dritte Teil ist keine Produkteigenschaft: Er wird außerhalb von OPAA durch
den Betreiber geregelt und nachgewiesen, über die Vergabe der Datenbankrechte, das
Vier-Augen-Prinzip beim administrativen Zugang und die Protokollierung der Datenbank selbst. Die
[Verantwortungsmatrix](#zwei-wege--die-behörde-wählt) führt diesen Punkt auf der Betreiberseite.

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
| **Kettenprüfung** | „ist der März 2027 seit seinem Abschluss unverändert?" | liefert keine Einträge und keinen Personenbezug, nur Status, Zahlen und Prüfsumme der [Prüfsummenkette](#der-sicherheitsgrad-der-ersten-stufe-einfaches-anfügen) eines Monats; weil die Kette alle Organisationen umfasst, nur für die Revision der Standardorganisation |

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
- **kein Vollabzug ohne Objekt-, Zeitraum- oder Ereignisbezug**, auch nicht als Export — einzige
  Ausnahme die Selbstauskunft, nur über die eigene Person (siehe unten)
- **keine Sicht „alle Ereignisse, bei denen Person X betroffen war"** über das Protokoll. Der berechtigte
  Bedarf dahinter — welche Rechte hatte X wann — wird über die
  [Rechtehistorie](#nachweisbarkeit-historisierung-von-rechten) beantwortet, die den Rechtestand
  abbildet und nicht das Handeln einzelner Beschäftigter

**Die eine Ausnahme für Dritte, und sie ist eng:** Der Personenfilter existiert genau einmal, im freigegebenen
Vorgang der [anlassbezogenen Klärung](#2-einen-personenbezogenen-auswertungspfad-gibt-es-nicht) — im
Vier-Augen-Prinzip unter Beteiligung der Personalvertretung, mit vorab festgelegter Person, vorab
festgelegtem Zeitraum und dokumentiertem Zweck, die die Abfrage **technisch** begrenzen. Ohne
freigegebenen Vorgang ist er nicht aufrufbar; mit ihm reicht er nicht weiter als die Freigabe. Er
findet nur die Einträge, in denen die Person handelt (`actor_ref`); `before`/`after` durchsucht er
nicht, auch nicht nach einer Besitzerin oder dem Ziel einer Übertragung: eine Suche in
`before`/`after` wäre ein weiterer Personeneinstieg der Klärung (Z2). Das tut allein die
Selbstauskunft. Der
Zugriff erzeugt seinen eigenen Eintrag, die betroffene Person wird unterrichtet, und die Zahl der Fälle
geht in den Bericht an die Personalvertretung, für einen wählbaren Berichtszeitraum aus der
[Auskunft über die Datenerhebung](#auskunft-über-die-datenerhebung).

**Für die Person selbst gibt es genau eine Ausnahme, nur über sie: die
[Selbstauskunft](#selbstauskunft-stufe-1)**; für Dritte bleibt allein die anlassbezogene Klärung. Sie liest die Protokolleinträge, in denen die angemeldete Person handelnd, betroffen, als
Kontoobjekt, als Besitzerin oder als Ziel einer Rechteübertragung steht, ohne Zeitgrenze und ohne Seitengrenze — ein Personeneinstieg und ein Vollabzug,
beides sonst ausgeschlossen. Die Person ist dabei **kein Parameter**, sondern allein die Identität
der Anfrage: Es gibt keinen Weg, die Auskunft für eine andere Person, „im Auftrag“, durch die
Verwaltung, die Revision oder die Datenschutzstelle auszulösen. Die Pseudonymkennung verlässt OPAA
auch dabei nicht; die Einträge nennen die Person als „Sie“. Ein Index auf `subject_ref` entsteht
dafür nicht ([Protokoll der Selbstauskunft](#protokoll-der-selbstauskunft)).

**Stand #393, technisch bereits umgesetzt:** zwei verschiedene Personen (Vier-Augen-Prinzip, auch als
Datenbank-Constraint, nicht nur anwendungsseitig), vorab festgelegte Person/Zeitraum/Zweck, technische
Begrenzung der Abfrage auf genau diese Grenzen, und eine Befristung der Freigabe (30 Tage ab Freigabe;
danach ist eine erneute Freigabe mit erneutem Vier-Augen-Prinzip nötig, nicht eine Verlängerung
derselben). **Noch nicht technisch durchgesetzt, sondern organisatorisch abzugrenzen:** dass die zwei
freigebenden Personen tatsächlich unterschiedliche Rollen im Sinne der Mitbestimmung besetzen (heute
genügen zwei beliebige AUDITOR-Konten — die **Beteiligung der Personalvertretung** an der Freigabe
selbst ist ein organisatorischer Prozessschritt, kein technisches Gate), die **Unterrichtung der
betroffenen Person** nach Abschluss und der Bericht an die Personalvertretung als Dokument. Diese
drei bleiben Dienstvereinbarung/Prozess, bis ein eigenes Ticket sie technisch abbildet. Technisch
geliefert werden die Zahlen für den Bericht: Die [Auskunft über die
Datenerhebung](#auskunft-über-die-datenerhebung) nennt je Anlass, wie viele Klärungen in einem
wählbaren Berichtszeitraum beantragt, freigegeben und abgelaufen sind, dazu die Zahl der
angehaltenen Chat-Löschungen.

**Aufbewahrung der Freigaben (#2859).** Antrag und Freigabe sind nach dem Ende der Nutzbarkeit nur
noch Nachweis und haben eine Löschfrist: Gelöscht wird am Ende des Kalenderjahres, das auf das Ende
der Nutzbarkeit folgt (Vorbild § 76 Abs. 4 BDSG), freigegebene und nie freigegebene gleich; für die
Löschfrist zählt bei einem nie freigegebenen Antrag als Ende der Nutzbarkeit der Antragszeitpunkt
plus die 30 Tage (freigeben lässt er sich unverändert auch danach). Die
Frist stellt die Systemverwaltung unter Administration → Datenschutz ein (1 bis 10 Jahre,
Werkseinstellung ein Jahr, `INCIDENT_SCOPE_RETENTION_CHANGED` mit Vorher und Nachher), damit eine
Dienstvereinbarung sie verlängern kann; gelöscht wird in einem täglichen Lauf ohne eigenen
Protokolleintrag, wie bei den übrigen Löschläufen. **Die Zahl der Fälle für den Bericht an die
Personalvertretung bleibt erhalten:** Jeder Antrag wird bei seiner Stellung, jede Freigabe bei ihrer Erteilung und das Ende
ihrer Nutzbarkeit je Kalendermonat und Zweck gezählt (#2895), in einer Zähltabelle ohne Person und
ohne Verweis auf den Antrag. Die [Auskunft über die
Datenerhebung](#export-und-auskunft) gibt diese Zahlen für einen Berichtszeitraum aus.

**Eine laufende Klärung hält die Konten, eine abgeschlossene nicht.** Solange ein Antrag offen ist
oder eine Freigabe noch nutzbar, lehnt OPAA die Löschung der Konten der betroffenen, der
beantragenden und der freigebenden Person ab (Legal Hold); die Klärung soll nicht dadurch enden,
dass ein beteiligtes Konto verschwindet. Danach hält sie kein Konto mehr: Wird eines davon gelöscht,
bleibt die Freigabe bestehen und nennt die Person unter ihrem Pseudonym aus dem Protokoll — hatte die
Person keine Protokolleinträge, unter einem eigenen. Damit bleibt der Nachweis bis zum Fristende
lesbar und zum Protokoll stimmig, ohne Klarnamen über die Löschung hinaus zu halten.

**Das Pseudonym bleibt bewusst zuordenbar (Entscheidung des Maintainers, #2859).** Solange eine
Freigabe die gelöschte Person nennt, bleibt dort ihr Protokollpseudonym bis zur Löschfrist der
Freigabe. Bis dahin lassen sich die Protokolleinträge der gelöschten Person über die Freigabe — mit
Begründung und Zeitraum — wieder ihr zuordnen; das ist die Ausnahme zur
[Löschung](#unveränderlichkeit-und-löschrecht), nach der das Protokoll sonst nicht mehr auf die
Person zurückführbar ist. OPAA nutzt das Pseudonym nicht als Filter: Nach der Löschung liefert die
Klärung zur betroffenen Person keine Einträge mehr. Ein offener Antrag, dessen betroffene oder
beantragende Person an der Anwendung vorbei gelöscht wurde, kann nicht mehr freigegeben werden.

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
  nicht entfernen kann. **Ein Lesen ohne Verwaltungsakt gibt es dabei nicht:** Die Systemverwaltung
  erreicht den Inhalt eines Dokuments — Original wie gespeicherten Chunk in der Suchdiagnose — nur
  über einen Grant, und der Abruf ohne Grant wird abgewiesen (#1828,
  [Verwalten ist nicht Lesen](./access-control.md#verwalten-ist-nicht-lesen-die-asymmetrie-bei-wissensbibliotheken)).
  Deshalb braucht es hier auch keine Ereignisart für das Lesen eines Dokuments: Protokollpflichtig ist
  das Recht — seine Vergabe an sich selbst ebenso wie die Übernahme —, und beides steht bereits in der
  [geschlossenen Liste der ersten Stufe](#die-ereignisse-der-ersten-stufe).
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
- Eine **Verkürzung** der Frist wirkt mit dem nächsten Monatslauf und ist selbst protokollpflichtig;
  sie darf nicht das Werkzeug sein, mit dem ein unbequemer Zeitraum verschwindet. Was diesen Satz
  technisch trägt, ist nicht eine Bremse an der Löschung, sondern die Nachvollziehbarkeit der
  Anordnung: Die Friständerung ist ein Verwaltungsakt, der mit altem und neuem Wert im Protokoll
  steht, gelöscht wird erst mit dem nächsten Monatslauf, und das Anwendungskonto kann den
  erreichten Löschstand (`last_cutoff`) nicht selbst schreiben. Was bereits gelöscht ist, holt eine
  spätere **Verlängerung** nicht zurück.

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

**Eine Verkürzung wirkt mit dem nächsten Monatslauf, und zwar vollständig** (#1851, Changeset 078).
Was ein Lauf entfernt, folgt allein aus der eingestellten Frist: Wer von drei Jahren auf ein Jahr
geht, hat nach dem nächsten Lauf keine Partition mehr, die älter als ein Jahr ist. Die ursprüngliche
Fassung deckelte den Fortschritt auf höchstens einen Kalendermonat je vergangenem Kalendermonat —
ein Deckel, der nie aufschließt, weil der Schnitt der eingestellten Frist selbst um einen Monat je
Monat weiterwandert. Eine Verkürzung wäre damit nie wirksam geworden; sie ist ersatzlos entfallen.

Die Funktion merkt sich in `last_cutoff` weiterhin, bis zu welchem Zeitpunkt sie zuletzt gelöscht
hat — jetzt als **Hochwassermarke**, die nie zurückgeht. Eine **Verlängerung** der Frist schützt
deshalb sofort und nimmt nichts zurück: Der Lauf löscht dann nichts, und die Marke bleibt stehen, wo
ein früherer Lauf schon war — sie ist die Grenze dessen, wofür das Protokoll noch antwortet. Das
Anwendungskonto kann diesen Fortschritt nicht manipulieren, da es auf die dafür genutzten Spalten
keinen Schreibzugriff hat. Jede Friständerung erzeugt über `AuditEventRecorder` einen eigenen Eintrag vom Typ
`AUDIT_LOG_CONFIGURATION_CHANGED` (kein neuer Ereignistyp nötig — dieser deckt „die
Protokollkonfiguration selbst" bereits seit #391 ab).

Die Warnung bei einer Protokollfrist unterhalb der Inhaltsaufbewahrung liefert aktuell immer `false`:
Eine konfigurierbare Inhaltsaufbewahrung existiert nicht (der dafür vorgesehene Umfang, #216, wurde
als nicht geplant geschlossen). Der zuvor als Erweiterungspunkt vorbereitete `ContentRetentionProvider`
wurde mit ihm entfernt (#817) und müsste bei Wiederaufnahme des Umfangs neu eingeführt werden.

Die **automatische Chat-Bereinigung je Space** (#1923, [chat-list.md](./chat-list.md)) ändert daran
nichts, und das ist geprüft: Sie ist keine installationsweite Inhaltsaufbewahrung, sondern eine
Höchstdauer, die nur in Spaces mit eingeschaltetem Schalter und nicht für angeheftete Chats gilt.
Ohne sie bleiben Chats unbegrenzt erhalten, eine Aufbewahrungsdauer, mit der die Protokollfrist
verglichen werden könnte, gibt es also weiterhin nicht. Die Bereinigung verkürzt außerdem nur die
Lebensdauer von Inhalten und kann ein Protokoll deshalb nicht überdauern lassen. Das Löschen eines
Chats erzeugt keinen Protokolleintrag, sodass keine Eintragsart ohne ihren Inhalt zurückbleiben
kann. Protokolliert wird allein das Umlegen des Schalters, mit altem und neuem Wert, als
`SPACE_CHANGED`. Mit dem Teilen von Chats oder einer allgemeinen Chat-Aufbewahrung ist die Warnung
neu zu bewerten.

Die **Löschung der Chats gesperrter Konten** (#2997,
[chat-list.md](./chat-list.md#löschung-der-chats-gesperrter-konten)) ist ebenfalls keine allgemeine
Inhaltsaufbewahrung: Sie greift nur nach einer Sperre durch die Verwaltung. Anders als die
Bereinigung je Space schreibt sie je Konto und Lauf ein Ereignis, weil die Löschung ohne Handeln
der Person geschieht und belegbar sein muss; es nennt weder Zahl noch Inhalt und folgt der
allgemeinen Protokollfrist.

Die Löschung eines Kontos entfernt bereits seit #391 automatisch die Pseudonymzuordnung
(`fk_audit_actor_pseudonyms_user_organization`, `ON DELETE CASCADE`, zusammengesetzt seit Migration 047) und lässt das Protokoll selbst unverändert — das
war keine neue Arbeit für #395, nur ein zusätzlicher Nachweis auf Anwendungsebene (siehe
`AuditActorPseudonymServiceIntegrationTest`, zusätzlich zum bereits bestehenden Nachweis auf
Datenbankebene in `Migration017AuditLogTest`).

Die **Ereignisse der Selbstauskunft** haben keine eigene Frist: Sie folgen der allgemeinen
Protokollfrist (Voreinstellung 3 Jahre, 1 bis 10 Jahre). Eine eigene, kürzere Frist wäre eine
zweite Löschmechanik neben der Monatspartition, die die Ablage nicht kennt; eine längere hätte
keinen Zweck, den die allgemeine Frist nicht schon trägt.

### Der Auszug für die Personalvertretung

Der Auszug ist kein Protokollbericht, sondern die **Beschreibung des Protokolls**. Er ist ohne Anlass,
jederzeit und vollständig exportierbar — vor dem Rollout einmal als Grundlage der Dienstvereinbarung,
danach jährlich als Nachweis, dass sich nichts stillschweigend verschoben hat.

**Er enthält:**

- die vollständige Liste der Ereignisarten mit Zweck je Art und den Angaben, die `before` und `after`
  laut Ereigniskatalog tragen dürfen
- die vollständige Feldliste des Protokollsatzes, je Feld mit Granularität, Zweck und Frist
- die geltenden Aufbewahrungsfristen und den Zeitpunkt ihrer letzten Änderung
- die Liste der vorhandenen Abfragewege **und** die Liste der ausdrücklich nicht vorhandenen, jeweils mit
  dem Ergebnis der automatisierten Prüfung darauf (siehe [Erfolgs-Metriken](#erfolgs-metriken))
- ob die Netzadresse eingeschaltet ist, seit wann und mit welcher Begründung
- Zahl und Anlasskategorien der anlassbezogenen Klärungen im Berichtszeitraum
- das **Ereignis der Löschung von Chats gesperrter Konten** ausdrücklich: Zweck (allein der Nachweis
  der fristgerechten Löschung), Zugriff (`AUDITOR` über dieselben Wege wie jedes Ereignis, keine
  Zählung je Person; die Zahl gelöschter Chats steht nur gesamt im Anwendungslog) und Frist
  (allgemeine Protokollfrist) — siehe [chat-list.md](./chat-list.md#löschung-der-chats-gesperrter-konten)
- die **Ereignisse der Selbstauskunft** ausdrücklich, weil sie zeigen, wer sein Auskunftsrecht
  wahrnimmt: Zweck (Nachweis der Erfüllung des Auskunftsrechts und Erkennung missbräuchlicher
  Abrufe), Zugriff (`AUDITOR` über die Wege nach Zeitraum, Ereignisart und Vorgang; die
  anlassbezogene Klärung im Rahmen ihrer Freigabe, weil die Ereignisse die Person als handelnde
  tragen; die Person selbst in ihrer nächsten Selbstauskunft; nach Objekt nicht, weil das Objekt ein
  Konto ist; keine Zählung je Person) und Frist (allgemeine Protokollfrist) — siehe [Protokoll der Selbstauskunft](#protokoll-der-selbstauskunft)

**Er enthält nicht:** keine Protokollsätze, keine Namen, keine Pseudonymkennungen, keine Zahlen je Person
oder je Organisationseinheit und keine Objektlisten. Ein Auszug, der belegen soll, dass es keine
personenbezogene Auswertung gibt, darf nicht selbst eine sein.

**Quelle und Versionsbindung (Zielbild):** Ereignisarten, Feldliste und Fristen des Auszugs kommen
aus dem Fakten-Manifest, das ein CI-Wächter gegen den Code vollständig hält; jede Änderung daran
erscheint im Update-Paket mit Stufenvorschlag, und die Muster-Dienstvereinbarung legt den Auszug als
statische Anlage auf eine Version fest — siehe [Behördenunterlagen und
Update-Paket](./behoerdenunterlagen.md#das-faktenmodell).

### Unveränderlichkeit und Löschrecht

Ein nur anfügendes Protokoll und ein nachträgliches Schwärzen schließen einander aus. Der Widerspruch wird
zugunsten der Unveränderlichkeit aufgelöst:

**Der Personenbezug wird ab dem Schreibzeitpunkt pseudonymisiert.** Das Protokoll enthält eine Kennung,
die Zuordnung zur Person liegt in einer getrennt gehaltenen Tabelle. Beim Löschen eines Kontos entfällt
dieser Eintrag — das Protokoll bleibt unverändert und ist danach nicht mehr auf eine Person zurückführbar.
Es wird nichts nachträglich verändert und nichts überschrieben. Eine Ausnahme ist befristet: Nennt
eine Freigabe der anlassbezogenen Klärung die Person, bleibt dort ihr Protokollpseudonym bis zur
Löschfrist der Freigabe, und über die Freigabe (mit Begründung und Zeitraum) lassen sich ihre
Protokolleinträge bis dahin wieder ihr zuordnen (siehe
[Zugriffswege](#zugriffswege-was-es-gibt-und-was-es-nicht-gibt)).

### Anbindung an ein zentrales Sicherheitsmonitoring

Behörden betreiben ihr Sicherheitsmonitoring in aller Regel zentral, oft für viele Fachverfahren
zusammen. OPAA liefert sein Revisionsprotokoll deshalb in einem gängigen Format an ein solches System
aus — Rechteänderungen, Verwaltungsaktionen, Zugriffe auf Protokolldaten.

**Der Export ist keine Umgehung.** Was hinausgeht, unterliegt denselben Zweck-, Zugriffs- und
Sparsamkeitsregeln wie das Protokoll selbst. Insbesondere führt der Weg über das Sicherheitsmonitoring
nicht an der Festlegung vorbei, dass es keinen personenbezogenen Auswertungspfad gibt: Was OPAA nicht
nach Person gruppiert, liefert es auch nicht so aus. Welche Ereignisklassen ausgeleitet werden, ist Teil
der dokumentierten Zweckbindung und damit Gegenstand der Dienstvereinbarung.

Gebaut ist die Ausleitung des Revisionsprotokolls als Syslog mit CEF, ohne Pseudonyme und als
Vorgabe ohne Objektnamen, ab dem Einschalten und ohne stillen Verlust, mit der Schemaversion jedes
Eintrags ([ADR-0055](../decisions/0055-ausleitung-an-ein-sicherheitsmonitoring.md)). Die eine
Nutzlast, die hinausgeht, ist das Monatssiegel der
[Prüfsummenkette](#der-sicherheitsgrad-der-ersten-stufe-einfaches-anfügen): Monat, Anzahl und
Kopf-Prüfsumme als eigene Felder der Meldung `AUDIT_LOG_MONTH_SEALED`, damit das Siegel außerhalb
der Datenbank liegt.
Fehlgeschlagene Anmeldungen und abgewiesene Verbindungsversuche bleiben im Anwendungslog und
erreichen das Sicherheitsmonitoring über die Log-Sammlung der Behörde.

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

Deshalb werden **alle Quellen historisiert**: Grants — Empfänger Person, Gruppe **und „Alle
Beschäftigten"** —, Gruppenmitgliedschaften und das verbleibende Reichweitenfeld am Asset (die
Freigabe einer Wissensbibliothek für externe KI-Werkzeuge, siehe
[external-access.md](./external-access.md#die-freigabe-der-bibliothek); `listed` entfällt mit
[ADR-0039](../decisions/0039-ein-katalog-und-ausdrueckliche-space-zuordnung.md) samt seiner
Intervalle). Die Space-Zuordnung wird nicht historisiert: Sie gewährt keinen Zugriff. Zu jedem Zeitpunkt ist
rekonstruierbar, wer welche Rechte hatte, seit wann und aufgrund welchen Vorgangs.

Eine Bibliothek, die vom 1. bis zum 10. März dem ganzen Haus offenstand, verschaffte in dieser Zeit
Zugriff — und das muss für den 3. März belegbar sein, auch wenn die Freigabe längst zurückgenommen ist.
Seit [ADR-0037](../decisions/0037-reichweite-als-freigabe-an-alle.md) ist das ein gewöhnliches
Grant-Intervall wie jedes andere; zuvor war es ein eigenes Feld mit eigener Historie. Der Umstieg hat
**jedes bestehende organisationsweite Intervall in ein Grant-Intervall überführt** (Migration 085): Wäre
nur der lebende Zustand übernommen worden, fiele die Antwort für abgeschlossene Zeiträume falsch aus,
und zwar in die gefährliche Richtung.

**Die Aufbewahrung der Historie folgt derselben Logik wie das Protokoll: Sie unterliegt einer
Höchstdauer mit erzwungenen Grenzen.** Der **Personenbezug** folgt dieser Logik dagegen **nicht**:
Die Subjekt- und Akteursspalten der Historientabellen verweisen mit `RESTRICT` auf `users` (die
Kontozustandshistorie ausgenommen, siehe „Der Lesepfad" unten) und sind
**nicht** ab dem Schreibzeitpunkt pseudonymisiert
([ADR-0036](../decisions/0036-berechtigungsmodell-gruppen-und-faehigkeiten.md), Entscheidung 8).
Begrenzt wird der Auswertungspfad stattdessen an der Leseseite — Höchstdauer, Vier-Augen-Vollmacht
für den Personen-Einstieg, Abrufprotokoll, Zeitfenster mit Seitenobergrenze und die Ein-Objekt-Regel
(siehe „Der Lesepfad" unten) —, und die Pseudonymisierung ist an den Personen-Einstieg und die
Kontolöschung gekoppelt (#391/#395). Praktische Folge, die dieses Kapitel ausspricht: Solange die
Pseudonymisierung fehlt, **hält eine Historienzeile die Löschung eines Kontos auf** (die
Löschschuld, die ADR-0036 unter „Konsequenzen“ benennt; Autorität ist `UserDeletionBlockerCoverageIntegrationTest`), und für ausgeschiedene Personen bleibt innerhalb der
Höchstdauer alles belegbar — was Prüfungen gerade brauchen. Die Löschung des **Objekts**, auf das
sich eine Zeile bezieht, lässt die Historie unverändert bestehen ([ADR-0016](../decisions/0016-loeschschicksal-rechtehistorie.md)).

**Stand #1833, technisch umgesetzt — die Höchstdauer, nicht die Pseudonymisierung.** Die Höchstdauer
ist eine einzige, systemweite Einstellung (`permission_history_retention_settings`) mit denselben
erzwungenen Grenzen wie die Protokollfrist: **12 bis 120 Monate**, als `CHECK` in der Datenbank und
nicht nur in der Validierung, **ausgeliefert mit 36 Monaten** — dieselbe Zahl und dieselbe
Begründung wie beim Protokoll. Ein monatlicher Lauf entfernt **abgeschlossene** Historienintervalle,
deren Ende älter ist als die Frist; **offene Intervalle bleiben unberührt**, weil sie ein gerade
geltendes Recht beschreiben und ihr Beginn beliebig weit zurückliegen darf. Für jeden Stichtag
innerhalb der Frist bleibt die Rekonstruktion damit lückenlos. Eine **Verkürzung wirkt auch hier mit
dem nächsten Monatslauf**, und zwar vollständig; eine Verlängerung wirkt sofort und nimmt nichts
zurück. Jede Änderung ist ein Governance-Ereignis (`PERMISSION_HISTORY_RETENTION_CHANGED`).

### Der Lesepfad: Stichtagsauskunft (#1822)

Bis #1822 war die Rekonstruktion gebaut, aber ohne Endpunkt: Die Prüferfrage war nur per SQL
beantwortbar. Sie ist jetzt eine Funktion der Rolle `AUDITOR`
(`GET /api/v1/audit/access-as-of`) — mit dem **Objekt-Einstieg** und den Schutzregeln aus
[ADR-0036](../decisions/0036-berechtigungsmodell-gruppen-und-faehigkeiten.md), Entscheidung 8:

- **Genau ein benanntes Objekt je Abfrage** (`objectType` plus `objectId`; eine Wissensbibliothek
  beantwortet „wer durfte lesen", ein Space „wer war Mitglied"). Keine Sammelabfrage über einen
  Space, eine Organisationseinheit oder einen Bibliotheksfilter — sonst setzte ein `AUDITOR` aus
  dreißig Objektabfragen desselben Referats das Rechteprofil jeder Person dieses Referats zusammen,
  ohne Vollmacht.
- **Pflichtzeitfenster und Seitenobergrenze**, dieselben wie bei jedem anderen Revisionsweg: 92
  Tage, Seite 49, 200 Zeilen je Seite. Eine zu weite Anfrage wird **abgelehnt, nicht
  zurechtgestutzt**.
- **Pflichtanlass**, und **jeder Abruf ist selbst ein Protokollereignis** —
  `PERMISSION_HISTORY_ACCESSED`, der abgewiesene Versuch eingeschlossen (`outcome = DENIED`).
  Anders als beim Selbstprotokoll des Protokollzugriffs ist das Objekt des Eintrags **die abgefragte
  Bibliothek oder der abgefragte Space**, damit der Abruf über den Objektweg wiederfindbar ist.
- **Die Antwort nennt die Aufbewahrungsgrenze mit** (`retentionCutoff`, `beyondRetention`): Vor ihr
  sind die abgeschlossenen Intervalle gelöscht, eine leere Antwort dort heißt „nicht mehr
  nachgewiesen" und nicht „kein Zugriff". Ohne diese Unterscheidung läse sich eine Lücke als
  Freispruch.
- **Und sie nennt die Quellenlücke** (`sourcesNotCovered`). [ADR-0036](../decisions/0036-berechtigungsmodell-gruppen-und-faehigkeiten.md),
  Entscheidung 8 führt die Quellen; in die Auskunft gehen Asset-Grants, Gruppenmitgliedschaft,
  Reichweite, Space-Mitgliedschaft, Eigentum und Kontozustand ein. **Nicht** nachgewiesen ist die
  **Systemrolle** — `LibraryAccessService#effectiveRole` gibt einer Systemverwaltung auf jeder
  Bibliothek ihrer Organisation `OWNER`, und kein Intervall hält das fest — und, für ein Fenster vor
  der Einführung der vollständigen Kontozustandshistorie, der **Kontozustand** (siehe unten). Die
  Auskunft schreibt deshalb nie „niemand hatte Zugriff", sondern nennt die Quellen, über die sie
  nichts oder nicht alles sagen kann; die Ansicht zeigt sie mit. Eine **Fähigkeit** ist keine Quelle dieser Frage: Sie öffnet einen
  Anlegepfad, nie einen Inhalt (ADR-0036, Entscheidung 5), und erscheint deshalb weder als
  Zugangsgrundlage noch als Lücke.
- **Eigentum.** Wer einen Space verantwortet, hat dort mindestens die Rolle `ADMIN`, mit oder ohne
  eigene Mitgliedschaft (`SpaceAccessPolicy#effectiveRole`); die Auskunft nennt jede Eigentümerin
  aus `asset_ownership_history` mit Grundlage `OWNERSHIP` für genau die Zeit ihres Eigentums, über
  eine Übertragung hinweg. Ein persönlicher Space entsteht ohne Eigentumsintervall; seine Eigentümerin
  lässt sich weder ändern noch entfernen, solange er besteht, und erscheint deshalb ab seiner Anlage
  aus dem Datensatz des Space. An einer Bibliothek begründet Eigentum dagegen keinen eigenen Lesezugang:
  Gelesen wird nach der Formel (`AssetAuthorization#requireContentRole`), und das Eigentum bringt
  seine Berechtigung mit — `OWNER` für eine Person, `MANAGER` für eine Eigentümergruppe. Diese
  Grants stehen in `asset_grant_history`; Eigentümerin und Mitglieder der Eigentümergruppe
  erscheinen über sie, solange sie gelten.
- **Kontozustand.** Ein Zeitraum, in dem ein Konto laut `account_state_history` nicht nutzbar war,
  zählt nicht als Zugang: Eine Sperre im Fenster teilt einen Zugriffszeitraum, eine Sperre über das
  ganze Fenster nimmt die Person aus der Antwort, gleich über welchen Weg sie erreicht wurde. Die
  Freigabe an „Alle Konten" nennt niemanden und wird nicht gekürzt. Historisiert ist jeder Zustand,
  in dem `AccountUsability` ein Konto nicht nutzbar nennt, mit eigener Ursache
  (`AccountStateHistoryCause`): Sperre aus dem Verzeichnisabgleich, Sperre eines lokalen Kontos
  durch die Verwaltung oder wegen Inaktivität, Ablauf, offene Einladung oder unbestätigte Adresse,
  abgeschaltete lokale Kontenverwaltung für Konten ohne Systemverwaltung, abgeschalteter und
  gelöschter Anmeldeanbieter — für einen Anbieter alle seine Konten. Wieder nutzbar wird ein Konto
  mit `DIRECTORY_UNLOCKED` oder `ACCOUNT_REACTIVATED`.
  - **Die Anmeldesperre nach Fehlversuchen bleibt bewusst draußen.** Sie ist kurz, endet von selbst
    und wird von jedem ausgelöst, der die Adresse kennt. Als Intervall hielte sie fest, wann jemand
    sein Passwort mehrfach falsch eingab — was der Fehlversuchszähler gerade nicht tut (siehe oben
    „Fehlgeschlagene Anmeldungen“) —, und ein Fremder könnte mit wenigen Versuchen je Viertelstunde
    die Historie einer Person so füllen, dass jede Auskunft über sie an der Obergrenze von 2000
    Intervallen scheitert. Die Auskunft nennt eine Person deshalb auch während einer Anmeldesperre:
    Sie überschätzt den Kreis um höchstens deren Dauer und verschweigt niemanden. Dasselbe hält
    `ConnectionLifecycleReconciler` für die Verbindungen (eine Anmeldesperre lässt sie unberührt).
  - **Nicht-lokale Konten** entstehen nur bei einer Anmeldung über einen aktiven Anbieter und sind
    damit bei ihrer Anlage nutzbar; deshalb braucht `users` keinen Trigger beim Einfügen.
  - **Geschrieben wird in der Datenbank.** Trigger auf `users`, `local_credentials` und
    `oidc_providers` werten die Regel von `AccountUsability` nach jeder Änderung neu aus, die sie
    ändern kann (`account_state_reconcile`), in der Transaktion der Änderung, unter einer Sperre je
    Konto (`FOR NO KEY UPDATE`: jede Neubewertung sperrt die `local_credentials`-Zeile, sofern es
    sie gibt, ein nicht lokales Konto vorher seine `users`-Zeile — dieselbe Reihenfolge, in der die
    Anwendung schreibt; eine Änderung der Zugangsdaten eines lokalen Kontos greift nie nach `users`.
    Eine Zeilensperre belegt anders als eine Advisory-Sperre keinen Eintrag der gemeinsamen
    Sperrtabelle, sodass Migration und Anbieterschalter über beliebig viele Konten in eine
    Transaktion passen; der Schalter der lokalen Konten sperrt die Zugangsdaten vorab in der
    Reihenfolge der Kennung, wie der Ablauf-Lauf) und mit den Grenzen der Uhr aus ADR-0032. Jede
    Neubewertung desselben Kontos wartet auf die gemeinsame Zugangsdaten-Sperre, bis die andere
    Transaktion festgeschrieben ist, und liest danach neu; die zuletzt festschreibende sieht damit
    beide Änderungen, und die Grenzen eines Kontos steigen in der Reihenfolge der Festschreibung.
    Auch eine Übergabe an einen Anbieter, die den Aussteller ändert und die Zugangsdaten löscht,
    ist so mit einer gleichzeitigen Sperre geordnet; wer auf sie gewartet hat, liest den Aussteller
    neu und bewertet das Konto als Anbieterkonto. Verbleibend: Eine Übergabe ändert mit Aussteller und
    Subjekt einen eindeutigen Schlüssel von `users` und sperrt die Zeile dafür `FOR UPDATE`; das
    Schreiben einer Historienzeile prüft ihren Fremdschlüssel mit `FOR KEY SHARE` auf derselben
    Zeile. Hält eine andere Transaktion die Zugangsdaten schon, bevor sie die Historie schreibt,
    können beide deadlocken: von der Sperre der Zugangsdaten bis zum Schreiben der Historie, beim
    Schalten der lokalen Konten über die ganze Transaktion. Dieselbe Klasse betrifft die Löschung
    eines Kontos gegen eine gleichzeitige Änderung seiner Zugangsdaten. In allen Fällen bricht
    Postgres eine der Transaktionen vollständig ab; eine falsche Kette entsteht nicht (#2979 sperrt
    in der Übergabe künftig vorab). Höchstens ein offenes Intervall je Konto erzwingt
    `uk_account_state_history_open`. Restrisiko wie bei ADR-0032: Wer als Eigentümer der Tabellen
    Trigger abschaltet, `TRUNCATE` ausführt oder die Historie per SQL ändert, umgeht das. So hält jeder Schreibweg den Übergang fest, gleich auf welcher Instanz und von welcher Version er kommt, auch eine Handkorrektur per SQL. Nur
    ein Ablauf tritt ohne Schreibzugriff ein; ihn hält `AccountStateExpirySweep` minütlich fest, mit
    einer Grenze nicht vor dem Ablaufdatum, höchstens 500 Konten je Lauf. Bis dahin nennt die
    Auskunft das Konto noch: Sie überschätzt den Kreis um diesen Takt, bei einem Massenablauf von
    mehr als 500 Konten zur selben Zeit um je eine Minute mehr je 500 Konten, und verschweigt
    niemanden.
  - **Dieselbe Regel wie `AccountUsability`, Bedingung für Bedingung:** Reihenfolge der Gründe,
    Ausnahme der Systemverwaltung, Vergleich des Ausstellers nach `OidcIssuerUris#normalize`
    (Steuerzeichen und Leerzeichen am Rand, abschließende Schrägstriche; Groß- und Kleinschreibung
    zählen), nur Anbieter vom Typ `OIDC`. `AccountStateParityIntegrationTest` hält beide über eine
    Matrix von Kontozuständen gegeneinander, samt der Ursache je Zustand; zwei Anbieterzeilen
    desselben Ausstellers (nur per SQL möglich, die Verwaltung trimmt) entscheidet wie in Java die
    letzte nach Sortierung und Anzeigename (bei gleicher Sortierung und gleichem Namen die
    Kennung, wo Java keine feste Reihenfolge hat). Zwei Grenzen: Ein Konto ohne Anbieter seines
    Ausstellers gilt als nicht nutzbar, auch im Modus `dev`, dessen Aussteller die Datenbank nicht
    kennt (dort nennt `AccountUsability` es nutzbar; `dev` ist kein Betriebsmodus). Und den Ablauf
    vergleicht die Datenbank mit ihrer eigenen Uhr, die Anwendung mit der Uhr ihres
    Hosts: Genau an der Grenze weichen beide um den Uhrversatz ab (mit NTP Millisekunden); ein
    Ablauf, den die Datenbank noch nicht sieht, holt der minütliche Lauf nach.
  - **Vollständig ab der Einführung.** Die Migration hält den Zeitpunkt fest
    (`permission_history_retention_settings.account_state_complete_since`) und öffnet für jedes
    Konto, das dann nicht nutzbar ist, ein Intervall ab diesem Zeitpunkt. Seit wann es das schon war,
    ist nicht rekonstruierbar; vorher kennt die Historie nur die Verzeichnissperren. Ein Fenster,
    das vor der Einführung beginnt, nennt den Kontozustand deshalb unter den nicht abgedeckten
    Quellen, ein späteres nur noch die Systemrolle.
  - **Die Intervalle gehen mit dem Konto.** Für sich nennen sie keinen Zugang: Jeder Zeitraum, den
    sie kürzen könnten, steht in einer anderen Historie, deren Personenspalte die Löschung weiter
    verweigert (ADR-0016). Deshalb verweist `account_state_history.user_id` mit `ON DELETE CASCADE`
    auf `users`, und ein einmal gesperrtes, abgelaufenes oder nur eingeladenes Konto bleibt löschbar.
- **Die Grenzen begrenzen die Arbeit, nicht nur die Ausgabe.** Trägt eine Quelle im gewählten
  Fenster mehr als 2000 Intervalle bei, oder umfasste die zusammengesetzte Antwort mehr als 5000
  Zugriffszeiträume, wird die Abfrage **abgelehnt** mit der Bitte, das Fenster enger zu fassen —
  aus demselben Grund, aus dem ein zu weites Zeitfenster abgelehnt und nicht gekürzt wird: Eine
  gekürzte Auskunft sähe vollständig aus.
- **Der Personen-Einstieg** („worauf hatte Person X am 3. März Zugriff") ist **nicht** Teil dieses
  Endpunkts und bleibt bis #391/#395 geschlossen; er verlangt dann eine eigene, befristete,
  begründete Vier-Augen-Vollmacht nach dem Muster des Vorfallsbereichs.
- **Getrennt von der Suchdiagnose:** Die Diagnose beantwortet den Jetzt-Zustand, die
  Stichtagsauskunft die Vergangenheit; keines ersetzt das andere
  ([hybrid-retrieval.md](./hybrid-retrieval.md), Berechtigungs-Leitplanke (b)).

Die Auskunft liest die Historie, nie den heutigen Bestand: Eine seither gelöschte Gruppe erklärt
einen Zugriff weiterhin — mit ihrer Kennung, aber **ohne Namen**, weil die Historie keinen
Namensschnappschuss führt (ADR-0036, Entscheidung 2: der ist erst zusammen mit der
Aufbewahrungshöchstdauer zulässig und kommt mit #1812/#1824).

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
der Sichtbarkeit ändern und löschen sowie die **Freigabe einer Bibliothek für externe KI-Werkzeuge setzen,
zurücknehmen und erlöschen lassen**. Ein Schreibpfad, der Leserechte ändert, ohne seine Historienzeile zu
schreiben, fällt dort auf, bevor er in Betrieb geht. Welche Klassen dabei überhaupt in Frage kommen, hält
derselbe Test gegen den Anwendungskontext, damit eine neue Klasse an den Rechtetabellen nicht unbemerkt
hinzukommt.

Für die **Reichweitenfelder** einer Bibliothek — die Freigabe für externe KI-Werkzeuge und die Freigabe-Obergrenze —
kommt ein Strukturtest hinzu (`KnowledgeLibraryReachWriterTest`): Er liest die übersetzten Klassen
und lässt jede ändernde Methode der Bibliothek nur aus genau einem Dienst zu — `updateExternalAccess`
aus `LibraryExternalAccessService`, `expireExternalAccess` aus `LibraryExternalAccessExpiryService`,
`markExternalAccessReminderSent` aus `LibraryExternalAccessReminderService` und `updateShareCap` aus
`KnowledgeLibraryService`. Jeder weitere Aufrufer, auch eine neue Klasse im selben Paket und auch
über eine Methodenreferenz, lässt den Test fehlschlagen. Dass die zugelassenen Schreibpfade der Freigabe ihre Historienzeile tatsächlich schreiben, prüft
der Integrationstest je Operation (`externalAccessWritePaths`: Setzen, Zurücknehmen, Ablauflauf).
Der Compiler trägt diese Einschränkung nicht: Die Methoden sind öffentlich, weil die Bibliothek in
`io.opaa.knowledge` liegt und dieses Paket nach der Modulschichtung nicht von `io.opaa.library`
abhängen darf. Am Strukturtest vorbei ginge es über direktes SQL, eine Datenbankmigration oder
Reflection; diese Wege sieht keine der Prüfungen.

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

**Umsetzungsstand (#238, #1813, #1931):** Grants — den an „Alle Konten" eingeschlossen —,
Gruppenmitgliedschaften, die Reichweitenfelder einer
Bibliothek und die installationsweiten Fähigkeiten (ADR-0036, Entscheidung 5) sind als Intervalle mit
auslösendem Vorgang historisiert, einschließlich eines Backfills für den
Altbestand (Ursache `BACKFILL`, `valid_from` aus dem jeweiligen Erstellungszeitpunkt der Fachzeile) —
ohne ihn wäre die Rekonstruktion für jeden Stichtag vor der Migration und für jedes seither unveränderte
Recht falsch, nicht bloß lückenhaft. Die Historie überlebt die Löschung einer Bibliothek oder Gruppe
(siehe [ADR-0016](../decisions/0016-loeschschicksal-rechtehistorie.md)): Die Fachobjekt-Spalten tragen
bewusst keinen Fremdschlüssel, damit eine reguläre Lösch-Operation die Beweislage nicht mit sich reißt.

**Umsetzungsstand der Freigabe für externe KI-Werkzeuge (#1731):** Die Freigabe einer
Wissensbibliothek für externe KI-Werkzeuge ist gebaut und liegt in `asset_visibility_history`, mit demselben Schreibpfadschutz (Integrationstest je Operation und
`KnowledgeLibraryReachWriterTest`, siehe oben). Setzen, Zurücknehmen und Erlöschen erzeugen je einen Protokolleintrag
(`ASSET_EXTERNAL_ACCESS_CHANGED`, `ASSET_EXTERNAL_ACCESS_EXPIRED`) und öffnen je ein neues Intervall;
der Ablauflauf schreibt ohne handelnde Person, unter einem Systemakteur. Die
Befristung **wirkt im Augenblick ihres Ablaufs**, nicht erst mit dem nächtlichen Lauf: Der Lauf
trägt Protokoll und Historie nach, er ist nicht die Bedingung der Wirkung — fiele die
Installation aus, wäre er das sonst tagelang. Der Stichtag „war diese
Bibliothek freigegeben?" wird aus der Historie allein beantwortet
(`AssetVisibilityHistoryService#externalAccessActiveAsOf`), also auch nach der monatsweisen Löschung des
Protokollzeitraums. **Aussetzen** ist im Modell vorgesehen (Zustand `SUSPENDED`), wird aber weiterhin
von nichts gesetzt: [#797](https://github.com/criew/opaa/issues/797) hat die Freigabe-Obergrenze
konnektor-gespeister Bibliotheken ausdrücklich auf die Freigabe an „Alle Konten" (bis ADR-0039
zusätzlich auf `listed`) begrenzt und lässt die
Freigabe für externe KI-Werkzeuge unberührt (siehe [external-access.md](./external-access.md#die-freigabe-ist-ein-reichweitenfeld-und-wird-wie-eines-behandelt)).

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
statt unmittelbar aus der Systemuhr: aus der Uhr der Datenbank, installationsweit monoton über alle
Instanzen und alle Historientabellen ([ADR-0053](../decisions/0053-mehrinstanzbetrieb-aktiv-aktiv.md),
Entscheidung 9). Lückenlos bleiben die Intervalle dabei: Das Schließen eines
Intervalls und das Öffnen des folgenden teilen sich einen Grenzwert. Zeilen der Länge null gibt es
weiterhin, aber ausschließlich als **Ereignismarkierung** für einen Widerruf oder eine Löschung — sie
halten den auslösenden Vorgang fest und werden von der Rekonstruktion nie als Zustand ausgewählt.

**Noch offen, bewusst nicht Teil dieser Ausbaustufe:**

- **Pseudonymisierung der Historie selbst.** Eine Pseudonymisierung ab Schreibzeitpunkt — wie sie
  das Protokoll kennt — ist nicht umgesetzt und wird es nach ADR-0036,
  Entscheidung 8, auch nicht in dieser Form; die Subjektspalten der
  Rechtehistorie sind stattdessen `ON DELETE RESTRICT` gegen die Nutzertabelle — eine Kontolöschung
  ist damit blockiert, solange Rechtehistorie zu diesem Konto existiert, bis #391/#395 die
  Pseudonymisierung liefern (siehe ADR-0016). Die **Aufbewahrungshöchstdauer** steht seit #1833 und
  begrenzt diese Sperre der Länge nach: Was die Frist verlässt, wird gelöscht.
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
die Negativfrage korrekt beantwortbar. **Die Fähigkeiten folgen derselben Systematik:** Ihre
ausgelieferten Intervalle (Ursache `DELIVERED`) beginnen beim Erstellungszeitpunkt der Organisation,
nicht bei der Migration — das Anlegen stand jedem angemeldeten Konto von deren erstem Tag an offen, und
ein Intervallbeginn von heute ließe die Auskunft für jeden früheren Stichtag fälschlich „niemand durfte"
antworten. Für einen Prüfzeitraum, der vor die Migration zurückreicht und
Rechte betrifft, die dort bereits beendet waren, bleibt die Rechtehistorie ohne Aussage.

**Die Rückwirkung bis zur Entstehung gilt nur für drei der Tabellen** — `asset_grant_history`,
`group_membership_history` und `capability_grant_history`. **Space-Mitgliedschaft und Eigentum
beginnen am Migrationszeitpunkt**, nicht bei der Entstehung der Fachzeile: Deren `created_at` sagt,
wann der Space oder die Mitgliedschaft angelegt wurde, nicht seit wann die heutige Rolle
beziehungsweise der heutige Eigentümer gilt. Eine vor Jahren angelegte Mitgliedschaft, deren Rolle
seither geändert wurde, und ein Space, dessen Verantwortung längst übertragen wurde, bekämen sonst
ein Intervall, das den heutigen Zustand rückwirkend bis zur Anlage behauptet — eine Aussage, die
falsch wäre und nicht nur unvollständig. Der Backfill setzt deshalb `valid_from` auf den
Migrationszeitpunkt: Für die Zeit davor behauptet er nichts.

**Zwei Bestände bleiben außerdem ganz ohne Historie**, und beide sind benannt statt verschwiegen:

- **Der persönliche Space** (`isDefault`) und seine Eigentümer-Mitgliedschaft. Beide legt das System
  bei der ersten Anmeldung an; niemand entscheidet sie, und kein Pfad schließt ihr Intervall je
  wieder. Da die Personenspalten der Historie `ON DELETE RESTRICT` tragen, machte ein Intervall hier
  jedes Konto, das sich je angemeldet hat, dauerhaft unlöschbar — dieselbe Grenze, die die
  Löschprüfung schon immer mit „Spaces außer dem Standard-Space" zieht. **Folge:** Eine
  Stichtagsauskunft nennt den persönlichen Space nicht. Jede Mitgliedschaft und jedes Eigentum, über
  die ein Mensch entscheidet — auch in einem persönlichen Space —, sind dagegen vollständig
  historisiert.
- **Rechte, die vor der Migration bereits beendet waren**, wie im Absatz darüber beschrieben.

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
Nachweisbeständen, kein offener Antrag und keine noch nutzbare Freigabe der anlassbezogenen Klärung;
praktisch ein nie benutztes Konto —, und löscht dessen persönlichen Space mit
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
Sitzungen und die [Selbstauskunft](#selbstauskunft-stufe-1) sind die einzigen vorgesehenen
Ausnahmen, beide nur für die Person selbst. Die Kontenliste der
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

#### Selbstauskunft (Stufe 1)

Jede angemeldete Person lädt die zu ihr gespeicherten Daten selbst herunter: ausgelöst in ihren
Einstellungen („Profil und Einstellungen“, Bereich „Meine Daten“), nach erneuter Anmeldung, als
ZIP. Grundlage sind die Entscheidungen des Maintainers vom 23.10.2026 und das Stakeholder-Review
mit Personalrat und Betrieb (Epic #2986).

**Abgrenzung zum Auskunftsantrag.** Das Werkzeug ersetzt nicht die förmliche Bearbeitung eines
Auskunftsantrags nach Art. 15 DSGVO durch die Behörde. Ein Antrag auf anderem Weg bleibt möglich
und wird bearbeitet wie bisher ([Betroffenenrechte](../behoerden/04-datenschutz/betroffenenrechte.md));
Oberfläche und Übersicht sagen das.

**Nur für die Person selbst, nicht delegierbar.**

- Die Person ist die Identität der Anfrage, kein Parameter. Keine Ausnahme für `SYSTEM_ADMIN`,
  `AUDITOR` oder die Datenschutzstelle; kein Export für Dritte, „im Auftrag“ oder in ein fremdes
  Postfach.
- Nicht über persönliche Zugangstokens externer KI-Werkzeuge erreichbar, nur mit einer Anmeldung
  an der Oberfläche.
- **Kein Export während einer Diagnose-Impersonation („Sicht als“).** Eine Anfrage, die einen
  fremden Rechtekontext trägt, wird abgewiesen; die Selbstauskunft liest nie in einem anderen als
  dem eigenen Kontext. Ein Test hält das fest.

**Hinweis**, wörtlich in der Oberfläche und oben in der HTML-Übersicht:

> Diese Auskunft ist nur für Sie. Niemand darf verlangen, dass Sie sie vorlegen oder weitergeben.

**Keine Signatur, kein Echtheitsnachweis.** Das ZIP trägt keine Signatur, keine Prüfsummendatei
und kein Siegel. Ein Echtheitsnachweis machte die Auskunft als Beleg verwertbar und lüde dazu ein,
ihre Vorlage zu verlangen — genau das, was der Hinweis ausschließt. Die Prüfsumme im
[Protokoll](#protokoll-der-selbstauskunft) belegt nur gegenüber der Revision, was ausgeliefert
wurde; sie steht nicht im ZIP.

##### Inhalt

Eine **Kategorie** ist ein Datum des Fakten-Manifests (`id`) mit mindestens einer Tabelle, die einen
Weg zur Person hat (#2988); der Zustand der Selbstauskunft je Person ist ein neues Datum
`self-disclosure`, das #2989 anlegt. Die Spalte „Exporteur“ nennt das Issue, das die Kategorie
liefert. Für #2988: `diagnostic_context_log.target_ref` enthält bei `target_kind = USER` ein
Pseudonym und wird personenbezogene Spalte mit Weg zur Person; ein Weg darf über eine Tabelle eines
anderen Datums führen (`documents` über `assets.owner_user_id`); eine Spalte, die nur unter einer
Bedingung befüllt herausgeht, gilt als Teil der Auskunft, die Bedingung trägt die Positivliste des
Exporteurs. Je Kategorie eine JSON-Datei; ausgegeben werden nur Felder einer
**Positivliste** des Exporteurs, nie eine ganze Zeile. Geheimnisse (Passwort-Hashes, Token-Werte und
-Hashes, verschlüsselte Zugangsdaten) stehen in keiner Positivliste. Spalten, die die Tabelle unten
nicht als ausgeschlossen nennt, sind Teil der Auskunft.

| Kategorie (`id`) | Exporteur | Enthalten | Ausgeschlossen, mit Grund |
|---|---|---|---|
| `self-disclosure` (neu, #2989) | #2989 | Zeitpunkt des letzten vollständigen Exports, Ergebnis des letzten Versuchs, Starts und vollständige Exporte des laufenden Tages | die Reservierung des laufenden Exports (betrifft immer den Export selbst, ohne Aussage) |
| `accounts` | #2990 | eigenes Konto: Kennung beim Anbieter, Anbieter, Adresse, Anzeigename, letzte Anmeldung, Systemrolle, Sperre durch den Verzeichnisabgleich, Anlage | — |
| `local-credentials` | #2990 | Sperre mit Grund, Ablauf, Bestätigung der Adresse, Anlagegrund, „Passwortwechsel ausstehend“ mit Anlass | `password_hash` (Geheimnis); `failed_login_attempts`, `lockout_until` (nach der erneuten Anmeldung stets zurückgesetzt, ohne Aussage); `session_generation`, `expiry_reminded_for` (technische Merker ohne Aussage über die Person) |
| `local-sessions-and-links` | #2990 | eigene Sitzungen aus `local_refresh_tokens` (Beginn, Ablauf, Widerruf mit Grund) und eigene Links aus `local_action_tokens` (Zweck, Anlage, Ablauf, Einlösung, Anlass, Anbieter) | `local_revoked_tokens` (nur Prüfwert und Ablauf eines widerrufenen Zugriffstokens; der Widerruf steht schon bei der Sitzung); alle Hashes und Familienkennungen |
| `account-state-history` | #2990 | Zeiträume, in denen das Konto nicht nutzbar war, mit Ursache | — |
| `settings-editors` | #2990 | Bereich und Zeitpunkt der Einstellungen, die die Person zuletzt geändert hat | Werte der Einstellungen (keine Daten über die Person; Absender und Zugangsname der Mail-Einstellungen gehören dem Dienstkonto) |
| `notifications` | #2990 | Art, Objektart, Titel, Text, Eingang, gelesen | — (Texte erzeugt OPAA und nennen Objekte, keine Personen; nennt ein Typ künftig eine andere Person, geht er ohne Text heraus) |
| `audit-log` | #2990 | Einträge, in denen die Person handelt (`actor_ref`), betroffen ist (`subject_ref`), Kontoobjekt ist oder als Besitzerin (`ownerId` bei den Ereignisarten mit Besitzerin) oder als Ziel einer ausgeführten Rechteübertragung (`targetId` nur bei `PERMISSION_TRANSFER_EXECUTED`, mit den Umfangszahlen der übertragenen Rechte; die Vorschau `PERMISSION_TRANSFER_PREVIEWED` nennt Umfangszahlen der Quelle zu einer womöglich nie ausgeführten Übertragung und erscheint in der Auskunft der Zielperson nicht) genannt ist, in `before`/`after` per Pseudonym oder, in älteren Einträgen, per Kontokennung, auch ohne Pseudonym (#3135): Zeitpunkt, Ereignisart, Objektart, Objektbezeichnung, Ergebnis, Vorher/Nachher nach Positivliste je Ereignisart, Vorgangskennung, Version des Ereigniskatalogs (`schemaVersion`), ob ein Anlass angegeben war | Pseudonymkennungen, auch die eigene; `reason`, auch der eigene (Begründungsfreitext, siehe unten); Ereignisse einer anlassbezogenen Klärung über die Person; `object_label` an fremden Kontoobjekten |
| `chats` | #2992 | eigene Chats (`author_id`) mit Titel, Filter, Nachrichten, Notizen, Anheften und Archiv; je Nachricht eine Zeile mit Kennzeichnung als KI-erzeugt (`generatedByAi`); an Antworten die Fundstellen nur mit Dokumenttitel (Dateiname), heutigem Namen der Bibliothek und Seite (bzw. Folie, Zeile, Nachricht oder Teil), zitiert oder nicht | Textausschnitte der Fundstellen (Dokumentinhalt); Überschriftenpfade, Blattnamen und Folientitel des Fundorts (Text aus dem Dokument); Dokument- und Chunkkennungen, Quellverweise und Metadaten der Fundstelle; `content_tsv` (abgeleiteter Suchindex) |
| `chat-cleanup-holds` | #2992 | ob und seit wann die Löschung der eigenen Chats nach einer Sperre angehalten ist; angehalten von „andere Person“ (nur die Verwaltung hält an) | — (eine Begründung wird nicht gespeichert; das Fortsetzen löscht die Zeile und steht im Protokoll) |
| `connected-accounts` | #2991 | eigene verbundene Konten (Bezeichnung beim Anbieter, Zeitpunkte), von der Person verbundene Bibliotheken und Bibliotheken, für deren Einwilligung die Person verantwortlich ist (eine andere verantwortliche Person als „andere Person“) | `connection_tokens`, `connection_authorizations`, `connection_grant_revocations` (Geheimnisse und Zwischenstände eines laufenden Verbindungsaufbaus oder Widerrufs, kurzlebig) |
| `connection-person-states` | #2991 | Ruhezustand der eigenen Verbindungen | — |
| `connection-log` | #2991 | Einträge, in denen die Person handelt oder betroffen ist | Pseudonymkennungen |
| `external-access-tokens` | #2991 | eigene Zugangstokens: Name, Anlage, Ablauf, letzter Nutzungstag, Widerruf mit Anlass | `token_prefix`, `token_lookup_hash` (Geheimnisbestandteile) |
| `library-settings` | #2992 | Bibliotheken, deren Freigabe für externe KI-Werkzeuge die Person gesetzt hat, mit Zeitpunkt; private Bibliotheken der Person mit Quelladresse ohne Zugangsdaten und Abfrageteil | `source_credentials` (Geheimnis) |
| `documents` | #2992 | **nur Verzeichnis**, zwei Wege zur Person: `documents.uploaded_by_user_id` (eigene Uploads) und `documents.library_id` → `assets.owner_user_id` des Datums `rights` bei privaten Bibliotheken (Nur-Besitzerin-Bibliothek, auch konnektor-gespeist). Enthalten: Dateiname, Datum, Bibliothek; `file_path` und `source_hierarchy_path` (Pfad, Ordner) nur bei privaten Bibliotheken — bei Uploads in geteilte Bibliotheken bleiben sie leer, weil sie dort die Ablage der Organisation beschreiben; `file_path` eines hochgeladenen Dokuments (`UPLOAD`) bleibt auch in privaten Bibliotheken leer | `file_path` bei Uploads (Speicherort des Originals in der Originalablage); `context_prefix_title` (aus dem Inhalt abgeleitet); `source_container_key`, `source_entry_url` (technische Schlüssel und Verweise der Quelle); Inhalte, `chunk_full_text`, `vector_store`, `document_metadata_values` (Dokumentinhalt und Einbettungen; Handänderungen an Metadaten stehen im Protokoll); `indexing_run_events`, `source_sync_revisits`, `source_event_batches` (Betriebsdaten des Bestands, kein Weg zur Person) |
| `indexing-runs` | #2992 | die von der Person gestartete Neueinbettung des Index (`embedding_index`, nur der letzte Lauf): Beginn, Ende, Ergebnis | `indexing_jobs` (der Anstoß einer Indexierung wird keiner Person zugeordnet gespeichert, `triggered_by` hält nur manuell, Zeitplan oder Webhook fest); Wartungsanstöße wie die Neuverarbeitung einer Bibliothek stehen als Ereignisse im Protokoll und gehen über `audit-log` heraus |
| `rights` | #2991 | eigene Assets mit Art und Name, **eigene Prompt-Bibliotheken eingeschlossen**; Grants an die Person und von ihr erteilte, Anlegerechte; Empfänger und Erteilende außer ihr nur als „andere Person“ bzw. Gruppenname | Grants Dritter an fremden Assets (kein Weg zur Person); Inhalte der Assets, etwa Prompt-Texte (Sachdaten ohne Personenbezug) |
| `permission-history` | #2991 | Intervalle, in denen die Person Subjekt, Eigentümerin oder Gruppenmitglied war, und von ihr bewirkte Änderungen | — |
| `groups` | #2991 | eigene Mitgliedschaften und Verantwortungen | — |
| `permission-transfers` | #2991 | Übertragungen, in denen die Person Quelle, Ziel oder handelnd war | Bezeichnungen der jeweils anderen Seite |
| `succession` | #2991 | Nachfolgefälle an Assets der Person und von ihr abgeschlossene | — |
| `favorites` | #2991 | eigene Favoriten | — |
| `spaces` | #2991 | eigene Spaces mit Modellanweisung, Mitgliedschaften, von der Person vorgenommene Zuordnungen | — |
| `space-membership-history` | #2991 | Intervalle der eigenen Mitgliedschaften und von der Person bewirkte Änderungen | — |
| `diagnostic-impersonation` | #2992 | Befugnisse „Sicht als“, die die Person hält, erteilt oder widerrufen hat: Bereich, Gültigkeit, Erteilung, Widerruf | Inhaber, Erteilende und Widerrufende außer ihr nur als „andere Person“ |
| `diagnostic-context-log` | #2992 | eigene Suchdiagnosen: Zeitpunkt, Zielart, Testfrage, Trefferzahl; **Diagnosen im Rechtekontext der Person** (`target_kind = USER`, `target_ref` = ihr Pseudonym): Zeitpunkt, handelnd „andere Person“, Trefferzahl | `justification`, auch die eigene (Begründungsfreitext); Testfrage einer Diagnose durch andere (Freitext Dritter); `permission_snapshot`, `hit_refs` (Rechte und Treffer anderer); `target_ref` als Kennung (Pseudonym). Gelesen wird nach `actor_ref` und `target_ref` mit dem Pseudonym der angemeldeten Person, ungruppiert und nie für eine andere — die einzige Ausnahme von der Zweckbindung des Diagnoseprotokolls ([Leitplanke (g)](hybrid-retrieval.md#berechtigungs-leitplanken)) |

**Ganz ausgeschlossen**, mit Hinweis in der Übersicht:

| Datum | Grund |
|---|---|
| Dokumentinhalte, Einbettungen, Originaldateien (`original-storage`) | Sachinhalte der Bibliotheken, keine Daten über die Person; Uploads erscheinen nur im Verzeichnis |
| Technisches Anwendungslog (`application-log`) | liegt beim Betrieb, nicht in OPAA; Auskunft darüber gibt die Behörde |
| Sicherungen | Kopien derselben Bestände beim Betrieb, nicht über die Anwendung lesbar |
| Geheimnisse (Passwort-Hashes, Tokens, verschlüsselte Zugangsdaten) | kein Auskunftswert, Ausgabe wäre ein Sicherheitsrisiko |
| Inhalte anlassbezogener Klärungen über die Person (`audit-incident-scopes` und die zugehörigen Protokolleinträge) | die Auskunft darüber läuft über die oder den Datenschutzbeauftragten; die Übersicht sagt nur, dass es sie geben kann |
| Pseudonymzuordnung (`audit-pseudonyms`) | die eigene Kennung machte die Auskunft zum Schlüssel ins Protokoll — wer ihre Vorlage erzwingt, hätte den ausgeschlossenen Personeneinstieg |
| Abgleichspläne (`directory-sync`) | Arbeitsstand vor einer Bestätigung, nennt viele Personen; die bewirkten Änderungen stehen in Rechten, Historie und Protokoll |
| Mail-Ausgang (`mail-outbox`) | Zustellpuffer von höchstens rund zwei Stunden; die Mails selbst liegen im Postfach der Person |
| Prompt-Texte | Sachdaten ohne Personenbezug (Manifest); eigene Prompt-Bibliotheken erscheinen als Assets unter `rights` |

**Andere Personen** erscheinen nie mit Namen, Adresse, Kennung oder Pseudonym — auch nicht in
`object_label`, `reason`, Vorher/Nachher oder Bezeichnungen. Sie stehen als **„andere Person“**,
Systemvorgänge und Dienstkonten als **„System“**, die Person selbst als **„Sie“**. Gruppen,
Bibliotheken und Spaces erscheinen mit ihrer Bezeichnung. Ein Test je Exporteur hält fest, dass
Namen und Kennungen eines zweiten Kontos im Export nicht vorkommen.

**Freitexte.** Eigene **Arbeitsinhalte** gehen unverändert heraus: Chats, Notizen,
Modellanweisungen, Testfragen eigener Diagnosen. Ohne sie wäre die Auskunft leer, und dass sie
nebenbei andere nennen können, liegt am Inhalt der Arbeit, nicht am Feld. **Begründungen von
Verwaltungs- und Prüfhandeln** gehen nie heraus, auch die eigenen nicht: `reason` im Protokoll und
die Begründung einer Suchdiagnose. Ihr Zweck ist es, einen Fall zu benennen, und der betrifft
regelmäßig andere Personen; maschinell bereinigen lässt sich ein solcher Freitext nicht. Ausgegeben
wird nur, ob eine Begründung angegeben war. Freitexte anderer gehen nie heraus. **Ausgenommen** ist
der Anlagegrund eines lokalen Kontos (`local_credentials.created_reason`): Er ist der Person ohnehin
offengelegt und steht in ihren Einstellungen ([ADR-0033](../decisions/0033-lokale-benutzerverwaltung.md),
Entscheidung 11). Die Oberfläche zeigt
der Person Diagnosen in ihrem Rechtekontext heute mit Namen und Begründung; der Export als Datei, die
OPAA verlässt, ist bewusst knapper und schreibt „andere Person“.

##### Format

- **ZIP**, Dateiname `opaa-selbstauskunft-<JJJJ-MM-TT>.zip` mit dem Datum in der Zeitzone der
  Installation, synchron gestreamt, keine Job-Tabelle und keine Zwischenablage auf dem Server.
- **Reihenfolge der Einträge:** zuerst `<kategorie>.json` je Kategorie, dann `manifest.json`, zuletzt
  `index.html`. So stehen Zahlen und Auszüge fest, wenn Manifest und Übersicht geschrieben werden;
  ein zweiter Durchlauf über die Daten entfällt.
- `<kategorie>.json` je Kategorie mit mindestens einem Datensatz: Kategorie, Titel, Feldliste mit
  Bezeichnungen und alle Datensätze; Zeitpunkte in ISO 8601 (UTC).
- `manifest.json`: Erzeugungszeitpunkt, OPAA-Version, Liste der Kategorien mit Dateinamen und Zahl
  der Datensätze. Personenbezogen ist darin nur der Erzeugungszeitpunkt.
- `index.html`: lesbare Übersicht, eine Datei ohne Skript und ohne externe Ressourcen, `lang="de"`,
  Zeitpunkte in der Zeitzone der Installation. Oben der Hinweistext. Je Kategorie Titel, Erklärung,
  Zahl der Datensätze, Ausschlüsse mit Grund und die **ersten höchstens 200 Datensätze** lesbar als
  Tabelle; bei mehr der Verweis auf `<kategorie>.json` für den Rest. Ein Datensatz der Übersicht ist
  eine flache Zeile der Positivliste ohne verschachtelte Teile (bei `chats` je Nachricht eine Zeile,
  nicht ein ganzer Chat), jedes Textfeld darin höchstens **2000 Zeichen**, gekürzt mit dem Hinweis
  „vollständig in `<kategorie>.json`“. Die gekürzten 200 Zeilen merkt sich der Export beim Schreiben
  der JSON-Datei; der Puffer ist damit je Kategorie begrenzt, auch beim Vielnutzer.
  Danach die ganz ausgeschlossenen Daten mit Grund, der Hinweis auf anlassbezogene Klärungen und die
  oder den DSB, die Abgrenzung zum Auskunftsantrag und der Satz, dass die Kategorien nacheinander
  gelesen werden.
- **Kein Schnappschuss eines Zeitpunkts.** Jeder Exporteur liest in einer eigenen kurzen lesenden
  Transaktion; eine lange Transaktion über alle Kategorien gibt es nicht. Ändert sich während des
  Exports etwas, kann eine Kategorie den älteren, eine spätere den neueren Stand zeigen. Die
  Übersicht sagt das.

Größenordnung (Analyse an #798): typisch ein Jahr rund 25 MB JSON (5 MB gepackt), Vielnutzer bis
rund 520 MB JSON (100 MB gepackt); über 95 % sind Chats.

##### Grenzen und Betrieb

**Kein Feature-Flag.** Die Selbstauskunft ist ab Werk an. Die Betriebsgrößen:

| Einstellung | Ort | Voreinstellung | Wertebereich | Wirkung |
|---|---|---|---|---|
| Notabschaltung `self_disclosure_settings.enabled` | Datenbank | an | an / aus | ohne Neustart auf allen Instanzen; ein laufender Export läuft zu Ende |
| Tagesgrenze `self_disclosure_settings.daily_limit` | Datenbank | 1 | 1 bis 10 | vollständige Exporte je Person und Tag |
| `opaa.self-disclosure.max-concurrent-exports` | Konfiguration je Instanz | 2 | 1 bis 16 | gleichzeitige Exporte je Instanz |
| `opaa.self-disclosure.max-duration` | Konfiguration | 30 Minuten | 5 Minuten bis 2 Stunden | danach bricht der Export ab |

Beide Datenbankeinstellungen ändert nur `SYSTEM_ADMIN` in der Administration; jede Änderung ist das
Protokollereignis `SELF_DISCLOSURE_SETTINGS_CHANGED` mit Vorher/Nachher der geänderten Schlüssel,
und beide stehen im Fakten-Manifest als datenwirksame Einstellungen. Ist die Selbstauskunft
abgeschaltet, sieht die Person statt des Auslösers:

> Die Selbstauskunft ist in dieser Installation derzeit abgeschaltet. Ihr Recht auf Auskunft nach
> Art. 15 DSGVO bleibt davon unberührt: Sie können die Auskunft bei Ihrer Behörde beantragen, in der
> Regel über die oder den Datenschutzbeauftragten.

**„Tag“** ist der Kalendertag in der Zeitzone der Installation (`TZ` des Backends, auf allen Instanzen
gleich, ADR-0053), gemessen an der Datenbankuhr; ein Export zählt für den Tag, an dem er begann.
Kalendertag statt gleitender 24 Stunden, weil die Person die Grenze so verstehen und vorhersehen kann
(„morgen wieder“) und weil sie nur einen Zähler je Person und Tag braucht statt eines Verlaufs der
Exportzeitpunkte, der ein Nutzungsprofil wäre. Die Folge — zwei Exporte kurz vor und kurz nach
Mitternacht — ist hingenommen.

**Zählweise.** Nur ein **vollständig ausgelieferter** Export verbraucht ein Kontingent; ein
abgebrochener (Rolling Update, VPN-Abbruch, Zeitüberschreitung, Fehler) oder abgewiesener nicht.
Vollständig ausgeliefert heißt: Der letzte Block des ZIP einschließlich Zentralverzeichnis ist ohne
Fehler in die Antwort geschrieben und geleert.

**Starts je Tag.** Unabhängig davon sind je Person und Kalendertag höchstens **Tagesgrenze + 4**
Starts möglich (Voreinstellung 5); darüber antwortet OPAA mit `429`. Ohne diese Grenze wären Abbrüche
unbegrenzt wiederholbar. Kein eigener Schalter: Die vier zusätzlichen Starts decken Abbrüche durch
Rolling Update oder VPN ab, mehr braucht kein Mensch an einem Tag. Folge: Mit der Voreinstellung ist
der Tag nach fünf Abbrüchen auch ohne vollständigen Export erschöpft; der Status sagt das („Starts für heute
erschöpft“).

**Restunschärfe in beiden Richtungen.**

- **Gezählt, aber nicht angekommen:** Geleert heißt an den Proxy und die TCP-Puffer übergeben, nicht
  beim Browser gespeichert. Bricht die Verbindung erst danach ab, zählt der Export, obwohl die Datei
  unvollständig ankam; mit `proxy_buffering off` ist das Fenster klein. Die Person kann am nächsten
  Tag erneut exportieren oder den Antrag bei der Behörde stellen.
- **Angekommen, aber nicht gezählt:** Ein Export, der kurz vor dem Zentralverzeichnis abbricht, hat
  fast alle Daten ausgeliefert, und die lokalen Dateiköpfe machen ein solches ZIP mit
  Reparaturwerkzeugen lesbar. Die Tagesgrenze begrenzt also die gezählten Exporte, nicht die
  abgeflossene Menge; die Grenze der Starts deckelt sie auf Tagesgrenze + 4 Durchläufe. Jeder Start
  verschickt eine Benachrichtigung und, wo eingerichtet, eine Mail, also höchstens so viele wie
  Starts. Beobachtungspunkt für den Betrieb ist die Metrik „abgebrochen“. Hingenommen, weil nur ein
  vollständig ausgelieferter Export den Tag verbrauchen soll (Entscheidung nach dem
  Stakeholder-Review) und jeder Durchlauf eine frische Anmeldung verlangt.

**Je Person höchstens ein laufender Export**, über alle Instanzen: Eine Reservierung in der Datenbank
mit Ablauf, die der laufende Export alle 30 Sekunden verlängert; sie verfällt 2 Minuten nach der
letzten Verlängerung. Eine verfallene Reservierung gilt als abgebrochener Export und hält nichts fest.
Zwei gleichzeitige Anfragen derselben Person ergeben genau einen Export; die zweite erhält `429`.

**Je Person gespeichert** wird nur: der Zeitpunkt des letzten vollständigen Exports („letzter
Export“), die Zähler des laufenden Tages für Starts und vollständige Exporte (überschrieben am
nächsten Tag), die laufende Reservierung und das Ergebnis des letzten Versuchs (abgeschlossen oder
abgebrochen, überschrieben beim nächsten). **Kein Verlauf.** Diese Felder bilden im Fakten-Manifest
das personenbezogene Datum `self-disclosure`, erscheinen in keiner Kontenliste und keiner
Schnittstelle der Verwaltung und sind Teil der eigenen Selbstauskunft (Kategorie oben).

**Last.** Über der Obergrenze je Instanz antwortet OPAA mit `503` und `Retry-After: 60`, ohne
Reservierung und ohne Verbrauch. Das fängt die Lastspitze bei Einführung ab. Der Export braucht eine
eigene Proxy-Location mit `proxy_buffering off` (#2989); ADR-0053, Entscheidung 1, wird dort für die
Streaming-Antwort fortgeschrieben — sie hat keinen Zustand über die Anfrage hinaus, ein Abbruch durch
Rolling Update ist zulässig, weil er nichts verbraucht. Metriken ohne Personenbezug: gestartet,
abgeschlossen, abgebrochen, abgewiesen nach Grund, Dauer, Größe, laufende Exporte je Instanz.

**Status für die Person:** verfügbar oder abgeschaltet, verbleibende Exporte und Starts heute, ab wann der
nächste möglich ist, Ergebnis des letzten Versuchs und ob ein neuer Versuch möglich ist.

##### Absicherung

**Erneute Anmeldung vor dem Export.** Der Export verlangt eine Anmeldung, die **höchstens 5 Minuten**
zurückliegt, gemessen am Anmeldezeitpunkt `auth_time` des Zugangstokens gegen die Serveruhr. Ein Token
aus einer bloßen Erneuerung (Refresh) trägt keinen oder nur den alten Anmeldezeitpunkt und genügt
nicht: Der lokale Aussteller lässt `auth_time` bei der Erneuerung weg, ein Identitätsanbieter behält
den alten Wert. Für lokale Konten heißt das, dass schon ein Neuladen der Seite (die Sitzung wird dabei
erneuert) vor dem Export erneut nach dem Passwort fragen lässt, auch kurz nach der Anmeldung. Lokale
Konten bestätigen mit dem Passwort (Fehlversuche zählen auf die Fehlversuch-Sperre), Konten eines
Identitätsanbieters über eine dort erzwungene Anmeldung (`prompt=login`, `max_age=0`); setzt der
Anbieter keinen frischen `auth_time`, wird abgewiesen, nicht durchgelassen. Geprüft wird beim Auslösen
des Exports; ein laufender Export wird nicht abgebrochen, wenn die 5 Minuten während der Übertragung
verstreichen. Fünf Minuten genügen für Bestätigung und Auslösen, ohne dass ein verlassener
Arbeitsplatz lange offen bleibt. Ersetzt die frühere Entscheidung „keine erneute Anmeldung“; der
Baustein ist #2996. Ein Einmal-Ticket für den Download des Browsers (#2989) darf nur mit frischer
Anmeldung entstehen, gilt höchstens 60 Sekunden und genau einmal und verlängert die 5 Minuten nicht.
Je Person gilt höchstens ein Ticket; ein neues ersetzt ein älteres. Der Browser löst es mit einem
Formular-POST ein, das Ticket steht im Rumpf und damit in keinem Zugriffsprotokoll; Start und Grenzen
werden beim Einlösen erneut geprüft, und erst das Einlösen zählt als Start.

**Benachrichtigung bei jedem Export**, beim Beginn — so erreicht sie die Person auch, wenn der Export
abbricht oder die Instanz dabei endet: in OPAA (Art `SELF_DISCLOSURE_STARTED`) und zusätzlich per
E-Mail über den Mail-Ausgang, wo Mail eingerichtet ist und das Konto eine Adresse hat. Inhalt
sinngemäß: „Am … um … wurde eine Selbstauskunft Ihrer Daten gestartet. Falls Sie das nicht waren,
ändern Sie Ihr Passwort und wenden Sie sich an Ihre Systemverwaltung.“ Kein Inhalt der Auskunft, kein
Link zum Download.

##### Protokoll der Selbstauskunft

**Zwei Ereignisse je Export**, damit ein Absturz der Instanz eine Spur hinterlässt: Ein Beginn ohne
Ende heißt „abgebrochen mit der Instanz“.

| Feld | `SELF_DISCLOSURE_EXPORT_STARTED` | `SELF_DISCLOSURE_EXPORT_FINISHED` |
|---|---|---|
| `actor_kind`, `actor_ref` | `USER`, Pseudonym der Person | ebenso |
| `object_type`, `object_id` | `USER_ACCOUNT`, Pseudonym der Person (wie die übrigen Kontoereignisse) | ebenso |
| `subject_kind`, `subject_ref` | `USER`, Pseudonym der Person | ebenso |
| `object_label`, `before`, `reason` | leer | leer |
| `after` | Liste der Kategorien | `result` (`COMPLETED` / `ABORTED`), `abortCause` (`CLIENT_DISCONNECTED`, `TIMEOUT`, `SHUTDOWN`, `ERROR`), `bytes` (ausgelieferte Bytes), `sha256` (über die ausgelieferten Bytes, auch bei Abbruch), Kategorien |
| `outcome` | `SUCCESS` | `SUCCESS` bei `COMPLETED`, `FAILURE` bei `ABORTED` |
| `correlation_ref` | Kennung des Exports | dieselbe |

Kein Inhalt, keine Datensatzzahlen je Kategorie, keine Netzadresse. Ein eigenes
`AUDIT_LOG_ACCESSED` für das Lesen von Protokoll, Verbindungs- und Diagnoseprotokoll schreibt die
Selbstauskunft nicht; die beiden Ereignisse treten an seine Stelle. **Abgewiesene Versuche**
(Tagesgrenze, Starts je Tag, Obergrenze, abgeschaltet, ohne frische Anmeldung, laufender Export)
schreiben **kein** Ereignis. Das ist eine ausdrückliche Ausnahme zur Zusage, dass auch abgelehnte
Zugriffe auf Protokolldaten festgehalten werden ([Ereignisliste](#die-ereignisse-der-ersten-stufe)):
Der Versuch scheitert, bevor irgendetwas gelesen wird, und ein Eintrag je Fehlversuch wäre ein
Verhaltensmerkmal ohne Nachweiswert. Abgewiesene Versuche gehen nur in die Metriken.

Die Ereignisse sind **gewöhnliche Protokollereignisse**. Erreichbar sind sie für `AUDITOR` über die
Wege nach Zeitraum, Ereignisart und Vorgang, über die anlassbezogene Klärung im Rahmen ihrer
Freigabe (sie tragen die Person als handelnde in `actor_ref`) und für die Person selbst in ihrer
nächsten Selbstauskunft; nach Objekt nicht. Dafür legt sie der [Auszug für die
Personalvertretung](#der-auszug-für-die-personalvertretung) mit Zweck, Zugriff und Frist offen
(Entscheidung des Maintainers vom 23.10.2026 im Zielkonflikt zwischen Personalrat und Betrieb).
Aufbewahrung: die allgemeine [Protokollfrist](#aufbewahrung).

**Kein Index auf `subject_ref`.** Die Auskunft sucht die eigenen Einträge über `actor_ref` (Index der
anlassbezogenen Klärung vorhanden), über das Kontoobjekt (Objektindex vorhanden) und über
`subject_ref` ohne Index, also mit einem Scan über alle Partitionen der Frist; im selben Scan über
`ownerId` und `targetId` in `before`/`after` bei ihren Ereignisarten. Ein Index auf
`subject_ref` wäre genau der Personeneinstieg „alle Ereignisse, bei denen Person X betroffen war“, den
dieses Dokument ausschließt, und machte ihn für jeden künftigen Abfrageweg billig. #2990 misst den
Scan auf einem Bestand von 10 Millionen Protokollsätzen über 36 Monatspartitionen. **Schwelle:**
Braucht die Protokollkategorie dort länger als 30 Sekunden, oder im Betrieb im 95. Perzentil länger
als 30 Sekunden, wird neu entschieden — über die Abfrage, nicht stillschweigend über einen Index.

#### Auskunft über die Datenerhebung

Die **Auskunft über die Datenerhebung** ist gebaut (#2558): das Fakten-Manifest der eingesetzten
Version, verbunden mit den wirksamen Werten der Instanz — jede datenwirksame Einstellung mit
Voreinstellung, wirksamem Wert und, bei Fristen in der Datenbank, dem Zeitpunkt der letzten Änderung;
eingeschaltete Datenflüsse, Konnektortypen in Gebrauch (ohne private Bibliotheken, die für niemanden
außer ihrer Besitzerin existieren) und Modellanbieter; dazu die Beschreibung des
Protokolls aus dem [Auszug für die Personalvertretung](#der-auszug-für-die-personalvertretung). Sie
geht als JSON und als Markdown heraus, ohne Geheimnisse, ohne personenbezogene Daten und ohne
Inhalte: Ausgegeben wird nur der Wert eines Schlüssels, den das Manifest als datenwirksam führt
(Positivliste), Adressen ohne Zugangsdaten und Abfrageteil. Abrufen darf sie nur `SYSTEM_ADMIN`;
jeder Abruf, auch der abgewiesene, ist ein Protokollereignis `DATA_COLLECTION_DISCLOSURE_EXPORTED`.
Abruf und Inhalt beschreibt das Handbuch unter [Nachvollziehbarkeit](../handbuch/betrieb/nachvollziehbarkeit.md#auskunft-über-die-datenerhebung-einer-installation);
sie ist der Eingang für das Update-Delta gegen die Instanz (#2559) und für die statischen Anlagen der
Dienstvereinbarung ([Behördenunterlagen und Update-Paket](./behoerdenunterlagen.md#instanzbezug)).
Das Ergebnis der automatisierten Prüfung je Abfrageweg ist noch nicht enthalten.

**Anlassbezogene Klärungen im Berichtszeitraum (#2895).** Der Abruf nimmt einen Berichtszeitraum
(`from`, `to`) aus ganzen Kalendermonaten, ohne Angabe das laufende und das vorige Kalenderjahr.
Ausgewiesen werden nur abgeschlossene Monate: Reicht der Zeitraum in den laufenden Monat oder
später, endet er mit dem letzten abgeschlossenen Monat, und die Auskunft nennt dieses Ende; ein
Zeitraum ohne abgeschlossenen Monat wird abgewiesen. Der ausgewiesene Zeitraum steht im
Protokollereignis des Abrufs. Je Anlasskategorie nennt die Auskunft, wie viele
Klärungen im Zeitraum beantragt, freigegeben und abgelaufen sind, ohne Personen, Begründungen und
Kennungen der Freigaben. Die Grenzen dieser Zahlen:

- **Ganze Monate.** Gezählt wird je Kalendermonat; ein Zeitraum, der einen Monat anschneidet, wird
  abgewiesen, statt eine Genauigkeit vorzutäuschen, die die Zählung nicht hat. Weil nur
  abgeschlossene Monate hinausgehen, ist der Monat zugleich die feinste zeitliche Auflösung, die über
  einen Fall erkennbar wird: Auch wer alle paar Minuten abruft, sieht einen Antrag frühestens nach
  Ende seines Monats. Gerade bei einem Verdacht auf unbefugten Zugriff kann die Systemverwaltung
  selbst Gegenstand der Klärung sein; sie soll aus der Auskunft nicht erfahren, dass eine läuft.
- **Kleine Zahlen.** Eine Zahl von 1 oder 2 erscheint als „weniger als 3". In einem kleinen Haus
  kann schon die Angabe „eine Klärung wegen Verdachts auf unbefugten Zugriff im März" mit
  Flurwissen auf eine Person deuten; der Auszug geht über die Personalvertretung hinaus in
  Anlagen und Akten. Die Schwelle schützt die einzelne Auskunft, nicht gegen gezieltes Abfragen:
  Aus zwei überlappenden Zeiträumen lässt sich die Zahl eines Monats erschließen. Dagegen steht,
  dass jeder Abruf mit seinem Zeitraum im Revisionsprotokoll steht und nur die Systemverwaltung
  ihn auslösen kann.
- **Keine Ablehnung.** Einen Zustand „abgelehnt" gibt es nicht (ein nicht gewollter Antrag wird nicht
  freigegeben und mit seiner Frist gelöscht); die Auskunft erfindet ihn nicht. „Beantragt" minus
  „freigegeben" ist keine Zahl abgelehnter Anträge, weil Antrag und Freigabe in verschiedene Monate
  fallen können.
- **Abgelaufen** heißt: Die Nutzbarkeit einer Freigabe (30 Tage) endete im Zeitraum. Weil nichts
  eine Freigabe widerruft oder verlängert, steht ihr Ende bei der Freigabe fest und wird dann
  gezählt; in einem abgeschlossenen Monat ist jedes so gezählte Ende eingetreten.
- **Ablösung der Jahreszählung.** Die monatliche Zählung ersetzt die jährliche Zählung der
  Freigaben (#2859); diese wird nicht mehr geschrieben und entfällt mit einem Contract-Changeset im
  Folgerelease. Fälle vor Einführung der Monatszählung werden nicht nachgerechnet: Vor `v0.1.0`
  gibt es keine Bestandsinstallationen, deren Zahlen zu übernehmen wären. Aus demselben Grund
  hingenommen: Beim Rolling Update auf die Version mit der Monatszählung fehlen dort Anträge und
  Freigaben, die die noch laufende Vorgängerversion annimmt; das betrifft nur Updates vor `v0.1.0`.
- **Angehaltene Chat-Löschungen (#3026).** Neben den Klärungen nennt die Auskunft, wie oft die
  Systemverwaltung im Zeitraum die Löschung der Chats eines gesperrten Kontos angehalten hat, je
  Kalendermonat ohne Person gezählt und mit derselben Schwelle. Eine Klärung, die die Löschung
  aufhält, zählt dort nicht mit; sie steht in den Zahlen der Klärungen. Wie bei der Monatszählung
  der Klärungen fehlen beim Rolling Update auf diese Version Anhaltevorgänge der noch laufenden
  Vorgängerversion; das betrifft nur Updates vor `v0.1.0`.

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
- **Ausgehende Verbindungen zu eingetragenen Adressen werden gegen interne Netze geprüft.** Die
  Adressen von Datenquellen (eingetragen von Bibliotheksanlegern und -verwaltern), von
  Identitätsanbietern samt Schlüsselverzeichnis und der Verwaltungsschnittstelle des
  Verzeichnisabgleichs (beide nur Systemverwaltung) werden vor dem Abruf aufgelöst und gegen private
  und lokale Adressbereiche geprüft; Ausnahmen stehen in einer Allowlist. Die Adresse eines
  Sprachmodells prüft eine eigene Adressprüfung mit eigener Allowlist beim Speichern, Testen und
  Abfragen, nicht bei jedem Aufruf. **Gegen DNS-Rebinding** bindet jede Prüfung den Namen kurz an
  die geprüften Adressen; Quellen und Zugänge prüfen vor jeder Verbindung. Gespeicherte Ziele, mit
  denen OPAA ohne eigene Prüfung verbindet (Modelle, Identitätsanbieter, Mailserver), bleiben bis
  zum Neustart bewacht: Danach geht keine Verbindung an eine Adresse in einem gesperrten Bereich;
  jede Instanz prüft sie nach dem Start einmal. TLS prüft weiter den Namen
  ([ADR-0063](../decisions/0063-bindung-gepruefter-namen-gegen-dns-rebinding.md)). Hinter dem
  Ausgangsproxy löst der Proxy auf; interne
  Ziele dort zu sperren ist Pflicht des Betriebs, zusätzlich empfiehlt sich die Beschränkung der
  ausgehenden Verbindungen auf Netzebene (Egress-Regeln oder ein Proxy mit Zielliste).
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

Maßgeblich ist **C5:2026**: Die Fassung ist seit April 2026 final und für Prüfzeiträume ab dem
1.6.2027 verbindlich (Recherche vom 13.10.2026 in Epic #2542). An der Abgrenzung ändert die neue
Fassung nichts; testiert wird weiterhin der Anbieter eines Cloud-Dienstes. Für Behörden, die OPAA über
einen solchen Anbieter beziehen, ordnet die Vorlage
[C5:2026 für das Betriebsmodell Cloud-Anbieter](../behoerden/06-informationssicherheit/c5-cloud-betrieb.md)
die Prüfbereiche Produkt und Anbieter zu.

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
der Prüfumfang, den Betreiber und Prüfstelle vereinbaren. Die Bereiche folgen der Gliederung von
C5:2020; ob C5:2026 Bereiche neu zuschneidet, ist vor einer Prüfungsplanung am Katalog selbst zu prüfen.

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

**Geänderte Zusage gegenüber der Personalvertretung**
([ADR-0039](../decisions/0039-ein-katalog-und-ausdrueckliche-space-zuordnung.md), Entscheidung 6):
Zurückgenommen ist die weitergehende Zusage, ein **Raum**, in dem jemand allein arbeitet, stehe fachlich
nie schlechter da als ein gemeinsamer, weil er alles Lesbare durchsuche. Jeder Space durchsucht genau,
was ihm zugeordnet ist; wer allein arbeitet, ordnet sich das benötigte Wissen selbst zu. Die
Gleichwertigkeit privater und geteilter Chats **im selben Space** und die Unbeobachtetheit privater
Inhalte bleiben unverändert. Die Änderung ist der Personalvertretung als geänderte Zusage vorzulegen.

Geschützt ist der **Inhalt**, nicht die Tatsache der Nutzung: Dass jemand arbeitet, wird protokolliert;
was er schreibt, nicht. Das gehört ausgesprochen, damit die Auskunft an die Beschäftigten stimmt.

### 2. Einen personenbezogenen Auswertungspfad gibt es nicht

> **Es gibt keine Schnittstelle und keine Oberfläche, die Nutzungs-, Chat- oder Herkunftsdaten nach Person
> filtert, gruppiert oder sortiert. Diese Funktion ist nicht abschaltbar vorhanden — sie existiert
> nicht.**

**Nicht abgeschaltet, sondern nicht gebaut.** Eine Funktion, die es nicht gibt, kann niemand einschalten.
Für eine Dienstvereinbarung ist das der Unterschied zwischen einer Zusage und einer Tatsache — und
zwischen „heute ist es aus" und „morgen ist es an, mit rückwirkend auswertbaren Daten von gestern".

Zwei Wege von den Daten zu einer Person bleiben notwendigerweise offen; keiner ist ein
Auswertungspfad, mit dem jemand andere Beschäftigte auswerten könnte:

- **Selbstauskunft** der betroffenen Person — ihr eigenes Auskunftsrecht, nicht delegierbar. Sie ist
  die **einzige Ausnahme, die der Person selbst offensteht, und nur über sie**: Personeneinstieg und
  Vollabzug, sonst ausgeschlossen, nie für andere; die Person ist die Identität der Anfrage, kein
  Filter, den jemand setzen könnte ([Selbstauskunft](#selbstauskunft-stufe-1)).
- **Anlassbezogene Klärung eines Sicherheitsvorfalls** — im **Vier-Augen-Prinzip unter Beteiligung der
  Personalvertretung**, mit dokumentiertem Anlass und eigenem Protokolleintrag über den Zugriff. Ein
  Produkt ohne jede Möglichkeit, einen Vorfall aufzuklären, wäre nicht betreibbar; eines, in dem diese
  Aufklärung der Normalweg ist, wäre nicht zustimmungsfähig.

Die anlassbezogene Klärung ist **inhaltlich begrenzt, nicht nur formal** — sie ist der einzige Weg
**für Dritte** von den Daten zu einer Person, und alles, was jemand wissen will, drückt künftig durch dieses eine Nadelöhr:

1. **Zweckausschluss.** Nicht verfügbar für arbeitsrechtliche, disziplinarische und leistungsbezogene
   Fragen — auch nicht bei Mischsachverhalten.
2. **Umfangsbegrenzung vorab.** Person, Zeitraum und Zweck werden vor der Freigabe festgelegt und
   begrenzen die Abfrage **technisch**. Sonst klärt man einen Vorfall vom Mai und liest dabei zwei Jahre.
3. **Unterrichtung der betroffenen Person** nach Abschluss, mit Anlass und Umfang — außer die Klärung
   richtet sich gegen einen Dritten.
4. **Bericht an die Personalvertretung** über Zahl der Fälle und Anlässe in Kategorien, ohne Namen,
   für einen wählbaren Berichtszeitraum ([Auskunft über die Datenerhebung](#auskunft-über-die-datenerhebung)).

### 3. Keine Ranglisten

Kein Vergleich einzelner Beschäftigter, keine Bestenlisten, keine Aktivitätsbewertung — **auch nicht als
spielerisches Element**. Der spielerische Rahmen ändert nichts an der Datengrundlage; er macht die
Auswertung nur geselliger.

Statistiken sind **aggregiert** je Organisationseinheit. Unterhalb einer Mindestgruppengröße wird der Wert
**unterdrückt statt angezeigt** — nicht gerundet, nicht anonymisiert dargestellt, sondern nicht
ausgegeben. Das Produkt setzt eine Voreinstellung und erzwingt eine Untergrenze; die angemessene Zahl
folgt aus dem tatsächlichen Zuschnitt der Einheiten und gehört in die Dienstvereinbarung. Für die
Auswertung **je Asset** gilt eine strengere Fassung, weil ein Asset oft nur eine Handvoll Nutzende hat:
Voreinstellung „aus", Schalter an der **Erhebung** statt an der Anzeige, und die Schwelle bemisst sich an
der Zahl der nutzenden Personen — unterhalb davon wird nicht erhoben (siehe
[spaces-and-assets.md](./spaces-and-assets.md#nutzungstransparenz)).

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
- **Behördenunterlagen:** Fakten-Manifest, Update-Paket und Vorlagen für Datenschutz, Mitbestimmung und
  Informationssicherheit machen die Eigenschaften dieses Dokuments für eine Behörde je Version
  belegbar → [behoerdenunterlagen.md](./behoerdenunterlagen.md)

---

## Wirkung auf Behördenunterlagen

Erfasst ist hier die [Selbstauskunft](#selbstauskunft-stufe-1) (Epic #2986); die übrigen Teile dieses
Dokuments sind vor Einführung des Abschnitts entstanden.

- [x] Neue oder geänderte personenbezogene Daten oder Protokollereignisse — Datum `self-disclosure`
  („letzter Export“, Zähler des Tages, Reservierung, letztes Ergebnis); `SELF_DISCLOSURE_EXPORT_STARTED`,
  `SELF_DISCLOSURE_EXPORT_FINISHED`, `SELF_DISCLOSURE_SETTINGS_CHANGED`; neue Benachrichtigungsart
  und Mail
- [x] Neue Sichtbarkeit: Daten werden für weitere Rollen oder Personen sichtbar — die Person sieht
  Protokolleinträge über sich, ihr Verbindungsprotokoll und ihre Sitzungsdaten, die bisher nur
  `AUDITOR` oder niemand über die Oberfläche sah
- [x] Neue Auswertung oder neuer Export, auch von bereits gespeicherten Daten — die Selbstauskunft
  als Vollabzug der eigenen Daten, nur für die Person selbst
- [ ] Geänderte Aufbewahrungs- oder Löschfrist
- [ ] Neuer externer Datenfluss (neuer Dienst, neues Ziel ausgehender Verbindungen)
- [ ] Geänderte Voreinstellung, geändertes Standardmodell oder geänderter System-Prompt
- [ ] Geänderte KI-Einstufung (Zweck oder Einsatzbereich im Sinne der KI-Verordnung)
- [ ] Keine Wirkung auf Daten, Sichtbarkeit, Auswertung, Datenflüsse oder Voreinstellungen

**Voraussichtliche Stufe:** Zustimmung (Z1, Z3; Z2 nicht, weil niemand mehr über **andere**
Personen sieht); das Stakeholder-Review mit Personalrat und Betrieb
ist erledigt (Kommentar am Epic #2986 vom 23.10.2026).

**Betroffene Vorlagen in `docs/behoerden/`:** `04-datenschutz/` (Betroffenenrechte,
Informationspflichten), das Faktenblatt Datenkatalog aus dem Fakten-Manifest und die
Muster-Dienstvereinbarung (#2566) mit den Bedingungen des Personalrats: Vorlageverbot mit
Verwertungsverbot, keine Auswertung der Ausübung, Offenlegung im Auszug, kein Export durch Dritte;
eine Stufe 2 wäre eine neue mitbestimmungspflichtige Änderung.

---

## Offene Fragen

- **Zweiter Ort für die Monatssiegel** der Prüfsummenkette außerhalb des Sicherheitsmonitorings, etwa
  eine Ablage der Behörde, die die Einträge `AUDIT_LOG_MONTH_SEALED` aufbewahrt; und eine Ansicht des
  Prüfergebnisses in der Oberfläche der Revision.
- Verlangt eine C5-Prüfung die Netzadresse im Protokollsatz zwingend? Falls ja, ist die Ausnahme
  schriftlich zu begründen.
- Wie wird die technische Trennung der Auswertungswege für Revision und Leitung konkret durchgesetzt —
  über getrennte Rollen, getrennte Endpunkte oder getrennte Bestände?
- Wie wird die vorab festgelegte Umfangsbegrenzung der anlassbezogenen Klärung technisch erzwungen?
- Ab wann und in welchem Umfang erfolgt die unabhängige Sicherheitsprüfung?
- Wie weit reicht der Bestandsexport für einen Betreiberwechsel — Rohbestände, Assets, Konfiguration,
  Protokolldaten?
- **Selbstauskunft Stufe 2** (Export durch oder über die Datenschutzstelle) — zurückgestellt bis zu
  einem echten Fall. Gegenvorschlag aus der Analyse an #798: Die Datenschutzstelle stößt an, zugestellt
  wird nur an die Person, die Stelle erhält nur einen Nachweis der Zustellung. Wäre eine neue
  mitbestimmungspflichtige Änderung.
- **Kategorienauswahl der Selbstauskunft** (z. B. Export ohne Chats) — nicht Teil von Stufe 1; würde
  die Größe begrenzen, macht aber Zählweise und Vollständigkeitsaussage der Übersicht komplizierter.
- **Aufteilung großer Kategorien der Selbstauskunft nach Monaten** (Chats, Protokoll,
  Benachrichtigungen; je Monat eine JSON-Datei) — mögliche Erweiterung, falls Rückmeldungen aus
  Behörden zeigen, dass einzelne Dateien beim Vielnutzer nicht mehr handhabbar sind.

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
  kein Protokollsatz auf eine Person zurückführbar, und kein Satz wurde verändert — ausgenommen über
  eine Freigabe der anlassbezogenen Klärung, die die Person nennt, bis zu deren Löschfrist.
- **Einführungsreife:** Das Nachweispaket ist vor der ersten Prüfung vollständig vorlegbar — gemessen
  daran, wie viele Rückfragen einer Prüfstelle es nicht schon beantwortet.

---

## Verwandte Dokumente

- [Identität, Rechte & Mandanten](./access-control.md) — Anmeldung, Kontenlebenszyklus, Systemverwaltung
- [Spaces, Assets & Zugangskontrolle](./spaces-and-assets.md) — Rechtemodell und Mitbestimmung im Detail
- [Monitoring, Kosten & Governance](./monitoring-and-governance.md) — was ausgewertet werden darf
- [Produktvision](../VISION.md) — Einordnung in die Themenbereiche und Phasen
