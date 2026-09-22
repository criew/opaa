# Identität, Rechte & Mandanten: Systemverwaltung, Anmeldung und Kontenlebenszyklus

> **Status: Entwurf — wesentliche Festlegungen stehen, einzelne Fragen sind offen.**
>
> **Phasenlage:** Phase 1. Anmeldung über den Verzeichnisdienst, Kontenlebenszyklus, Gruppen als
> Rechtesubjekt und rechtebewusste Suche gehören zum Fundament; ohne sie ergibt ein Start in einer
> Behörde keinen Sinn. Feinschliff an Sitzungsverwaltung und Rezertifizierung folgt in Phase 2.

> **Abgrenzung:** Das Space-, Asset- und Rechtemodell ist in
> [Spaces, Assets & Zugangskontrolle](./spaces-and-assets.md) beschrieben und entschieden und wird hier
> **nicht wiederholt**. Die Sicherheits-, Nachweis- und Prüfbarkeitsthemen — revisionssicheres
> Protokoll, Rechtehistorie, DSGVO-Vollständigkeit, C5-Fähigkeit, Mitbestimmungsfähigkeit — stehen in
> [Sicherheit, Nachweis & Prüfbarkeit](./security-and-compliance.md). Dieses Dokument behandelt
> Systemverwaltung, Identität und den Lebenszyklus der Konten. Das frühere Workspace-Konzept ist
> abgelöst.

## Motivation

Nicht alles Organisationswissen ist für jeden bestimmt. Ein Haus hat öffentliche Richtlinien,
fachbereichsbezogene Dokumentation, besonders geschützte Bestände und Unterlagen, die nur der Revision
zugänglich sind.

Wer was sehen darf, regelt das Rechtemodell in
[Spaces, Assets & Zugangskontrolle](./spaces-and-assets.md). Wer das System verwaltet, woher Identitäten
kommen, wie sie entstehen und wie sie wieder verschwinden, regelt dieses Dokument. Der Lebenszyklus ist
dabei der sicherheitskritische Teil: Ein Konto, das nach dem Ausscheiden weiterbesteht, ist ein
Zugangsweg, den niemand mehr beobachtet.

---

## Überblick

Die Zugangskontrolle in OPAA hat vier Schichten:

1. **Asset-Rechte** — wer auf Wissensbibliotheken, Agenten und Prompt-Bibliotheken zugreift →
   [Spaces, Assets & Zugangskontrolle](./spaces-and-assets.md)
2. **Space-Rollen** — wer in einem Arbeitsraum mitarbeitet, kuratiert und verwaltet → ebenda
3. **Systemverwaltung und Revision** — wer das System als Ganzes verwaltet, wer das Protokoll
   auswerten darf und welche beiden Befugnisse neben den Rollen stehen → dieses Dokument
4. **Identität und Kontenlebenszyklus** — woher Nutzer kommen, wie sie sich anmelden und wie ihr Zugang
   endet → dieses Dokument

Die fünfte Schicht — der **Nachweis**, dass diese vier Schichten gewirkt haben — steht in
[Sicherheit, Nachweis & Prüfbarkeit](./security-and-compliance.md).

---

## Systemverwaltung

### System-Admin-Rolle

Über den Space- und Asset-Rollen steht die **System-Admin**-Rolle für organisationsweite Administration.
Sie ist eine systemweite Rolle und wird auf der Benutzer-Entität gespeichert.

System-Admins können:

