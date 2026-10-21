# ADR-0036: Berechtigungsmodell — Subjekte, Gruppenherkunft, Synchronisation, Fähigkeiten und Lebenszyklus

## Status

Akzeptiert · zuletzt geändert 2026-10-21 · ergänzt durch [ADR-0037](0037-reichweite-als-freigabe-an-alle.md),
[ADR-0039](0039-ein-katalog-und-ausdrueckliche-space-zuordnung.md) und
[ADR-0041](0041-verbindungen-als-eigenes-modul.md) · ersetzt Entscheidung 6 von
[ADR-0018](0018-quellkonfiguration-in-der-bibliothek.md) · präzisiert ADR-0016 (Entscheidung 8),
ADR-0025 (Entscheidungen 2 und 3) und ADR-0033 (Entscheidung 2)

## Kontext

Epic #1295 stellte drei Fragen: welche **Subjekte** das Rechtemodell trägt, **woher** Gruppen kommen und
**wie** ihre Mitgliedschaft synchronisiert wird. Eine vierte kam aus dem gebauten Stand: Spaces und
Bibliotheken konnte jedes angemeldete Konto anlegen (ADR-0018, Entscheidung 6).

Der Stand im September 2026, soweit er die Entscheidungen band: Ein Rechtesubjekt
(`PermissionSubject`) war genau ein Nutzer oder eine Gruppe. Gruppen gab es als `ORG_UNIT`
(Verzeichnisabgleich), `AD_HOC` (intern) und `IDENTITY_PROVIDER` (Gruppen-Claim); die Herkunft steckte
in einem Präfix von `external_id`, **ohne** Fremdschlüssel auf `oidc_providers` — wurde ein Anbieter
gelöscht, blieb eine Gruppe zurück, deren Mitgliedschaft sich nie mehr änderte, deren Grants aber
weiter wirkten. Der Verzeichnisabgleich hatte Trockenlauf, Plausibilitätsschwelle und
Leerergebnis-Schutz, aber keinen Konnektor; die einzige produktive Gruppenquelle war der Token, und
genau der hatte keine dieser Schutzmechaniken. Space-Mitglieder waren nur Personen. Die Rechtehistorie
hatte drei Tabellen und keinen Lesepfad. Das Rechteprofil der Suchdiagnose war bereits eine Gruppe.

Vorentschieden und hier nicht neu zur Wahl: Subjekte sind Nutzer und Gruppen, flach, ohne freie Rollen
und ohne Schachtelung; die Gruppe bleibt Subjekt am Grant; Gruppen werden Space-Mitglieder mit Rolle;
globale Berechtigungen sind Fähigkeiten, ausgeliefert an „Alle Konten"; Mandantenfähigkeit (#1442) ist
Randbedingung. Die Abwägung mit Systemvergleich steht im Konzeptpapier
[`discussion-berechtigungsmodell-gruppen-und-faehigkeiten.md`](../discussions/discussion-berechtigungsmodell-gruppen-und-faehigkeiten.md),
die Stakeholder-Berichte in
[`discussion-berechtigungsmodell-stakeholder.md`](../discussions/discussion-berechtigungsmodell-stakeholder.md).

## Entscheidung

**Begriffe.** Oberfläche und Handbuch benutzen die Spalte „In der Oberfläche".

| Begriff | In der Oberfläche | Bedeutung |
| --- | --- | --- |
| Rolle | Rolle | gestufte Berechtigung an **einem Objekt**, für Person oder Gruppe |
| Fähigkeit | Anlegerecht | unbefristetes Recht, etwas **anzulegen**; an Person, Gruppe oder „Alle Konten"; öffnet nie einen Inhalt |
| Befugnis | Vollmacht | befristete, an **genau eine Person** gebundene Erlaubnis mit Gegenstand und Begründungspflicht („Sicht als", Vorfallsbereich) |
| Systemrolle | Systemrolle | `SYSTEM_ADMIN`, `AUDITOR` |
| Wirksame Gruppe | — | darf ein Recht **erhalten**: nicht aufgelöst, Anbieter aktiviert, bei internen Gruppen nicht deaktiviert (Entscheidung 14); darf leer sein |
| Handlungsfähige Gruppe | — | wirksame Gruppe mit mindestens einem aktiven Konto; das Maß, ob sie als Eigentümerin oder Space-`ADMIN` **handeln** kann |
| Betriebsliste | Ohne Zuständigkeit | die Liste der Systemverwaltung aus Entscheidung 6 |

### 1. Vorbilder und die Zusage „kein Lesebypass"

OPAA übernimmt Fähigkeiten an Gruppen und Personen (Confluence „Create Space"), schreibgeschützte
Verzeichnisgruppen neben internen Gruppen, Gruppenpflege je Gruppe (Nextcloud) und „der letzte
Verantwortliche kann sich nicht entfernen" (GitLab). OPAA schreibt nie ins Verzeichnis. **Keine
Super-Gruppe mit Inhaltszugriff:** `AssetAccessService#readableAssetIds` kennt keinen
Systemverwalter-Bypass, und keine Fähigkeit aus Entscheidung 5 darf ihn einführen. Die Zusage gilt für
Suche und Download gleichermaßen (`LibraryDocumentService#loadContent` prüft dieselbe
Formel, die Systemverwaltung erhält ohne Grant `403`; Übernahme nur über die protokollierte
Eigentumsübertragung). In der Verwaltung führt `effectiveRole` den Systemverwalter als `OWNER` —
Verwalten ist nicht Lesen. Eine Störung wird über die Suchdiagnose mit Rechteprofil nachgestellt.

### 2. Gruppenherkunft: `provider_id` als echter Fremdschlüssel

- `groups.provider_id` (nullbar, FK auf `oidc_providers`, `ON DELETE RESTRICT`), Prüfregel
  `kind = AD_HOC ⇔ provider_id IS NULL`; `external_id` trägt nur die Kennung der Quelle, Eindeutigkeit
  `(organization_id, provider_id, kind, external_id)`; `source_path` trägt den Pfad der Quelle, weil
  gleichnamige Untergruppen sonst nicht unterscheidbar sind. Die Herkunft ist ableitbar
  (`GroupResponse.origin`, `provider`), nicht doppelt gespeichert; `kind` bleibt der Mechanismus.
- **Je Anbieter genau ein Gruppenmechanismus**, Token-Claim oder Verzeichnisabgleich; der Konflikt ist
  ein `409` (`chk_oidc_providers_directory_sync`). Der Abgleich ist eine Einstellung der Anbieterzeile
  (Issuer, Intervall) und pausiert, solange die Zeile deaktiviert ist (`DIRECTORY_SYNC_PROVIDER_DISABLED`)
  oder ihr Abgleich aus ist (`DIRECTORY_SYNC_NOT_ENABLED`); `is_default` wirkt nur auf Anmeldevorschlag
  und Löschschutz. Im `dev`-Modus gibt es keine Anbieterzeile und darum keinen Abgleich.
- **Herkunft als Anzeige**, nie als Präfix im Namen. Namenseindeutigkeit interner Gruppen wird nicht
  erzwungen, weil ein `409` die Existenz einer Gruppe verriete (Entscheidung 9); die Oberfläche warnt
  bei sichtbaren Namensgleichen. Anbieter tragen `is_external` (Vorgabe: alle außer dem Standardanbieter);
  Gruppen externer Anbieter sind in jeder Auswahl sichtbar abgehoben, ein Recht an sie verlangt eine
  Zwischenfrage. Auditeinträge und Rechtehistorie tragen zur Gruppen-ID die Herkunft als Text — nur
  zulässig unter der Aufbewahrungshöchstdauer aus Entscheidung 8.
- **Deaktivieren eines Anbieters** behält die bestehenden Wächter (`OidcProviderService`: Standardanbieter
  nicht bei weiteren aktiven, Bestätigung für den letzten, `LocalAdminAvailabilityGuard`) und ändert
  nichts an Gruppen, Mitgliedschaften und Grants. Seine Gruppen sind aber **keine wirksamen Gruppen**:
  kein neues Grant-Ziel, kein neues Space-Mitglied, in der Auswahl nicht wählbar mit Grund. Die Rücknahme
  manipulierter Mitgliedschaften nach einem Vorfall bleibt Handarbeit.
- **Löschen eines Anbieters wird verweigert, solange seine Gruppen wirken** (`409` mit Zählung,
  `ProviderGroupEffects`), mit Arbeitsliste je Anbieter (`GET /api/v1/admin/oidc-providers/{id}/impact`);
  der Ausweg ist die Übertragung (Entscheidung 10) oder das Entfernen der Wirkungen. Gruppen ohne Wirkung
  werden mit dem Anbieter gelöscht.
- Lokale Konten (ADR-0033) haben keinen Gruppenmechanismus und werden Mitglied interner Gruppen. Eine
  Anbietergruppe enthält nur Konten mit dem Issuer dieses Anbieters.

### 3. Synchronisation: Token oder zeitgesteuerter Pull, kein SCIM

Token ist die Vorgabe (funktioniert ohne Konfiguration), Pull die Empfehlung für Häuser mit gepflegtem
Verzeichnis. Pull statt Push, weil die Schutzmechanik auf Schnappschüsse gebaut ist und kein eingehender
Schreibpfad entsteht. **Der Vorgabeweg ist der ungeschützte:** Eine Umbenennung des Claim-Werts ist im
Token-Modus ein Gruppenwechsel ohne Schutzmechanik. Deshalb: `GET /api/v1/me/groups` nennt je Anbieter
Mechanismus, Intervall und letzten Abgleich; die Betriebsliste zeigt „Freigaben ohne Empfänger"
(Entscheidung 6); das Handbuch nennt die Genauigkeit der Rechtehistorie je Mechanismus (im Token-Modus
gilt eine Verzeichnisänderung erst ab der nächsten Anmeldung).

- **Erster Konnektor: Keycloak Admin REST API** (`io.opaa.directory.sync.keycloak`): Die
  Keycloak-Nutzer-ID ist der `sub`, eine Abbildungsregel entfällt; LDAP und Microsoft Graph folgen mit
  ausdrücklicher Abbildungsregel. SCIM ist zurückgestellt, nicht abgelehnt. Direkte Mitglieder, nicht transitiv;
  Zugangsdaten des Dienstkontos verschlüsselt über `CredentialsEncryptor`; Verbindungstest und
  Adressprüfung wie beim Anbieter (`OidcProviderConnectionTester`).
- **Ausstehender Plan** (`DirectorySyncPendingPlan`): Der Leerergebnis-Schutz bleibt ein harter Abbruch
  (`ABORTED_EMPTY_RESULT`); ein neuer Lauf ersetzt den ausstehenden Plan; bestätigt wird gegen einen
  frischen Schnappschuss (`DirectorySyncPlanFingerprint`); das Alter eines offenen Plans steht in
  Statuszeile und Verwaltungsübersicht.
- **Kontosperren aus dem Abgleich** unterliegen derselben Schwelle und demselben Bestätigungsweg, sind
  rückholbar, werden historisiert (`AccountStateHistory`, Entscheidung 8); die Person erhält bei der
  nächsten Anmeldung Grund und Ansprechstelle.
- **Mechanismuswechsel:** Token-Gruppen bleiben mit eingefrorener Mitgliedschaft stehen, sind kein neues
  Grant-Ziel, der Differenzbericht weist sie aus; nichts wird still entzogen.
- **„Sicht als"** (`DiagnosticImpersonationGrantService`) nimmt jede Anbietergruppe (`ORG_UNIT` und
  `IDENTITY_PROVIDER`) als Geltungsbereich, interne Gruppen nicht; die Mindestgruppengröße wird bei jeder
  Nutzung geprüft, nicht nur bei der Erteilung.

### 4. Interne Gruppen: Gruppenverantwortliche

- Jede interne Gruppe hat einen oder mehrere **Verantwortliche** (`GroupSteward`), ausschließlich
  natürliche Personen, nicht automatisch Mitglied. Sie pflegen Mitglieder (nur Konten der eigenen
  Organisation), Name und Beschreibung, ernennen und entlassen Verantwortliche, geben die Gruppe zur
  Verwendung frei (Entscheidung 9), deaktivieren sie (Entscheidung 14) und löschen sie (`409`, solange sie
  Grants trägt, Assets besitzt oder Space-Mitglied ist). Der letzte Verantwortliche kann sich nicht
  entfernen; scheidet er aus, greift Entscheidung 6.
- Verantwortung abgeben ist ein sichtbarer eigener Schritt in „Meine Gruppen"
  (`GET /api/v1/me/stewarded-groups`), kein Automatismus aus Personendaten.
- Anlegen ist die Fähigkeit `CREATE_INTERNAL_GROUP`, ausgeliefert an **niemanden**; das Handbuch
  empfiehlt die Vergabe an eine Gruppe wie „Referatsleitungen". Beim Anlegen weist die Oberfläche darauf
  hin, dass „vor Kollegen verborgen" nicht „verborgen" heißt: Wer anlegt oder ein Recht gibt, sieht, an
  wen (Entscheidung 9).
- Anbietergruppen bleiben schreibgeschützt (`GroupService#rejectOrgUnit`) und haben keine
  Verantwortlichen; ihr Schutzkennzeichen setzt die Systemverwaltung (Entscheidung 9).
- Mitglieder sehen ihre Verantwortlichen namentlich; Aufnahme und Entfernung werden der Person über
  ADR-0019 angezeigt, ohne Mail.
- Verantwortlichkeit ist Audit (`GROUP_STEWARD_APPOINTED`/`_DISMISSED`), keine Historienzeile: Sie trägt
  kein Leserecht.
- API ohne `/admin` (`/api/v1/groups`); ein Nichtverantwortlicher erhält `404` wie für eine unbekannte
  Gruppe.

### 5. Globale Berechtigungen sind Fähigkeiten — Ablösung von ADR-0018, Entscheidung 6

| Fähigkeit (`Capability`) | Auslieferung |
| --- | --- |
| `CREATE_SPACE` | „Alle Konten"; der Standard-Space entsteht bei der ersten Anmeldung unabhängig davon |
| `CREATE_LIBRARY` (Upload) | „Alle Konten" |
| `CREATE_CONNECTOR_LIBRARY` | „Alle Konten", je Geltungsbereich (unten) |
| `CREATE_PROMPT_LIBRARY` | „Alle Konten": ein Prompt bindet kein Wissen und erreicht keine Quelle |
| `CREATE_INTERNAL_GROUP` | niemand |

Bewusst keine Fähigkeit: Reichweitenentscheidungen am Objekt („organisationsweit freigeben",
„Fremdzugang freigeben") — dafür sind `MANAGER`/`OWNER` und die Freigabe-Obergrenze (#797) zuständig.

- **Datenmodell:** `capability_grants` mit Subjekt `USER` | `GROUP` | `ALL_ACCOUNTS`
  (`CapabilitySubjectType`), Eindeutigkeit je (Organisation, Fähigkeit, Subjekt, Geltungsbereich),
  Historie `capability_grant_history`. Auswertung `CapabilityService#hasCapability` =
  `SYSTEM_ADMIN ∨ ALL_ACCOUNTS ∨ Nutzer ∨ eine seiner Gruppen`, über `GroupMembershipResolver`.
- **Geltungsbereich** (ADR-0041): `CREATE_CONNECTOR_LIBRARY` trägt `scope` =
  `TYPE:<Schlüssel>` oder `PROFILE:<Kennung>`, Pflicht für diese Fähigkeit, leer für alle anderen; rights
  deutet den Wert nicht, connections prüft ihn und löscht die Grants eines gelöschten Profils. Der
  Geltungsbereich wählt einen Anlegepfad, ist kein Gegenstand im Sinn einer Befugnis und bleibt an Gruppen
  und „Alle Konten" vergebbar. Ein neuer Konnektortyp oder ein neues Profil hat keine Zeile und ist damit
  aus; der Seed vergibt die fünf ursprünglichen Typen (`FILESYSTEM`, `HTTP_DIRECTORY`, `RSS_FEED`,
  `CONFLUENCE`, `S3`). `GET /api/v1/me/capabilities` führt die Fähigkeit als Zeichenkette, sobald sie in
  einem Geltungsbereich gilt; welcher Weg nutzbar ist, liefern `GET /api/v1/source-types` und die Zugänge
  je Eintrag (nur noch nutzbare). Entzug wirkt nur auf die Neuanlage — das Anlegen einer
  Bibliothek und das erstmalige Verbinden eines Kontos auf einem Profil; neu verbinden, trennen und
  löschen zählen nicht dazu. Läufe stoppt allein die Sperre von Typ oder Profil in connections.
- **Verhältnis:** `SYSTEM_ADMIN` besitzt jede Fähigkeit implizit; Fähigkeiten sind nie Leserechte;
  `AUDITOR` verleiht keine Fähigkeit; „Sicht als" und Vorfallsbereich bleiben Vollmachten; keine neue
  Systemrolle — ein „Bibliotheksverwalter" ist eine Gruppe mit Fähigkeiten.
- **Durchsetzung:** im Service (`SpaceService#createSpace`, `KnowledgeLibraryService#createLibrary`,
  `GroupService#createGroup`), `403` mit Grund, in der Spezifikation als eigene Entscheidung der Operation
  deklariert. Entzug wirkt ohne Neuanmeldung, auf jeder Instanz: Die Fähigkeit wird je
  Aufruf gelesen, die Gruppenmitgliedschaft dahinter hängt am Invalidierungsbus
  ([ADR-0053](0053-mehrinstanzbetrieb-aktiv-aktiv.md) Entscheidung 5). `GET /api/v1/me/capabilities` (`MyCapabilitiesResponse`)
  liefert die eigenen Fähigkeiten, damit die Oberfläche erklärt statt versteckt; die
  Verwaltungsübersicht zeigt je Fähigkeit
  den Stand als Klartextzeile. Vergabe und Entzug sind Governance-Ereignisse
  (`CAPABILITY_GRANTED`/`_REVOKED`).
- **Nur-Besitzerin-Regel**: `assets.owner_only`, beim Anlegen gesetzt, unveränderlich,
  verlangt `owner_type = USER`; rights erzwingt sie an jedem Grant, an der Freigabestufe, bei der
  Eigentumsübertragung und in der Nachfolge (eine private Bibliothek wird nie „Nachfolge offen", sie
  folgt ADR-0041). Keine Stufe der Freigabe-Obergrenze, auch die Systemverwaltung kann sie nicht lockern;
  Space-Zuordnungen privater Bibliotheken sieht nur die Besitzerin. „Sicht als" liefert eine private
  Bibliothek nie. Einen Vorfallszugriff auf private Bibliotheken gibt es nicht (#2171): OPAA hält nur eine
  Kopie, ein Vorfall wird in der Originalquelle untersucht.
- **Das Verbindungsprotokoll liest `AUDITOR`** über `AuditAccessGate` (Anlass, begrenzter
  Zeitraum, eigener Zugriffseintrag); `SYSTEM_ADMIN` liest es nicht. Keine Fähigkeit (nie ein Leserecht),
  keine neue Systemrolle (`SystemRole` ist einwertig). In Kauf genommen: `AUDITOR` kann aus dem
  Rollen-Claim stammen (ADR-0025, Entscheidung 4).

Die Pfad-Allowlist und `TargetAddressValidator` bleiben von der Berechtigung unabhängige Sicherungen.

### 6. Lebenszyklus: „Nachfolge offen" als abgeleiteter Zustand

„Nachfolge offen" ist die Abwesenheit eines handlungsfähigen Verantwortlichen: beim Asset der Eigentümer,
beim Space Eigentümer oder ein handlungsfähiges `ADMIN`-Mitglied, bei einer internen Gruppe ein aktiver
Verantwortlicher. Der Zustand wird **abgeleitet, nicht gespeichert** (Muster ADR-0033, Entscheidung 3);
gespeichert wird nur der Vorgang (`succession_cases`), den ein **Feststellungslauf**
(`SuccessionDetectionService`, `opaa.succession.detection-cron`, Vorgabe stündlich) anlegt und schließt.
Vorgänge unterliegen der Aufbewahrungsfrist des Protokolls, nicht der Rechtehistorie.

- **Wirkung:** Das Objekt bleibt nutzbar, Rechte bleiben, die **Reichweite ist eingefroren** (keine neuen
  Grants, keine höhere Freigabestufe, keine neue Bereitstellung, keine neuen Space-Mitglieder). Nichts
  wird gelöscht.
- **Betriebsliste** (`GET /api/v1/admin/succession`, `SuccessionKind`): vollständig ab dem ersten Tag,
  drei Reiter mit derselben Mechanik — **Offene Nachfolgen** (Objekt, derzeitiger Adressat, Alter;
  Stufung Space → übrige handlungsfähige `ADMIN`, Asset einer internen Gruppe → deren Verantwortliche,
  sonst Systemverwaltung — eine Zuständigkeitsangabe, keine Zugangsbeschränkung), **Freigaben ohne
  Empfänger** (wirksame, nicht handlungsfähige Gruppen mit Wirkung) und **Gruppen ohne Wirkung**
  (interne Gruppen ohne Grant, Space-Mitgliedschaft, Eigentum und aktives Mitglied). Keine Frist, keine
  Mail, keine Eskalation; Einträge älter als `opaa.succession.aging-threshold-months` (Vorgabe 12)
  werden hervorgehoben, sonst nichts; einen Sichtungsvermerk gibt es nicht.
- **Am Objekt** sehen Leseberechtigte Zustand und Adressat, ohne Datum, früheren Eigentümer und Grund;
  Suchtreffer und Quellenverweise tragen keine Kennzeichnung.
- **Objektbezogen in beide Richtungen:** keine Abfrage „was gehörte Person X" und keine Auswertungsachse
  über die handelnde Person. Die Liste darf als Hinweis die Verzeichnisgruppen des Ausgeschiedenen
  nennen (Bestand, keine Aktivität). Die Übernahme ist die Übertragung (Entscheidung 10).

**Vier Schutzregeln:**

1. Ein Space verliert nie sein letztes handlungsfähiges `ADMIN`-Mitglied durch eine Verwaltungshandlung
   (`409`); eine Gruppe zählt als `ADMIN`, solange sie handlungsfähig ist. Den Eigentümerwechsel darf
   jedes handlungsfähige `ADMIN`-Mitglied, das eine Person ist; der Space-Eigentümer bleibt eine Person.
2. Eine Kontosperre wird nie wegen offener Zuständigkeitsfragen abgelehnt; einzige Ausnahme ist der
   Schutz des letzten anmeldefähigen Systemverwalters
   (`LocalAdminAvailabilityGuard#requireAnotherLoginCapableAdmin`).
3. Der letzte Verantwortliche einer internen Gruppe kann sich nicht entfernen (Entscheidung 4).
4. Nur wirksame Gruppen sind neues Grant-Ziel und neues Space-Mitglied
   (`AssetGrantService#requireGrantableGroup`). Eine **leere** wirksame Gruppe bleibt erteilbar, mit
   Warnung — sonst scheiterte „anlegen, freigeben, Mitglieder aufnehmen" am ersten Schritt.

### 7. Diagnose im Gruppenkontext

Das Rechteprofil der Suchdiagnose bleibt eine Gruppe, mit derselben Herkunftsanzeige und
Sichtbarkeitsregel wie die Subjekt-Auswahl (Entscheidung 9). Die Suchdiagnose bleibt `SYSTEM_ADMIN`
vorbehalten; wird sie je geöffnet, schaltet dieselbe Änderung die Diagnosesperre (Leitplanke (e) in
`hybrid-retrieval.md`) auch für Profil-Läufe scharf. Leitplanke (b) bleibt: Die Diagnose ist kein
Zugriffshistorien-Nachweis.

Der **Space-Kontext** im Profil ist gebaut (#1835: `spaceId` im Diagnose-Request,
`SearchDiagnosisService`); fünf Schutzpunkte gelten einzeln:

1. Suchbereich = dem Space zugeordnete ∩ für die Gruppe lesbare Bibliotheken.
2. Die Mindestgruppengröße gilt für die Schnittmenge aus aktiven Mitgliedern der Gruppe und Zugang zum
   Space auf jedem Weg, geprüft zum Zeitpunkt des Laufs, sonst `403`; ist die Prüfung zu teuer,
   entfällt der Space-Kontext, nicht die Prüfung.
3. Gezählt werden aktive Konten, nicht Mitgliedschaftszeilen.
4. Profil-Läufe mit Space-Kontext werden protokolliert, eine Zeile je Lauf.
5. `SYSTEM_ADMIN`-Vorbehalt wie oben.

Die Mindestgruppengröße ist ein anderes Maß als das der Nutzungstransparenz in `spaces-and-assets.md`:
Für ein Rechteprofil zählt der Rechtekontext, nicht die Nutzung.

### 8. Rechtehistorie, Stichtagsauskunft und Aufbewahrung

**Audit verfällt, Historie bleibt.** Ein Audit-Ereignis sagt, *dass* jemand etwas tat, und wird nach der
Protokollfrist gelöscht; eine Historienzeile sagt, *in welchem Zeitraum* ein Recht galt. Jede
Änderungsart hat genau eine Seite (Paket `io.opaa.permission`):

| Änderungsart | Tabelle der Stichtagsauskunft | Audit |
| --- | --- | --- |
| Asset-Grant | `asset_grant_history` | ja |
| Gruppenmitgliedschaft, Deaktivierung einer internen Gruppe (Entscheidung 14) | `group_membership_history` | ja |
| Fremdzugang am Asset | `asset_visibility_history` (ADR-0037, ADR-0039) | ja |
| Space-Mitgliedschaft (Person oder Gruppe, mit Rolle) | `space_membership_history` | ja |
| Eigentum an Asset und Space | `asset_ownership_history` (Objekttyp `ASSET` oder `SPACE`) | ja |
| Fähigkeit | `capability_grant_history` | ja, Governance-Ereignis |
| Kontozustand (nutzbar/nicht nutzbar, mit Ursache) | `account_state_history` | ja |
| Verantwortliche interner Gruppen, Vollmachten, Nachfolgevorgänge | keine — Betriebsrechte der Gegenwart | ja |
| Systemrolle | zugesagt, **nicht gebaut** (Offen); bis dahin nur Audit | ja |

Alle Historientabellen tragen `valid_from`/`valid_to` nach ADR-0016: Subjektspalten `RESTRICT`
(außer am Kontozustand, siehe unten), Objektspalten ohne Fremdschlüssel. **Die Personenspalten bleiben `RESTRICT` und werden nicht ab
Schreibzeitpunkt pseudonymisiert** — die Pseudonymisierung ist Voraussetzung des Personen-Einstiegs und
der Kontolöschung, nicht des Schreibens, weil eine fehlende Auswertungsfunktion auffällt, eine
Schreibblockade unter Termindruck aber per Ausnahme fällt. Damit sind die beiden offenen Folgefragen
aus ADR-0016 beantwortet: Namensschnappschuss ja (unter der Höchstdauer), Pseudonymisierung ab
Schreibzeitpunkt nein.

- **Kontozustand:** Historisiert ist jeder Zustand, in dem `AccountUsability` ein Konto nicht
  nutzbar nennt, mit eigener Ursache: Verzeichnissperre, Sperre durch Verwaltung oder wegen
  Inaktivität, Anmeldesperre bis zu ihrem Ende (`locked_until`), Ablauf, offene Einladung,
  abgeschaltete lokale Kontenverwaltung, abgeschalteter oder gelöschter Anbieter. Es schreibt die
  Datenbank: Trigger auf `users`, `local_credentials` und `oidc_providers` werten die Regel nach
  jeder Änderung neu aus (`account_state_reconcile`), in deren Transaktion, gleich welche Version oder
  welcher Weg schreibt (ADR-0053, Entscheidung 13); einen ohne Schreibzugriff erreichten Ablauf hält
  ein minütlicher Lauf fest. Die Kette eines Kontos beginnt mit seiner ersten Zustandsänderung; ein
  nie gesperrtes Konto hat keine Zeile. Die Kontenmenge zum Stichtag ist `users` (angelegt vor dem
  Stichtag), korrigiert um diese Intervalle. Vollständig ist das ab der Einführung
  (`account_state_complete_since`); ein früheres Fenster nennt den Kontozustand als nicht abgedeckt.
  Für sich nennen die Intervalle keinen Zugang — jeder Zeitraum, den sie kürzen, steht in einer
  Historie mit `RESTRICT` —, deshalb gehen sie mit dem Konto (`ON DELETE CASCADE`). Keinen zweiten
  Lesepfad („Verlauf" am Konto).
- **Stichtagsauskunft** (`PointInTimeAccessService`, Rolle `AUDITOR`): **Objekt-Einstieg**
  (`GET /api/v1/audit/access-as-of`: „wer durfte Objekt Z am 3. März lesen") ohne weitere Vollmacht, aber
  mit verpflichtendem, begrenztem Zeitfenster und Seitenobergrenze — eine zu weite Anfrage wird
  abgelehnt, nicht zurechtgestutzt — und **genau ein benanntes Objekt je Abfrage**, keine Sammelabfrage.
  Der **Personen-Einstieg** („worauf hatte Person X Zugriff") ist nicht gebaut und nur mit einer
  befristeten, begründeten Vier-Augen-Vollmacht nach dem Muster von `audit_incident_scope_grants`
  zulässig. Jeder Abruf ist ein Protokollereignis, auch der abgewiesene.
- **Aufbewahrungshöchstdauer** (`PermissionHistoryRetentionSettings`, 12–120 Monate, ausgeliefert 36 —
  dieselben Grenzen wie `chk_audit_retention_settings_months`, weil `security-and-compliance.md` die
  Historie der Protokolllogik unterstellt); Änderungen sind Governance-Ereignisse. Sie ist Vorbedingung
  jeder personenbezogenen Historienquelle und des Namensschnappschusses aus Entscheidung 2. ADR-0016
  bleibt unberührt: Die Historie überlebt die Löschung des Objekts, nicht die Höchstdauer.
- **Verfahren:** Vor dem Rollout legt die Dienststelle die Auskunft über die Datenerhebung vollständig
  vor; die Personalvertretung erhält einen Testzugang; Änderungen an Mindestgruppengröße,
  Abgleichintervall, Plausibilitätsschwelle und Aufbewahrungsfristen sind Governance-Ereignisse.

### 9. Sichtbarkeit von Gruppen und die Herleitung „warum sehe ich das"

**Interne Gruppen sind Opt-in** (`groups.released_for_use`): Verantwortliche geben eine Gruppe zur
Verwendung frei; erst dann ist sie für andere Rechtevergebende wählbar. Die Auswahl eines Empfängers
setzt eine bewusste Handlung der Verantwortlichen voraus. Drei Festlegungen:

1. Die Migration setzte „freigegeben" für jede Bestandsgruppe mit Wirkung; „nicht freigegeben" gilt nur
   prospektiv.
2. Durchgesetzt im Service (`GroupService`, `GroupStates`), für jeden Weg einschließlich der Eingabe per
   ID: Eine nicht freigegebene interne Gruppe ist für einen Aufrufer, der weder Mitglied,
   Verantwortlicher noch `SYSTEM_ADMIN` ist, `404`.
3. Die Bestandsänderung ist ausgesprochen: Ein `MANAGER` kann eine neue interne Gruppe erst als
   Empfänger wählen, wenn ihre Verantwortlichen sie freigegeben haben.

**Wer ein Recht gibt, sieht, an wen:**

| Wer | Name und Herkunft | Mitgliederzahl | Mitgliederliste |
| --- | --- | --- | --- |
| Verantwortliche einer internen Gruppe | ja | voll | ja |
| `SYSTEM_ADMIN` | ja | voll | ja — der Abruf ist das Audit-Ereignis `GROUP_MEMBERS_READ` |
| Wer der Gruppe an einem Objekt ein Recht einräumt oder verwaltet, solange die Gruppe dort ein Recht hält | ja | heutige Zahl aktiver Konten, ohne Mindestgruppengröße | ja, auch bei kleinen Gruppen; **außer bei geschützten Gruppen** |
| Rechtevergebende in der Subjekt-Auswahl | Anbietergruppen: ja; interne nur, wenn freigegeben | Zahl aktiver Konten; unterhalb der Mindestgruppengröße „kleine Gruppe" | nein |
| Mitglied | seine eigenen Gruppen mit Verantwortlichen | Größe | nein |
| Sonstige | nichts | — | — |

Die Mitgliederlisten-Ausweitung hat vier Begrenzungen: nur solange die Gruppe dort ein Recht hält; nur
für zur Verwendung freigegebene Gruppen; Vorgabe ist nicht freigegeben (die Bestandsmigration löste das
nur prospektiv ein); nicht bei geschützten Gruppen. Die
Mindestgruppengröße in der Subjekt-Auswahl ist damit eine Voreinstellung, kein Schutz (wer verwaltet,
kann sie über „Recht geben, aufklappen, entziehen" umgehen; die Spur ist die Rechtehistorie). Sie bleibt
Schutz für „Sicht als" und die Suchdiagnose.

**Geschützte Gruppen** (`groups.protected`, für Personalvertretung, Schwerbehindertenvertretung,
Gleichstellung, Personalvorgänge): Das Kennzeichen setzt und löst **allein die Systemverwaltung** (seit
#1978, Audit `GROUP_PROTECTION_CHANGED`), für interne und Anbietergruppen; die Nachprüfbarkeit ist die
Sicherung, nicht die Unverfügbarkeit für die Administration. Eine geschützte Gruppe ist nicht über
Suche auffindbar, sondern nur über ihre vollständige Bezeichnung wählbar
(`findProtectedByOrganizationIdAndName`), erscheint in fremden Listen als „Geschützte Gruppe" ohne Namen (damit ein
Space-`ADMIN` eine Mitgliedschaft beenden kann, die er sonst nicht sähe), zeigt Grant-Gebern weder Zahl
noch Mitglieder, sondern bei einer internen Gruppe die Verantwortlichen, bei einer Anbietergruppe
niemanden (`GroupMemberDisclosure`); die Herleitung zeigt Dritten bei ihr nur die effektive Rolle, keine
Gruppenableitung.

**Der Bericht des Verzeichnisabgleichs ist ein zweiter Weg zu den Namen und wird protokolliert, nicht
maskiert** (`DirectorySyncReportDisclosure`): Jede Antwort an die Systemverwaltung, die
Namen ausliefert (ausstehender Plan, Probelauf, Lauf von Hand, Bestätigung), schreibt vor der Antwort
`DIRECTORY_SYNC_REPORT_READ` mit Weg, Anbieter, Zahl der Gruppen und Personen — nie den Namen;
scheitert der Eintrag, scheitert die Antwort. Ein Bericht ohne Namen schreibt nichts, der
zeitgesteuerte Lauf liefert niemandem etwas aus, und die Oberfläche ruft einen Plan einmal je Plan ab.

**Herleitung für die eigene Person** (`GET …/access-derivation`): Für jedes Asset und jeden Space zeigt
die Oberfläche der Person ihren eigenen Weg zur effektiven Rolle (`AccessBasis`: direkter Grant, Grant
über Gruppe mit Name, Herkunft, Mechanismus und Zeitpunkt, Freigabe an „Alle Konten", direkte
Space-Mitgliedschaft, Space-Mitgliedschaft über Gruppe, Eigentum), ohne
Vollmacht, ohne Protokoll, ohne Mitglieder offenzulegen. Gegenüber anderen nennt die Herleitung den
Gruppennamen nur dort, wo die Person die Mitgliedschaft verwaltet (Space-Mitgliederliste für
`ADMIN`/Eigentümer). Die Zeile einer Gruppe zeigt die heutige Zahl aktiver Konten, kein
Zuwachssignal.

### 10. Die Übertragungsoperation

Eine allgemeine, protokollierte Operation auf der Gruppenachse (`PermissionTransferService`,
`POST /api/v1/permission-transfers`) überträgt Grants, Space-Mitgliedschaften, Fähigkeiten, Eigentum an Assets und Spaces
und (intern) Verantwortliche (`PermissionTransferScope`) — mit Vorschau, Bestätigung, Audit
(`PERMISSION_TRANSFER_PREVIEWED`/`_EXECUTED`) und sauberem Historienschnitt. Vier Anlässe, eine Mechanik:
Reorganisation, Anbieterablösung, Mechanismuswechsel, Nachfolgeübernahme.

- Quelle und Ziel: Gruppe → Gruppe (voller Umfang), Gruppe → Person, Person → Person. **Bei einer Person
  als Quelle nur Eigentum und Verantwortung**; Grants und Space-Mitgliedschaften einer Person sind weder
  übertragbar noch in der Vorschau aufzählbar — sonst wäre die Vorschau die personenbezogene
  Rechteübersicht ohne Vollmacht. Das Ziel muss wirksam sein, darf leer sein. Der Umfang ist bei
  Gruppen wählbar: alle Wirkungen oder eine Teilmenge (nur Grants, nur Eigentum, nur die Wirkungen an
  einem Anbieter).
- Wer: `SYSTEM_ADMIN` organisationsweit; für „Eigentum und Verantwortung, die ich selbst trage" auch die
  Person selbst. Ein `MANAGER` ändert Grants an seinem Asset weiterhin einzeln.
- Historienschnitt: je Zeile `valid_to` der Quelle und `valid_from` des Ziels mit demselben Zeitstempel
  und einer gemeinsamen Vorgangskennung (`TRANSFERRED_OUT`/`_IN`).
- Die Vorschau ist Pflicht und selbst ein Protokollereignis, auch bei Abbruch. Objekte tragen den
  Vorgang, bei einer Gruppe als Quelle deren Namen, bei einer Person nicht.
- Nicht enthalten: die Rücknahme nach einem Vorfall (Entscheidung 2) und eine automatische Auslösung
  durch den Verzeichnisabgleich — die Übertragung bleibt eine Entscheidung.

### 11. Migration und ihre Fehlerfälle

Gruppen gelöschter Anbieter, `ORG_UNIT`-Gruppen ohne Standardanbieter und anbieterlose
`ORG_UNIT`-Gruppen aus dem `dev`-Modus wurden zu **internen Gruppen ohne Verantwortliche**
(Herkunftsvermerk in der Beschreibung, Audit unter dem Akteur `migration`), damit `provider_id` mit
`RESTRICT` setzbar war — die Baseline hat bewusst keine Rollback-Blöcke (ADR-0034). Der neue
Eindeutigkeitsschlüssel wurde vor dem alten angelegt, dann das Präfix geschnitten. Ein Migrationsfehler,
den man vorher kennt, ist ein Testfall: jedes Changeset mit Delta-Test. Die Changesets selbst sind mit
der Baseline vom 27.09.2026 zusammengefasst.

### 12. Paketschnitt: `io.opaa.permission`, typunabhängige Grants, Asset-Schale

Rechtesubjekt, Grants, Rechteformel (`AssetAccessService`), Herleitung, Fähigkeiten und Rechtehistorie
liegen im Paket `io.opaa.permission`; `library`, `group` und `space` hängen nur in Richtung dieses
Pakets, `ModularArchitectureTest` hält die Richtung (Modulregeln: `backend/AGENTS.md`,
`backend/src/main/java/io/opaa/permission/AGENTS.md`).

**Asset-Schale:** Die Typunabhängigkeit der Grants wird über die gemeinsame Tabelle
`assets` gehalten (Kennung, Typ, Organisation, Name, Eigentümer, Herkunft), auf die jede Typtabelle
zeigt (`KnowledgeLibrary extends Asset`, Paket `io.opaa.asset`); `asset_grants` und
`space_asset_associations` verweisen mit Fremdschlüssel auf `assets`. Katalog, Nachfolge
(`succession_cases.asset_type`) und Reichweitensperre sind eine Abfrage über die Schale. Ein neuer Typ ist
eine Tabelle, eine Entität, eine `AssetTypeDefinition` und ein Wert in `AssetType` sowie in
`chk_audit_log_object_type` (auch die Prompt-Bibliothek). Das Inhaltsrecht
(`AssetAuthorization#requireContentRole`), die Eigentümergruppe beim Anlegen
(`AssetGrantService#requireOwnableGroup`) und die Eigentümernamen (`AssetOwnerNames`) leben in der Schale.
`io.opaa.asset` hängt von `io.opaa.permission` ab, nicht umgekehrt, und kennt weder `library` noch
`space`, `group` oder `succession`. Die Historientabellen bleiben nach
ADR-0016 ohne Fremdschlüssel auf `assets`.

### 13. Randbedingungen

Alles je Organisation: `groups.organization_id`, `capability_grants.organization_id`, Verantwortliche
derselben Organisation, Nachfolgeliste je Organisation. `oidc_providers` trägt keine `organization_id`
(#1442); sobald Anbieter einer Organisation zugeordnet werden, kommt die Prüfregel „Gruppe und Anbieter in
derselben Organisation" als zusammengesetzter Fremdschlüssel hinzu. `kind` bleibt der Mechanismus-Enum
(API-Typ, Prüfbedingung, Filter); `parentGroupId` bleibt Anzeige- und Aggregationsachse der
Aufbauorganisation und trägt keine Mitgliedschaft. ADR-0016, ADR-0025, ADR-0033 und ADR-0034 gelten
unverändert; jede API-Änderung beginnt in der Spezifikation (ADR-0006).

### 14. Deaktivieren einer internen Gruppe

Eine interne Gruppe lässt sich vorübergehend deaktivieren — der umkehrbare Zwischenzustand
zwischen „Freigabe zurücknehmen" (Rechte bleiben wirksam) und „übertragen und löschen" (endgültig).

1. **Eigener Schalter** `groups.deactivated` (`deactivated_at`), nur für `kind = AD_HOC`, unabhängig von
   der Freigabe. Reaktivieren stellt genau den vorherigen Stand wieder her: Grants,
   Space-Mitgliedschaften, Anlegerechte, Mitgliedschaften, Verantwortliche, Freigabe- und Schutzkennzeichen
   bleiben gespeichert.
2. **Während deaktiviert wirkt die Gruppe für niemanden**, durchgesetzt **an einer Stelle**: Der
   `GroupMembershipResolver` liefert sie in `groupIdsForUser` nicht und für sie in
   `resolveUserIds`/`activeMemberIds` niemanden. Damit blenden Rechteformel und Suche, Rechteprofil der
   Suchdiagnose, `SpaceAccessPolicy`, `CapabilityService#hasCapability`, Herleitung und aus Rechten
   folgende Zuständigkeiten sie ohne eigene Prüfung aus. Die Verwaltung der Gruppe selbst bleibt
   möglich; Mitgliederzahlen bleiben die tatsächlichen.
3. **Keine wirksame und keine handlungsfähige Gruppe:** kein neues Grant-Ziel, Space-Mitglied,
   Übertragungsziel oder Eigentümerin; nicht wählbar mit Grund (`GroupState.DEACTIVATED`); zählt nicht
   als handlungsfähiges `ADMIN`-Mitglied; erscheint nicht in „Freigaben ohne Empfänger".
4. **Verweigert (`409`, beziffert)**, solange die Gruppe einzige Eigentümerin eines Assets (jede Art der
   Schale) oder letztes handlungsfähiges `ADMIN`-Mitglied eines Space ist — Schutzregel 1 aus
   Entscheidung 6; Ausweg ist die Übertragung oder eine weitere Zuständigkeit (`GroupDeactivationPreview`).
5. **Wer:** Verantwortliche und Systemverwaltung (`PUT /api/v1/groups/{id}/deactivation`); sonst
   `404`. Anbietergruppen sind nicht deaktivierbar (`400`).
6. **Sofortige Wirkung:** Cache-Invalidierung nach Commit für alle Mitglieder und Benachrichtigung jedes
   `GroupMembershipChangeListener`; der Trigger auf `groups` leert die Rechte-Caches jeder Instanz mit
   dem Commit (ADR-0053 Entscheidung 5).
7. **Keine Frist, keine Wiedervorlage.**
8. **Audit:** `GROUP_DEACTIVATED`/`GROUP_REACTIVATED` mit bezifferten Folgen, nie mit Namen.
9. **Rechtehistorie:** Das Deaktivieren schließt jedes offene Mitgliedschaftsintervall in
   `group_membership_history` mit Ursache `GROUP_DEACTIVATED`, das Reaktivieren öffnet für jedes dann
   bestehende Mitglied ein neues mit `GROUP_REACTIVATED`; eine Aufnahme während der Deaktivierung öffnet
   kein Intervall. Jede Rekonstruktion schneidet Grant und Mitgliedschaft über diese Intervalle und stimmt
   ohne Änderung. Ein Intervall dieser Tabelle heißt damit „wirkte über die Gruppe", nicht „stand in der
   Mitgliederliste".

### Zahlen

| Größe | Wert | Ort |
| --- | --- | --- |
| Mindestgruppengröße (Rechteprofile, „Sicht als", Subjekt-Auswahl) | erzwungene Untergrenze **5**, Voreinstellung 5, nur nach oben änderbar | `GroupSizeProperties.ENFORCED_MINIMUM`, `OPAA_MINIMUM_GROUP_SIZE` |
| Alterungsschwelle der Nachfolgeliste | **12 Monate**, konfigurierbar (orientiert an `DiagnosticImpersonationGrant.MAX_VALIDITY_MONTHS`) | `opaa.succession.aging-threshold-months` |
| Feststellungslauf | stündlich | `opaa.succession.detection-cron` |
| Plausibilitätsschwelle des Abgleichs | **30 %** | `opaa.directory-sync.change-threshold-fraction` |
| Abgleichintervall | Vorgabe **6 Stunden**, je Anbieter 5 Minuten bis 7 Tage | `OidcProviderService.DEFAULT_SYNC_INTERVAL_MINUTES`, `oidc_providers.directory_sync_interval_minutes` |
| Aufbewahrungshöchstdauer der Rechtehistorie | **12–120 Monate**, ausgeliefert **36** | `PermissionHistoryRetentionSettings` |

### Zusagen aus der Stakeholder-Runde

Die Zusagen sind Teil der Entscheidung. Kennungen nach `discussion-berechtigungsmodell-stakeholder.md`.

| Zusage | Stand | geändert mit |
| --- | --- | --- |
| A1 Herleitung nennt Dritten den Gruppennamen nur, wo sie die Mitgliedschaft verwalten | gilt (Entscheidung 9) | geändert gegenüber der Forderung „nie", weil die Space-Mitgliederliste ohnehin nur `ADMIN`/Eigentümer sehen |
| A2 „kleine Gruppe" statt Zahl unterhalb der Mindestgruppengröße | gilt nur in der Subjekt-Auswahl | #2134: an bestehender Freigabe/Mitgliedschaft heutige Zahl und Namen |
| A3 geschützte Gruppen in keiner fremden Liste | geändert: namenlose Zeile in fremden Listen, damit ein Space-`ADMIN` eine Mitgliedschaft beenden kann | #1978: Kennzeichen setzt die Systemverwaltung |
| A4 Mitglieder sehen Verantwortliche; Aufnahme/Entfernung werden angezeigt | gilt (Entscheidung 4) | — |
| A5 Hinweis beim Anlegen einer internen Gruppe | gilt | — |
| A6 Mitgliederabruf durch `SYSTEM_ADMIN` ist Audit-Ereignis | gilt (`GROUP_MEMBERS_READ`) | #1991: Abgleichsbericht als zweiter Weg protokolliert |
| B1–B6 Mindestgruppengröße für Profile, Schnittmenge zum Laufzeitpunkt, Protokoll mit Space-Kontext, `SYSTEM_ADMIN`-Vorbehalt, erzwungene Untergrenze, Leitplanke (b) | gilt (gebaut, #1835, Entscheidung 7) | — |
| C1–C4 Sperren aus dem Abgleich: Schwelle und Bestätigung, rückholbar, Grund und Ansprechstelle, kein stiller Entzug | gilt (Entscheidung 3) | — |
| D1 Höchstdauer als Vorbedingung weiterer Quellen | gilt (Entscheidung 8) | — |
| D2 Personen-Einstieg nur mit Vier-Augen-Vollmacht | gilt; Personen-Einstieg nicht gebaut | — |
| D3 jeder Abruf ein Ereignis, auch abgewiesen | gilt | — |
| D4 Zeitfenster und Seitenobergrenze, zu weite Anfrage abgelehnt | gilt | — |
| D5 Namensschnappschuss nur mit D1 | gilt | — |
| D6 Verantwortlichkeit kein Historienartefakt | gilt | — |
| D7 Pseudonymisierung | geändert: Voraussetzung des Personen-Einstiegs und der Kontolöschung, nicht des Schreibens | Entscheidung 8 |
| E1–E7 objektbezogene Nachfolgeliste, Arbeit unberührt, Vorgänge unter Protokollfrist, Bestand statt Aktivität, Fähigkeitsvergabe als Governance-Ereignis, Fähigkeit ≠ Vollmacht, kein Lesebypass | gilt | — |
| F1–F4 Auskunft über die Datenerhebung, Testzugang, Protokollpflicht für Wertänderungen, Ausweisung jeder Auflage | gilt (Entscheidung 8) | — |
| Z1 Space-Kontext als eigenes Issue mit fünf Schutzpunkten | gilt (gebaut, #1835) | — |
| Z2 geschützte Gruppen ohne Gruppenableitung für Dritte | gilt | — |
| Z2b Ansprechstellen an Anbietergruppen | entfallen | #1978 |
| Z3 Ein-Objekt-Regel der Stichtagsauskunft | gilt | — |
| Z3b `RESTRICT` für alle neuen Historientabellen | gilt | — |
| Z4 Person als Quelle nur Eigentum und Verantwortung; Vorschau protokolliert; Objekthinweis ohne Personennamen | gilt (Entscheidung 10) | — |
| Z5 Kennzeichnung am Objekt ohne Eigentümer, Grund, Datum | gilt | — |
| Z6 „kleine Gruppe" für beide Zahlen des Zuwachssignals | gegenstandslos: Signal entfallen; geschützte Gruppen tragen keine Zahl | #2134 |
| Z7 keine Auswertungsachse über die handelnde Person | gilt | #2336: Sichtungsvermerk entfallen |
| Betrieb 2.5 `409` beim Anbieterlöschen nur mit Übertragung | gilt (Entscheidungen 2, 10) | — |
| Referatsleitung 4 „wer ein Recht gibt, sieht, an wen" | gilt, Ausnahme geschützte Gruppen | #1978: bei Anbietergruppen Auskunft durch die Systemverwaltung |
| Betrieb 4.2 Historientabelle für Verantwortliche | zurückgewiesen: Verantwortung trägt kein Leserecht | — |
| Referatsleitung 3 Pull als Vorbedingung für Grants | zurückgewiesen: Mechanismus ist Anbietereinstellung; Alternative übernommen | — |
| Sachbearbeitung 4a Anlegen interner Gruppen bis zur Vergabe sperren | zurückgewiesen: Auslieferung ändert kein Verhalten; Handbuchempfehlung | — |

## Konsequenzen

**Einfacher:** Subjekte sind Nutzer und Gruppen, Herkunft ist ein Fremdschlüssel, Synchronisation je
Anbieter genau eine Quelle. Eine Reorganisation ist ein Vorgang mit lesbarer Historie. Ein gelöschter
Anbieter kann keine wirkende Gruppe verwaisen lassen. „Warum sehe ich das" hat eine Antwort im Produkt.
Der Paketzyklus `library` ↔ `group` ist aufgelöst. Die Mitbestimmungsfähigkeit hängt an
Produkteigenschaften (Untergrenze, Vier-Augen-Vollmacht, Abrufprotokoll, Ein-Objekt-Regel, Höchstdauer).

**Schwieriger:** Die Löschschuld wächst mit jeder `RESTRICT`-Personenspalte (Autorität:
`UserDeletionBlockerCoverageIntegrationTest`); eine Kontolöschung bleibt blockiert, bis die
Pseudonymisierung der Historienspalten gebaut ist. Eine neue interne Gruppe ist ohne Freigabe nicht als
Empfänger wählbar. Verantwortliche, Freigabe, Schutzkennzeichen, Deaktivierung, Fähigkeiten,
Nachfolge- und Übertragungsvorgänge sind Verwaltungsobjekte, die erklärt werden müssen.

**Restrisiken:** Der Vorgabeweg Token hat keine Schutzmechanik; das Produkt macht die Folge sichtbar,
beseitigt sie nicht. Die Mindestgruppengröße in der Subjekt-Auswahl ist kein Schutz mehr (Entscheidung
9). Die Zusagen „Entzug wirkt sofort" erreichen eine Vorgängerversion im Rolling-Update-Fenster
erst nach der Lebensdauer ihrer Caches (ADR-0053 Entscheidung 5).

## Verworfene Alternativen

- Freie Rollen als drittes Rechtesubjekt, Gruppenschachtelung, standortgebundene Gruppen, Super-Gruppe
  mit Inhaltszugriff, Schreibweg ins Verzeichnis — Vorentscheidungen (1).
- Herkunft im Präfix belassen, `origin`-Enum statt `kind`, Präfix im Gruppennamen, erzwungene
  Namenseindeutigkeit (verrät Existenz), Gruppen deaktivierter Anbieter wählbar lassen, Gruppen
  gelöschter Anbieter auflösen statt Löschen verweigern (2).
- SCIM zuerst (Deltas ohne Schnappschutz, eingehender Schreibpfad, Lizenz), Token und Pull am selben
  Anbieter (zwei Wahrheiten), LDAP/Graph als erster Konnektor (Abbildungsregel nötig), Leerergebnis in
  den Bestätigungsweg verlegen, Pull als Vorbedingung für Grants (3).
- Pflege nur durch Systemverwaltung, Selbstbeitritt (gibt sich selbst Rechte), Gruppen als
  Verantwortliche, automatische Abgabe beim Referatswechsel, Historientabelle für Verantwortliche,
  Anlegen bis zur Vergabe sperren (4).
- Fähigkeit als Grant auf ein Pseudo-Asset (gestufte Rolle vs. Mengenelement), „Alle Konten" als
  Gruppenobjekt, Reichweitenentscheidungen als Fähigkeit doppeln, neue Systemrolle (5).
- „Nachfolge offen" als Flag (driftet), gestufte Zuständigkeit ohne vollständige Liste, „aktivste
  Mitglieder" (Aktivitätsauswertung), `manager`-Attribut, Pflichtsichtung mit Frist, Kennzeichnung in
  Suchtreffern, leere Gruppen vom Erteilen ausschließen (6).
- Space-Kontext sofort (Mindestgröße hinge an der Gruppe statt an der Schnittmenge), Zählung „über G im
  Space" (strukturell null) (7).
- Historie ohne Höchstdauer, beim alten Historienstand bleiben, Pseudonymisierung ab Schreibzeitpunkt
  oder als Schreibvoraussetzung, zweiter Lesepfad am Konto, Sammelabfragen, zu weite Abfrage
  zurechtstutzen, Kontozustand je Schreibweg im Anwendungscode schreiben (ein vergessener Weg, eine
  ältere Version oder eine Handkorrektur ließe eine Lücke), Kontozustand mit `RESTRICT` (machte jedes
  einmal gesperrte Konto unlöschbar) (8).
- Opt-out-Sichtbarkeit (Schutz hinge am Setzen), Durchsetzung nur in der Auswahlliste (Kosmetik),
  A1/A3 im Wortlaut, Zustimmungspflicht bei Gruppenzuwachs, Maskierung des Abgleichsberichts (9).
- Keine Übertragungsoperation (endet im `UPDATE` auf der Datenbank), Sonderfall je Anlass, Person als
  Quelle mit allen Wirkungen, Objekthinweis mit Personennamen, automatische Auslösung durch den
  Abgleich (10).
- `RESTRICT` ohne Migrationsvorkehrung, alten Schlüssel vor dem neuen fallen lassen (11).
- Dritter Wert neben der Freigabe, Ausblenden an jeder Prüfstelle einzeln, eigene Zustandstabelle der
  Deaktivierung, Deaktivieren trotz Eigentum (14).

## Offen

- Personen-Einstieg der Stichtagsauskunft und Historientabelle für Systemrollen (Entscheidung 8)
  sind nicht gebaut; die Schutzpunkte stehen fest.
- Eine allgemeine Autoren-Benachrichtigung bei wesentlicher Erweiterung des Leserkreises.
- Pseudonymisierung der `RESTRICT`-Personenspalten der Rechtehistorie als Voraussetzung von
  Personen-Einstieg und Kontolöschung.
- Anbieter je Organisation (`oidc_providers.organization_id`, #1442).
- Schlüsselrotation von `CredentialsEncryptor` (keine Rotation im Bestand vorgesehen) für das
  Dienstkonto-Passwort des Verzeichnis-Konnektors.
- Allgemeine Protokollpflicht für Profil-Läufe ohne Space-Kontext; Lastverhalten der Ableitung
  „Nachfolge offen" (Antwort wäre ein materialisiertes Ergebnis des Feststellungslaufs, kein Flag).
- Mehrfachzugehörigkeit zu Organisationseinheiten; ob Entra-P1/P2-Häuser die SCIM-Zurückstellung
  mittragen.