- Spaces im Namen einer anderen Person anlegen — alle übrigen Spaces legen Nutzer selbst an
  (der Standard-Space entsteht automatisch bei der ersten Anmeldung); Löschbefugnis regelt der
  Space-Verantwortliche, siehe [Löschung eines Space](#löschung-eines-space). Eine Gruppe als
  Space-Mitglied aufzunehmen ist dagegen keine System-Admin-Befugnis, sondern eine Handlung der
  `ADMIN`-Mitglieder des Space selbst
- Konnektoren konfigurieren
- Quell-Zuordnungen definieren — welche Quelle in welche Wissensbibliothek indiziert
- Die Freigabe-Obergrenze konnektor-gespeister Bibliotheken setzen
- Benutzerverzeichnis-Synchronisation und Gruppen konfigurieren
- Globale Modell-Policies und Governance-Einstellungen setzen
- Assets im Zustand „Nachfolge offen" einer neuen Zuständigkeit zuweisen

**System-Admins existieren je Organisation.** Die Mandantengrenze gilt auch für sie; es gibt keine
organisationsübergreifende Sicht.

Wichtige Abgrenzung: System-Admins verwalten das System, sind aber **nicht automatisch berechtigt, jeden
Inhalt zu lesen**. Der Zugriff auf Wissensbibliotheken folgt der Rechteliste des Assets. Wo eine Übernahme
nötig ist (offene Nachfolge, Offboarding), ist sie ein protokollierter Verwaltungsakt und keine
stillschweigende Leseberechtigung. Private Inhalte bleiben auch dabei unlesbar — in jedem Space.

Wie weit diese Abgrenzung im gebauten Stand trägt und wo sie es heute **nicht** tut, steht unten unter
[Verwalten ist nicht Lesen](#verwalten-ist-nicht-lesen-die-asymmetrie-bei-wissensbibliotheken).

### Die Revisionsrolle `AUDITOR`

Neben `SYSTEM_ADMIN` trägt die Benutzer-Entität eine zweite systemweite Rolle: **`AUDITOR`** (gebaut,
#393/#394). Ein Konto hält genau einen der drei Werte `USER`, `SYSTEM_ADMIN` oder `AUDITOR` — die Rollen
sind Alternativen, nicht Stufen.

`AUDITOR` öffnet ausschließlich den Revisionsweg, und der besteht aus drei Teilen:

- die **vier begrenzten Abfragen** auf das Protokoll,
- den **Vorgang der anlassbezogenen Klärung** (beides siehe
  [Zugriffswege](./security-and-compliance.md#zugriffswege-was-es-gibt-und-was-es-nicht-gibt)),
- und das **Gesamtprotokoll der Suchdiagnosen**: Zeitraumabfrage und Einzelsatzansicht des
  Diagnose-Protokolls stehen ebenfalls allein `AUDITOR` offen, je mit Pflicht-Anlass und eigenem
  Protokolleintrag auch für den abgewiesenen Abruf (siehe
  [Berechtigungs-Leitplanken](./hybrid-retrieval.md#berechtigungs-leitplanken), Leitplanke (h)).

Sie trägt keine einzige der oben aufgezählten Verwaltungsbefugnisse.

**Die Trennung gilt in beide Richtungen.** `SYSTEM_ADMIN` trägt keinen Lesezugriff auf das Protokoll: Ein
Leseversuch der Systemverwaltung wird abgewiesen — und der abgewiesene Versuch wird selbst protokolliert.
Wer das System verwaltet, wertet die Spur seines eigenen Verwaltungshandelns nicht aus.

Ist bei einem OIDC-Anbieter ein Rollen-Claim gesetzt, ist der Anbieter für beide Rollen führend; die
manuelle Rollenvergabe ist für Konten dieses Anbieters dann gesperrt (siehe
[Claim-Zuordnung je Anbieter](#übergabe-eines-lokalen-kontos-an-eine-anbieteridentität-gebaut-1563)).

### Zwei Befugnisse neben den Rollen

Zwei Zugriffe stehen **neben** dem Rollenmodell und werden einzeln, benannt und befristet vergeben. Sie
sind eigene Tabellenzeilen, keine Rolleneigenschaft:

| Befugnis | Ablage | Wer vergibt | Technische Grenze |
|---|---|---|---|
| **„Sicht als"** — eine Suchdiagnose im Rechtekontext einer anderen Person ausführen | `diagnostic_impersonation_grants` | die Systemverwaltung | Geltungsbereich ist genau eine Gruppe, die der Dienst auf `ORG_UNIT` einschränkt; Laufzeit höchstens zwölf Monate je Vergabe, als `CHECK` in der Datenbank |
| **Vorfallsbereich** — die anlassbezogene Klärung mit Personenfilter | `audit_incident_scope_grants` | zwei verschiedene `AUDITOR`-Konten, eines beantragt, ein anderes gibt frei | Vier-Augen-Prinzip als `CHECK` in der Datenbank; Person, Zeitraum und Zweck vorab festgelegt; die Freigabe ist 30 Tage nutzbar |

> **`SYSTEM_ADMIN` schließt keine von beiden ein**, und die beiden schließen einander nicht ein.

Im Einzelnen:

- **„Sicht als":** Die Systemverwaltung *erteilt* die Befugnis, hält sie dadurch aber nicht. Die Prüfung
  beim Ausführen sieht ausschließlich die Zeilen der Vollmachtstabelle an und kennt keinen Rollenzweig —
  für keine Rolle. Ein Administrator ohne eigene Vollmacht wird abgewiesen wie jeder andere. Die
  Bibliotheksmenge des fremden Kontexts entsteht dabei aus derselben Formel wie sonst, nur für die
  Zielperson bzw. die Gruppe des Rechteprofils. Die **eine** Rollenverzweigung auf diesem Weg betrifft
  den Profilkontext: Dass ein Rechteprofil keine Bibliothek umfassen darf, die die ausführende Person
  nicht selbst einsehen darf, wird für einen `SYSTEM_ADMIN` nicht geprüft — dieselbe Asymmetrie wie
  unter [Verwalten ist nicht Lesen](#verwalten-ist-nicht-lesen-die-asymmetrie-bei-wissensbibliotheken).
- **Vorfallsbereich:** Antrag, Freigabe und Abfrage stehen allein `AUDITOR` offen; die Systemverwaltung
  erreicht diesen Weg gar nicht.
- **Zueinander:** Wer diagnostiziert, wertet nicht das Protokoll aus, in dem seine Diagnose steht. Die
  Begründung steht bei den
  [Berechtigungs-Leitplanken](./hybrid-retrieval.md#berechtigungs-leitplanken), Leitplanke (c).

### Fähigkeiten: die installationsweiten Anlegerechte

Neben den Rollen und den beiden Befugnissen steht eine dritte Art von Recht: die **Fähigkeit** (in der
Oberfläche **Anlegerecht**) — das unbefristete Recht, etwas *anzulegen*. Sie wird an eine Person, eine
Gruppe oder an **„Alle Konten"** vergeben, hat keinen Gegenstand und öffnet **nie** einen Inhalt.
Festgelegt in [ADR-0036](../decisions/0036-berechtigungsmodell-gruppen-und-faehigkeiten.md),
Entscheidung 5, die damit ADR-0018, Entscheidung 6 samt Nachtrag ablöst.

| Fähigkeit | Was sie öffnet | Ausgeliefert an |
|---|---|---|
| `CREATE_SPACE` | einen Space anlegen | Alle Konten |
| `CREATE_LIBRARY` | eine Bibliothek für Uploads anlegen | Alle Konten |
| `CREATE_CONNECTOR_LIBRARY` | eine Bibliothek mit Konnektor anlegen (Dateisystem, Webverzeichnis, Feed, Confluence, S3) | Alle Konten |
| `CREATE_INTERNAL_GROUP` | eine interne Gruppe anlegen | niemanden |

> **`CREATE_INTERNAL_GROUP` wirkt seit #1814.** Wer sie hält, legt eine interne Gruppe an und wird
> deren erster Verantwortlicher — ohne Systemrolle und ohne Ticket. Die Verwaltungsübersicht nennt
> in ihrer Klartextzeile, wer sie hält; ausgeliefert ist sie an niemanden, die Öffnung ist eine
> Entscheidung des Hauses.

- **Der ausgelieferte Zustand ist der heutige.** Nach der Migration legt jedes Konto Spaces und
  Bibliotheken genau wie vorher an; interne Gruppen bleiben der Systemverwaltung vorbehalten,
  solange niemand `CREATE_INTERNAL_GROUP` hält. Wer einschränken will, entzieht „Alle Konten" und
  erteilt einer benannten Gruppe.
- **`CREATE_CONNECTOR_LIBRARY` ist eine eigene Fähigkeit**, weil Konnektorbibliotheken Serverpfade und
  Zugangsdaten erreichen und eine Freigabe-Obergrenze tragen (#797) — der erste Kandidat, den ein Haus
  nach der Migration auf eine benannte Gruppe einschränkt.
- **Dieselbe Fähigkeit gilt auch objektlos, vor der Anlage** (#1856): `POST
  /api/v1/libraries/source-test` ohne `libraryId` sowie die beiden Auswahl-Endpunkte
  `POST /api/v1/libraries/confluence/spaces` und `POST /api/v1/libraries/s3/buckets` ohne
  `libraryId` sondieren Serverpfade und Zugangsdaten, ohne dass schon eine Bibliothek existiert, an
  der eine Rolle geprüft werden könnte — sie verlangen deshalb dasselbe Anlegerecht wie das Anlegen
  selbst. Mit `libraryId` bleibt es bei der bestehenden `MANAGER`-Schranke der Bibliothek; die
  Pfad-Allowlist und die Zielprüfung bleiben in beiden Fällen unverändert unabhängig von der
  Berechtigung.
- **Die Auswertung** lautet: `SYSTEM_ADMIN` **oder** Erteilung an „Alle Konten" **oder** an das Konto
  **oder** an eine seiner Gruppen. `SYSTEM_ADMIN` besitzt damit jede Fähigkeit implizit; **die Rolle
  `AUDITOR` verleiht keine** — sie ist ein Lesepfad in das Protokoll und sonst nichts.
- **Keine Fähigkeit öffnet einen Inhalt.** Insbesondere verändert keine von ihnen die Menge der
  lesbaren Bibliotheken; der fehlende Systemverwalter-Durchgriff in der Suche bleibt, wie er ist
  (siehe [Verwalten ist nicht Lesen](#verwalten-ist-nicht-lesen-die-asymmetrie-bei-wissensbibliotheken)).
- **Der persönliche Space** entsteht bei der ersten Anmeldung unabhängig von `CREATE_SPACE`: das ist
  Bereitstellung, kein Anlegen.
- **Fehlt die Fähigkeit, erklärt die Antwort statt zu verschweigen:** `403` mit dem Code
  `CAPABILITY_REQUIRED`, dem Namen des fehlenden Anlegerechts und dem Hinweis, an wen man sich wendet.
  `GET /api/v1/me/capabilities` liefert der Oberfläche die eigenen Fähigkeiten, damit sie das schon vor
  dem Versuch sagen kann.
- **Der Entzug wirkt ohne Neuanmeldung.** Die Fähigkeit wird je Anfrage aus der Datenbank ausgewertet.
  Diese Zusage trägt, weil [ADR-0021](../decisions/0021-single-instance-betrieb.md) einen einzigen
  Prozess voraussetzt; fällt diese Annahme, fällt die Zusage mit.
- **Vergabe und Entzug sind Governance-Ereignisse** (`CAPABILITY_GRANTED`/`CAPABILITY_REVOKED`) und
  bekommen zusätzlich ein Intervall in `capability_grant_history` — die Stichtagsauskunft „wer durfte
  am 3. März Konnektorbibliotheken anlegen" ist damit innerhalb der Aufbewahrungshöchstdauer
  beantwortbar. Der Entzug einer Fähigkeit von „Alle Konten" ändert die Arbeitsbedingungen aller
  Beschäftigten und ist deshalb kein technisches Ereignis unter vielen.
- **Verwaltet wird die Fähigkeit von `SYSTEM_ADMIN`** (`/api/v1/admin/capabilities`). Eine Fähigkeit zu
  *halten* heißt nicht, sie *vergeben* zu dürfen.
- **Was keine Fähigkeit ist:** „Space organisationsweit sichtbar machen", „Bibliothek organisationsweit
  freigeben" und „Fremdzugang freigeben" sind Reichweitenentscheidungen am Objekt und bleiben bei
  `MANAGER`/`OWNER`. „Sicht als" und Vorfallsbereich bleiben Befugnisse und werden nie Fähigkeiten.

### Verwalten ist nicht Lesen: die Asymmetrie bei Wissensbibliotheken

Die Abgrenzung oben — Systemverwaltung ist nicht automatisch Leseberechtigung — ist im Code als
Asymmetrie zwischen zwei Prüfwegen umgesetzt, und die wird ohne Erklärung leicht als Widerspruch
gelesen.

Auf dem Weg der **einzelnen Bibliothek** gilt die Systemverwaltung als `OWNER`: Sie kann jede Bibliothek
ansehen, umbenennen, ihre Sichtbarkeit ändern, Rechte darauf vergeben — und auch ihre Dokumente öffnen
und herunterladen, wobei dieser Zugriff heute **keinen Protokolleintrag** erzeugt: Die Auslieferung der
Originaldatei schreibt kein Ereignis, und eine Ereignisart für das Lesen eines Dokuments gibt es nicht,
während [Verwaltungsaktionen](./security-and-compliance.md#verwaltungsaktionen-und-agentenaktionen) dort
als protokollpflichtig zugesagt sind (offen in
[#1828](https://github.com/criew/opaa/issues/1828)). Die Abgrenzung oben ist an dieser Stelle also enger
formuliert, als der gebaute Stand sie hält; die Fassung des Rechtemodells ist Gegenstand von Epic #1295.

Auf dem Weg der **Suche** gilt das nicht. Die Menge der lesbaren Bibliotheken wird allein aus Grants,
Gruppenmitgliedschaften und organisationsweiter Sichtbarkeit gebildet — ohne Ausnahme für die
Systemverwaltung.

Ein Administrator darf also jede Bibliothek verwalten, ruft in einem Chat aber nur aus denen ab, die ihm
tatsächlich zugestanden wurden (die Formel steht unter
[Rechte an einem Asset erhalten](./spaces-and-assets.md#rechte-an-einem-asset-erhalten)). Die Asymmetrie
zeigt damit dort in die sichere Richtung, wo es auf die Antwort ankommt: Nichts, was ein Administrator in
einer Antwort zu lesen bekommt, kann aus einer Bibliothek stammen, auf die er keinen Grant hat. Ein
Zugriff über den Verwaltungsweg bleibt dagegen ein einzelner, gezielter Aufruf und reichert seine
Suchtreffer nicht stillschweigend an.

Die **Bibliotheksliste** folgt dabei der Suchformel, nicht dem Verwaltungsweg: Ein Administrator sieht
dort nur, was ihm zugestanden wurde. Einzeln aufrufen und verwalten kann er trotzdem jede Bibliothek.

### Dokumentenfluss: Konnektoren gegen Benutzer-Uploads

Die zwei Wege, auf denen Dokumente in OPAA gelangen, haben unterschiedliche Autorisierungsanforderungen:

- **Konnektoren (System-Admin):** System-Admins konfigurieren Konnektoren und legen fest, welche Quelle in
  welche Wissensbibliothek indiziert. Der primäre Weg für automatisierte Massenaufnahme.
- **Manuelle Uploads:** Wer an einer Wissensbibliothek mindestens `EDITOR` ist, kann Dokumente hochladen —
  in eine eigene Bibliothek oder in jede andere, an der er dieses Recht hat.

Wesentliche Verschiebung gegenüber dem alten Modell: Der System-Admin entscheidet, **wohin** indiziert
wird; der Bibliotheks-Eigentümer entscheidet, **wer es sieht**.

**Die Freigabe-Obergrenze deckelt, wo sie gesetzt ist, `visibility` und `listed` einer
Konnektorbibliothek — die einzige technische Sicherung dieser beiden Felder gegen eine zu weite
Freigabe durch den Bibliotheks-Eigentümer (gebaut, #797, Maintainer-Festlegung vom 21.09.2026).**
Sie wirkt aber erst, **nachdem** sie gesetzt wurde:

- Gedeckelt werden **ausschließlich `visibility` und `listed`** der Konnektorbibliothek — keine
  Gruppengrößen-Schwelle für Grants (eine frühere Fassung dieses Abschnitts sah eine solche Schwelle
  vor; sie entfällt ersatzlos, mit derselben Begründung wie die gestrichene Größenschwelle bei Grants
  im Allgemeinen, siehe [spaces-and-assets.md](./spaces-and-assets.md#freigabe-an-eine-gruppe-braucht-keine-zustimmung)),
  ohne die Freigabe für Fremdzugänge ([external-access.md](./external-access.md#die-freigabe-der-bibliothek)
  hält fest, warum diese trotz einer früheren, anderslautenden Absicht außen vor bleibt), und ohne
  erteilte Rechte an Personen und Gruppen (`asset_grants`) — ein bestehender Grant bleibt auch nach
  dem Senken der Obergrenze bestehen und muss gesondert zurückgenommen werden.
- Die Systemverwaltung setzt die Obergrenze **je Bibliothek**, nicht installationsweit
  (`PUT /api/v1/libraries/{libraryId}/share-cap`). **Ausgeliefert ist die Obergrenze unrestriktiv**
  (`visibility_cap = ORGANIZATION`, `listed_cap = true`, Migration 069) — eine neu angelegte
  Konnektorbibliothek unterliegt deshalb **keiner** Einschränkung, bis die Systemverwaltung die
  Obergrenze für sie eigens senkt. Die Bibliothek selbst startet dagegen bei der engsten Reichweite
  (`PRIVATE`, `listed = false` als Vorgaben von Formular und Spezifikation); ungedeckelt ist nicht
  die Bibliothek, sondern die **Wahl** des Anlegenden. Da `CREATE_CONNECTOR_LIBRARY` an „Alle
  Konten" ausgeliefert ist (siehe oben), kann zwischen Anlage und Setzen der Obergrenze eine Lücke
  liegen, in der der Anlegende den eingespeisten Bestand bis zur organisationsweiten Stufe heben
  kann. Zwei Wege dagegen, beide betrieblich statt
  technisch: die Obergrenze nach jeder Neuanlage einer Konnektorbibliothek prüfen, oder
  `CREATE_CONNECTOR_LIBRARY` auf eine benannte Gruppe einschränken, sodass nur noch diese Gruppe
  überhaupt anlegen kann. **Ob ein installationsweiter Vorgabewert gebaut wird, auf den `createLibrary`
  jede neue Konnektorbibliothek setzt, ist eine offene Maintainer-Frage** — die Festlegung vom
  21.09.2026 regelt nur, *was* gedeckelt wird und *was beim Senken geschieht*, nicht *je Bibliothek
  vs. installationsweiter Vorgabewert*; dieser PR baut keinen Vorgabewert.
  Ein Bibliotheks-Eigentümer oder `MANAGER` kann `visibility`/`listed` nicht über eine gesetzte
  Obergrenze hinaus anheben — der Versuch scheitert mit `409`, weil die eigene Berechtigung nicht in
  Frage steht, sondern die Anfrage mit der gesetzten Obergrenze kollidiert.
- Wird eine gesetzte Obergrenze **nachträglich gesenkt**, nimmt das System eine bereits
  weitergehende `visibility`/`listed`-Einstellung **sofort zurück** (auf die neue Obergrenze
  geklemmt) — mit Audit-Ereignis (`CONNECTOR_LIBRARY_SHARE_LIMIT_CHANGED` für das Setzen der
  Obergrenze, `ASSET_VISIBILITY_CHANGED` für die dadurch ausgelöste Klemmung) und einer Zeile in der
  Sichtbarkeits-Historie, genauso wie bei einer Änderung durch den Eigentümer selbst. Erteilte
  Rechte an Personen und Gruppen sowie eine bestehende Fremdzugangsfreigabe bleiben unberührt (siehe
  oben) und sind gesondert zu prüfen. Es gibt bewusst **keinen** Zustand „verletzt, aber geduldet" —
  eine frühere Fassung sah ein Aussetzen ohne Entzug vor; das ist mit der Maintainer-Festlegung
  entschieden anders: Die Bibliothek liegt in der Hand der Systemverwaltung, die die Obergrenze
  selbst setzt, sodass ein sofortiges Zurücknehmen keine widersprüchliche Zuständigkeit erzeugt
  (anders als beim Strikt-Space, siehe
  [spaces-and-assets.md](./spaces-and-assets.md#wenn-die-voraussetzung-eines-strikt-space-nachträglich-bricht)).
- `UPLOAD`-Bibliotheken tragen keine Obergrenze (`400` beim Versuch, eine zu setzen): Dieselbe Person
  kuratiert dort ohnehin jedes Dokument einzeln, es gibt nichts, wovor die Obergrenze schützen müsste.
  Da ADR-0018 die gemischte Speisung einer Bibliothek aus Konnektor und manuellem Upload strukturell
  ausschließt (eine Bibliothek trägt genau einen `sourceType`), stellt sich die Frage nach einer
  „ebenfalls gedeckelten" gemischten Bibliothek nicht mehr.

### Löschung eines Space

Ein Space zu löschen ist unter dem neuen Modell ein vergleichsweise harmloser Vorgang: Er vernichtet
**keine Dokumente**, weil diese in Wissensbibliotheken liegen, die anderen gehören. Gelöst werden die
Assoziationen; die Assets selbst bleiben unberührt.

**Space-eigene Inhalte werden dabei nicht gelöscht.** Die Regel „Zurückziehen statt Löschen" gilt auch
hier: Geteilte Chats und Artefakte werden zurückgezogen und bleiben für ihre Autoren und im Nachweis
erhalten; private Inhalte bleiben ohnehin ihren Erstellern erhalten. Andernfalls wäre die Space-Löschung
ein Massenlöschpfad für die Arbeitsspuren fremder Beschäftigter — genau das, was der Schutz an anderer
Stelle ausschließt. Ein Protokolleintrag hält den Vorgang fest.

Löschen darf nur der im Space als Verantwortlicher hinterlegte Nutzer oder ein System-Admin.

---

## Anmeldung und Identität

Benutzer authentifizieren sich über:

- **Single Sign-On (SSO)** — OIDC (empfohlen für den Regelbetrieb; SAML nur über eine Föderation)
- **Lokale Konten** — E-Mail-Adresse und Passwort, von OPAA selbst geführt (optional, Standard aus;
  das Konto der Systemverwaltung ist immer ein lokales Konto)
- **API-Tokens** — persönliche Zugangstokens für programmatischen Zugang und für fremde KI-Werkzeuge
  über den MCP-Server; entschieden am 18.09.2026, noch nicht gebaut. Nur lesend, mit Pflicht-Ablauf,
  installationsweit abschaltbar und nur für Bibliotheken, die dafür ausdrücklich freigegeben sind —
  siehe [external-access.md](./external-access.md)

**Empfohlen ist die SSO-Anbindung an das im Haus vorhandene Identitätsmanagement.** Sie ist nicht nur
bequemer, sondern die Voraussetzung dafür, dass der Kontenlebenszyklus überhaupt an einer Stelle geführt
werden kann. Lokale Konten laufen am zentralen Ausscheideprozess vorbei; jedes dauerhaft betriebene
lokale Konto ist deshalb eine Ausnahme, die begründet und regelmäßig überprüft gehört. OPAA macht das
sichtbar statt es zu verbieten: Jedes lokale Konto trägt einen Anlagegrund (Pflicht, zweckgebunden)
und in der Regel ein Ablaufdatum; die Verwaltung führt die Liste der lokalen Konten mit Filtern
„ohne Ablaufdatum", „länger als 90 Tage nicht genutzt" und „offene Einladungen", erinnert Person und
Systemverwaltung vor einem Ablauf per E-Mail, legt der Systemverwaltung einmal im Quartal die Konten
ohne Ablaufdatum zur Wiedervorlage vor, sperrt lokale Konten nach einer Inaktivitätsfrist automatisch,
und die lokale Benutzerverwaltung ist im Regelbetrieb abgeschaltet.

> **Lokale Benutzerverwaltung** (Epic #1529, Beschluss aus #1368 vom 10.09.2026): Der
> **Erstadministrator ist immer ein lokales Konto**, das beim allerersten Start entsteht (Einmalpasswort
> einmalig im Log, Wechsel bei der ersten Anmeldung erzwungen); damit ist eine Installation nie ohne
> anmeldefähigen Systemverwalter, auch ohne konfigurierten Identitätsanbieter. Die lokale Verwaltung für
> reguläre Konten ist per Schalter zuschaltbar: Anlegen mit Einladung per E-Mail, Sperren, Zurücksetzen,
> Ablaufdatum, Passwort vergessen, optionale Selbstregistrierung — dafür bekommt OPAA eine
> Mail-Infrastruktur (SMTP). Solange die Verwaltung aus ist, melden sich lokale Systemverwalter über eine
> eigene Anmeldeseite an. Die Architektur legt
> [ADR-0033](../decisions/0033-lokale-benutzerverwaltung.md) fest: lokaler Issuer `urn:opaa:local` mit
> der Konto-UUID als Subject, Backend als Aussteller kurzlebiger Access-Tokens mit rotierendem
> Refresh-Cookie und sofortigem Widerruf, lokaler Anbieter als Anbieterzeile, Rate-Limiting je Adresse
> und je Konto mit Sperre nach Fehlversuchen, keine Zusammenführung über die E-Mail (nur eine
> administrativ angestoßene, von der betroffenen Person selbst durch Anmeldung beim Anbieter
> eingelöste Übergabe eines lokalen Kontos an ihre Anbieteridentität). Kein stiller Eingriff in ein
> Konto: Sperre, Entsperrung, Zurücksetzen, bevorstehender Ablauf und Übergabe werden der Person per
> E-Mail mitgeteilt, und eine beendete Sitzung nennt beim nächsten Aufruf ihren Grund.
>
> **Gebaut ist alles davon, die Übergabe eingeschlossen** (#1563); die Abschnitte unten beschreiben
> den Stand. Die Bedienung im Alltag steht im Produkthandbuch, Kapitel „Benutzerverwaltung".

### Aussteller, Tokens und Sitzungen lokaler Konten

Das Backend ist Token-Aussteller für lokale Konten:
`POST /api/v1/auth/local/login` prüft E-Mail-Adresse (groß-/kleinschreibungsunabhängig, nur unter
dem lokalen Issuer `urn:opaa:local`) und Passwort und stellt ein HS256-Access-Token (15 Minuten;
Claims `jti`, `iss`, `sub` = Konto-ID, `iat`, `exp`, `email`, `name`, `pcr`, keine Rolle) sowie ein
rotierendes Refresh-Token im Cookie `opaa_refresh` aus (`HttpOnly`, `SameSite=Strict`, `Secure`
nach `OPAA_AUTH_LOCAL_COOKIE_SECURE`, `Path=/api/v1/auth/local`). Jede abgewiesene Anmeldung —
unbekannte Adresse, falsches Passwort, gesperrtes, abgelaufenes oder eingeladenes Konto,
abgeschaltete Verwaltung — ist dieselbe Antwort mit derselben Antwortzeitklasse (Hash-Vergleich
auch gegen einen Dummy-Hash); der Fehlversuchszähler wird atomar geführt, die Sperre nach fünf
Fehlversuchen steht weiter unten. Anmeldefähig ist nur ein aktives Konto; bei ausgeschalteter
Verwaltung (Schalter = `enabled` der `LOCAL`-Anbieterzeile) nur ein lokaler `SYSTEM_ADMIN`.
`POST …/refresh` rotiert das Cookie innerhalb seiner Familie (Leerlauffrist 7 Tage, absolute
Höchstdauer 30 Tage, für lokale Systemverwalter 4 h / 12 h; keine Rotation verlängert das
Familienende); die erneute Vorlage eines bereits rotierten Tokens widerruft **alle** Familien und
alle Access-Tokens des Kontos (`REUSE_DETECTED`), wird als `LOCAL_SESSION_REVOKED` protokolliert und
als Warnung ins Anwendungslog geschrieben (Konto-ID, nie die Adresse). Zwei gleichzeitige Vorlagen
desselben Tokens gelten als Wiederverwendung — die Oberfläche serialisiert `refresh` (ein Aufruf zur
Zeit). Ein `refresh` für ein nicht mehr anmeldefähiges Konto oder ein reguläres Konto bei
abgeschalteter Verwaltung wird wie ein unbekanntes Token behandelt (`401`, Cookie gelöscht). `POST …/logout` widerruft Familie und das
vorgelegte Access-Token sofort (`jti`-Sperrliste), ohne Protokolleintrag. `refresh` und `logout`
verlangen das CSRF-Double-Submit-Token (Cookie `XSRF-TOKEN`, Header `X-XSRF-TOKEN`; fehlt es:
`403` mit Code `CSRF_TOKEN_MISSING`); kein anderer Endpunkt tut das. `login`, `refresh` und
`logout` werden ohne Bearer-Token aufgerufen — ein abgelaufenes Bearer-Token würde vor dem Handler
mit `401` abgewiesen. `POST …/change-password` (Bearer) prüft das aktuelle Passwort, wendet die
Passwortrichtlinie an (Mindestlänge aus den Einstellungen, höchstens 64 Zeichen/72 Byte, nicht die
eigene Adresse, nicht in der mitgelieferten Sperrliste häufiger Passwörter — Verstöße als
`fieldErrors` mit Codes `TOO_SHORT`, `TOO_LONG`, `EQUALS_EMAIL`, `TOO_COMMON`), setzt alle bis zum
Wechsel ausgestellten Access-Tokens außer Kraft, widerruft alle Refresh-Familien
(`PASSWORD_CHANGED`), protokolliert `LOCAL_PASSWORD_CHANGED` und antwortet wie eine Anmeldung: neues
Token ohne `pcr` und ein neues Refresh-Cookie für die laufende Sitzung. Ein Token mit `pcr = true` erreicht außer `/api/v1/auth/local/*` nichts: jede andere
Anfrage endet mit `403`, Code `PASSWORD_CHANGE_REQUIRED` und dem Anlass (`INITIAL`,
`ADMIN_RESET`, `SECURITY`). Lokale Tokens prüft ein eigener Decoder vor der Anbieter-Registry,
unabhängig von der `LOCAL`-Zeile und ihrem Schalter; jede Abweisung nennt im Header
`WWW-Authenticate` ihren Grund als `error_description`: `local_accounts_disabled`,
`account_locked:<admin|failed_logins|inactivity>`, `account_expired`, `account_not_active`,
`session_revoked[:<admin_lock|password_changed|admin_reset|admin_action|reuse_detected|handed_over>]`,
`unknown_account`, `malformed_token` — die Oberfläche unterscheidet sie so vom abgelaufenen Token
und startet keinen Erneuerungsversuch. Der Schalter der lokalen Verwaltung wird dabei **vor** den
Widerrufsprüfungen gelesen (nur `malformed_token` liegt davor): Das Abschalten widerruft die
Sitzungen regulärer lokaler Konten zwar zusätzlich sofort, abgewiesen wird aber mit
`local_accounts_disabled`, dem Marker aus ADR-0033, Entscheidung 4 (#1595). Zwei Konten passieren
den Schalter und behalten deshalb ihren eigenen Grund: der lokale `SYSTEM_ADMIN` und das
**übergebene** Konto, das den Anbieter-Issuer trägt und über das der Schalter der *lokalen*
Verwaltung nicht bestimmt — es liest weiter `session_revoked:handed_over` und damit den einen Weg,
den es noch hat. Bei allen übrigen verdeckt der Schalter den Grund dahinter (gesperrt, abgelaufen,
unbekanntes Subjekt): Diese Konten kommen bei abgeschalteter Verwaltung ohnehin nicht herein, und
für ein unbekanntes Subjekt ist die Verdeckung die aufzählungsfreundlichere Antwort. Die beiden
Verwaltungsanlässe sind getrennt: `admin_reset` nennt das Zurücksetzen eines Passworts (über die
Admin-API und über den Wiederanlauf des Notanker-Kontos, der ebenfalls eines setzt),
`admin_action` den Verwaltungsakt ohne Passwortwechsel — heute das Abschalten der lokalen
Verwaltung. Nur ein anmeldefähiges (`ACTIVE`) Konto passiert den Validator; der Anlass von
`session_revoked` ist der letzte Verwaltungsakt an den Refresh-Familien des Kontos. Der lokale
Issuer legt nie ein Konto an (unbekanntes `sub` → `401`) und schreibt E-Mail und Anzeigename nicht
aus dem Token zurück. `GET /api/v1/auth/config` führt die
`LOCAL`-Zeile nicht unter `providers`, sondern als `localAccounts { enabled,
selfRegistrationEnabled, passwordResetEnabled, passwordMinLength }`; die beiden Selbstbedienungs-
flüsse gelten nur mit gesetzter öffentlicher Basis-URL als verfügbar. Ein täglicher Lauf löscht
Zeilen der drei Token-Tabellen spätestens sieben Tage nach Ablauf oder Widerruf; Inaktivitätssperre
und Ablauf-Erinnerungen hängen sich dort ein. Aussteller, Anmelde-Endpunkte und
`pcr`-Filter existieren nur im `oidc`-Betriebsmodus; die Verwaltungs-Endpunkte lokaler Konten und
der Schalter (#1537) sind profilunabhängig, damit die Oberfläche und die E2E-Suite im
`dev`-Modus laufen — ein dort angelegtes lokales Konto kann sich mangels Aussteller nicht anmelden.

### Sitzungsführung in der Oberfläche

Die Oberfläche kennt zwei Sitzungsarten. Die **lokale Sitzung**
hält ihr Access-Token ausschließlich im Speicher — nichts im `localStorage` — und wird nach einem
Neuladen über das HttpOnly-Cookie wiederhergestellt: Beim Start versucht die Anwendung genau
**einen** Erneuerungsaufruf, und das auch nur, wenn zuletzt eine lokale Sitzung bestand. Diesen
Vermerk hält der nicht geheime Schlüssel `opaa.auth.lastSessionKind` im `localStorage`; er wird
beim lokalen Login gesetzt und **nur dann** gelöscht, wenn die Sitzung tatsächlich endet — bei der
Abmeldung einer lokalen Sitzung, bei einem `401` auf `/refresh` und bei einer Abweisung, die einen
Marker oder `401`/`403` nennt. Ein `5xx` oder ein Netzfehler lässt ihn stehen: Der Dienst war
vorübergehend nicht erreichbar, die Sitzung deswegen nicht vorbei, und ein zweiter Tab desselben
Browsers verlöre sonst beim nächsten `401` seine Erneuerung. Das Ende einer Anbietersitzung rührt
den Vermerk ebenfalls nicht an. Das CSRF-Cookie taugt dafür nicht: Es steht nach jeder Antwort im Browser, also schon
nach dem ersten `GET /auth/config` einer abgemeldeten Person. Ohne den Vermerk gibt es keinen
Netzaufruf, damit eine reguläre Anmeldung über einen Identitätsanbieter keine Fehlermeldung sieht,
die sie nie ausgelöst hat. Die Sitzungsart hält allein der Anwendungszustand des Tabs.

Erneuerung, Abmeldung und die Behandlung eines `401` verzweigen nach Sitzungsart. Alle
Erneuerungen laufen durch **eine** geteilte Anfrage — innerhalb eines Tabs über eine laufende
Zusage, über alle Tabs desselben Browsers hinweg über eine Web Lock (`navigator.locks`, Name
`opaa.local.refresh`; fehlt die Schnittstelle, bleibt es bei der Serialisierung im Tab; ein
Zeitlimit von 15 Sekunden auf den Anmeldeaufrufen begrenzt, wie lange eine hängende Anfrage die
Sperre halten kann). Zwei Tabs,
die dasselbe rotierende Refresh-Token vorlegen, wären serverseitig eine Wiederverwendung und
würden **alle** Sitzungen des Kontos beenden. `refresh` und `logout` tragen das
Double-Submit-Token im Header `X-XSRF-TOKEN`; weist der Server es mit `CSRF_TOKEN_MISSING` ab, holt
die Anwendung das Cookie einmal neu und wiederholt den Aufruf. Ein abgelaufenes Access-Token wird
nie mitgeschickt.

Nennt eine Abweisung im Header `WWW-Authenticate` einen der Gründe `local_accounts_disabled`,
`account_locked` (mit Anlass), `account_expired`, `session_revoked` (mit Anlass),
`account_not_active`, `unknown_account`, `malformed_token` oder `unknown_issuer`, unternimmt die
Anwendung **keinen** Erneuerungsversuch: Sie beendet die Sitzung und nennt den Grund als deutschen
Satz — je Marker und je Anlass ein eigener. Ein `403` mit dem Code `PASSWORD_CHANGE_REQUIRED` führt
auf die Seite `/account/password`; bis das neue Passwort steht, ist keine andere Route erreichbar,
und der Anlass (`INITIAL`, `ADMIN_RESET`, `SECURITY`) steht dort als Klartextsatz. Der erfolgreiche
Wechsel übernimmt die Sitzung, die das Backend in derselben Antwort ausstellt — es folgt keine
erneute Anmeldung.

### Der erste Systemverwalter, das Notanker-Konto und der Aussperrschutz

Beim allerersten Start im
`oidc`-Betriebsmodus legt `LocalAdminSeeder` — vor dem Webserver, gegen Wiederholung durch eine
Markierungszeile gesichert — die `LOCAL`-Anbieterzeile („Lokale Konten", deaktiviert, nie
Standard) und das lokale Notanker-Konto der Systemverwaltung an: Adresse aus
`OPAA_INITIAL_ADMIN_EMAIL` (der alte Vorgabewert `admin@opaa.local`, eine fehlende oder keine
E-Mail-Adresse werden abgelehnt — Fehler im Log, keine Markierung, Anlage beim nächsten Start),
Anzeigename „Systemverwaltung", Anlagegrund „Notanker-Konto der Systemverwaltung", `is_bootstrap`,
`SYSTEM_ADMIN`, Adresse als bestätigt. Auf einer **Neuinstallation** (keine Konten in `users` —
nicht die OIDC-Übernahmemarkierung, die im selben Start auch nach einer abgelehnten Adresse
entsteht) ist das Konto scharf: mit `OPAA_INITIAL_ADMIN_PASSWORD`, falls gesetzt (CI/E2E, kein
erzwungener Wechsel), sonst mit einem erzeugten Einmalpasswort, das **einmalig** als deutlich
markierter Block ins Anwendungslog geschrieben wird und bei der ersten Anmeldung gewechselt werden
muss (`pcr` mit Anlass `INITIAL`). Auf einer **Bestandsinstallation** entsteht das Konto als
`INVITED` ohne Passwort und ohne Log-Ausgabe; `OPAA_LOCAL_ADMIN_RESET=force` aktiviert es beim
nächsten Start einmalig (entsperrt, Ablauf gelöscht, neues Einmalpasswort, `SYSTEM_ADMIN`
wiederhergestellt, alle Sitzungen widerrufen; ein gelöschtes Konto wird neu angelegt) — auditiert
als `LOCAL_ADMIN_RESET`; die Anlage als `LOCAL_ADMIN_SEEDED`. Jede erfolgreiche Anmeldung mit dem
Notanker-Konto (erkannt über `is_bootstrap`, nicht über die Adresse) ist ein Audit-Ereignis
`LOCAL_BOOTSTRAP_ACCOUNT_LOGIN` und löst die Mail `BOOTSTRAP_ACCOUNT_USED` an alle übrigen
Systemverwalter aus. Die
Erstadministrator-Regel für OIDC-Konten ist aufgehoben: `InitialAdminPolicy` wirkt nur noch für den
Dev-Issuer (`dev-admin` bleibt Systemverwalter); IdP-Konten werden Systemverwalter allein durch
Rollenvergabe. `OPAA_OIDC_BOOTSTRAP=force` funktioniert bis zum 31.03.2027 weiter und warnt bei
jeder Verwendung mit Ersatz und Datum. Mit `OPAA_LOCAL_ADMIN_ALLOWED_CIDRS` (IPv4/IPv6, leer =
keine Beschränkung) melden sich lokale `SYSTEM_ADMIN`-Konten nur aus den genannten Netzen an — die
Abweisung ist dieselbe wie bei einem falschen Passwort, liegt aber vor der Fehlversuchszählung (aus einem nicht erlaubten Netz lässt sich das Konto nicht sperren); geprüft
wird die aufgelöste Client-Adresse (siehe Rate-Limiting unten).

**Aussperrschutz.** `LocalAdminAvailabilityGuard` ist die eine Stelle für „nie ohne
anmeldefähigen Systemverwalter": Unter dem Advisory-Lock je Organisation zählt er nur
**anmeldefähige** Systemverwalter — lokale Konten nach derselben `isLoginCapable`-Regel wie die
Anmeldung (aktiv, mit Passwort, nicht gesperrt oder abgelaufen) und Konten eines **aktivierten**
OIDC-Anbieters (im `dev`-Modus: des Dev-Issuers). Über ihn laufen der Rollenentzug per Token
(`TokenRoleSynchronizer`) und per Verwaltung (`POST /api/v1/admin/users/{id}/role`, 409 mit Code
`LAST_LOGIN_CAPABLE_ADMIN`) sowie das Deaktivieren und Löschen jedes aktivierten
OIDC-Anbieters sowie Sperren, Befristen und Löschen lokaler Systemverwalter. Weil beide
Rollenwege denselben Lock nehmen, gilt die Zusicherung auch dann, wenn ein manueller Entzug
zeitgleich mit einem Token-Entzug läuft: Ist der zweite Entzug der des letzten anmeldefähigen
Systemverwalters, wird er abgelehnt. Die
`LOCAL`-Zeile ist über die Anbieter-API weder löschbar noch Standard, ihr Issuer nicht änderbar
(nur der Anzeigename), Adressprüfung und Verbindungstest entfallen für sie; ihr
Aktivieren/Deaktivieren ist der Schalter der lokalen Verwaltung (`LOCAL_ACCOUNTS_ENABLED`/
`_DISABLED`), und das Abschalten beendet die Sitzungen aller regulären lokalen Konten (ein
`UPDATE` von `password_invalidated_before`; `LOCAL_SESSION_REVOKED` je Konto mit tatsächlich
aktiver Sitzung), nicht die der Systemverwalter. Der erste OIDC-Anbieter wird
auch neben der `LOCAL`-Zeile automatisch Standard; der Standard kann deaktiviert oder gelöscht
werden, sobald er der letzte aktivierte OIDC-Anbieter ist — nur mit `acknowledgeLastProvider=true`
(sonst 409 `LAST_PROVIDER_ACKNOWLEDGEMENT_REQUIRED`) und nur, wenn ein lokales
Systemverwalterkonto mit Passwort besteht (sonst 409 `LAST_LOGIN_CAPABLE_ADMIN`).

### Client-Adresse, Grenzen der Anmeldung und Kontosperre

Die Client-Adresse jeder Anfrage bestimmt
`io.opaa.security.ClientIpResolver` (`TrustedProxyClientIpResolver`), die eine Stelle für
Rate-Limits und die Netzbeschränkung: `X-Forwarded-For` zählt nur, wenn die Verbindung selbst aus
einem Netz in `OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS` kommt (Vorgabe leer = Header ignoriert), und
dann ist der Client der erste Eintrag von rechts, der kein vertrauter Proxy ist — der Eintrag, den
der nächste vertraute Proxy angehängt hat; ein vom Client mitgeschickter Anfang der Kette und
weitere vertraute Zwischenstationen zählen nicht. Das Ergebnis ist immer eine numerische Adresse
(ein Eintrag, der keine ist, fällt auf die Verbindungsadresse zurück; kein DNS). Verbindungsadresse
und Header liest der Resolver unterhalb aller Request-Wrapper, denn Springs `ForwardedHeaderFilter`
(`server.forward-headers-strategy: framework`) schreibt `getRemoteAddr()` bereits aus dem
**linkesten** `X-Forwarded-For`-Eintrag um und versteckt den Header — `getRemoteAddr()` direkt zu
lesen wäre von jedem Client wählbar; kein Code liest es mehr direkt. `0.0.0.0/0` und
`::/0` lehnt der Start ab; der `oidc`-Betriebsmodus warnt bei leerer Liste mit der Folge im
Klartext; mehrere `X-Forwarded-For`-Zeilen gelten als eine Kette, ein eingehendes `Forwarded`
(RFC 7239) verwirft der Frontend-nginx. Der Compose-Stack hat dafür ein festes Netz und eine feste
Adresse des Frontend-Containers (`docker-compose.yml`, `OPAA_COMPOSE_SUBNET`/
`OPAA_COMPOSE_FRONTEND_ADDRESS`), `.env.docker.example` vertraut genau dieser Adresse (`/32`, nicht
dem Netz — die Gateway-Adresse spräche jeder Host-Prozess), und der Backend-Port ist nur an
`127.0.0.1` gebunden. `GET /api/v1/admin/diagnostics/client-address`
(Systemverwaltung) zeigt für genau diese Anfrage Verbindungsadresse, empfangenen Header, ob er
gezählt hat, und die aufgelöste Adresse. Der bestehende `RateLimitFilter` (Sliding Window über
Caffeine, ein Limiter je Regel, Schlüsselzahl begrenzt — darüber fällt die Grenze für die
vergessenen Schlüssel offen aus, was die globale Grenze auffängt) nutzt dieselbe Auflösung, matcht
gegen den dekodierten Pfad innerhalb der Anwendung (kein `X-Forwarded-Prefix`, keine
Prozentkodierung führt an einer Regel vorbei), prüft erst das Kontingent des Clients und verbraucht
die globale Grenze nur für durchgelassene Anfragen, zählt IPv6-Clients je `/64` und antwortet auf
jede Überschreitung mit `429`, `Retry-After` und dem einen deutschen Fehlertext; CORS-Preflights
zählen nicht. Grenzen der lokalen Anmeldung (`opaa.rate-limit.local-auth.*`): Login 10/60 s je
Adresse, Refresh 30/60 s je Adresse, Passwortwechsel 5/300 s je Konto (vor dem Vergleich des
aktuellen Passworts), Registrierung 5/3600 s je Adresse und 3/3600 s je E-Mail-Adresse, „Passwort
vergessen" 5/3600 s je Adresse und 3/3600 s je E-Mail-Adresse, Passwort setzen und E-Mail
bestätigen je 10/900 s je Adresse; Login, Registrierung und „Passwort vergessen" haben zusätzlich eine globale Grenze je
Fenster (100 bzw. 50), deren Überschreiten eine Warnung und die Metrik `opaa.rate_limit.rejected`
(`limit`, `scope=global`) erzeugt. Die adress- und kontobezogenen Grenzen liegen in
`LocalAuthRateLimiter` (E-Mail-Adressen nur als Hash im Speicher); die Endpunkte der
Selbstbedienung rufen `requireAddressAllowance` vor jeder Verarbeitung auf. Nach
fünf Fehlversuchen (`OPAA_AUTH_LOCAL_LOCKOUT_MAX_ATTEMPTS`) sperrt `LocalAccountLockoutListener`
das Konto für feste 15 Minuten (`OPAA_AUTH_LOCAL_LOCKOUT_DURATION`, `locked_reason =
FAILED_LOGINS`, keine progressive Verlängerung; die Sperre setzt den Zähler auf null, während der
Sperre wird nichts gezählt — nach ihrem Ende gilt wieder das volle Kontingent): auditiert **einmal** als `LOCAL_ACCOUNT_LOCKED_AFTER_FAILED_LOGINS`
(Systemakteur `local-auth`, Subjekt als Pseudonym, ohne Zähler) und als Metrik
`opaa.auth.local_account_lockout`; der einzelne Fehlversuch steht nur im Anwendungslog mit der
Konto-ID, nie der Adresse. Die Anmeldung antwortet während der Sperre exakt wie bei einem
falschen Passwort; Tokens des gesperrten Kontos weist der Validator über den Zustand ab
(`account_locked:failed_logins`). Das Ende der Sperre erzeugt kein Ereignis; der Zähler geht auch
bei jeder erfolgreichen Anmeldung auf null. Keine Mail bei dieser Sperre; „Passwort vergessen"
bleibt offen, und ein eingelöster Rücksetzlink hebt sie auf.

### Kontolebenszyklus lokaler Konten

Die Verwaltung lokaler Konten liegt unter
`/api/v1/admin/local-users` und `/api/v1/admin/local-auth-settings` (nur `SYSTEM_ADMIN`, auf die
eigene Organisation begrenzt). **Anlegen** mit E-Mail-Adresse, Anzeigename, Rolle, Pflicht-Anlagegrund
(höchstens 200 Zeichen, zweckgebunden, für die Person einsehbar, nie als Wert im Protokoll) und
Ablaufdatum — vorbelegt mit `default_expiry_days`, ausdrücklich auf „ohne Ablauf" setzbar, nie
stillschweigend unbefristet —, entweder **per Einladung** (Konto `INVITED`, Adresse vom Verwalter
bestätigt, Aktionstoken `SET_PASSWORD` mit `invitation_token_ttl_hours`, Mail
`LOCAL_ACCOUNT_INVITATION`) oder **mit erzeugtem Anfangspasswort** (einmalig in der Antwort, Wechsel
bei der ersten Anmeldung mit Anlass `INITIAL`). **Link-Rückfall:** Geht die Mail nicht hinaus — SMTP
aus, Versand fehlgeschlagen oder `OPAA_PUBLIC_BASE_URL` nicht gesetzt —, enthält die Antwort den Link
genau einmal (ohne Basis-URL als Pfad relativ zur Installation `/set-password?token=…`); der
Zustellweg steht im Protokoll (`LOCAL_USER_INVITED` mit `MAIL_SENT`, `MAIL_FAILED` oder
`LINK_DISPLAYED`). Eine E-Mail-Adresse ist nur unter dem lokalen Issuer eindeutig (409 `EMAIL_TAKEN`).
Der persönliche Space entsteht auf demselben Weg wie bei jeder Anmeldung. Die **Liste** führt
ausschließlich lokale Konten mit Zustand, Rolle, Ablauf, Anlagegrund und **Aktivität nur als Klasse**
(`NEVER`, `INACTIVE_90_DAYS`, `ACTIVE` — nie als Zeitstempel, nicht danach sortierbar), mit den
Prüffiltern „offene Einladungen" (`status=INVITED`), „ohne Ablaufdatum" und „länger als 90 Tage nicht
genutzt", Seitengröße höchstens 50, kein Export; `…/summary` liefert die Zahlen für den Hinweis der
Oberfläche und das Datum der nächsten Wiedervorlage. Daneben liefert
`GET /api/v1/admin/accounts` (#1601, nur `SYSTEM_ADMIN`, eigene Organisation) **alle** Konten
seitenweise: lokale mit derselben `LocalUserResponse` unter `local`, Anbieterkonten mit `provider`
(Anzeigename und ob der Anbieter noch Tokens ausstellt; abwesend, wenn die Anbieterzeile gelöscht
wurde) und `roleManagedByProvider`. Es gelten dieselben Grenzen — höchstens 50 Zeilen je Seite, kein
Export, keine Sortierung nach der Aktivität —, und die Aktivitätsklasse steht **nur** an lokalen
Zeilen. Sortierbar sind `displayName`, `email`, `origin`, `role`, `status`, `expiresAt` und
`createdAt`; die drei Kategoriefelder ordnen nach fester Rangfolge (Herkunft: lokal, dann Anbieter
nach Namen, dann ein Issuer ohne Anbieterzeile; Rolle nach Privileg; Zustand nach Dringlichkeit mit
den Anbieterkonten zuletzt). Filter: `query` (E-Mail und Anzeigename beider Kontotypen), `providerType`,
`providerId`, `role` sowie `status`, `withoutExpiry` und `inactive`, die die Liste auf lokale Konten
eingrenzen. **Ändern** (`PATCH`) von Anzeigename, Adresse,
Anlagegrund und Ablaufdatum ist ein `LOCAL_USER_CHANGED` mit Vorher/Nachher **nur für das
Ablaufdatum** und sonst nur den Namen der geänderten Felder; die Rolle läuft über denselben Pfad wie
`POST /api/v1/admin/users/{id}/role` (Rollenereignisse, Aussperrschutz). **Sperren** (`locked_reason
ADMIN`) beendet alle Sitzungen sofort (`password_invalidated_before` am Konto, Refresh-Familien
`ACCOUNT_LOCKED`; `LOCAL_SESSION_REVOKED` nur, wenn tatsächlich eine Sitzung lief), ersetzt eine
Fehlversuch-Sperre und setzt den Zähler zurück; **Entsperren** hebt jede Sperre und den Zähler auf.
Beides wird der Person per Mail mitgeteilt (`ACCOUNT_LOCKED` mit dem Grund, den der Verwalter für die
Person formuliert — nur in der Mail, nie im Protokoll —, `ACCOUNT_UNLOCKED`). Selbstsperre und
Selbstlöschung sind 409 (`SELF_LOCKOUT`, `SELF_DELETE`); Sperren, Ablauf in der Vergangenheit,
Rollenentzug und Löschen des letzten anmeldefähigen Systemverwalters laufen über den Aussperrschutz
(409 `LAST_LOGIN_CAPABLE_ADMIN`, auch bei zwei gleichzeitigen Anfragen). **Zurücksetzen** per Link
(`RESET_PASSWORD`, `reset_token_ttl_minutes`, ein neuer Link entwertet ältere, Mail
`ADMIN_PASSWORD_RESET`, Link-Rückfall und Zustellweg wie bei der Einladung,
`LOCAL_USER_PASSWORD_RESET_REQUESTED`; für ein Konto ohne Passwort wird die Einladung erneut
gesendet) oder mit **erzeugtem Passwort** (einmalig in der Antwort, Wechsel mit Anlass `ADMIN_RESET`,
`LOCAL_USER_PASSWORD_GENERATED`) — beides beendet alle Sitzungen. **Löschen** ist die Ausnahme:
nur ein Konto, das nichts referenziert — keine Bibliothek, kein Space außer dem persönlichen, kein
Chat und kein Eintrag in den Rechte- und Nachweisbeständen mit Nutzerbezug (Gruppenhistorie,
Grant-Historie, Grants, Space-Zuordnungen, Klärungsvorgänge) —, praktisch also
ein nie benutztes Konto; sonst 409 `ACCOUNT_OWNS_CONTENT` mit dem Rat zu sperren (die blockierenden
Tabellen stehen nur im Anwendungslog), nie das Notanker-Konto (409 `BOOTSTRAP_ACCOUNT`). Der
persönliche Space wird als `SPACE_DELETED` protokolliert mitgelöscht, Zugangsdaten, Tokens und die
Pseudonymzuordnung gehen mit, das Protokoll behält seine pseudonymen Zeilen (`LOCAL_USER_DELETED`).
Die **Diagnose-Vollmachten** sperren nicht: Alle ihre Personenspalten kaskadieren (ADR-0016,
Nachtrag), und vor dem Löschen widerruft OPAA die vom Konto erteilten, noch gültigen Vollmachten
weiterbestehender Inhaber ausdrücklich — je Vollmacht ein `DIAGNOSTIC_IMPERSONATION_REVOKED` in der
Form eines regulären Widerrufs, in derselben Transaktion; die übrigen Zeilen gehen ohne eigenes
Ereignis mit der Kaskade. Ein `PATCH` mit einem Ablaufdatum in der Vergangenheit lässt das Konto sofort ablaufen (beim
Anlegen ist ein vergangenes Datum ein 400). **Einstellungen:** `enabled` schaltet die
`LOCAL`-Zeile über denselben Pfad wie die Anbieter-API (die Antwort nennt beim Abschalten die Zahl der
beendeten Sitzungen), die übrigen Werte liegen in `local_auth_settings` mit den Grenzen aus ADR-0033
(`LOCAL_ACCOUNTS_SETTINGS_CHANGED` mit Vorher/Nachher der geänderten Schlüssel); Selbstregistrierung
nur mit Domänenliste, „Passwort vergessen" und Selbstregistrierung nur einschaltbar, wenn
`OPAA_PUBLIC_BASE_URL` gesetzt ist (409 `PUBLIC_BASE_URL_REQUIRED`). Der **tägliche Lauf** sperrt
Konten ohne Aktivität über `inactive_days` (`locked_reason INACTIVITY`, `LOCAL_USER_LOCKED` unter dem
Systemakteur `local-auth`, Mail `ACCOUNT_LOCKED`; das Notanker-Konto ausgenommen, der letzte
anmeldefähige Systemverwalter wird mit einer Warnung übersprungen), erinnert 14 Tage vor einem
Ablauf die Person (`ACCOUNT_EXPIRING`, nur aktive Konten, Kalendertag in der Zeitzone des Laufs,
einmalig) und schickt den Systemverwaltern je Lauf höchstens **eine** Wiedervorlage
`ADMIN_REVIEW_REMINDER` mit der Zahl der Konten, die ihren Blick brauchen — die in 14 Tagen
auslaufenden und am ersten Tag eines Quartals zusätzlich alle ohne Ablaufdatum — und dem Link zur
Liste, ohne Namen; je Lauf höchstens 200 Sperren bzw. Mails, der Rest folgt am nächsten Tag. Sperren,
erzeugtes Passwort und Adresswechsel entwerten jeden offenen Einladungs- und Rücksetzlink.

### Selbstbedienung: Passwort setzen, Passwort vergessen, Selbstregistrierung

Die Selbstbedienung lokaler Konten
liegt ohne Anmeldung unter `/api/v1/auth/local/{set-password, forgot-password, register,
verify-email}`; die Links in den Mails zeigen auf die SPA-Routen `/set-password?token=…` und
`/verify-email?token=…`, nie auf einen `GET` mit Nebenwirkung. **Passwort setzen**
löst einen Einladungs- (`SET_PASSWORD`) oder Rücksetzlink (`RESET_PASSWORD`) atomar und genau einmal
ein: unbekannt, abgelaufen, verbraucht, falscher Zweck und ein Konto, das inzwischen durch Verwalter
oder Inaktivität gesperrt oder abgelaufen ist, antworten alle mit derselben 400 `TOKEN_INVALID`; die
Passwortrichtlinie antwortet mit Feldfehlern und lässt den Link offen. Der eingelöste Link setzt den
Hash, hebt einen erzwungenen Wechsel auf, bestätigt beim **Einladungslink** die Adresse (die
Einladung ist der Akt, mit dem der Verwalter für sie bürgt — ein Rücksetzlink bestätigt nichts, eine
unbestätigte Selbstregistrierung bleibt auf ihren Bestätigungslink angewiesen), hebt eine
**Fehlversuch-Sperre** auf (nicht die Sperre durch Verwalter oder Inaktivität), entwertet alle
übrigen offenen Passwortlinks des Kontos und beendet jede Sitzung (`password_invalidated_before`,
Familien `PASSWORD_CHANGED`); Ereignis `LOCAL_PASSWORD_SET` mit dem Zweck des Links. **Passwort
vergessen** existiert nur, solange die Verwaltung eingeschaltet, die Einstellung an und
`OPAA_PUBLIC_BASE_URL` gesetzt ist — sonst ist der Pfad **von einer unbekannten Route nicht zu
unterscheiden**: Ohne Sitzung antwortet er wie jede unbekannte Route unter `/api` mit 401, mit
identischem Rumpf und identischen Kopfzeilen, für gültigen Rumpf, kaputten Rumpf und andere Methode
gleichermaßen; die Ratenbegrenzung zählt ihn währenddessen gar nicht, sonst fiele die Verkleidung
genau unter Last. Mit Token bleibt es bei der Standard-404, die eine unbekannte Route dort ebenfalls
liefert. Der Zustand der Flüsse steht allein in `/auth/config` (#1592). Wo er existiert,
antwortet er immer 204 nach
**genau derselben Zeit** (250 ms; nach der Prüfung der Adresse läuft die gesamte Arbeit — Suche,
Link, Versand — auf dem Mail-Thread `MailDispatchExecutor`, die Anfrage wartet nur die feste Frist
ab; scheitert eine Aufgabe dort, bleibt eine `ERROR`-Zeile mit Ausnahmeklasse und maskierter
Nachricht), unabhängig davon, ob ein Konto besteht: ein aktives Konto und ein Konto in Fehlversuch-Sperre
erhalten die Mail `PASSWORD_RESET` mit einem `RESET_PASSWORD`-Link (`reset_token_ttl_minutes`; ein
neuer Link entwertet ältere), Konten mit Verwalter- oder Inaktivitätssperre, abgelaufene und
eingeladene Konten sowie unbekannte Adressen erhalten nichts und antworten gleich. Die Anfrage
ändert keinen Zustand und wird nicht protokolliert — Sitzungen enden erst beim Einlösen.
**Selbstregistrierung** existiert nur mit Verwaltung an, `self_registration_enabled`, **nichtleerer
Domänenliste** und Basis-URL (sonst ununterscheidbar von einer unbekannten Route, wie oben). Adresse, Anzeigename und Passwort werden zuerst
geprüft (Feldfehler `email`, `displayName`, `password` — sie verraten nichts über Konten); danach
wird der **Hash in jedem Ausgang auf dem Anfrage-Thread** berechnet (das Klartext-Passwort wartet
nie in einer Warteschlange), und alles Weitere läuft auf dem Mail-Thread — die Antwort ist in
jedem Ausgang **dieselbe 202 nach derselben Zeit** (dem Größeren aus fester Frist und einem
BCrypt, nicht der Frist selbst): Domäne gegen die Liste (exakt), Adresse gegen den lokalen Issuer
— eine
freie Adresse mit erlaubter Domäne wird als Konto angelegt (Rolle `USER`, Ablauf **Pflicht** aus
`default_expiry_days`, Anlagegrund „Selbstregistrierung", Adresse unbestätigt und damit `INVITED`,
nicht anmeldefähig) und erhält `REGISTRATION_VERIFICATION` mit einem 24 Stunden gültigen
`VERIFY_EMAIL`-Link; eine **noch unbestätigte Selbstregistrierung derselben Adresse** erhält einen
neuen Link (der ältere ist entwertet), Hash und Name bleiben — sonst könnte eine zweite
Registrierung ein Passwort hinterlegen, das die erste Person dann bestätigt (bewusste Regel gegen
ein Pre-Hijacking); eine belegte Adresse
(auch bei gleichzeitiger Anlage, Unique-Index) und eine fremde Domäne legen nichts an und senden
nichts — auch keine Hinweis-Mail. Ereignis `LOCAL_USER_REGISTERED` unter dem Systemakteur
`local-auth`, nur bei der Anlage. Der persönliche Space entsteht erst bei der ersten Anmeldung nach
der Bestätigung, auf dem Weg jeder Anmeldung — eine nie bestätigte Registrierung hinterlässt keinen
Space. **E-Mail bestätigen** löst den `VERIFY_EMAIL`-Link genau einmal ein und setzt
`email_verified_at` (400 `TOKEN_INVALID` wie oben); danach ist das Konto `ACTIVE`. Ein
administratives Zurücksetzen macht eine unbestätigte Selbstregistrierung **nicht** anmeldefähig —
der Rücksetzlink bestätigt nichts; ist die Selbstregistrierung inzwischen abgeschaltet, ist der Weg
für den Verwalter das Löschen des unbestätigten Kontos und eine Einladung. Die Rate-Limits
aus #1535 greifen je Client-Adresse über die Pfadregeln (auch für `verify-email`, 10/900 s) und je
E-Mail-Adresse vor jeder Verarbeitung — für einen abgeschalteten Fluss greift **keines von beiden**:
Sein Pfad wird weder gezählt noch erreicht er die Adressprüfung. Der
Mail-Thread hat eine begrenzte Warteschlange (1000); ein Versand, der keinen Platz findet, wird mit
einer Warnung verworfen. Kein Roh-Token steht in Log, Datenbank oder Protokoll; der
Protokollmitschnitt des SMTP-Transports (`org.eclipse.angus.mail`) ist in `application.yml` auf
`INFO` festgenagelt.

### Anmeldeseite und Selbstbedienungsseiten

Die **Anmeldeseite** zeigt die Passwortmaske nur, solange die lokale Verwaltung eingeschaltet ist;
Anbieterkacheln und Maske stehen als getrennte, benannte Bereiche untereinander („Mit
Identitätsanbieter" zuerst, dann „Mit Konto dieser Installation"). Die Links „Passwort vergessen?"
und „Konto registrieren" erscheinen nur, wenn `GET /api/v1/auth/config` den jeweiligen Fluss als
verfügbar meldet. Jede abgewiesene Anmeldung liest sich gleich („Anmeldung nicht möglich. Prüfen Sie
E-Mail-Adresse und Passwort."); eine Begrenzung nennt die Wartezeit aus `Retry-After`, ein nicht
erreichbares Backend sagt genau das. Nach einer Abweisung springt der Fokus zurück ins erste Feld.
Der Vertrauenshinweis ist kontextabhängig formuliert. Die **Systemverwalter-Anmeldung**
(`/login/system`) ist immer erreichbar — der Weg zurück in eine Installation, deren letzter
Anbieter falsch konfiguriert ist —, erscheint als stiller Fußlink der Anmeldeseite und leitet auf
`/login` um, sobald die lokale Verwaltung eingeschaltet ist. Ein Rücksprungziel aus `?from=` oder
dem Router-Zustand wird auf einen Pfad desselben Origins normalisiert, bevor es verwendet wird. Im
`dev`-Betriebsmodus ändert sich nichts.

**Die vier Selbstbedienungsseiten** liegen außerhalb des
Anwendungsrahmens und ohne Sitzung: `/set-password?token=…` (Einladung **und** Rücksetzung — eine
Seite, Überschrift „Passwort festlegen", die die Herkunft des Links bewusst nicht nennt),
`/verify-email?token=…`, `/forgot-password` und `/register`. Die beiden Linkziele funktionieren
**immer**, auch bei ausgeschalteten Schaltern — ein Link aus einer Einladung oder einer
administrativen Rücksetzung darf nicht in eine Umleitung laufen; `/forgot-password` und `/register`
leiten auf `/login` um, sobald `localAccounts` den jeweiligen Fluss nicht als verfügbar meldet,
entscheiden aber nichts, solange die Konfiguration noch lädt. `/verify-email` löst den `POST` beim
Laden **genau einmal** aus (`useRef`-Sperre gegen den doppelten Effektlauf im StrictMode — der
zweite Aufruf würde den gerade verbrauchten Link als ungültig melden) und zeigt „wird geprüft",
„bestätigt" oder „Link ungültig"; eine Begrenzung nennt dort die Wartezeit und sagt, dass der Link
dadurch nicht verbraucht ist.

**Kein Roh-Token in der Adresszeile.** Beide Seiten lesen den Token genau einmal und **entfernen ihn
sofort aus der URL**, bevor die erste Anfrage der Seite hinausgeht: Solange er dort steht, ist er der
Referrer jeder gleichherkünftigen Anfrage, und das nginx der Installation sendet
`Referrer-Policy: same-origin` — der Token stünde damit in dessen Zugriffsprotokoll, entgegen
ADR-0033, Entscheidung 9. Die Seite behält den gelesenen Wert, der Fluss kostet das also nichts;
**ein Neuladen verliert den Token** und die Seite liest sich dann als ungültiger Link. Das ist die
billigere Folge, weil der Link aus der Mail weiter funktioniert — beide Fehlertexte sagen genau das
(„Ein erneutes Laden dieser Seite hilft nicht — öffnen Sie den Link aus der E-Mail noch einmal.").
Nach einem erfolgreich eingelösten Passwortlink beendet die Seite außerdem eine **noch offene lokale
Sitzung dieses Tabs** ohne Abmeldeaufruf: Das Backend hat alle Sitzungen des Kontos widerrufen, und
„Zur Anmeldung" würde sonst mit einem toten Token in die Anwendung führen.

Jedes Passwortfeld trägt einen Sichtbarkeits-Umschalter (tastaturerreichbar wie jedes
Bedienelement), die Richtlinie steht als Satz daneben („mindestens n Zeichen, höchstens 64 Zeichen
und 72 Byte, nicht die eigene Adresse, nicht auf der Sperrliste" — das Minimum aus `/auth/config`),
und eine vierstufige Stärkeanzeige bewertet die Eingabe als **Orientierung**: Sie blockiert nichts,
weil das Backend entscheidet, zählt Codepoints wie die Richtlinie und meldet ihre Bewertung als
höfliche Live-Region. „Sicheres Passwort erzeugen" baut über die Web-Crypto-API ein Passwort, das die
Richtlinie per Konstruktion erfüllt, macht es sichtbar und lässt es kopieren; wo es ein
Wiederholungsfeld gibt, füllt es dieses mit. Feldfehler erscheinen am Feld — **je Feld** alle
verletzten Regeln einer Antwort, weil die Richtlinie sie gesammelt meldet; nennt eine Abweisung ein
Feld, das das Formular nicht zeigt, erscheint ihr Satz in der Meldung über dem Formular statt zu
verschwinden. Nach einer Abweisung springt der Fokus auf das erste betroffene Feld, und wenn keines
benannt ist, auf die Meldung selbst. Erfolg und Scheitern sind eine **eigene Ansicht** an der Stelle des
Formulars, nicht eine Popup-Meldung: „Wenn zu dieser Adresse ein Konto besteht, haben wir eine
E-Mail geschickt." (vergessen und registrieren — für bekannte und unbekannte Adressen gleich),
„Passwort festgelegt — Sie können sich jetzt anmelden.", „Dieser Link ist nicht mehr gültig." (eine
Meldung für unbekannt, abgelaufen, verbraucht, falscher Zweck und fehlenden Token) — jede mit einem
Weg zurück. Die Registrierungsbestätigung bietet „Registrierung erneut absenden" an, weil eine
erneute Registrierung derselben unbestätigten Adresse den Bestätigungslink erneut schickt. Eine 429
nennt die Wartezeit aus `Retry-After`, ohne von „Versuchen" zu sprechen — die Grenze zählt auch
Anfragen anderer aus demselben Netz. Die Registrierung zeigt weder Rolle noch Ablaufdatum und sagt,
welche Daten gespeichert werden; den Datenschutzhinweis des Hauses gibt die Systemverwaltung heraus,
solange es dafür keine Seite im Produkt gibt (#143).

**Passwort ändern** (`/account/password`) nutzt dieselben Bausteine; nur eine abgewiesene
**aktuelle** Eingabe wird geleert, ein abgewiesenes neues Passwort lässt die übrigen Felder stehen.
In den Benutzereinstellungen erscheint der Abschnitt „Ihr Konto" mit der Schaltfläche „Passwort
ändern" **nur für lokale Sitzungen**; eine Anbietersitzung verweist dort auf ihren Identitätsanbieter, die
Entwicklungsanmeldung auf nichts. Der freiwillige Wechsel kehrt danach in die Einstellungen zurück
und bleibt angemeldet — das Backend stellt in derselben Antwort eine Sitzung aus. Derselbe Abschnitt
zeigt der Person ihren **Anlagegrund** („Anlass des Kontos"), den die Systemverwaltung bei der Anlage
festgehalten hat: Er ist Teil der Selbstauskunft (ADR-0033, Entscheidung 11) und erreicht die
Oberfläche über `GET /api/v1/auth/me` (`createdReason`, leer bei einem Konto eines
Identitätsanbieters).

### Benutzerverwaltung in der Administration

Unter Administration → Benutzer (`/admin/users`, nur `SYSTEM_ADMIN`; andere sehen den Hinweis statt
der Verwaltung) liegt die Oberfläche zu dieser API — seit #1601 in zwei Bereichen, die eigene Routen
sind (`/admin/users/accounts`, `/admin/users/settings`; der nackte Pfad leitet auf die Konten).
„Benutzer & Gruppen" ist dort in zwei Einträge der Sekundärspalte geteilt — „Benutzer" und
„Gruppen" —, und der Admin-Einstieg der globalen Leiste führt auf „Benutzer".

**Bereich „Einstellungen".** Die Karte **Lokale Anmeldung** trägt die drei Schalter: `Lokale
Anmeldung aktiv` (Abschalten nur nach einem Konsequenz-Dialog; die Rückmeldung nennt die Zahl der
Konten, die ihre Sitzung verloren haben), `Selbstregistrierung` (Konsequenz-Dialog „öffentlich
erreichbares Formular, Rate-Limits gelten", nur mit nichtleerer Domänenliste) und `Passwort
vergessen`; die beiden linkgebundenen Schalter sind **gesperrt und begründet**, solange
`OPAA_PUBLIC_BASE_URL` fehlt, und ein 409 `PUBLIC_BASE_URL_REQUIRED` erscheint als Satz mit dem
nächsten Schritt. Darunter stehen die Regeln und Fristen (Domänen, Mindestlänge, vorbelegtes
Ablaufdatum, Inaktivitätsfrist, Gültigkeit der beiden Links) und der Zustand des Mailversands mit
Verweis auf die E-Mail-Seite — ohne SMTP werden Einladung und Rücksetzlink zur Übergabe angezeigt
statt versendet.

**Bereich „Konten".** Die Liste führt **alle Konten der Organisation** über
`GET /api/v1/admin/accounts` — lokale Konten und Konten der Identitätsanbieter in einer Tabelle,
jede Zeile mit ihrer **Herkunft** als Symbol und Wort („Lokal" mit Schlüssel, der Anzeigename des
Anbieters mit Gebäude; ein Konto unter einem Issuer ohne Anbieterzeile — eine gelöschte Zeile, im
`dev`-Modus der synthetische Dev-Issuer — steht als „Kein Anbieter" mit seinem Issuer im
Tooltip). Ein **Hinweisbanner** aus `…/local-users/summary` nennt lokale Konten ohne
Ablaufdatum und offene Einladungen, sagt die Auflage („begründet und befristet, regelmäßig zu
überprüfen") und springt in den jeweiligen Filter — der Sprung setzt die Herkunft auf „Lokal". Die
**Filterleiste** führt Suche (300 ms entprellt, über E-Mail und Anzeigename beider Kontotypen), die
Herkunft (alle, lokal, alle Anbieter, ein einzelner Anbieter), die Filter Zustand/Rolle/Auflage —
Zustand und Auflage beschreiben lokale Konten und grenzen die Liste auf sie ein —, Sortierung nach
den sieben erlaubten Feldern — auch nach Herkunft, Rolle und Zustand, die als Kategorien eine feste
Rangfolge haben statt einer alphabetischen — und Seitenblättern (höchstens 50 je Seite).
**Sieben Spalten** — sechs mit Werten und die Spalte der Zeilenmenüs —, weil Name und Adresse sich
eine Zelle teilen und Ablauf und Aktivität ebenso:
Name mit der Adresse darunter, Herkunft, Rolle (mit dem Etikett „Vom Anbieter geführt", wenn ein
aktivierter Anbieter die Rollen über seinen Claim führt), Zustand als Punkt **und** Text
(Eingeladen, Aktiv, Gesperrt mit Grund, Abgelaufen) samt Etikett „Passwortwechsel ausstehend" — bei
Anbieterkonten stattdessen „Beim Anbieter", „Anbieter deaktiviert" oder „Anmeldung nicht möglich",
Letzteres mit einem Warnsymbol in der Signalfarbe und dem Grund im Tooltip (der Text selbst bleibt
sekundär, weil die Signalfarbe als Fließtext den Kontrastschwellwert unterschreitet) —, Ablauf mit
der **Aktivität als Klasse** darunter
(„nie", „länger als 90 Tage nicht", „aktiv" — nicht sortierbar und **nur für lokale Konten**; ein
Anbieterkonto trägt keine), und Anlagedatum mit gekürztem Anlagegrund; unter Tablet-Breite wird
daraus eine Kartenliste. Das **Zeilenmenü hängt vom Kontotyp ab**: Ein lokales Konto führt
Bearbeiten, Sperren beziehungsweise Entsperren (nur am jeweils passenden Zustand), Rücksetz-Link per
E-Mail, Passwort erzeugen, „Übergabe anstoßen …" und — nachrangig unter einer Trennlinie, weil
Sperren der Regelweg ist — Löschen; am eigenen Konto sind Sperren und Löschen deaktiviert, am
Notanker-Konto das Löschen und die Übergabe (beide mit sichtbarer Begründung am Eintrag). Ein
Anbieterkonto führt „Rolle ändern …" (Dialog über `POST /api/v1/admin/users/{id}/role`, denselben
Endpunkt wie für lokale Konten; deaktiviert und begründet, wenn der Anbieter die Rollen führt; die
Ablehnung des Aussperrschutzes erscheint als Satz mit dem nächsten Schritt), den Verweis „Anbieter
verwalten" und den Hinweis, dass Sperren, Befristen und Löschen beim Anbieter erfolgen — dafür gibt
es serverseitig keine Operation. Anlegen und Bearbeiten lokaler Konten laufen über einen Dialog mit
Pflicht-Anlagegrund (Hilfetext nennt, was nicht hineingehört), vorbelegtem Ablaufdatum samt
ausdrücklichem „kein Ablaufdatum" und der Wahl zwischen Einladung und Anfangspasswort;
Einladungslink und erzeugtes Passwort erscheinen **genau einmal** in einem eigenen Dialog mit
Kopierschaltfläche, Zustellweg-Satz und dem Hinweis, dass die Ansicht nicht wiederkehrt.
Es gibt **keinen Export**. Auf der Anbieterseite erscheint die
`LOCAL`-Zeile nicht als Anbieter (ihr Schalter ist der obige), und das Deaktivieren oder Löschen des
**letzten aktivierten** OIDC-Anbieters zeigt vorher die Konsequenz („danach können sich nur noch
lokale Konten anmelden") und sendet erst dann `acknowledgeLastProvider=true`; die beiden Konfliktcodes
`LAST_LOGIN_CAPABLE_ADMIN` und `LAST_PROVIDER_ACKNOWLEDGEMENT_REQUIRED` erscheinen als Sätze mit dem
nächsten Schritt.

### Übergabe eines lokalen Kontos an eine Anbieteridentität (gebaut, #1563)

Der eine benannte Ausnahmeweg zum Zusammenführungsverbot aus ADR-0025 (ADR-0033, Entscheidung 12).
Ein Verwalter kann ihn nicht allein gehen: Der Code geht an die hinterlegte Adresse, und die
Identität kommt aus einer Anmeldung beim Anbieter, die nur die Person führen kann. Der Vorbehalt
gilt dem **Link-Rückfall** — geht die Mail nicht hinaus, hält der Verwalter den Code in der Hand und
muss ihn so übergeben, dass er sicher ist, mit der Person zu sprechen; die Oberfläche sagt das an
der Anzeige des Links. Er löst das Problem, an dem der alte
Modus `basic` scheiterte: Wer im Anlaufbetrieb ohne Identitätsanbieter startet und später auf einen
umstellt, nimmt Spaces, Mitgliedschaften und Rolle mit, statt sie an der alten Identität
zurückzulassen.

**Anstoßen.** `POST /api/v1/admin/local-users/{id}/handover` (nur `SYSTEM_ADMIN`) nennt die Kennung
eines **aktivierten OIDC-Anbieters** und einen **Pflicht-Anlass** (höchstens 200 Zeichen,
zweckgebunden wie der Anlagegrund) — und sonst nichts: Es gibt kein Feld für eine Identität. OPAA
erzeugt einen einmaligen Übergabecode (72 Stunden, HMAC-Hash wie jeder Aktionstoken; ein neuer Code
entwertet den älteren), an dem Anbieter und Anlass hängen, schickt den Link an die **hinterlegte**
Adresse des Kontos (`ACCOUNT_HANDOVER_REQUESTED`) und protokolliert `LOCAL_USER_HANDOVER_REQUESTED`
mit Anbieter-Kennung und Zustellweg. Geht die Mail nicht hinaus oder fehlt `OPAA_PUBLIC_BASE_URL`,
enthält die Antwort den Link **genau einmal** zur Übergabe von Hand — wie bei der Einladung. Das
lokale Konto bleibt bis zur Einlösung unverändert benutzbar. Abgelehnt wird der Anstoß für das
Notanker-Konto (409 `BOOTSTRAP_ACCOUNT`) und dann, wenn kein weiterer anmeldefähiger Systemverwalter
bliebe (409 `LAST_LOGIN_CAPABLE_ADMIN`).

**Einlösen.** Der Link führt auf die Seite `/handover`, die den Code sofort aus der Adresszeile
nimmt und über `POST /api/v1/auth/local/handover/preview` zeigt, **was mitgeht**: persönlicher
Space, Zahl der Space- und Gruppenmitgliedschaften, Systemrolle — dazu der Anlass der
Systemverwaltung und der Name des Anbieters, bei dem die Anmeldung erfolgt. Die Person wählt hier
nichts aus; der Anbieter steht am Code. Ein Klick startet den Code-Flow bei genau diesem Anbieter;
nach dem Rücksprung ruft die Seite `POST /api/v1/auth/local/handover/redeem` auf — mit dem
Access-Token des Anbieters **im Rumpf der Anfrage**, nie im `Authorization`-Header, und ohne
irgendeinen anderen Aufruf dazwischen: Ein Bearer-Token im Header würde das Konto anlegen, dessen
Abwesenheit diese Anfrage gerade prüfen muss. Der Endpunkt ist ohne Sitzung erreichbar und prüft das
Anbieter-Token selbst über dieselbe Anbieter-Registry wie jede andere Anfrage (Signatur, Issuer,
Ablauf, `azp`).

**Was dabei geschieht** — in einer Transaktion: `users.issuer` und `users.subject` werden auf die
Identität **aus dem geprüften Token** umgeschrieben, die lokalen Zugangsdaten sowie alle Link- und
Sperrlisteneinträge des Kontos gelöscht, alle Sitzungen widerrufen (Sitzungsmarker
`session_revoked:handed_over`) und der Code verbraucht. Danach die Mail `ACCOUNT_HANDED_OVER` und
das Ereignis `LOCAL_USER_HANDED_OVER` mit Anbieter-Kennung und Zahlen, **nie** mit dem Subject. Die
nächste Anmeldung über den Anbieter findet das Konto über den gewöhnlichen Schlüssel
`(Issuer, Subject)`; im Provisionierer gibt es dafür keinen Sonderpfad.

**Grenzen.** Unter `(Issuer, Subject)` darf noch kein Konto bestehen — sonst 409
`PROVIDER_ACCOUNT_EXISTS`, und es wird nichts zusammengeführt. Eine Anmeldung bei einem anderen als
dem angestoßenen Anbieter ist 409 `PROVIDER_MISMATCH`. Der Aussperrschutz greift **auch beim
Einlösen**: Zwischen Anstoß und Einlösung können Wochen liegen, und erst die Einlösung nimmt den
lokalen Verwalter weg. Unbekannter, abgelaufener, verbrauchter und zweckfremder Code sind dieselbe
Antwort (400 `TOKEN_INVALID`) — und ebenso ein Code, dessen Konto inzwischen durch die Verwaltung
oder wegen Inaktivität gesperrt oder abgelaufen ist: Eine Übergabe hebt keinen dieser Zustände auf
(eine laufende Fehlversuch-Sperre zählt nicht dagegen, sie endet von selbst). Ein Adresswechsel,
eine Sperre und ein erzeugtes Passwort schließen den offenen Übergabecode wie jeden anderen
offenen Link des Kontos, und mit einer gelöschten Anbieterzeile verschwinden alle für sie
vorbereiteten Übergaben (`ON DELETE CASCADE`). Einen Rückweg von einer Anbieteridentität zu einem
lokalen Konto gibt es nicht.

Die Mandantengrenze gilt auch für die Anmeldung: Eine Identität gehört zu **genau einer** Organisation.
Es gibt kein Konto, das mehrere Mandanten sieht, und keinen Wechsel zwischen ihnen innerhalb einer
Sitzung.

> **Mehrere OIDC-Anbieter** (Epic #1294): Eine Installation kann mehrere Identitätsanbieter
> gleichzeitig anbieten — die Anmeldeseite zeigt sie zur Auswahl. Die Architektur legt
> [ADR-0025](../decisions/0025-mehrere-oidc-anbieter.md) fest: Anbieter in der Datenbank mit
> Admin-Oberfläche, Identität strikt als `(Issuer, Subject)` ohne Zusammenführung über die E-Mail,
> Erstadministrator-Regel nur für den Standardanbieter, Verzeichnisabgleich an den Standardanbieter
> gebunden. Der Ist-Stand der Umsetzung steht im Epic.

**Anbieterverwaltung (gebaut, #1329).** Die Identitätsanbieter liegen in der Tabelle
`oidc_providers` und werden über die Admin-API `/api/v1/admin/oidc-providers` (nur `SYSTEM_ADMIN`)
gepflegt: Anzeigename, Issuer-URI (eindeutig), Client-ID, optional die Backend-seitige
JWK-Set-Adresse, die Claim-Zuordnung, Reihenfolge, Standardanbieter, aktiviert/deaktiviert. Jede
Änderung ist ein Audit-Ereignis (`OIDC_PROVIDER_*`) und wirkt ohne Neustart des Backends: Das
Backend prüft jedes Token gegen den Anbieter seines Issuers, ein deaktivierter Anbieter wird ab dem
nächsten Token abgewiesen (`unknown_issuer`). Der erste Anbieter ist automatisch der Standardanbieter;
er kann weder deaktiviert noch gelöscht werden, bevor ein anderer aktivierter Anbieter Standard ist.
Die Issuer-URI eines Anbieters, über den bereits Konten angelegt wurden, ist nicht änderbar. Beim
ersten Start im `oidc`-Modus übernimmt OPAA den bisherigen `OPAA_OIDC_*`-Anbieter einmalig als
Standardanbieter „Verzeichnisdienst"; `OPAA_OIDC_BOOTSTRAP=force` stellt ihn im Notfall wieder
her. Vom Betreiber eingegebene Adressen durchlaufen eine eigene Adressprüfung
(`OPAA_OIDC_TARGET_VALIDATION_*`); die Bootstrap-Adressen sind immer erlaubt. Ein Verbindungstest
prüft Discovery-Dokument und JWK-Set vor dem Speichern. Die Anmeldeseite (#1332) und die
Verwaltungsoberfläche (#1333) folgen.

**Kontenmodell je Anbieter (gebaut, #1330).** Die Identität eines Kontos ist das Paar
`(Issuer, Subject)`. Dieselbe Person bei zwei Anbietern hat zwei Konten mit getrennten
persönlichen Spaces, Systemrollen und Space-Rechten — eine Zusammenführung über die E-Mail findet
nie statt, auch nicht stillschweigend bei gleicher Adresse (Anbieter B kann kein Konto aus Anbieter
A übernehmen). Die Erstadministrator-Regel (`OPAA_INITIAL_ADMIN_EMAIL`) greift nur beim Anlegen
eines Kontos und nur über den **Standardanbieter** (im `dev`-Modus: den Dev-Issuer); dieselbe
Adresse aus einem zweiten Anbieter ergibt einen regulären Nutzer, ebenso ein Konto, das angelegt
wird, solange noch kein Standardanbieter existiert — beides wird als Warnung protokolliert, da die
Regel nur einmal, beim Anlegen, greift. Ein Token eines deaktivierten oder gelöschten Anbieters wird
mit `401` und `error_description="unknown_issuer"` abgewiesen, sodass die Oberfläche den Fall vom
abgelaufenen Token unterscheiden kann.

**Claim-Zuordnung je Anbieter (gebaut, #1331).** Jede Anbieterzeile legt fest, aus welchen
Token-Claims E-Mail und Anzeigename gelesen werden (Vorgabe `email`/`name`, Rückfall
`preferred_username`, nie das Subject) und optional, aus welchem Claim Rollen und Gruppen kommen —
dieselbe Token-Struktur ergibt unter zwei Anbietern zwei verschieden gelesene Identitäten. Ist ein
**Rollen-Claim** gesetzt (Pfad in Punktnotation, z. B. `realm_access.roles`, mit den Rollenwerten
für `SYSTEM_ADMIN` und `AUDITOR`), ist der Anbieter für diese Systemrollen führend: Bei jeder
Anfrage wird die Rolle aus dem Token abgeleitet (`SYSTEM_ADMIN` vor `AUDITOR`, sonst regulärer
Nutzer) und nur bei Abweichung geschrieben — als Rollenänderung mit Audit-Ereignis unter dem
Systemprozess-Akteur `identity-provider`. Drei Sicherungen: Der letzte `SYSTEM_ADMIN` wird nie per
Token entzogen (bedingter, je Organisation serialisierter `UPDATE`; der abgelehnte Entzug wird
protokolliert und als `SYSTEM_ADMIN_ROLE_REVOCATION_REFUSED` auditiert), die manuelle Rollenvergabe
ist für Konten eines solchen Anbieters gesperrt (409), und die Oberfläche (#1333) verlangt beim
Setzen des Rollen-Claims eine Bestätigung. `AUDITOR` ist gegen den Entzug durch einen benannten
Claim nicht geschützt — gegen einen fehlenden oder unbrauchbaren Claim seit #1830 schon (siehe
unten). Ist ein **Gruppen-Claim** gesetzt, werden die Gruppennamen des Tokens bei jeder Anmeldung zu
Mitgliedschaften in Gruppen der Art „Gruppe aus dem Identitätsanbieter" (`IDENTITY_PROVIDER`).
Jede solche Gruppe benennt ihren Anbieter als Fremdschlüssel (`groups.provider_id`, seit #1812);
`external_id` trägt den blanken Namen aus dem Claim, bis zu 255 Zeichen: gleichnamige
Gruppen zweier Anbieter sind zwei Gruppen, ein Anbieter erreicht nie die Gruppen eines anderen;
Mitgliedschaften folgen dem Token (Historie `IDENTITY_PROVIDER_ADDED`/`_REMOVED`, Audit unter
`identity-provider`), die Gruppen selbst bleiben bestehen und sind in der Gruppenverwaltung
schreibgeschützt; sie sind weder Gegenstand des Verzeichnisabgleichs noch als „Sicht als"-Bereich
wählbar. Der **Verzeichnisabgleich** ist seit #1816 an die **Anbieterzeile selbst** gebunden, nicht
mehr an den Standardanbieter: Er ist je Anbieter einschaltbar, löst die Subjects des Verzeichnisses
nur unter den Konten **dieses** Anbieters auf (ein gleichnamiges Subject eines zweiten Anbieters
erbt keine Mitgliedschaft) und verwaltet ausschließlich dessen Organisationseinheiten. Ohne
Anbieterzeile — im Betriebsmodus `dev` — findet gar kein Abgleich statt; ist die Zeile deaktiviert,
pausiert er. Schaltet ein Haus einen Anbieter vom Gruppen-Claim auf den Abgleich um, bleiben seine
Token-Gruppen mit eingefrorener Mitgliedschaft stehen und sind **kein neues Ziel einer
Berechtigung** mehr; bestehende bleiben unverändert.

**Was ein Token über Rollen sagt — und was nicht (gebaut, #1830).** „Keine Auskunft" und
„ausdrücklich keine erhöhte Rolle" sind zwei verschiedene Aussagen, und nur die zweite ist ein
Entzug — dieselbe Unterscheidung wie bei den Gruppen (siehe
[Gruppensynchronisation ist ein Rechteereignis](#gruppensynchronisation-ist-ein-rechteereignis)).
Der Anmeldeweg unterscheidet deshalb drei Fälle:

| Was das Token sagt | Was geschieht |
|---|---|
| Der Rollen-Claim ist vorhanden und nennt einen der konfigurierten Rollenwerte | Die Rolle wird übernommen (`SYSTEM_ADMIN` vor `AUDITOR`), bei Abweichung geschrieben und auditiert. |
| Der Rollen-Claim ist vorhanden und nennt **keinen** der konfigurierten Werte — auch der leere Claim | Herabstufung auf `USER`, auditiert. Der Anbieter bleibt die führende Quelle; nur der letzte anmeldefähige `SYSTEM_ADMIN` wird davon ausgenommen. |
| Der Rollen-Claim **fehlt**, ist falsch geformt (anderer Typ, nur unbrauchbare Werte) oder wurde vom Anbieter durch einen Overage-Hinweis (`_claim_names`) ersetzt | **Nichts ändert sich.** Die gespeicherte Rolle bleibt, es wird kein Rollenereignis und kein Audit-Eintrag geschrieben. Der Vorfall wird je Anbieter **und Ursache** gedrosselt im Anwendungsprotokoll gemeldet (höchstens alle 300 Sekunden einmal); ein Wechsel der Ursache ist eine eigene Meldung. |

Der dritte Fall ist der praktisch wichtige: Wird am Identitätsanbieter der Rollen-Mapper entfernt,
umbenannt oder bei einer Umstellung vorübergehend falsch gesetzt, verlöre sonst **jeder** Revisor
seine `AUDITOR`-Rolle und **jeder Systemverwalter bis auf einen** seine `SYSTEM_ADMIN`-Rolle — je
Anmeldung eine Herabstufung, jede davon als reguläres „Rolle geändert"-Ereignis, das den Vorfall wie
eine beabsichtigte Änderung aussehen lässt. Das Nachladen über die Schnittstelle des Anbieters ist
auch hier nicht gebaut.

**Anmeldeseite mit mehreren Anbietern (gebaut, #1332).** `GET /api/v1/auth/config` liefert ohne
Anmeldung die aktivierten Anbieter, deren Schlüssel das Backend abrufen konnte — Anzeigename,
Issuer-URI (zugleich die Authority des Anmeldeflusses), Client-ID, Standard-Kennzeichen und
Reihenfolge, nichts über Claim-Zuordnung oder Konten. Die Anmeldeseite zeigt je Anbieter eine
Schaltfläche in der konfigurierten Reihenfolge; vorgeschlagen (die eine primäre Schaltfläche)
wird der zuletzt im Browser benutzte Anbieter, sonst der Standardanbieter, sonst der erste. Mit
genau einem Anbieter bleibt es beim direkten Einstieg. Einen eigenen Weg „Mit anderem Konto
anmelden" gibt es nicht mehr (#1629, #1630): Den Kontowechsel übernimmt der Anbieter, dessen Sitzung
das Abmelden in OPAA mitbeendet. Die SPA hält je Anbieter einen eigenen
OIDC-Client; der Anbieter des laufenden Flusses und der aktiven Sitzung ist je Tab gemerkt
(`sessionStorage`), der zuletzt benutzte nur als Vorschlag (`localStorage`). Der Callback
`<Origin>/auth/callback` ist für alle Anbieter derselbe; wurde der Anbieter während des Flusses
deaktiviert, erklärt die Seite das und führt zurück zur Anmeldung. Die Abmeldung ist ein
RP-initiierter Logout beim Anbieter der aktiven Sitzung; ein Anbieter ohne
`end_session_endpoint` endet in einer lokalen Abmeldung mit Hinweis. Antwortet das Backend auf
ein Token mit `unknown_issuer` (Anbieter deaktiviert oder gelöscht), wird die Sitzung ohne
Erneuerungsversuch beendet und der Grund benannt.

**Verwaltungsoberfläche (gebaut, #1333).** Unter Administration → Identitätsanbieter (nur
`SYSTEM_ADMIN`) stehen die Anbieter in Anmeldereihenfolge mit Standard-Kennzeichen, Zustand
(erreichbar, nicht erreichbar mit Grund aus der Registry, deaktiviert) und dem Hinweis, wenn ein
Anbieter die Rollen führt. Reihenfolge per Pfeilschaltflächen, Standardanbieter wechseln,
aktivieren/deaktivieren und löschen jeweils mit Konsequenz-Hinweis (Nutzer des Anbieters können
sich nicht mehr anmelden, Konten bleiben); der Standardanbieter bietet weder Deaktivieren noch
Löschen an. Der Formulardialog führt Anzeigename, Issuer-URI, Client-ID, die Backend-seitige
JWK-Set-Adresse (als Vertrauensanker benannt) und die Claim-Zuordnung — kein Secret-Feld, die SPA
ist Public Client. Das Setzen eines Rollen-Claims verlangt eine ausdrückliche Bestätigung; ein
Verbindungstest prüft Discovery-Dokument und JWK-Set vor dem Speichern; Fehlermeldungen des
Backends (etwa die verweigerte Issuer-Änderung eines Anbieters mit Konten) erscheinen im Dialog.
Eine Anleitung nennt die aus dem eigenen Origin zusammengesetzte Weiterleitungs-URI
`<Origin>/auth/callback` und den Origin, den CSP-Schritt (`OPAA_CSP_CONNECT_SRC_EXTRA`, Neustart
des Frontend-Containers) und die Adress-Allowlist des Backends. Im `dev`-Modus weist die Seite
darauf hin, dass Anbieter erst im OIDC-Modus wirken.

**Der Verzeichniszugang eines Anbieters** (#1817) wird an derselben Zeile hinterlegt, geprüft und
entfernt (`PUT`/`POST …/test`/`DELETE …/directory-connector`) und bleibt `SYSTEM_ADMIN`-Sache.
**Der Verbindungstest fällt nicht unter #1856** — die Abgrenzung, die dort für die Quellprobe der
Bibliotheken gezogen wurde (eine Probe ohne Objektbezug braucht ein eigenes Recht, weil sie sonst
jedem offenstünde, der irgendetwas anlegen darf), trägt hier nicht: Es gibt kein engeres Recht als
die Systemverwaltung, das eine Verzeichnisanbindung einrichten dürfte, und die Probe erreicht
ausschließlich eine Adresse, die dieselbe Allowlist passiert hat wie der Issuer. Das Geheimnis des
Dienstkontos ist in keiner Antwort enthalten; lässt es sich nicht mehr entschlüsseln, bleibt die
Anbieterverwaltung bedienbar und meldet den Zugang beim nächsten Lauf als nicht erreichbar.

---

## Verzeichnisdienst: Synchronisation und Kontenlebenszyklus

### Was übernommen wird

OPAA gleicht mit dem Verzeichnisdienst über einen **wiederkehrenden Abgleich** ab, den es selbst
anstößt. **SCIM ist ausdrücklich nicht der Weg** ([ADR-0036](../decisions/0036-berechtigungsmodell-gruppen-und-faehigkeiten.md),
Entscheidung 3): Ein Pull nutzt die gebaute Schutzmechanik unverändert — sie ist auf Schnappschüsse
gebaut, ein Push liefert Deltas, für die es weder ein „leeres Ergebnis" noch eine Schwelle je Lauf
gibt —, öffnet keinen eingehenden Schreibpfad und kommt mit jeder Quelle aus, die eine
Leseschnittstelle hat.

```
Abgleich: turnusmäßig je Anbieter (Vorgabe alle 6 Stunden, 5 Minuten bis 1 Woche)

Aus dem Verzeichnis:
  - Benutzernamen und E-Mail-Adressen
  - Gruppenmitgliedschaften
  - Organisationseinheit (Referat, Abteilung, Amt)
  - Funktionsbezeichnung
  - Kontostatus (aktiv / gesperrt / ausgeschieden)
```

Das Verzeichnis ist die **führende Quelle**. Wer dort gesperrt ist, ist in OPAA gesperrt; ein
abweichender Zustand in OPAA ist kein Zustand, den ein Admin von Hand herstellen können sollte.

**Je Anbieter genau eine Gruppenquelle (ADR-0036, Entscheidung 2):** Der Verzeichnisabgleich ist
**je Anbieter ein- und ausschaltbar** und gilt für die Konten genau dieses Anbieters — nur unter
ihnen werden die Subjects seines Verzeichnisses aufgelöst, und nur seine Organisationseinheiten
entstehen daraus. Ein Anbieter mit eingeschaltetem Abgleich hat keinen Gruppen-Claim und umgekehrt;
die Verwaltung weist den Konflikt mit `409` und verständlichem Grund ab, weil dieselbe Gruppe sonst
zweimal entstünde, mit zwei Wahrheiten über die Mitgliedschaft und zwei Entzugszeitpunkten. Ein
gleichnamiges Subject bei zwei Anbietern ergibt zwei Konten, und jedes erhält nur die Gruppen seines
eigenen Anbieters. **Der Abgleich eines deaktivierten Anbieters pausiert.**

**Der Zeitverzug ist für alle sichtbar, nicht nur für die Systemverwaltung.** „Meine Gruppen“
(`GET /api/v1/me/groups`) nennt je Anbieter Mechanismus, Intervall und Zeitpunkt des letzten
Abgleichs. Das ist keine schutzwürdige Information und erspart den Anruf bei der IT, wenn eine neu
aufgenommene Kollegin bis zum nächsten Lauf nichts sieht.

**Die Genauigkeit der Rechtehistorie hängt am Mechanismus.** Im Token-Modus erscheint eine
Verzeichnisänderung vom 3. März für eine Person, die sich am 20. März anmeldet, mit dem 20. März; im
Abgleichmodus mit dem Zeitpunkt des Laufs.

### Der Lebenszyklus eines Kontos

```
   Anlage im            erster            Rollenwechsel /        Ausscheiden
   Verzeichnis          Login             Gruppenänderung        im Verzeichnis
        │                 │                     │                     │
        ▼                 ▼                     ▼                     ▼
   Konto bereit  →   Konto aktiv   →   Rechte neu berechnet   →   sofort deaktiviert
                                              │                          │
                                              ▼                          ▼
                                    Rechteereignis im Protokoll   Assets: „Nachfolge offen"
```

1. **Anlage.** Ein Konto entsteht durch die Bereitstellung aus dem Verzeichnis, nicht durch eine
   Einladung im Produkt. Wer im Verzeichnis nicht existiert, hat in OPAA nichts. Konten anderer
   Anbieter entstehen bei der ersten Anmeldung über diesen Anbieter — mit eigener Identität
   `(Issuer, Subject)`, ohne Verzeichnisgruppen und ohne Erstadministrator-Regel.
2. **Erste Anmeldung.** Der Standard-Space (`isDefault`) entsteht dabei; eine Wissensbibliothek legt die Person
   bei Bedarf selbst an (siehe [Wissensquellen](./knowledge-sources.md)). Ein Anlaufbestand an
   Assets ergibt sich aus den Gruppen der Person.
3. **Änderung.** Wechselt jemand das Referat, ändern sich seine Gruppenmitgliedschaften — und damit
   seine Rechte, **ohne dass jemand in OPAA etwas tut**. Das ist der Regelfall und der Grund, warum die
   Synchronisation als Rechteereignis behandelt wird (siehe unten).
4. **Ausscheiden.** Verschwindet ein Konto im Verzeichnis oder wird es dort gesperrt, wird der Zugang in
   OPAA **beim nächsten Abgleich des betreffenden Anbieters automatisch entzogen** — ohne Ticket,
   ohne Handgriff und ohne Bedingung. Das ist die Anforderung, an der der IT-Grundschutz und jede Prüfung als Erstes ansetzen.
   Für Konten anderer Anbieter ist der Hebel der Anbieter selbst (gesperrtes Konto: keine Anmeldung
   mehr, Token-Gruppen enden mit der nächsten Anmeldung) oder das Deaktivieren des ganzen Anbieters in
   der Anbieterverwaltung (ab dem nächsten Token abgewiesen, Konten bleiben).

**Lokale Konten haben denselben Lebenszyklus, aber keinen Automatismus, der ihn von außen anstößt.**
Sie entstehen durch eine Einladung der Systemverwaltung oder durch Selbstregistrierung, nicht durch
eine Bereitstellung; ihr Ausscheiden erkennt kein Verzeichnis. Genau deshalb hängen an ihnen die
Ersatzmechanismen aus den Abschnitten oben: Pflicht-Anlagegrund und Ablaufdatum bei der Anlage,
Sperre nach Inaktivität, Erinnerung vor dem Ablauf, vierteljährliche Wiedervorlage der Konten ohne
Ablaufdatum und die Empfehlung, die lokale Verwaltung im Regelbetrieb abgeschaltet zu lassen. Der
Regelweg beim Ausscheiden ist auch hier die **Sperre**, nicht die Löschung; gelöscht werden kann nur
ein Konto ohne Besitz, praktisch also ein nie benutztes.

**Die Deaktivierung wird nie durch offene Eigentumsfragen aufgehalten.** Eine Regel, die verlangt, erst
die Nachfolge für dutzende Assets zu klären, wird am Freitagnachmittag umgangen und schützt dann gerade
nicht. Was mit den Assets geschieht, steht unter [Offboarding](#offboarding).

#### Wie der Entzug gebaut ist (#1818)

Der Abgleich liest den Kontostatus aus derselben Quelle wie die Gruppen — beim Keycloak-Konnektor das
Kennzeichen `enabled` je Konto des Realms. Ein Konto, das das Verzeichnis als gesperrt meldet **oder
gar nicht mehr meldet**, wird beim nächsten Lauf gesperrt. Dabei gilt:

- **Gesperrt, nicht gelöscht.** Mitgliedschaften, Spaces, Rollen und Eigentum bleiben unberührt; die
  Sperre ist rückholbar, und der nächste Lauf nimmt sie zurück, sobald das Verzeichnis das Konto
  wieder als freigeschaltet meldet. OPAA schreibt nie ins Verzeichnis, und eine Entsperrung von Hand
  gibt es deshalb nicht — sie wäre beim nächsten Lauf wieder weg.
- **Sofort wirksam, auch gegen ein laufendes Token.** Jede Anfrage eines gesperrten Kontos wird mit
  `401` abgewiesen, und seine Zugangstokens ([ADR-0035](../decisions/0035-fremdzugaenge-mcp-server-und-zugangstokens.md))
  treten mit der Sperre außer Kraft. Die betroffene Person erhält dabei **Grund und Ansprechstelle**,
  nicht nur eine Abweisung.
- **Dieselbe Schwelle, derselbe Bestätigungsweg** wie bei Mitgliedschaftsentzügen: Ein Lauf, der einen
  auffällig großen Teil der Konten eines Anbieters sperren würde, legt seinen Plan zur Bestätigung vor,
  statt ihn anzuwenden; die Zahl der Sperren steht neben den entzogenen Mitgliedschaften in der
  Verwaltungsübersicht. Eine **leere Kontenliste** sperrt niemanden — sie ist ein harter Abbruch ohne
  bestätigbaren Plan, genau wie eine leere Gruppenliste.
- **Der letzte anmeldefähige Systemverwalter wird nie gesperrt** — über denselben Wächter wie jeder
  andere Entzug der Systemverwalterrolle. Die zurückgehaltene Sperre steht im Bericht des Laufs, statt
  stillschweigend zu entfallen.
- **Jede Sperre und jede Entsperrung ist ein Protokollereignis**, verbunden mit dem Kopfeintrag des
  Laufs. Zusätzlich wird der **Kontozustand historisiert** (aktiv/gesperrt mit `valid_from`/`valid_to`):
  Sie belegt beide Zeitpunkte lückenlos und trägt die Stichtagsauskunft, wenn die Protokollfrist
  abgelaufen ist. Die Kette eines Kontos beginnt mit seiner ersten Zustandsänderung und reicht ab da
  bis zu seiner Anlage zurück; ein nie gesperrtes Konto war seit seiner Anlage aktiv. Einen zweiten
  Lesepfad — einen „Verlauf" am Konto in der Benutzerverwaltung — gibt es bewusst nicht.

### Gruppensynchronisation ist ein Rechteereignis

Die übernommenen **Gruppen sind Rechtesubjekt**: Rechte an Assets werden an Nutzer oder an Gruppen
vergeben, und die Verteilungsstufe „Fachbereich" ist ein Grant an die Abteilungs- oder Amts-Gruppe.
Details im [Rechtemodell](./spaces-and-assets.md#gruppen-als-rechtesubjekt).

Daraus folgt eine Festlegung, die leicht übersehen wird: Ein Synchronisationslauf ist **keine technische
Wartungsroutine, sondern eine Rechteänderung im laufenden Betrieb**. Eine einzige geänderte
Gruppenmitgliedschaft kann Zugriff auf ganze Bestände geben oder nehmen.

- Jede **bewirkte** Rechteänderung wird einzeln festgehalten — je Änderung, nicht je Lauf. Ein Lauf, der
  meldet „412 Objekte verarbeitet", beantwortet keine Prüferfrage.
- Änderungen wirken **sofort** auf die rechtebewusste Suche; es gibt keinen zwischengespeicherten
  Rechtestand, der eine Entziehung überdauert.
- Ein Lauf, der eine **auffällig große** Zahl an Entzügen oder Zuweisungen bewirken würde — etwa weil im
  Verzeichnis eine Gruppe umbenannt wurde —, wird angezeigt und ist bestätigungspflichtig, statt
  stillschweigend durchzulaufen. Der häufigste Fehlerfall ist nicht der Angriff, sondern die
  fehlgeschlagene Umstellung. Vier Festlegungen zum Lebenszyklus eines solchen Plans:
  **ein neuer Lauf ersetzt ihn** (sonst staut sich alle sechs Stunden einer, und irgendwann wird der
  älteste bestätigt); **bestätigt wird gegen einen frischen Schnappschuss**, und weicht das Ergebnis
  vom gezeigten ab, wird neu vorgelegt statt angewendet; **sein Alter steht in der Statuszeile** und
  auf der Verwaltungsübersicht, nicht nur auf einer Unterseite; und **Bestätigung wie Verwerfen sind
  Protokollereignisse** mit der handelnden Person und ihrem Anlass.
- Fällt der Verzeichnisdienst aus, gilt der **letzte bekannte Stand weiter**, und der Ausfall wird
  gemeldet. Ein leeres Abgleichergebnis darf nie als „alle Gruppenmitgliedschaften entfallen" gedeutet
  werden — und es ist der eine Fall, der **nicht** bestätigungsfähig ist: „Die Quelle hat nicht
  geantwortet, wie sie soll" darf niemand wegklicken.
- **Der Wechsel des Mechanismus entzieht nichts still.** Token-Gruppen und Verzeichnisgruppen sind
  verschiedene Objekte. Wird der Abgleich für einen Anbieter eingeschaltet, weist der
  Differenzbericht des ersten Laufs seine Token-Gruppen als **„werden nicht mehr gepflegt"** aus; sie
  bleiben mit eingefrorener Mitgliedschaft stehen. Die
  [Übertragung](#rechte-einer-gruppe-auf-eine-andere-übertragen-gebaut-1834) ihrer Berechtigungen
  auf die neuen Gruppen ist eine eigene Handlung und wird nie automatisch ausgelöst.

**Dieselbe Regel gilt für die Gruppen aus dem Token.** „Keine Auskunft" und „ausdrücklich keine
Gruppen" sind zwei verschiedene Aussagen, und nur die zweite ist ein Entzug. Der Anmeldeweg
unterscheidet deshalb drei Fälle:

| Was das Token sagt | Was geschieht |
|---|---|
| Der Gruppen-Claim ist vorhanden und nennt Gruppen | Die Mitgliedschaften dieses Anbieters werden auf genau diese Gruppen gebracht — Zugang und Entzug wie bisher, je Änderung historisiert und protokolliert. |
| Der Gruppen-Claim ist vorhanden und **leer** | Entzug aller Mitgliedschaften dieses Anbieters, historisiert und protokolliert. Der Anbieter bleibt die führende Quelle, und „ein Entzug wirkt bei der nächsten Anmeldung" bleibt gültig. |
| Der Gruppen-Claim **fehlt**, ist falsch geformt, nennt nur unbrauchbare Werte oder wurde vom Anbieter durch einen Overage-Hinweis ersetzt | **Nichts ändert sich.** Der letzte bekannte Stand bleibt; Mitgliedschaften, Rechtehistorie und Nachweisprotokoll bleiben unberührt. Der Vorfall wird je Anbieter **und Ursache** gedrosselt gemeldet — ein Wechsel der Ursache ist eine eigene Meldung, der Overage eigens benannt. |

Der dritte Fall ist kein Randfall: Wird am Identitätsanbieter der Gruppen-Mapper versehentlich
entfernt oder umbenannt, trüge jede einzelne Anmeldung sonst einen stillen Rechteentzug — Konto für
Konto, ohne dass je eine Schwelle überschritten würde, an der ein Lauf bestätigungspflichtig wird.
Der **Overage-Hinweis** ist der Fall, in dem der Anbieter den Claim wegen seiner Größe durch einen
Verweis ersetzt (Entra ID ab 200 Gruppen, `_claim_names` nach OpenID Connect 5.6.2); das Nachladen
über die Schnittstelle des Anbieters ist nicht gebaut, gemeldet wird der Vorfall trotzdem.

Die Synchronisation ändert nur die **Herkunft** von Gruppenmitgliedschaften, nicht das Rechtemodell. In
der ersten Ausbaustufe werden Gruppen im System gepflegt.

#### Herkunft einer Gruppe (gebaut, #1812)

Jede Gruppe trägt ihre Herkunft als **Verweis auf den Identitätsanbieter**, nicht als Namenszusatz:
Entweder sie gehört zu genau einem Anbieter — dann stammt sie aus dessen Verzeichnisabgleich oder
aus dessen Gruppen-Claim —, oder sie ist eine **interne Gruppe** dieser Installation. Die Antwort der
Schnittstelle nennt beides: `origin` (`INTERNAL` oder `PROVIDER`), den Anbieter mit Anzeigename und
Kennzeichen „extern", und `sourcePath`, den Pfad der Quelle („/Haus/Abteilung 5/Referat 50"). Ohne
diesen Pfad sind die gleichnamigen Untergruppen eines Verzeichnisses („Leitung", „Sachbearbeitung")
nicht auseinanderzuhalten, und der Anbietername hilft dort nicht.

Gleichnamige Gruppen zweier Anbieter bleiben **zwei Gruppen** und sind über ihre Kennung getrennt;
eine Namenseindeutigkeit wird nicht erzwungen. Die Anzeige unterscheidet sie („Referat 50 ·
Verzeichnis Haus A"), der gespeicherte Name trägt nie ein Präfix.

**Ein Anbieter, den die Systemverwaltung als „extern" kennzeichnet**, gehört einem anderen Haus.
Vorgabe ist: jeder Anbieter außer dem Standardanbieter ist extern, bis die Systemverwaltung es
ändert; die Zeile der lokalen Konten ist es nie. Das Kennzeichen ist nur durch die Systemverwaltung
änderbar und wird als Änderung der Anbieterzeile protokolliert.

**Ein deaktivierter Anbieter lässt seine Gruppen, Mitgliedschaften und Berechtigungen unverändert
stehen — sie sind aber keine wirksamen Gruppen mehr:** Sie sind kein neues Ziel einer Berechtigung,
und eine Berechtigung an sie wird mit einem Hinweis abgelehnt. Bestehende Berechtigungen bleiben
unangetastet. Ohne diese Regel wirkte eine Freigabe an „Referat 50 (Anbieter deaktiviert)" für
niemanden — und mit der Wiederaktivierung schlagartig für alle, ohne erneute Entscheidung.

**Wird ein Anbieter gelöscht**, gehen seine Gruppen mit ihm — aber nur, solange keine von ihnen noch
wirkt. Trägt eine seiner Gruppen noch eine Berechtigung oder ist sie Eigentümerin eines Objekts,
wird das Löschen mit `409` abgelehnt; die Meldung nennt die Zahl der betroffenen Gruppen,
Berechtigungen und Objekte. **Aus dieser Ablehnung führt seit #1834 die
[Übertragung](#rechte-einer-gruppe-auf-eine-andere-übertragen-gebaut-1834) heraus:** Sind die
Wirkungen der Gruppen auf die des neuen Anbieters übergegangen, fällt der `409` von selbst.
Deaktivieren bleibt daneben jederzeit möglich.

> Festgeschrieben in [ADR-0036](../decisions/0036-berechtigungsmodell-gruppen-und-faehigkeiten.md),
> Entscheidungen 2 und 11.

#### Interne Gruppen: Verantwortliche, Freigabe und Schutzkennzeichen (gebaut, #1814 — ohne die Schutzwirkungen nach außen, #1820)

Eine **interne Gruppe** wird nicht von der Systemverwaltung gepflegt, sondern von benannten
**Verantwortlichen**. Die Vorentscheidung „Wer eine Querschnittsgruppe braucht, legt sie explizit
an" trägt im Alltag nur, wenn nicht jede Mitgliederänderung ein Ticket ist — und lokale Konten
([ADR-0033](../decisions/0033-lokale-benutzerverwaltung.md)) bekommen Gruppen ausschließlich auf
diesem Weg.

- **Verantwortliche sind ausschließlich natürliche Personen.** Eine Gruppe als Verantwortliche wäre
  Schachtelung durch die Hintertür. Verantwortlich zu sein macht niemanden zum Mitglied.
- **Wer eine interne Gruppe anlegt, wird ihr erster Verantwortlicher** — im selben Vorgang, damit
  keine Gruppe ohne Verantwortliche entsteht.
- **Verantwortliche dürfen:** Mitglieder aufnehmen und entfernen (nur Konten der eigenen
  Organisation), Name und Beschreibung ändern, weitere Verantwortliche ernennen und entlassen, die
  Gruppe zur Verwendung freigeben, sie als geschützt kennzeichnen und sie löschen — Letzteres unter
  denselben Bedingungen wie bisher (`409`, solange die Gruppe Berechtigungen trägt, ein Anlegerecht
  hält oder ein Objekt besitzt).
- **Die Rechteprüfung ist je Gruppe, nicht je Rolle.** Die Schnittstelle liegt deshalb unter
  `/api/v1/groups` statt unter `/admin`; wer nicht verantwortlich ist, erhält dieselbe Antwort wie
  für eine unbekannte Gruppe (`404`). Ein `403` verriete, dass es die Gruppe gibt. Unter `/admin`
  bleibt genau eine Gruppenoperation: die Liste **aller** Gruppen der Organisation.
- **Die Systemverwaltung darf jede Gruppe pflegen** — sie muss eine Bestandsgruppe ohne
  Verantwortliche wieder besetzen können. **Die eine Ausnahme ist das Schutzkennzeichen** (siehe
  unten).
- **Der letzte Verantwortliche kann sich nicht selbst entfernen.** Die Abgabe der Verantwortung ist
  ein eigener, sichtbarer Schritt: erst die Nachfolge benennen, dann selbst zurücktreten. Nur die
  Systemverwaltung kann den letzten Verantwortlichen entlassen — ein Konto, das das Haus verlässt,
  muss lösbar sein; die Gruppe steht danach ohne Verantwortliche da und gehört in die Betriebsliste
  offener Nachfolgen.
- **Aufnahme und Entfernung werden der betroffenen Person angezeigt** — in der Anwendung, ohne Mail.
  Sonst endet ein Leserecht „sofort", ohne dass die Person erfährt, dass und durch wen. **Mitglieder
  sehen ihre Verantwortlichen namentlich** (`GET /api/v1/me/groups`).
- **Verantwortlichkeit ist Protokoll, keine Historienzeile.** Ernennung, Entlassung und Abgabe
  erzeugen Audit-Ereignisse; eine Historientabelle gibt es bewusst nicht, weil Verantwortung kein
  Leserecht trägt und für „wer konnte am Tag X was lesen" ohne Bedeutung ist. Mitglieder**änderungen**
  stehen unverändert in der Rechtehistorie — mit dem Verantwortlichen als Akteur.
- **Anbietergruppen bleiben schreibgeschützt.** Sie haben keine Verantwortlichen, sondern
  Ansprechstellen, die die Systemverwaltung benennt (#1875).

**Freigabe zur Verwendung.** Eine interne Gruppe ist erst dann für andere Rechtevergebende wählbar,
wenn ihre Verantwortlichen sie **freigegeben** haben — das Gegenstück zu `listed` bei Assets:
Auffindbarkeit ist eine bewusste Handlung. Drei Festlegungen dazu:

1. **Die Durchsetzung liegt im Dienst, nicht in der Auswahlliste**, und gilt für jeden Weg — auch
   für die Eingabe der Kennung von Hand. Eine nicht freigegebene interne Gruppe ist für einen
   Aufrufer, der weder Mitglied noch Verantwortlicher noch `SYSTEM_ADMIN` ist, „nicht gefunden".
   Gebaut ist das an beiden Wegen, die eine Gruppe heute zum Zuge bringen: der Berechtigung auf ein
   Objekt (`AssetGrantService`) und der Aufnahme als Mitglied eines Space (`SpaceService`). Die
   Vergabe eines Anlegerechts fragt nicht danach, weil sie ausschließlich der Systemverwaltung
   offensteht und die Freigabe für sie ohnehin ohne Wirkung ist.
2. **Die Migration hat „freigegeben" gesetzt** für jede interne Gruppe, die am Migrationstag eine
   Wirkung hatte: eine Berechtigung auf ein Objekt, ein Anlegerecht, Eigentum an einem Objekt oder
   eine Mitgliedschaft in einem Space (#1815). Niemand verliert eine Möglichkeit, die er benutzt
   hat; „Vorgabe nicht freigegeben" gilt damit **nur prospektiv**.
3. **Das ist eine Bestandsänderung, und sie wird ausgesprochen:** Ein `MANAGER` kann eine neu
   angelegte interne Gruppe erst nach deren Freigabe als Empfänger wählen. Die Rücknahme der
   Freigabe nimmt die Gruppe aus jeder Auswahl; bestehende Berechtigungen bleiben unberührt.

**Geschützte Gruppen.** Für die Gruppen der Personalvertretung, der Schwerbehindertenvertretung, der
Gleichstellung und für Personalvorgänge gilt dieselbe Sonderstellung wie für die entsprechenden
Bibliotheken ([hybrid-retrieval.md](./hybrid-retrieval.md#berechtigungs-leitplanken), Leitplanke
(e)). **Das Kennzeichen setzt und löst die zuständige Stelle selbst, nicht die Administration** —
bei einer internen Gruppe ihre Verantwortlichen. Eine Systemverwaltung, die es setzen oder lösen
könnte, machte den Schutz zu ihrem; die Antwort auf ihren Versuch ist `403` mit dem Code
`STEWARDSHIP_REQUIRED`. **Dieselbe Antwort bekommt sie an der Freigabe einer geschützten Gruppe**:
Wer die Gruppe in jede Auswahl stellen kann, entscheidet sonst über den Schutz, ohne das Kennzeichen
anfassen zu dürfen.

**Was vom Schutz gebaut ist.** Gebaut sind das Kennzeichen, sein Vorbehalt für die Verantwortlichen
samt der Freigabe, und das Audit-Ereignis jeder Änderung. **Noch nicht gebaut** sind die drei
Wirkungen nach außen: nicht über die Suche auffindbar, in fremden Listen namenlos als „geschützte
Gruppe", und statt der Mitgliederliste die Ansprechstelle für den, der ihr ein Recht einräumt. Sie
kommen mit der gemeinsamen Subjekt-Auswahl (#1820); bis dahin verhält sich eine geschützte,
freigegebene Gruppe gegenüber Dritten wie jede andere freigegebene Gruppe.

**Der Abruf der Mitgliederliste durch die Systemverwaltung ist ein Audit-Ereignis**
(`GROUP_MEMBERS_READ` mit der Zahl der Mitglieder) — ADR-0036, Entscheidung 9 räumt ihr die volle
Liste ein und hält dafür fest, dass sie sie abgerufen hat. Wer die Gruppe selbst verantwortet,
erzeugt beim Lesen nichts.

> Festgeschrieben in [ADR-0036](../decisions/0036-berechtigungsmodell-gruppen-und-faehigkeiten.md),
> Entscheidungen 4 und 9.

#### Rechte einer Gruppe auf eine andere übertragen (gebaut, #1834)

Vier Anlässe brauchen dieselbe Mechanik, und ohne sie endet jeder in Handarbeit: die
**Reorganisation** (Referat 50 wird zu Referat 52), die **Anbieterablösung**, der **Wechsel des
Mechanismus** von Token auf Verzeichnisabgleich und die **Nachfolge**. Statt 200 Objekte einzeln
umzuhängen — was erfahrungsgemäß in einem `UPDATE` auf der Datenbank endet und die Rechtehistorie ab
dem Tag wertlos macht — gibt es **eine** protokollierte Operation.

**Was sie bewegt.** Der Umfang ist wählbar; alles oder eine Teilmenge:

| Umfang | Von Gruppe auf Gruppe | Von Gruppe auf Person | Von Person auf Person |
|---|---|---|---|
| Berechtigungen an Objekten | ja | — | — |
| Mitgliedschaften in Spaces | ja | — | — |
| Anlegerechte | ja | — | — |
| Eigentum an Bibliotheken | ja | ja | ja |
| Eigentum an Spaces | — | — | ja |
| Verantwortung für interne Gruppen | — | — | ja |

**Ein Space gehört immer einer natürlichen Person** (Entscheidung 6), sein Eigentum wechselt deshalb
nur zwischen Personen; der neue Eigentümer wird dabei, falls nötig, als `ADMIN`-Mitglied aufgenommen.

**Mit dem Eigentum geht die Rolle mit.** Eine Rolle an einer Bibliothek entsteht aus Grants, nicht
aus der Eigentümerspalte — die Übertragung des Eigentums verschiebt deshalb auch den Grant, der zum
Eigentum gehört: `OWNER` für eine Person, `MANAGER` für eine Gruppe, wie beim Anlegen. Ohne das hielte
der Nachfolger nichts und die Quelle alles.

**Bei einer Person als Quelle bleibt es bei Eigentum und Verantwortung.** Berechtigungen und
Space-Mitgliedschaften einer Person sind hier weder übertragbar noch aufzählbar: Die Vorschau wäre
sonst eine Abfrage „alle Wirkungen der Person X" für die Systemverwaltung — ohne Vollmacht und ohne
Begründung, also genau die personenbezogene Rechteübersicht, die für die Vergangenheit unter einer
Vier-Augen-Vollmacht steht. Fachlich wird sie nicht gebraucht: Eine Nachfolge betrifft Eigentum und
Verantwortung, und die Berechtigungen einer ausgeschiedenen Person enden mit ihrem Konto.

**Wer.** Die Systemverwaltung organisationsweit. Für „das Eigentum und die Verantwortung, die ich
selbst trage" auch die Person selbst — die Abgabe aus „Meine Gruppen". Ein `MANAGER` ändert die
Berechtigungen an seinem Objekt weiterhin einzeln; die Massenoperation bleibt ein Verwaltungsakt.

**Ablauf.** Die **Vorschau ist Pflicht** und nennt in einem Satz, was bewegt würde („12
Berechtigungen an 7 Objekten, Mitglied in 2 Spaces, Eigentum an 3 Objekten"). Sie ist **selbst ein
Protokollereignis** (`PERMISSION_TRANSFER_PREVIEWED`) — auch wenn niemand sie ausführt: Sie liest
alles, was ein Subjekt hält, und dass jemand gelesen hat, gehört ins Protokoll. **Die Pflicht ist
durchgesetzt, nicht nur beschrieben:** Die Vorschau gibt eine Kennung zurück, die die Ausführung
vorzeigen muss; sie gilt 30 Minuten, gehört dem Aufrufer, dem sie gezeigt wurde, und trägt einen
**Abdruck der gezeigten Zeilen** — je Berechtigung die Rolle und die Befristung, je
Space-Mitgliedschaft der Space, dazu Anlegerechte, Eigentum und Verantwortlichkeiten. Eine Rolle,
die sich zwischen Vorschau und Bestätigung ändert, fällt damit auf, obwohl die Zahlen gleich
bleiben. Weicht der Abdruck ab, wird nichts übertragen und die Vorschau neu vorgelegt (`409`, Code
`TRANSFER_PREVIEW_REQUIRED`) — dieselbe Mechanik wie bei der Bestätigung eines Abgleichsplans. Die Ausführung verlangt darüber hinaus eine **ausdrückliche Bestätigung** und
schreibt `PERMISSION_TRANSFER_EXECUTED` mit Quelle, Ziel, Umfang und Zahl der Zeilen.

**Eine Obergrenze je Vorgang.** Höchstens 500 Zeilen; darüber wird abgelehnt, mit der Zahl und dem
Weg über eine Teilmenge des Umfangs — **gezählt, bevor etwas geladen wird**, und schon in der
Vorschau: Eine Gruppe mit hunderttausend Berechtigungen wird abgewiesen, ohne dass eine einzige
Zeile in die Anwendung kommt. Eine Übertragung ist eine Schreibtransaktion über bis zu vier
Historientabellen — unbegrenzt zu laufen ist für genau die Anlässe, für die sie gebaut ist, kein
Betriebszustand.

**Eine bereits abgelaufene Berechtigung der Quelle wird beendet, aber nicht neu vergeben** — sie
verschafft nichts, und am Ziel entstünde eine tote Zeile. Ihre Zeile verschwindet trotzdem: Sie ist
sonst weiterhin ein Grund, aus dem die Gruppe nicht gelöscht werden kann. In den Zahlen der Vorschau
erscheint sie nicht.

**Das Ziel muss wirksam sein** — nicht aufgelöst, sein Anbieter aktiviert —, **darf aber leer
sein**: Im Token-Modus entsteht die Gruppe des neuen Anbieters erst mit der ersten Anmeldung, und
eine Übertragung, die darauf wartete, wäre genau das, was die Anbieterablösung nicht leisten kann.
Die Quelle unterliegt dieser Regel nicht; eine aufgelöste Gruppe ist hier der Regelfall. Über die
Organisationsgrenze hinweg gibt es keine Übertragung.

**Der Schnitt in der Rechtehistorie.** Je betroffener Zeile endet das Intervall der Quelle und
beginnt das des Ziels — mit **demselben Zeitstempel** und einer **gemeinsamen Vorgangskennung**. Die
Stichtagsauskunft zeigt damit an jedem Tag genau ein Subjekt, und zwei Intervalle zweier Subjekte
sind als dieselbe Entscheidung erkennbar, statt nur zufällig gleich datiert zu sein.

**Treffen beide Seiten am selben Objekt aufeinander, bleibt die stärkere Rolle stehen.** Eine
Übertragung gibt Rechte weiter; sie nimmt dem Ziel nie etwas weg. Das Ziel bekommt in diesem Fall
kein neues Intervall — sein Zustand hat sich nicht geändert.

**Die betroffenen Objekte tragen den Vorgang** in ihrer Freigabeansicht („übertragen am 14.03.2026,
Vorgang …"), bei einer Gruppe als Quelle mit deren Namen. **Bei einer Person als Quelle ohne ihren
Namen:** Ein an vielen Objekten wiederholter Hinweis auf das Ausscheiden einer benannten Person,
außerhalb jeder Protokollfrist, wäre sonst die Folge.

**Wer den Namen der Quellgruppe zu sehen bekommt, entscheidet Entscheidung 9, nicht der Vermerk.**
Eine **geschützte** Gruppe erscheint dort wie in jeder anderen fremden Liste als „geschützte Gruppe"
ohne Namen; eine **nicht freigegebene** interne Gruppe wird gegenüber jemandem, der weder Mitglied
noch verantwortlich noch Systemverwaltung ist, nicht benannt. Eine Quellgruppe, die es nicht mehr
gibt, nennt der Vermerk nur der Systemverwaltung — ihre Sichtbarkeit kann niemand mehr prüfen.

**Der Vorgang unterliegt der Aufbewahrungshöchstdauer der Rechtehistorie** (#1833): Er wird mit dem
Löschlauf entfernt, sobald er älter ist als die eingestellte Frist. Die Objektliste geht mit ihm, die
Historienintervalle bleiben und verlieren nur die Vorgangskennung. Ohne das stünden der
Namensschnappschuss der Quellgruppe und der Vermerk an jedem Objekt unbefristet.

**Nicht enthalten:** die Rücknahme von Mitgliedschaften nach einem Vorfall („alle Mitgliedschaften
dieses Anbieters seit T") und jede automatische Auslösung durch den Verzeichnisabgleich. Eine
Reorganisation im Verzeichnis erzeugt eine aufgelöste Gruppe und einen Eintrag in der Betriebsliste;
die Übertragung bleibt eine Entscheidung.

> Festgeschrieben in [ADR-0036](../decisions/0036-berechtigungsmodell-gruppen-und-faehigkeiten.md),
> Entscheidung 10.

#### Nachfolge offen: Lebenszyklus von Eigentum und Zuständigkeit (gebaut, #1819 — ohne Oberfläche, #1821)

**„Nachfolge offen" ist ein abgeleiteter Zustand, kein gespeichertes Kennzeichen.** Er bedeutet: Es
gibt keinen handlungsfähigen Verantwortlichen mehr.

| Gegenstand | Handlungsfähig heißt |
|---|---|
| Bibliothek einer Person | Das Konto ist nutzbar (weder im Verzeichnis gesperrt noch als lokales Konto ausgesetzt) |
| Bibliothek einer Gruppe | Die Gruppe ist wirksam **und** erreicht mindestens ein aktives Konto |
| Space | Der Eigentümer oder ein `ADMIN`-Mitglied ist handlungsfähig — eine Gruppe zählt, solange sie es ist |
| Interne Gruppe | Mindestens eine verantwortliche Person mit nutzbarem Konto |

Ein Kennzeichen müsste an jedem Auslöser gesetzt und zurückgenommen werden und triebe beim ersten
vergessenen Pfad auseinander; die Ableitung ist an jeder Stelle dieselbe Abfrage (Muster
„abgeleiteter Kontozustand statt `status`-Spalte", ADR-0033/3). **Der Zustand endet von selbst**,
sobald wieder jemand handlungsfähig ist.

**Was der Zustand bewirkt: die Reichweite ist eingefroren — mehr nicht.** Das Objekt bleibt nutzbar,
bestehende Rechte bleiben, **nichts wird gelöscht**. Abgelehnt werden, mit Grund und mit der
Zuständigkeit in der Meldung (`409`, Code `SUCCESSION_OPEN`):

- eine neue oder geänderte Berechtigung an der Bibliothek,
- eine größere Sichtbarkeit oder Auffindbarkeit,
- eine Freigabe für Fremdzugänge,
- eine neue Bereitstellung in einem Space (beide Seiten),
- ein neues Mitglied im Space.

Erlaubt bleibt alles, was die Reichweite **nicht** vergrößert: umbenennen, einschränken, Rechte
entziehen, lesen, suchen, indexieren. Das ist keine Beschreibung, sondern die Schnittstelle: Der
Wächter sitzt an jeder der fünf Stellen **hinter** der Fallunterscheidung und prüft genau die
Erweiterung — eine höhere Rolle oder eine hinausgeschobene Befristung, eine erstmalige oder
verlängerte Freigabe, eine erstmalige Bereitstellung, ein neues Mitglied. Die Herabstufung, die
vorgezogene Befristung, die verkürzte oder zurückgenommene Freigabe und das entfernte Mitglied
laufen unverändert durch; je Pfad hält das ein Test fest. Aus demselben Grund verweigert die
Schutzregel des letzten handlungsfähigen `ADMIN` nur den **Verlust**: Ein Space, der ohnehin keinen
mehr hat, verliert durch eine Entfernung keinen.

**Ein benannter Feststellungslauf** (Vorgabe stündlich, `OPAA_SUCCESSION_DETECTION_CRON`) legt die
**Vorgänge** an und schließt sie: Zeitpunkt der Erstfeststellung und Ende. Die Ableitung bleibt die
Wahrheit — der Lauf schreibt nur den Zeitstempel, ohne den „Alter" in Wahrheit „seit dem letzten
Hinsehen" hieße. Beendet eine Übertragung den Zustand, schließt sie den Vorgang selbst und nennt die
handelnde Person — aber **nur, wenn der Zustand wirklich endet** und nur für den Reiter, den sie
betrifft: Geht ein Objekt an einen Empfänger, der ebenfalls nicht handeln kann, bliebe ein
geschlossener und sogleich neu angelegter Vorgang ein Alter von null, und eine übertragene
Verantwortlichkeit beendet keine „Gruppe ohne Wirkung". Endet der Zustand von allein, schließt ihn
der Lauf und nennt niemanden. Der Lauf selbst fährt **eine Transaktion je Organisation** und fängt
den Fehler einer Organisation ab: Ihre Teilarbeit wird ganz zurückgerollt, die übrigen
Organisationen laufen weiter.

Nachfolgevorgänge und Sichtungsvermerke unterliegen der **Protokollfrist**, nicht der
Rechtehistorie: Sie sagen nichts über Leserechte aus — und die Frist wird auch vollzogen. Ein
monatlicher Löschlauf entfernt **abgeschlossene** Vorgänge samt ihren Sichtungsvermerken, sobald ihr
Ende länger zurückliegt als die Protokollfrist aus `audit_retention_settings` (Vorgabe 36 Monate,
`AuditRetentionSettingsService.DEFAULT_RETENTION_MONTHS`) — eine Verwaltungseinstellung, keine
Umgebungsvariable; ein offener Vorgang wird nie gelöscht,
gleich wie alt er ist. Mit dem Vorgang verschwinden der Freitext des Vermerks und die beiden
Personenspalten, die ohnehin `ON DELETE SET NULL` tragen.

**Die Betriebsliste hat drei Reiter**, alle mit derselben Mechanik (Feststellungslauf, Alter,
objektbezogener Einstieg, Sichtungsvermerk):

| Reiter | Inhalt |
|---|---|
| **Offene Nachfolgen** | Bibliotheken, Spaces und interne Gruppen ohne handlungsfähigen Verantwortlichen, mit Adressat und Alter |
| **Freigaben ohne Empfänger** | wirksame Gruppen, die Berechtigungen tragen oder Space-Mitglied sind und kein aktives Konto mehr erreichen, mit der Zahl der betroffenen Objekte |
| **Gruppen ohne Wirkung** | interne Gruppen ohne Berechtigung, ohne Anlegerecht, ohne Space-Mitgliedschaft, ohne Eigentum und ohne aktives Mitglied |

„Freigaben ohne Empfänger" ist das sichtbare Signal für den ungeschützten Token-Pfad: Nach einer
Umbenennung des Gruppen-Claims sind 40 Freigaben tot, und sonst zeigt es nichts an.

**Die Liste ist vollständig ab dem ersten Tag**, unabhängig vom Adressaten — die Stufung ist eine
Zuständigkeits*angabe*, keine Zugangsbeschränkung: Space → die übrigen handlungsfähigen
`ADMIN`-Mitglieder; Bibliothek einer internen Gruppe → deren Verantwortliche; alles andere → die
Systemverwaltung. Ohne die vollständige Liste erreichte ein Fall der zweiten Stufe die dritte nie.

**Objektbezogen in beide Richtungen.** Einstieg über das Objekt; der Eigentümer wird je Zeile
genannt, aber es gibt **keine** Abfrage, keine Sortierung und keinen Parameter nach ihm — und ebenso
wenig nach der handelnden Person. Wer einen Vorgang beendet oder einen Sichtungsvermerk gesetzt hat,
steht am Vorgang und ist dort lesbar, ist aber keine Auswertungsachse. Ein
Spezifikationstest hält das fest: Die Liste nimmt genau `kind`, `page` und `size` entgegen und keinen
Sortierparameter.

**Alterungsschwelle mit Sichtungsvermerk, ohne Zwang.** Einträge älter als
`OPAA_SUCCESSION_AGING_THRESHOLD_MONTHS` (Vorgabe 12 Monate, orientiert an der Höchstfrist der
Vollmacht) werden hervorgehoben; ein Sichtungsvermerk („geprüft am …, weiterhin offen, Grund") hebt
die Hervorhebung für eine weitere Periode auf. Keine Frist, keine Eskalation, keine Mail.

**Die Kennzeichnung am Objekt nennt Zustand und Adressat — sonst nichts.** Kein Datum, kein
bisheriger Eigentümer, kein Grund: Der Zustand tritt bei einem personengehörenden Objekt mit der
Kontosperre ein, und ein datierter Vermerk neben dem Eigentümernamen wäre eine Statusmeldung über
eine Kollegin. Sie steht an **Übersicht und Detailansicht** des Objekts, beide aus derselben
Ableitung — auch das Kennzeichen `successionOpen` eines Space liest sie, damit eine Antwort nicht
zwei Wahrheiten trägt. **Suchtreffer und Quellenverweise in Antworten tragen die Kennzeichnung
nicht** — der Zustand betrifft die Zuständigkeit, nicht die Richtigkeit des Inhalts.

„War Mitglied von Referat 50" steht **nur in der Betriebsliste**, gefüllt aus den Gruppen des
bisherigen Eigentümers: ein Hinweis, wo eine Nachfolge zu suchen ist, keine Auswertung. Die
Kennzeichnung am Objekt trägt ihn nicht.

**Die Übernahme ist die [Übertragung](#rechte-einer-gruppe-auf-eine-andere-übertragen-gebaut-1834)**
mit dem Umfang „Eigentum und Verantwortung". Dieses Issue liefert Zustand, Liste, Adressat und die
Stelle, an der die Operation ansetzt; die Oberfläche der Liste und der Übertragungsdialog kommen mit
#1821.

**Eine Kontosperre wird nie wegen offener Eigentums- oder Zuständigkeitsfragen abgelehnt** — sie ist
die eine Handlung, die den Zustand erzeugen darf. Die einzige Ausnahme bleibt der Schutz des letzten
anmeldefähigen Systemverwalters.

> Festgeschrieben in [ADR-0036](../decisions/0036-berechtigungsmodell-gruppen-und-faehigkeiten.md),
> Entscheidungen 6 und 8.

Der Nachweis, worauf eine Person zu einem beliebigen Stichtag Zugriff hatte, entsteht aus der
Historisierung dieser drei Quellen und ist in
[Sicherheit, Nachweis & Prüfbarkeit](./security-and-compliance.md#nachweisbarkeit-historisierung-von-rechten)
beschrieben.

---

## Sitzungen, Netzbereiche und erzwungene Neuanmeldung

### Einschränkung auf Netzbereiche

Der Zugang lässt sich auf Netzbereiche einschränken (CIDR-Notation), getrennt für die interaktive
Anmeldung und für API-Identitäten:

```
Netzbereiche (organisationsweite Vorgabe):
  interaktiv:    Hausnetz + VPN-Bereich der Dienststelle
  Fremdzugänge:  für den ganzen Kanal gesetzt, Voreinstellung: nur Hausnetz
  Ausnahmen:     benannt, befristet, begründet
```

**Die Einschränkung für maschinelle Zugänge gilt dem Kanal, nicht dem einzelnen Token**
(Entscheidung vom 18.09.2026, siehe [external-access.md](./external-access.md#der-schalter-der-installation)).
Eine CIDR je Token ist für einen Arbeitsplatzclient hinter wechselnden Adressen unbrauchbar und wäre
zugleich ein Anwesenheitsmerkmal: Gesetzt, scheitert jeder Aufruf aus der Heimarbeit, und die
Abweisung entsteht als Ereignis irgendwo im Betrieb. Mit den **Service-Accounts** kommt sie je
Identität wieder — dort ist die Adresse fest und die Identität keine Person.

Die Einschränkung ist eine **Zugangs-, keine Auswertungsfunktion**. Sie prüft, ob eine Verbindung
zulässig ist; sie erzeugt keinen Aufenthaltsnachweis. Die Netzadresse ist deshalb auch **nicht Teil des
Standard-Protokollsatzes** — begründet in
[Sicherheit, Nachweis & Prüfbarkeit](./security-and-compliance.md#der-protokollsatz). Der abgewiesene
Verbindungsversuch wird als Sicherheitsereignis festgehalten, der zulässige nicht.

### Sitzungsverwaltung

- **Höchstdauer und Leerlauffrist** sind organisationsweit gesetzt, nicht je Nutzer verhandelbar.
- Eine Sitzung ist an ihre Identität gebunden. Endet die Gültigkeit beim Identitätsanbieter, endet sie in
  OPAA — spätestens beim nächsten Erneuerungsversuch, nicht erst nach Ablauf der eigenen Frist.
- **Übersicht der eigenen Sitzungen** für jede Person, mit der Möglichkeit, einzelne oder alle zu
  beenden. Das ist eine Selbstauskunfts- und Selbstschutzfunktion und für niemanden sonst sichtbar.
- **Laufende Antworten und Agentenläufe** werden beim Ende einer Sitzung abgebrochen, nicht im
  Hintergrund fortgeführt. Ein Lauf, der die Rechte einer beendeten Sitzung weiterträgt, wäre genau die
  Lücke, die die sofortige Wirkung von Rechteänderungen aushebelt.

**Für lokale Konten hält OPAA diese Fristen selbst**, weil es für sie der Aussteller ist: ein
kurzlebiges Zugangstoken, dazu ein rotierendes Refresh-Token mit einer Leerlauffrist und einer
absoluten Höchstdauer, die keine Rotation verlängert — für lokale Systemverwalterkonten mit eigenen,
deutlich kürzeren Werten. Die Zahlen sind organisationsweite Betriebseinstellungen und stehen in der
Variablenliste des Deployment-Kapitels. Widerruf wirkt **sofort**, nicht erst mit dem Ablauf: einzeln
über die Sperrliste der Zugangstokens (Abmeldung), auf einen Schlag über einen Zeitstempel am Konto
(Passwortwechsel, Sperre, Zurücksetzen, Abschalten der Verwaltung). Die Vorlage eines bereits
rotierten Refresh-Tokens gilt als Wiederverwendung und beendet **alle** Sitzungen des Kontos. Eine
Übersicht der eigenen Sitzungen gibt es für lokale Konten noch nicht — der Weg, alle übrigen
Sitzungen zu beenden, ist der eigene Passwortwechsel.

### Erzwungene Neuanmeldung

Eine erzwungene Neuanmeldung beendet bestehende Sitzungen und verlangt eine erneute Authentisierung. Sie
wird ausgelöst:

- durch die Systemverwaltung — für eine Person, eine Gruppe oder alle, etwa nach einem
  Sicherheitsvorfall oder einer Änderung an den Modell- und Governance-Vorgaben, die vor der
  Weiterarbeit zur Kenntnis zu nehmen ist;
- **automatisch** bei Sperrung oder Ausscheiden im Verzeichnis;
- **automatisch** bei einer Rechteänderung, die den Zugang selbst betrifft (Entzug der
  System-Admin-Rolle, Wechsel der Organisationseinheit);
- **automatisch** bei jedem Verwaltungsakt an einem lokalen Konto, der seine Zugangsdaten oder seinen
  Zustand ändert: Passwortwechsel, Sperre, Zurücksetzen durch die Systemverwaltung, Abschalten der
  lokalen Verwaltung und die erkannte Wiederverwendung eines Refresh-Tokens.

Der Vorgang ist protokollpflichtig. Er ist ein Verwaltungsakt gegenüber der betroffenen Person und kein
stiller Eingriff: Wer neu anmelden muss, erfährt beim nächsten Aufruf, dass und warum. Für lokale
Konten ist das technisch eingelöst — die Abweisung trägt einen Marker mit Anlass, den die Oberfläche
als deutschen Satz zeigt, und die Verwaltungsakte an einem Konto werden der Person zusätzlich per
E-Mail mitgeteilt. Die einzige Ausnahme ist die Sperre nach Fehlversuchen: Sie löst bewusst keine
Mail aus, weil sie sonst ein Belästigungskanal für jeden wäre, der eine Adresse kennt.

---

## API-Tokens und Service-Accounts

> **Erste Stufe entschieden (18.09.2026):** Gebaut wird zunächst nur das **persönliche Zugangstoken**
> für lesende Fremdzugänge — fest auf Suchen und Abrufen begrenzt (kein Umfangsmenü), mit
> Pflicht-Ablaufdatum unter einer systemweiten Obergrenze, mit einer konkreten und danach
> unveränderlichen Bibliotheksauswahl und einem Kontingent je Token, hinter einem installationsweiten
> Schalter (Standard aus), einer kanalweiten Netzbeschränkung (Vorgabe Hausnetz) und einer
> **pflichtbefristeten** Freigabe je Bibliothek (Standard aus, höchstens ein Jahr). Diese Freigabe ist
> ein Reichweitenfeld: Sie wird wie `visibility` und `listed` historisiert, fällt aber **nicht** unter
> die [Freigabe-Obergrenze](#dokumentenfluss-konnektoren-gegen-benutzer-uploads) konnektor-gespeister
> Bibliotheken — #797 hat deren Wirkung ausdrücklich auf `visibility`/`listed` begrenzt. **Service-Accounts
> als eigene Identität ohne Person bleiben Zielbild** und sind in
> dieser Stufe nicht enthalten. Einzelheiten: [external-access.md](./external-access.md).

Das Zielbild, auf das die erste Stufe zuläuft:

```
API-Token erstellen:
  Name:        "Fachverfahren-Anbindung"
  Rechte:      erbt die Asset-Rechte des ausstellenden Nutzers oder Service-Accounts
  Umfang:      [read_documents, ask_questions]
  Rotation:    90 Tage
  Netzbereich: optional (CIDR)
  Rate-Limit:  konfigurierbar
```

Ein Token kann **nie mehr Rechte haben als sein Inhaber**. Service-Accounts sind reine API-Identitäten
ohne interaktive Anmeldung; sie erhalten ihre Rechte wie jeder andere Träger von Rechten über Grants oder
Gruppen.

Für den Lebenszyklus gilt dieselbe Logik wie für Personen: Ein Token, dessen ausstellender Nutzer
ausscheidet, **verliert seine Wirkung mit dessen Konto**. Ein Token, das die Deaktivierung überdauert,
wäre der bequemste Weg, den Kontenlebenszyklus zu umgehen. Service-Accounts brauchen deshalb einen
benannten menschlichen Verantwortlichen, der selbst dem Lebenszyklus unterliegt — fällt er weg, greift
dieselbe Nachfolgeregelung wie bei Assets.

---

## Offboarding

Wenn ein Nutzer die Organisation verlässt:

1. Die Verzeichnis-Synchronisation entfernt oder sperrt ihn; er kann sich nicht mehr anmelden, bestehende
   Sitzungen enden, seine Tokens wirken nicht mehr.
2. **Die Deaktivierung wird nie durch offene Eigentumsfragen aufgehalten.**
3. Seine Assets gehen in den Zustand **„Nachfolge offen"**: nutzbar und mit unveränderten Rechten, aber
   mit **eingefrorener Reichweite** — keine neuen Grants, keine höhere Freigabestufe, keine neue
   Bereitstellung. Zuständig für die Nachfolge ist der Kurator der Organisationseinheit, ersatzweise der
   System-Admin; der Vorgang erscheint mit Frist auf der Governance-Arbeitsliste.
4. Für zentral gepflegte Bestände ist Gruppen-Eigentum der Regelfall und verhindert das Problem von
   vornherein.
5. Sein Standard-Space (`isDefault`) wird deaktiviert, nicht gelöscht (Nachweisgründe) — und **nicht lesbar
   gemacht**. Private Chats und Artefakte darin bleiben unzugänglich. Geteilte Inhalte unterliegen der
   Aufbewahrungsregel.

Die **Löschung** eines Kontos ist davon zu unterscheiden: Sie ist ein Vorgang nach DSGVO und in
[Sicherheit, Nachweis & Prüfbarkeit](./security-and-compliance.md#vollständigkeit-nach-dsgvo-löschung-und-export)
beschrieben.

---

## Sonderfälle

### Breiter Lesezugriff für Stabsstellen und Leitung

Nicht über eine Sonderrolle, sondern über **Gruppen**: Die Stabsstelle erhält als Gruppe Leserechte an den
einschlägigen Wissensbibliotheken. Das skaliert, ist im Katalog nachvollziehbar und läuft über denselben
Weg wie jede andere Freigabe — kein Sonderpfad, der bei einer Prüfung erklärt werden müsste.

### Revision und Rechnungsprüfung

Prüfende Stellen brauchen Unabhängigkeit. Empfohlen ist ein eigener Space im **Strikt-Modus** (nur
Bibliotheken, deren Leserkreis alle Mitglieder umfasst), damit in der Prüfung keine Inhalte an
Unberechtigte gelangen und die Prüfakte sauber abgegrenzt bleibt.

**Der Preis gehört an dieselbe Stelle wie die Empfehlung:** Ein hausweit geteilter Agent ist in aller
Regel an mindestens eine Bibliothek gebunden, deren Leserkreis die Prüfstelle nicht umfasst — im
Strikt-Modus ist er dort nicht aufrufbar. Die Prüfstelle verliert damit faktisch den größten Teil der
geteilten Agenten des Hauses. Das ist vertretbar und für die Unabhängigkeit sogar folgerichtig, muss aber
vor der Entscheidung bekannt sein und nicht drei Monate später auffallen.

### Externe Beteiligte

```
Nutzer:      externe Beraterin
Spaces:      [Projekt-X]
Assets:      Grant USER auf genau die benötigte Bibliothek
Befristung:  bis 2026-03-31
Hinweis:     Sie sieht alle Chats und Artefakte des Space — vor der Aufnahme prüfen
```

Die Aufnahme externer Personen ist besonders folgenreich, weil ihnen damit alle **geteilten** Inhalte des
Space offenstehen — also die Arbeitsergebnisse namentlich bekannter Beschäftigter. Externe Konten sind
gekennzeichnet, die Aufnahme verlangt eine ausdrückliche Bestätigung und wird protokolliert. Ein bloßer
Hinweistext genügt hier nicht. Für solche Fälle ist ein eigener, eng geschnittener Space der richtige Weg.

Externe Konten stammen häufig **nicht** aus dem Verzeichnis des Hauses. Für sie ist die Befristung des
Kontos deshalb Pflicht und nicht Option — sie ersetzt den Ausscheideprozess, der bei eigenen Beschäftigten
automatisch greift.

---

## Integrationspunkte

- **Authentifizierung:** SSO-Anbieter und Verzeichnisdienst
- **Benutzer-Frontends:** Rechte an jeder Schnittstelle durchsetzen →
  [user-frontends.md](./user-frontends.md)
- **Daten-Indizierung:** Zuordnung von Quellen zu Wissensbibliotheken →
  [data-indexing-rag.md](./data-indexing-rag.md)
- **RAG-Engine:** Filter über die lesbaren Bibliotheken des Nutzers, als Teil der Vektorsuche
- **Fremdzugänge:** Zugangstokens und MCP-Server als Ausschnitt bestehender Leserechte, mit eigener
  Freigabe je Bibliothek → [external-access.md](./external-access.md)
- **Modelle:** zentrale Vorgaben gelten je Organisation → [llm-integration.md](./llm-integration.md)
- **Sicherheit und Nachweis:** jede Rechte- und Kontenänderung erzeugt einen Protokolleintrag →
  [security-and-compliance.md](./security-and-compliance.md)
- **Betrieb:** Nutzer- und Gruppendaten aus dem Verzeichnis →
  [deployment-infrastructure.md](./deployment-infrastructure.md)

---

## Offene Fragen

- Attributbasierte Zugangskontrolle (ABAC) zusätzlich zu Rollen und Gruppen?
- Zeitlich befristete Rechte mit automatischem Verfall und turnusmäßiger Rezertifizierung — als Arbeit
  erfasst, im Schnitt aber noch offen.
- Genehmigungsworkflows für besonders geschützte Bestände?
- Klassifizierungsstufen (offen, intern, vertraulich) als eigenes Merkmal?
- **Rechte aus Quellsystemen:** Sollen Berechtigungen des Quellsystems zusätzlich zu den
  Bibliotheksrechten durchgesetzt werden? Grundsätzlich erwünscht, aber aufwendig — Benutzerkennungen und
  Rechtemodelle stimmen zwischen Quellsystem und OPAA nicht notwendig überein.
- **Mehrfachzugehörigkeit:** Wie werden Beschäftigte abgebildet, die zwei Organisationseinheiten
  angehören? Das Verzeichnis kennt den Fall, das Aggregationsmodell der Auswertung muss ihn ebenfalls
  vertragen.
- Welche Ereignisse eine erzwungene Neuanmeldung auslösen, ist als Ausgangsliste festgehalten und wird
  sich im Betrieb schärfen.

---

## Erfolgs-Metriken

- **Wirksamkeit des Lebenszyklus:** Zeit zwischen Sperrung im Verzeichnis und Wirkungslosigkeit des
  Zugangs in OPAA, einschließlich aller Sitzungen und Tokens. Ziel ist eine Größenordnung von Minuten,
  nicht von Tagen.
- **Leistung:** Rechteprüfung erhöht die Abfragezeit um weniger als 50 ms.
- **Genauigkeit:** keine unbeabsichtigten Zugriffe; kein Synchronisationslauf, der eine Rechteänderung
  bewirkt hat, ohne sie einzeln festzuhalten.
- **Verständlichkeit:** Der Anteil der Support-Anfragen, die sich auf „warum sehe ich das nicht" beziehen,
  sinkt über die ersten drei Monate.

---

## Verwandte Dokumente

- [Spaces, Assets & Zugangskontrolle](./spaces-and-assets.md) — das Rechtemodell
- [Sicherheit, Nachweis & Prüfbarkeit](./security-and-compliance.md) — Protokoll, Rechtehistorie, DSGVO,
  C5-Fähigkeit, Mitbestimmungsfähigkeit
- [Monitoring, Kosten & Governance](./monitoring-and-governance.md) — Grenzen, Kosten und Auswertung
- [Daten-Indizierung & RAG](./data-indexing-rag.md)
