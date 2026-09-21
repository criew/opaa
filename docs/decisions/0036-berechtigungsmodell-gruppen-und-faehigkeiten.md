# ADR-0036: Berechtigungsmodell — Subjekte, Gruppenherkunft, Synchronisation, Fähigkeiten und Lebenszyklus

## Status

**Vorgeschlagen (19.09.2026, Issue [#1810](https://github.com/criew/opaa/issues/1810), Epic
[#1295](https://github.com/criew/opaa/issues/1295) „Gruppen, Rollen und Berechtigungen", Phase 1).**
Setzt die Abstimmungen vom 10.09. und 19.09.2026 um und schreibt die Empfehlungen des
Konzeptpapiers aus [#1809](https://github.com/criew/opaa/issues/1809) fest. Der Maintainer setzt den
Status auf „Akzeptiert" (`docs/AGENT-ORGANIZATION.md`, „ADRs"); Vorbild sind ADR-0033 und ADR-0035,
die beide als Entwurf kamen und mit dem Abschluss ihres Epics umgestellt wurden.

**Setzt den Merge von PR #1825 (Konzeptpapier, #1809) voraus.** Dieser ADR delegiert die gesamte
Abwägung und jede Antwort auf eine Stakeholder-Auflage dorthin; bis zu dessen Merge lösen die fünf
Verweise auf `docs/discussions/` nicht auf.

**Löst mit seiner Annahme ab:** [ADR-0018](0018-quellkonfiguration-in-der-bibliothek.md),
Entscheidung 6 samt Nachtrag vom 19.08.2026 (#484) — die Anlage einer Bibliothek wird dann eine
vergebbare Fähigkeit, ausgeliefert an „Alle Konten" (Entscheidung 5 unten). ADR-0018 gilt im Übrigen
unverändert.

**Alle Werte sind gesetzt.** Die beiden Auslieferungswerte, die der Entwurf vom 19.09.2026 noch offen
ließ — die Voreinstellung der Mindestgruppengröße und der ausgelieferte Wert der
Aufbewahrungshöchstdauer —, hat der Maintainer entschieden; sie stehen in „Zahlen, die dieser ADR
setzt".

**Präzisiert, ohne aufzuheben:** [ADR-0016](0016-loeschschicksal-rechtehistorie.md) (Entscheidung 8),
[ADR-0025](0025-mehrere-oidc-anbieter.md) (Entscheidungen 2 und 3),
[ADR-0033](0033-lokale-benutzerverwaltung.md) (Entscheidung 2).

**Grundlage der Abwägung** — dieser ADR wiederholt sie nicht, er verweist:
[`discussion-berechtigungsmodell-gruppen-und-faehigkeiten.md`](../discussions/discussion-berechtigungsmodell-gruppen-und-faehigkeiten.md)
(dritte Fassung, mit Systemvergleich, Optionstabellen und Quellen) und die sechs unveränderten
Berichte in
[`discussion-berechtigungsmodell-stakeholder.md`](../discussions/discussion-berechtigungsmodell-stakeholder.md)
(Betrieb/Informationssicherheit, Personalrat in zwei Sichtungen, Referatsleitung, Sachbearbeitung,
Code-Review).

## Kontext

Das Epic stellt drei Fragen: **Welche Subjekte** trägt das Rechtemodell, **woher** kommen Gruppen,
und **wie** wird ihre Mitgliedschaft synchronisiert. Dazu kommt eine vierte, die sich aus dem
gebauten Stand ergibt: Die Anlage von Spaces und Bibliotheken ist heute für jedes angemeldete Konto
offen (`/api/**` steht in `OidcSecurityConfig` auf `authenticated()`, weder `SpaceController` noch
`LibraryController` prüfen mehr) — das widerspricht der Absicht des Epics und ist für Bibliotheken
eine ausdrücklich getroffene Entscheidung (ADR-0018/6 mit Nachtrag #484), die dieser ADR ablöst.

Der gebaute Stand in Kürze, soweit er die Entscheidungen bindet:

| Baustein | Stand auf `main` |
| --- | --- |
| Rechtesubjekt | `PermissionSubject` in `io.opaa.group`: genau ein Nutzer **oder** eine Gruppe, mit `organizationId`; Auflösung über `GroupMembershipResolver` (Caffeine, 10 Minuten, Invalidierung nach Commit) |
| Gruppenarten | `GroupKind.ORG_UNIT` (Verzeichnisabgleich, schreibgeschützt, `parentGroupId`, `dissolved`), `AD_HOC` (im System angelegt), `IDENTITY_PROVIDER` (Gruppen-Claim, `external_id = oidc:<anbieter-uuid>:<name>`) |
| Herkunft | steckt in `kind` und im Präfix von `external_id`; **kein Fremdschlüssel** auf `oidc_providers`; `OidcProviderService#deleteProvider` sieht Gruppen nicht an |
| Token-Abgleich | `TokenGroupSynchronizer` setzt bei jeder Anmeldung genau die Gruppen, die das Token nennt; historisiert und auditiert unter dem Akteur `identity-provider` |
| Verzeichnisabgleich | Pull über `DirectoryClient#fetchGroups(organizationId)`; Trockenlauf, Plausibilitätsschwelle (Vorgabe 0,3), Leerergebnis-Schutz, Sperre und Statuszeile **je Organisation**; Mitglieder nur unter dem Issuer des `TrustedProvider`; im Betrieb ist `NoOpDirectoryClient` der einzige Bean; kein Zeitplan |
| Asset-Grants | `asset_grants.library_id NOT NULL` mit Fremdschlüssel; Subjekt Nutzer oder Gruppe; `AssetGrantService#requireGrantableGroup` prüft Organisation und `isDissolved()`, sonst nichts |
| Space-Mitgliedschaft | `SpaceMembership` trägt nur `user_id`; `SpaceAccessPolicy` ist eine eigene Rechteachse ohne `PermissionSubject` und ohne Rechtehistorie; `SpaceService#removeMember` schützt allein den Eigentümer, und zwar als `ValidationException` (400) |
| Systemrollen | `USER`, `SYSTEM_ADMIN`, `AUDITOR`; `LibraryAccessService#effectiveRole` gibt dem Systemverwalter `OWNER`, `readableLibraryIds` kennt **keinen** Bypass |
| Befugnisse | „Sicht als" (`diagnostic_impersonation_grants`, Geltungsbereich eine `ORG_UNIT`-Gruppe, `MAX_VALIDITY_MONTHS = 12`) und Vorfallsbereich (`audit_incident_scope_grants`, Vier-Augen-Freigabe) |
| Rechtehistorie | drei Tabellen (`asset_grant_history`, `group_membership_history`, `library_visibility_history`); `PermissionHistoryService#readableLibraryIdsAsOf` ist gebaut und getestet, hat aber **keinen einzigen produktiven Aufrufer** |
| Rechteprofil der Diagnose | `SearchDiagnosisService.PermissionProfile(id, name, libraryCount)` **ist** eine Gruppe; `SearchAdminController` ist durchgehend `hasRole('SYSTEM_ADMIN')` |

Zwei Folgen dieses Stands tragen die Entscheidungen unten. **Erstens** ist der gesamte
Verzeichnisabgleich außerhalb der Tests wirkungslos, weil kein Konnektor existiert; die einzige
produktive Gruppenquelle ist der Token — und genau dieser Weg hat keine der Schutzmechaniken.
**Zweitens** hängt jede Anbietergruppe nur über eine Zeichenkette an ihrem Anbieter: Wird er
gelöscht, bleibt eine Gruppe zurück, deren Mitgliedschaft sich nie mehr ändern kann, deren Grants
aber weiter wirken.

### Was vorentschieden ist und hier nicht neu zur Wahl steht

1. **Subjekte sind Nutzer und Gruppen, flach.** Keine freien Rollen als drittes Rechtesubjekt, keine
   Gruppenschachtelung; die festen Systemrollen bleiben.
2. **Die Gruppe bleibt Subjekt am Grant** und wird nicht in Einzelnutzer aufgelöst.
3. **Gruppen werden Space-Mitglieder mit Rolle**, nach dem Muster der Asset-Grants (#1815);
   `memberSource = GROUP` (#358) wird nicht wiederbelebt.
4. **Globale Berechtigungen sind Fähigkeiten**, ausgeliefert mit „Alle Konten dürfen" (#1813).
5. **Das Epic liefert das typunabhängige Grant-Fundament** (#1811), auf dem #1726 aufbaut.
6. **Mandantenfähigkeit (#1442) ist Randbedingung**, nicht Gegenstand.

### Begriffe

Sechs Wörter für sechs verschiedene Dinge. Im Amtsdeutsch ist „Befugnis" das Alltagswort für die
ersten drei zusammen; **ADR, Oberfläche und Handbuch benutzen durchgehend die Spalte
„In der Oberfläche".**

| Begriff | In der Oberfläche | Bedeutung |
| --- | --- | --- |
| **Rolle** | Rolle | gestufte Berechtigung an **einem Objekt**, für Person oder Gruppe (`VIEWER` an einer Bibliothek, `ADMIN` in einem Space) |
| **Fähigkeit** | Anlegerecht | unbefristetes Recht, etwas **anzulegen**; an Person, Gruppe oder „Alle Konten"; ohne Gegenstand; öffnet nie einen Inhalt |
| **Befugnis** | Vollmacht | befristete, an **genau eine Person** gebundene Erlaubnis mit Gegenstand und Begründungspflicht („Sicht als", Vorfallsbereich) |
| **Systemrolle** | Systemrolle | `SYSTEM_ADMIN`, `AUDITOR` |
| **Wirksame Gruppe** | — | eine Gruppe, der ein Recht **erteilt** werden darf: nicht aufgelöst und ihr Anbieter aktiviert (bei internen Gruppen entfällt das Zweite). Sie **darf leer sein** |
| **Handlungsfähige Gruppe** | — | eine wirksame Gruppe mit mindestens einem **aktiven** Konto — das Maß dafür, ob sie als Eigentümerin oder Space-`ADMIN` **handeln** kann |

„Vollmacht" ist bereits Sprachgebrauch des Projekts (ADR-0016, Nachtrag: „Diagnose-Vollmacht");
„Anlegerecht" ist neu und deckt die erste Liste der Fähigkeiten vollständig ab.

## Entscheidung

### Übersicht

| # | Frage | Entscheidung | Verworfen, weil |
| --- | --- | --- | --- |
| 1 | Vorbilder | Fähigkeiten an Gruppen/Personen (Confluence), schreibgeschützte Verzeichnisgruppe neben internen Gruppen, delegierte Gruppenpflege je Gruppe (Nextcloud/Jira), „letzter Verantwortlicher kann nicht gehen" (GitLab) | Schachtelung (Entra beantwortet „gilt die Untergruppe mit?" dreimal verschieden), standortgebundene Gruppen (SharePoint), Projektrollen als Indirektion (Jira), Custom Roles (GitLab), Super-Gruppe mit Inhaltszugriff (Confluence) |
| 2 | Gruppenherkunft | `groups.provider_id` als echter Fremdschlüssel (`NULL` = intern), `kind` bleibt Mechanismus, `external_id` ohne Präfix, `source_path` | Beim heutigen Modell bleiben (Anbieterbezug ohne Integrität); `origin`-Enum plus Abschaffung von `kind` (bricht API-Typ, Prüfbedingung, Filter und den Geltungsbereich der Vollmacht für einen Begriff) |
| 3 | Synchronisation | je Anbieter **Token** (Vorgabe) **oder** zeitgesteuerter Pull mit Bestätigungsweg; erster Konnektor Keycloak Admin REST API | SCIM (Push ohne Schnappschuss, für den die ganze gebaute Schutzmechanik gilt; eingehender Schreibpfad; Bereitstellungslizenz). Token **und** Pull am selben Anbieter (zwei Wahrheiten über dieselbe Mitgliedschaft) |
| 4 | Pflege interner Gruppen | Gruppenverantwortliche (Personen) | Nur Systemverwaltung (Pflege bleibt liegen); Selbstbeitritt (wer beitritt, gibt sich selbst Rechte); Space-`ADMIN` pflegt „seine" Gruppe (Standortbindung) |
| 5 | Globale Berechtigungen | eigene Tabelle `capability_grants` mit dritter Subjektart `ALL_ACCOUNTS` | Fähigkeit als Grant auf ein Pseudo-Asset „Organisation": Grants tragen eine **gestufte** Rolle mit `atLeast`, eine Fähigkeit ist ein **Mengenelement**; und „Alle Konten" als synthetisches Gruppenobjekt dopplete `visibility = ORGANIZATION` |
| 6 | Lebenszyklus | „Nachfolge offen" als **abgeleiteter** Zustand mit benanntem Feststellungslauf; vollständige Liste ab Tag eins | Gespeichertes Flag (driftet beim ersten vergessenen Auslöser); gestufte Zuständigkeit **ohne** vollständige Liste (ein Fall der Stufe 2 erreicht Stufe 3 nie); „aktivste Mitglieder" als Adressat (personenbezogene Aktivitätsauswertung) |
| 7 | Diagnose | Rechteprofil bleibt eine Gruppe; Space-Kontext erst mit eigenem Issue und fünf Schutzpunkten | Space-Kontext sofort: die Mindestgruppengröße hinge an der Gruppe statt an der Schnittmenge und wäre damit wirkungslos |
| 8 | Rechtehistorie | erweitern **mit** Aufbewahrungshöchstdauer und geregeltem Lesepfad als Vorbedingung | Stand halten (mit #1815 wird eine Space-Zeile zur Berechtigung für Hunderte; die Negativfrage wäre nach Ablauf der Protokollfrist unbeantwortbar); erweitern ohne Höchstdauer (unbefristete Datenspur auf acht Quellen) |
| 9 | Sichtbarkeit | Opt-in „Freigabe zur Verwendung" für interne Gruppen, Bestandsgruppen mit Wirkung migriert als freigegeben | Wie heute (jeder `MANAGER` erteilt an jede Gruppe, auch per ID); Opt-out (der Schutz hängt daran, dass ihn jemand setzt — und gerade die Gruppen, die ihn brauchen, entstehen von Leuten, die an Freigabedialoge nicht denken) |
| 10 | Übertragung | **eine** allgemeine Operation auf der Gruppenachse, mit vier Anlässen | Keine Operation (Handarbeit an 200 Objekten endet im `UPDATE` auf der Datenbank); Sonderfall je Anlass (dreimal dieselbe Historienschreibung mit drei Fehlerquellen) |
| 11 | Migration | Waisen-Gruppen werden interne Gruppen ohne Verantwortliche | `provider_id` mit `RESTRICT` ohne Vorkehrung: Liquibase bricht ab, die Anwendung startet nicht, und die Baseline hat bewusst keine Rollback-Blöcke |

### 1. Vorbilder, und die eine Zusage, die dabei nicht fällt

OPAA übernimmt das Confluence-Muster „Create Space(s)" als an Gruppen **und** Personen vergebbare
Fähigkeit, das Betriebsmodell „Read Only, with Local Groups" (Verzeichnisgruppen schreibgeschützt,
interne Gruppen daneben, Verzeichniskonten dürfen Mitglied interner Gruppen sein), die
Gruppenadministration je Gruppe (Nextcloud) und die Regel „der letzte Verantwortliche kann sich
nicht selbst entfernen" (GitLab). OPAA schreibt **nie** ins Verzeichnis.

**Die Super-Gruppe wird nicht übernommen, und das hat eine prüfbare Form.**
`LibraryAccessService#readableLibraryIds` kennt keinen Systemverwalter-Bypass, und **keine
Fähigkeit aus Entscheidung 5 darf ihn einführen**. Diese Zusage gilt für die **Suche**; in der
Bibliotheksverwaltung führt `effectiveRole` den Systemverwalter als `OWNER`. Beide Sätze stehen hier
nebeneinander, damit es nicht zwei Lesarten gibt. Wer eine Störung nachstellen muss („die Suche
liefert ein Dokument, das ich nicht sehen dürfte"), tut das über die **Suchdiagnose mit
Rechteprofil** — das ist der dafür vorgesehene Weg, nicht ein Umweg über eine Rolle.

### 2. Gruppenherkunft: `provider_id` als echter Fremdschlüssel

- **Neue Spalte `groups.provider_id`** (nullbar, Fremdschlüssel auf `oidc_providers`, `ON DELETE
  RESTRICT`), Prüfregel `kind = AD_HOC ⇔ provider_id IS NULL`. `external_id` verliert das Präfix und
  trägt nur noch die Kennung aus der Quelle; die Eindeutigkeit wird
  `(organization_id, provider_id, kind, external_id)`. Die Herkunft im Sinne von #1443 („intern oder
  Anbieter X") ist damit **ableitbar**, nicht doppelt gespeichert; `GroupResponse` liefert sie als
  `origin` und `provider { id, displayName }` (Spezifikation zuerst, ADR-0006). Eine zusätzliche
  `origin`-Spalte gibt es nicht.
- **`source_path`** trägt den Pfad der Quelle („/Haus/Abteilung 5/Referat 50"). Ohne ihn sind die
  gleichnamigen Untergruppen eines Keycloak-Verzeichnisses („Leitung", „Sachbearbeitung") nicht
  unterscheidbar, und der Anbietername hilft dort nicht.
- **Je Anbieter genau ein Gruppenmechanismus**, Token-Claim **oder** Verzeichnisabgleich. Das
  Einschalten des Abgleichs verlangt einen leeren `groups_claim` und umgekehrt; die Verwaltung meldet
  den Konflikt mit `409` und verständlichem Grund. Sonst entstünde dieselbe Verzeichnisgruppe zweimal,
  mit zwei Wahrheiten über die Mitgliedschaft und zwei Entzugszeitpunkten.
- **Anzeige statt erzwungener Eindeutigkeit.** Die Herkunft ist ein Zusatz zum Namen
  („Referat 50 · Verzeichnis Haus A · 23 Mitglieder"), nie ein Präfix im gespeicherten Namen. Für
  interne Gruppen wird Namenseindeutigkeit **nicht** erzwungen: Ein `409` beim Anlegen verriete jedem
  Inhaber des Anlegerechts, dass eine Gruppe dieses Namens existiert — genau das Datum, das
  Entscheidung 9 schützt. Stattdessen warnt die Oberfläche, wenn eine für den Anlegenden **sichtbare**
  Gruppe gleich heißt.
- **Externe Anbieter sind mehr als ein Textzusatz.** Die Anbieterzeile trägt ein Kennzeichen
  **„extern"** (Vorgabe: jeder Anbieter außer dem Standardanbieter, bis die Systemverwaltung es
  ändert). Gruppen externer Anbieter sind in jeder Auswahl **sichtbar anders** dargestellt (Symbol,
  nicht nur Text), und das Erteilen eines Rechts an eine solche Gruppe verlangt eine Zwischenfrage —
  dieselbe Mechanik, die `access-control.md` für externe Konten schon vorsieht.
- **Auditeinträge und Rechtehistorie tragen zur Gruppen-ID die Herkunft als Text**
  („Referat 50 (Verzeichnis Haus A)"), damit ein Prüfer sie liest, ohne die Anbietertabelle zu
  joinen. Dieser **Namensschnappschuss ist nur zusammen mit der Aufbewahrungshöchstdauer aus
  Entscheidung 8 zulässig**: Ohne sie bliebe der Name einer gelöschten Gruppe
  „Disziplinarverfahren 2026" unbefristet an jedem ihrer früheren Mitglieder hängen.

**Deaktivieren eines Anbieters** behält **genau die heutigen Wächter** und bekommt **keine neuen**:
`OidcProviderService#setEnabled` weist den Standardanbieter ab, solange weitere Anbieter aktiv sind,
verlangt für den letzten aktiven OIDC-Anbieter die ausdrückliche Bestätigung und lässt nie eine
Installation ohne anmeldefähigen Systemverwalter zurück (`LocalAdminAvailabilityGuard`, ADR-0033).
Gruppen, Mitgliedschaften und Grants bleiben unverändert stehen.

**Die Gruppen eines deaktivierten Anbieters sind aber keine wirksamen Gruppen:** Sie sind kein neues
Grant-Ziel und kein neues Space-Mitglied, erscheinen in der Auswahl als nicht wählbar mit Grund;
bestehende Grants bleiben unangetastet. Sonst wirkte eine Freigabe an „Referat 50 (Anbieter
deaktiviert)" für niemanden — und mit der Wiederaktivierung schlagartig für alle, ohne erneute
Entscheidung.

> **Präzisierung gegenüber dem Papier, erledigt.** Dass „der Verzeichnisabgleich eines deaktivierten
> Anbieters pausiert", war zum Zeitpunkt dieses ADR **nicht** der Fall: `TrustedProvider#issuer()`
> löste den Standardanbieter über `findByDefaultProviderTrue()` auf, **ohne** `enabled` zu prüfen.
> Mit **#1832** (PR #1837, gemergt) prüfte er `findByDefaultProviderTrueAndEnabledTrue()`.
>
> **Mit #1816 (PR #1855) ist die Bindung an `is_default` aufgehoben und `TrustedProvider` entfallen.**
> Der Abgleich ist eine Einstellung der Anbieterzeile: Er liest Issuer und Herkunft aus **ihr**,
> läuft je Anbieter und pausiert, solange **diese** Zeile deaktiviert ist (`409`
> `DIRECTORY_SYNC_PROVIDER_DISABLED`) oder ihr Abgleich ausgeschaltet ist (`409`
> `DIRECTORY_SYNC_NOT_ENABLED`). `is_default` wirkt damit nur noch auf den Anmeldevorschlag und den
> Löschschutz des Standardanbieters. `TrustedProvider` hatte nach dem Umbau keinen produktiven
> Aufrufer mehr — „der eine Anbieter, dem eine Installation über die Anmeldung hinaus vertraut" ist
> kein Begriff des Modells mehr — und ist ersatzlos gelöscht.

**Die Rücknahme von Mitgliedschaften nach einem Vorfall bleibt im ersten Schritt Handarbeit.** Die
Deaktivierung nimmt keine Mitgliedschaft zurück, die ein kompromittierter Anbieter vorher über
manipulierte Tokens gesetzt hat. `group_membership_history` mit `valid_from` gibt die gezielte
Rücknahme „alle Mitgliedschaften dieses Anbieters seit T" her; sie wird **nicht** gebaut. Der ADR
sagt das ausdrücklich, statt es als Nebenwirkung eintreten zu lassen; Entscheidung 10 ist die spätere
Grundlage.

**Löschen eines Anbieters wird verweigert, solange seine Gruppen wirken** (`409`), mit Zählung
(„3 Gruppen tragen 12 Berechtigungen an 7 Bibliotheken und sind Mitglied in 2 Spaces") und einer
**Arbeitsliste je Anbieter**, von der aus jede Gruppe übertragen oder ihre Wirkung entfernt wird.
`ON DELETE RESTRICT` trägt „keine Gruppe ohne Anbieter" strukturell. Gruppen ohne Wirkung werden mit
dem Anbieter gelöscht. Der Lebenszyklus braucht damit keinen eigenen Fall „Anbieter gelöscht".

> **Zeitliche Einschränkung, ausdrücklich ausgesprochen** (Betrieb, Auflage 2.5). Die
> Übertragungsoperation (Entscheidung 10) kommt als eigenes Issue **nach** #1811 und #1815. Zwischen
> #1812 und ihr besteht der `409` **ohne anderen Ausweg als das Entfernen der Wirkungen**. Das ist
> bewusst in Kauf genommen, weil #1812 sonst die ganze Verzeichniskette hinter ein großes Issue
> kettete; Deaktivieren bleibt jederzeit möglich, und das Löschen eines Anbieters ist kein Vorgang,
> den eine Installation in diesem Zeitfenster braucht.

**Lokale Konten** (ADR-0033) haben keinen Gruppenmechanismus; sie werden Mitglied **interner**
Gruppen. Eine Anbietergruppe enthält nur Konten, die den Issuer dieses Anbieters tragen — im
Token-Pfad heute implizit, im Verzeichnisabgleich je Anbieter (#1816) ausdrücklich.

### 3. Synchronisation: Token oder zeitgesteuerter Pull, kein SCIM

**Je Anbieter genau eine Gruppenquelle.** Token ist die Vorgabe, weil er ohne Konfiguration mit jedem
Anbieter funktioniert. Pull statt Push, weil Pull die gebaute Schutzmechanik unverändert nutzt (sie
ist auf Schnappschüsse gebaut; ein Push liefert Deltas, für die es keinen „leeren Lauf" und keine
Schwelle je Lauf gibt), keinen eingehenden Schreibpfad öffnet und mit jedem Anbieter geht, der eine
Leseschnittstelle hat. **Kein SCIM im ersten Schritt.** `access-control.md` nennt SCIM heute als
bevorzugten Weg; dieser Satz wird **umgeschrieben**, nicht ergänzt.

**Der Vorgabeweg ist der ungeschützte, und dieser ADR spricht das aus.** Die vier Schutzmechaniken —
Trockenlauf, Schwelle, Leerergebnis-Schutz (gebaut) und Bestätigungsweg (mit #1816 hinzukommend) —
hat nur der Verzeichnisabgleich, der wegen `NoOpDirectoryClient` heute nirgends läuft. Der
Token-Pfad hat keine davon: Eine Umbenennung des Claim-Werts im Identitätsmanagement ist dort ein
**Gruppenwechsel ohne jede Schutzmechanik** — ab der ersten Anmeldung ist jede Person Mitglied der
neuen Gruppe, die Freigaben an der alten Gruppen-ID wirken für niemanden mehr, und im Protokoll
stehen nur Einzelereignisse. Daraus folgt:

- **#1807 schließt nur „Claim fehlt ≠ Claim leer"** (einschließlich des Entra-Overage-Falls). Mehr
  nicht.
- **Ein sichtbares Signal** für leerlaufende Gruppen mit Wirkung liefert die Betriebsliste
  („Freigaben ohne Empfänger", Entscheidung 6).
- **Das Handbuch nennt den Pull-Modus den empfohlenen** für Häuser mit gepflegtem Verzeichnis, und
  Token als die Vorgabe, die ohne Konfiguration funktioniert.
- **Die Genauigkeit der Rechtehistorie ist mechanismusabhängig:** Im Token-Modus erscheint eine
  Verzeichnisänderung vom 3. März für eine Person, die sich am 20. März anmeldet, mit dem 20. März.
  Das Handbuch nennt diese Genauigkeit je Mechanismus, und die Herleitung (Entscheidung 9) zeigt je
  Gruppe, welcher Mechanismus sie pflegt.
- **Auch der Zeitverzug ist sichtbar, und nicht nur für die Systemverwaltung:** „Meine Gruppen"
  (`GET /api/v1/me/groups`) nennt je Anbieter Mechanismus, Intervall und Zeitpunkt des letzten
  Abgleichs. Das ist keine schutzwürdige Information und erspart den Anruf bei der IT.

**Erster Konnektor: die Keycloak Admin REST API.** Sie ist der einzige Kandidat, bei dem die
Identität des Mitglieds ohne Abbildungsregel mit dem Konto in OPAA zusammenfällt (die
Keycloak-Nutzer-ID **ist** der `sub`); der Fehler, den ADR-0025 für einen Abgleich ohne Issuer
beschreibt, kann dort strukturell nicht entstehen. LDAP folgt als zweiter Konnektor mit
ausdrücklicher Abbildungsregel, Microsoft Graph als dritter. Drei Festlegungen für #1817:

1. **Direkte Mitglieder, nicht transitiv.** `GET /admin/realms/{realm}/groups/{id}/members` liefert
   nur die direkten Mitglieder (geprüft am Quelltext von `GroupResource`). Das deckt sich mit
   „Mitgliedschaft vererbt nicht" (`spaces-and-assets.md`). Eine Keycloak-Abteilung mit nur
   Untergruppen ist in OPAA eine **leere** Gruppe; der Differenzbericht des ersten Laufs nennt die
   Mitgliederzahl je Gruppe, damit der Betrieb das vor dem Anwenden sieht.
2. **Zugangsdaten des Dienstkontos** liegen verschlüsselt über `io.opaa.security.CredentialsEncryptor`
   (AES-256-GCM, `enc:v1:`), nicht in `application.yml`.
3. **Der Verbindungstest** folgt dem Muster von `OidcProviderConnectionTester`, die Admin-API-Adresse
   der Allowlist-Prüfung.

**Lebenszyklus eines ausstehenden Plans** (#1816), vier Festlegungen:

1. **Der Leerergebnis-Schutz bleibt ein harter Abbruch.** `ABORTED_EMPTY_RESULT` heißt „die Quelle
   hat nicht geantwortet, wie sie soll" — das darf niemand wegklicken.
2. **Ein neuer Lauf ersetzt den ausstehenden Plan.** Sonst staut sich alle sechs Stunden einer, und
   irgendwann wird der älteste bestätigt.
3. **Bestätigt wird gegen einen frischen Schnappschuss;** weicht das Ergebnis vom gezeigten ab, wird
   neu vorgelegt.
4. **Ein ausstehender Plan ist ein lauter Zustand:** sein Alter steht in der Statuszeile und auf der
   Verwaltungsübersicht, nicht nur in einer Unterseite.

**Kontosperren aus dem Abgleich (#1818) unterliegen derselben Schwelle und demselben
Bestätigungsweg** wie Mitgliedschaftsentzüge. Eine Sperre aus dem Abgleich ist **rückholbar** und
hinterlässt keinen Bruch in der Historie (der Kontozustand wird historisiert, Entscheidung 8); die
betroffene Person erhält bei der nächsten Anmeldung **Grund und Ansprechstelle**, nicht nur eine
Abweisung.

**Wechsel des Mechanismus.** Token-Gruppen (`IDENTITY_PROVIDER`) und Verzeichnisgruppen (`ORG_UNIT`)
sind verschiedene Objekte. Der erste Lauf weist die Token-Gruppen im Differenzbericht als „werden
nicht mehr gepflegt" aus; sie bleiben mit eingefrorener Mitgliedschaft stehen, sind kein neues
Grant-Ziel mehr, und die Verwaltung bietet je Gruppe die Übertragung an (Entscheidung 10). **Nichts
wird stillschweigend entzogen.**

**Nebenwirkung auf die Vollmacht „Sicht als".** `DiagnosticImpersonationGrantService#grant` verlangt
heute eine `ORG_UNIT`-Gruppe als Geltungsbereich. In einem Haus, das im Token-Modus bleibt, entstehen
nie `ORG_UNIT`-Gruppen — die Vollmacht wäre dort dauerhaft nicht vergebbar. **Der Geltungsbereich
wird auf jede Anbietergruppe erweitert** (`ORG_UNIT` und `IDENTITY_PROVIDER`), sofern sie oberhalb
der Mindestgruppengröße liegt; interne Gruppen bleiben ausgeschlossen. **Die Mindestgruppengröße wird
zum Zeitpunkt jeder Nutzung geprüft, nicht nur bei der Erteilung** — eine Gruppe mit sieben aktiven
Mitgliedern bei Erteilung kann ein halbes Jahr später eines haben, und die Vollmacht wäre dann ein
Personenkontext ohne dessen Schutzmechanik.

### 4. Interne Gruppen: Gruppenverantwortliche

- **Jede interne Gruppe hat einen oder mehrere Verantwortliche**, ausschließlich natürliche Personen
  (keine Gruppe als Verantwortliche — das wäre Schachtelung durch die Hintertür). Verantwortliche
  sind nicht automatisch Mitglied.
- **Sie dürfen:** Mitglieder aufnehmen und entfernen (nur Konten der eigenen Organisation), Name und
  Beschreibung ändern, weitere Verantwortliche ernennen und entlassen, die Gruppe **zur Verwendung
  freigeben** (Entscheidung 9), sie als **geschützt** kennzeichnen (Entscheidung 9) und sie löschen —
  Letzteres unter denselben Bedingungen wie heute `deleteGroup` (`409`, solange die Gruppe Grants
  trägt, Assets besitzt oder Space-Mitglied ist).
- **Der letzte Verantwortliche kann sich nicht entfernen.** Scheidet er aus, greift Entscheidung 6.
- **Verantwortung abgeben ist ein eigener Schritt.** Der häufigste Fall ist nicht die Kontosperre,
  sondern der Referatswechsel bei fortbestehendem Konto. Ein Automatismus ist nicht möglich, ohne die
  Gruppenmitgliedschaften einer Person auszuwerten, und er ist nicht erwünscht. Stattdessen zeigt
  „Meine Gruppen" die eigenen Verantwortlichkeiten mit der Handlung „Verantwortung abgeben an …"
  (Entscheidung 10), und die Ernennung nennt die Erwartung, dass Verantwortung beim Aufgabenwechsel
  abgegeben wird. **Kein Zwang, kein Signal aus Personendaten — ein sichtbarer Ausgang.**
- **Das Anlegen ist die Fähigkeit `CREATE_INTERNAL_GROUP`, ausgeliefert an niemanden.** Heute kann
  nur `SYSTEM_ADMIN` anlegen, und die Auslieferung darf Verhalten nicht ändern. Das Handbuch
  empfiehlt bei der Einführung ausdrücklich, sie an eine Gruppe wie „Referatsleitungen" zu vergeben.
  Die Öffnung ist eine Entscheidung des Hauses.
- **Anbietergruppen bleiben schreibgeschützt.** `GroupService#rejectOrgUnit` weist heute bereits
  `ORG_UNIT` **und** `IDENTITY_PROVIDER` ab; die anderslautenden Stellen im Javadoc von
  `LibraryAccessService` und in `hybrid-retrieval.md` sind falsch und gehen an #1808/#1826.
  Anbietergruppen haben keine Verantwortlichen, sondern Ansprechstellen (Entscheidung 9).
- **Mitglieder sehen ihre Verantwortlichen namentlich** (`GET /api/v1/me/groups` liefert sie mit),
  und **Aufnahme wie Entfernung werden der betroffenen Person angezeigt** — über die
  Benachrichtigungsinfrastruktur aus [ADR-0019](0019-minimale-benachrichtigungsinfrastruktur.md),
  ohne Mail. Sonst endet ein Leserecht „sofort", ohne dass die Person erfährt, dass und durch wen.
- **„Vor Kollegen verborgen" heißt nicht „verborgen":** Wer eine Gruppe anlegt oder ihr ein Recht
  gibt, sieht, an wen (Entscheidung 9).
- **Verantwortlichkeit ist Audit, keine Historienzeile.** Ernennung, Entlassung und Abgabe erzeugen
  Audit-Ereignisse. Verantwortung trägt kein Leserecht und ist für „wer konnte am Tag X was lesen"
  ohne Bedeutung — nach dem Nachtrag zu ADR-0016 ein Betriebsrecht der Gegenwart wie die Vollmacht.
- **Wildwuchs wird sichtbar gemacht, nicht verhindert:** Die Betriebsliste führt interne Gruppen ohne
  Wirkung und ohne aktives Mitglied mit Alter (Entscheidung 6). Löschen bleibt eine Handlung.
- **API ohne `/admin`**, Rechteprüfung je Gruppe; ein Nichtverantwortlicher erhält dieselbe Antwort
  wie für eine unbekannte Gruppe (`404`, wie `loadGroup` heute über die Organisationsgrenze hinweg).

### 5. Globale Berechtigungen sind Fähigkeiten — Ablösung von ADR-0018, Entscheidung 6

**Erste Liste:**

| Fähigkeit | Heute | Auslieferung |
| --- | --- | --- |
| `CREATE_SPACE` | jeder (`authenticated`) | „Alle Konten" |
| `CREATE_LIBRARY` (Upload) | jeder (ADR-0018/6) | „Alle Konten" |
| `CREATE_CONNECTOR_LIBRARY` (Dateisystem, Webverzeichnis, RSS, Confluence, S3) | jeder (#484) | „Alle Konten" |
| `CREATE_INTERNAL_GROUP` | nur `SYSTEM_ADMIN` | **niemand** |

`CREATE_CONNECTOR_LIBRARY` ist eine **eigene** Fähigkeit, weil Konnektorbibliotheken Serverpfade und
Zugangsdaten erreichen und die Freigabe-Obergrenze (#797) tragen; das Handbuch nennt sie als **ersten
Kandidaten, den ein Haus nach der Migration auf eine benannte Gruppe einschränkt** — mit dieser
Begründung, nicht nur als technische Möglichkeit. Der persönliche Space entsteht bei der ersten
Anmeldung **unabhängig** von `CREATE_SPACE` (Bereitstellung, kein Anlegen).

Bewusst **nicht** in der ersten Liste: „Space organisationsweit sichtbar machen", „Bibliothek
organisationsweit freigeben", „Fremdzugang freigeben" — das sind Reichweitenentscheidungen am Objekt,
für die `MANAGER`/`OWNER` und #797 zuständig sind; als globale Fähigkeit gedoppelt ergäben sie zwei
Prüfstellen für dieselbe Frage.

**Datenmodell: eigene Tabelle `capability_grants`** mit `organization_id`, `capability` (Enum,
Prüfbedingung), Subjekt nach dem Muster von `asset_grants` (`subject_user_id` | `subject_group_id`)
**plus der dritten Subjektart `ALL_ACCOUNTS`**; Eindeutigkeit je (Organisation, Fähigkeit, Subjekt);
eigene Historientabelle `capability_grant_history` mit `valid_from`/`valid_to`. Die eine Auswertung
lautet `hasCapability(user, capability) = SYSTEM_ADMIN ∨ Grant an ALL_ACCOUNTS ∨ Grant an Nutzer ∨
Grant an eine seiner Gruppen`; sie lebt im Paket aus Entscheidung 12 und nutzt `PermissionSubject`
und `GroupMembershipResolver`.

**Verhältnis zu Rollen und Vollmachten:**

- **`SYSTEM_ADMIN` besitzt jede Fähigkeit implizit.** Fähigkeiten sind darunter angesiedelt und
  **niemals** Leserechte: Eine Fähigkeit öffnet einen Anlegepfad, nie einen Inhalt.
- **Die Rolle `AUDITOR` verleiht keine Fähigkeit.** Sie ist ein Lesepfad in das Protokoll und sonst
  nichts; ein Konto mit dieser Rolle hat, was „Alle Konten" oder seine Gruppen ihm geben.
- **„Sicht als" und Vorfallsbereich bleiben Vollmachten und werden nie Fähigkeiten.** *Fähigkeit* =
  unbefristet, an Nutzer/Gruppe/Alle, ohne Gegenstand. *Vollmacht* = befristet, an genau eine Person,
  mit Gegenstand und Begründungspflicht. Beide sind an Gruppen oder „Alle Konten" **nicht** vergebbar.
- **Keine neue Systemrolle.** Ein Haus, das einen „Bibliotheksverwalter" will, legt eine Gruppe an
  und gibt ihr die Fähigkeiten.

**Durchsetzung und Sichtbarkeit:**

- Prüfung im Service (`SpaceService#createSpace`, `KnowledgeLibraryService#createLibrary`, künftig
  `GroupService#createGroup`); Antwort `403` mit Grund und Hinweis, an wen man sich wendet. Das `403`
  wird in der Spezifikation als **eigene Entscheidung der Operation** deklariert
  (`TransportStatusCodeSpecificationTest`, AGENTS.md).
- **Der Entzug wirkt ohne Neuanmeldung**, weil je Anfrage aus der Datenbank ausgewertet und der
  kleine Cache nach Commit invalidiert wird wie bei `GroupMembershipResolver`. **Diese Zusage trägt,
  weil [ADR-0021](0021-single-instance-betrieb.md) einen einzigen Prozess voraussetzt; fällt diese
  Annahme, fällt die Zusage mit.**
- `GET /api/v1/me` liefert die eigenen Fähigkeiten, damit die Oberfläche bei fehlender Fähigkeit
  **erklärt statt versteckt**.
- **Die Verwaltungsübersicht zeigt je Fähigkeit den Stand als Klartextzeile** („Alle Konten dürfen
  Konnektorbibliotheken anlegen") — eine Anzeigezeile, kein Assistent —, damit der ausgelieferte
  Zustand bei der Einführung bewusst bestätigt oder geändert wird.
- **Vergabe und Entzug sind Governance-Ereignisse** (`CAPABILITY_GRANTED`/`_REVOKED`), die im Auszug
  für die Personalvertretung erscheinen: Der Entzug einer Fähigkeit von „Alle Konten" ändert die
  Arbeitsbedingungen aller Beschäftigten und ist kein technisches Ereignis unter vielen.
- **Migration:** drei Zeilen je Organisation an `ALL_ACCOUNTS`, keine für `CREATE_INTERNAL_GROUP`;
  Delta-Test. Nach der Migration verhält sich jede Installation wie vorher.

**Damit wird ADR-0018, Entscheidung 6 samt Nachtrag #484 mit der Annahme dieses ADR abgelöst.** Die
dortige Feststellung „die Anlage-Berechtigung bleibt dauerhaft offen" gilt dann nicht mehr; was
bleibt, ist die
Pfad-Allowlist und die Adressprüfung (`TargetAddressValidator`) als von der Berechtigung
**unabhängige** Sicherung — sie greift weiterhin unabhängig davon, wer die Bibliothek anlegt. Der
ausgelieferte Zustand ist identisch mit dem heutigen; wer einschränken will, entzieht „Alle Konten".

**Offen und hier nicht entschieden:** #1828 hat gefunden, dass die Systemverwaltung heute das
Original jedes Dokuments ohne Grant und ohne Protokolleintrag herunterlädt
(`LibraryDocumentService#loadContent` über `effectiveRole`; die Klasse schreibt kein Audit-Ereignis).
„Verwalten ist nicht Lesen" gilt damit für die Suche, nicht für den Download. Ob der Weg geschlossen
oder zu einem protokollierten, begründeten Verwaltungsakt wird, entscheidet **#1828**; dieser ADR
hält nur fest, dass die Variante „wie heute, Spezifikation zurücknehmen" der Grundlinie aus
Entscheidung 1 widerspräche.

### 6. Lebenszyklus: „Nachfolge offen" als abgeleiteter Zustand

**„Nachfolge offen" ist die Abwesenheit eines handlungsfähigen Verantwortlichen** — für ein Asset der
Eigentümer (Person: Konto aktiv; Gruppe: handlungsfähig), für einen Space der Eigentümer oder ein
handlungsfähiges `ADMIN`-Mitglied, für eine interne Gruppe mindestens ein aktiver Verantwortlicher.

**Der Zustand wird abgeleitet, nicht gespeichert.** Ein Flag müsste an jedem Auslöser gesetzt und
zurückgenommen werden und driftete beim ersten vergessenen Pfad; eine Ableitung ist an jedem Ort
dieselbe Abfrage (Muster „abgeleiteter Kontozustand statt `status`-Spalte", ADR-0033/3). Gespeichert
wird nur der **Vorgang**: wann der Zustand erstmals festgestellt wurde, wer ihn beendet hat. Weil
eine Ableitung keinen schreibenden Auslöser hat, legt ein **benannter, regelmäßiger
Feststellungslauf** (Vorgabe stündlich) die Vorgänge an und schließt sie — sonst hieße „Alter" in
Wahrheit „seit dem letzten Hinsehen". Nachfolgevorgänge und Sichtungsvermerke unterliegen der
Aufbewahrungsfrist des **Protokolls**, nicht der Rechtehistorie: Sie sagen nichts über Leserechte aus.

**Was der Zustand bewirkt:** Das Objekt bleibt nutzbar, bestehende Rechte bleiben, aber die
**Reichweite ist eingefroren** — keine neuen Grants, keine höhere Freigabestufe, keine neue
Bereitstellung, für Spaces keine neuen Mitglieder. **Nichts wird gelöscht.** Dieser Satz gehört in
Handbuch und Oberfläche, sonst wird aus einem Verwaltungszustand ein Arbeitshindernis, dessen Druck
bei den Betroffenen landet.

**Die Liste der Systemverwaltung enthält alle offenen Nachfolgen ab dem ersten Tag**, mit „Objekt",
„derzeitiger Adressat" und „Alter". Keine Frist, keine Mail, keine Eskalationslogik —
**Vollständigkeit**. Die Stufung ist eine Zuständigkeits*angabe*, keine Zugangsbeschränkung zur
Liste: Space → die übrigen handlungsfähigen `ADMIN`-Mitglieder; Asset einer internen Gruppe → deren
Verantwortliche; alles andere → Systemverwaltung. Ohne die vollständige Liste erreichte ein Fall der
Stufe 2 die Stufe 3 nie, und die Prüferfrage „nennen Sie alle Bestände ohne handlungsfähigen
Verantwortlichen" wäre nicht beantwortbar.

Dazu:

- **Alterungsschwelle mit Sichtungsvermerk, ohne Zwang.** Einträge älter als eine konfigurierbare
  Schwelle (**Vorgabe 12 Monate**, orientiert an der Höchstfrist der Vollmacht) werden hervorgehoben;
  ein Sichtungsvermerk („geprüft am …, weiterhin offen, Grund") hebt die Hervorhebung für eine
  weitere Periode auf. Das verhindert, dass eine Zeile zehn Jahre unberührt bleibt, ersetzt aber
  keine fachliche Zuständigkeit und löst nichts aus.
- **Sichtbar am Objekt, nicht nur in der Liste.** Übersicht und Detailansicht tragen für jeden
  Leseberechtigten **Zustand und Adressat** („Nachfolge offen — zuständig: Systemverwaltung") —
  **ohne Datum, ohne den bisherigen Eigentümer, ohne Grund**: Der Zustand tritt bei einem
  personengehörenden Asset mit der Kontosperre ein, und ein datierter Vermerk neben dem
  Eigentümernamen wäre eine Statusmeldung über eine Kollegin. Das Datum steht in der Betriebsliste.
  **Nicht** gekennzeichnet werden Suchtreffer und Quellenverweise in Antworten: Der Zustand betrifft
  die Zuständigkeit, nicht die Richtigkeit des Inhalts, und die Kennzeichnung dort würde jede Antwort
  zu einer Zustandsauswertung machen.
- **Die Liste ist objektbezogen, in beide Richtungen.** Einstieg über das Objekt; der frühere
  Eigentümer wird je Zeile genannt, aber es gibt **keine** Abfrage „was gehörte Person X", keine
  Sortierung und keine Zählung nach früherem Eigentümer — **und ebenso wenig nach der handelnden
  Person**: Wer einen Vorgang beendet oder einen Sichtungsvermerk gesetzt hat, steht am Vorgang und
  ist dort lesbar, ist aber keine Auswertungsachse und kein API-Parameter. Sonst ergäben
  Feststellungslauf, Sichtungsvermerk und vollständige Liste eine Bearbeitungsspur über die
  Systemverwaltung.
- **Hinweis statt Auswertung:** Die Liste darf als Vorschlag die Verzeichnisgruppen des
  Ausgeschiedenen nennen („war Mitglied von Referat 50") — Bestandsinformation, keine
  Aktivitätsauswertung.
- **Die Übernahme ist die Übertragungsoperation** (Entscheidung 10). Für den Regelfall
  Verzeichnisgruppe heißt das: Referat 50 wurde aufgelöst, seine Bibliotheken gehen mit **einer**
  Übertragung an Referat 52 — nicht Objekt für Objekt.

**Vier Schutzregeln, die den Zustand vermeiden — und zwei Grenzen, die sie ausdrücklich haben:**

1. **Ein Space verliert nie sein letztes handlungsfähiges `ADMIN`-Mitglied durch eine
   Verwaltungshandlung.** Entfernen, Herabstufen oder Austritt des letzten `ADMIN` wird mit `409`
   abgelehnt — heute schützt `SpaceService#removeMember` nur den Eigentümer, und das als
   `ValidationException`, also `400`. **Eine Gruppe zählt als `ADMIN`, solange sie handlungsfähig
   ist**; das beantwortet die offene Frage aus #1815. Den Eigentümerwechsel darf künftig **jedes
   handlungsfähige `ADMIN`-Mitglied, das eine natürliche Person ist**, an sich oder an ein anderes
   solches Mitglied übertragen (heute nur Eigentümer oder `SYSTEM_ADMIN`) — das ist die eine
   Verhaltensänderung. Eine Gruppe kommt dafür nicht in Betracht: Der Space-Eigentümer bleibt eine
   natürliche Person.
2. **Eine Kontosperre wird nie wegen offener Eigentums- oder Zuständigkeitsfragen abgelehnt.** Sie
   ist die eine Handlung, die den Zustand erzeugen darf. **Die einzige Ausnahme bleibt der Schutz des
   letzten anmeldefähigen Systemverwalters** (#1349, ADR-0033; heute
   `LocalAdminAvailabilityGuard#requireAnotherLoginCapableAdmin`, `409`).
3. **Der letzte Verantwortliche einer internen Gruppe kann sich nicht entfernen** (Entscheidung 4).
4. **Nur wirksame Gruppen sind neues Grant-Ziel und neues Space-Mitglied.** Das deckt aufgelöste
   Gruppen (heute `requireGrantableGroup`) und Gruppen deaktivierter Anbieter mit **einer** Regel.
   Eine **leere** wirksame Gruppe bleibt erteilbar — mit Warnung und Eintrag in „Freigaben ohne
   Empfänger" —, sonst scheiterte „Gruppe anlegen, freigeben, Mitglieder aufnehmen" am ersten
   Schritt, und das Ziel einer Anbieterablösung im Token-Modus (die Gruppe entsteht erst mit der
   ersten Anmeldung, `TokenGroupSynchronizer#findOrCreate`) wäre nie erreichbar.

**Die Betriebsliste hat drei Reiter mit derselben Mechanik** (Feststellungslauf, Alter,
objektbezogener Einstieg, Sichtungsvermerk): **Offene Nachfolgen**; **Freigaben ohne Empfänger**
(wirksame, nicht handlungsfähige Gruppen, die Grants tragen oder Space-Mitglied sind, mit Zahl der
betroffenen Objekte und Alter); **Gruppen ohne Wirkung** (interne Gruppen ohne Grant, ohne
Space-Mitgliedschaft, ohne Eigentum, ohne aktives Mitglied).

### 7. Diagnose im Gruppenkontext

Das Rechteprofil **bleibt eine Gruppe**; `target_ref` trägt die Gruppen-ID, „damit ‚kein
Personenbezug im Protokoll' eine Struktureigenschaft" ist (`hybrid-retrieval.md`). Die Profilliste
ist eine Gruppenliste und bekommt dieselbe Herkunftsanzeige und dieselbe Sichtbarkeitsregel wie die
Subjekt-Auswahl (Entscheidung 9).

**Der Space-Kontext kommt in einem eigenen Umsetzungs-Issue nach #1815**, dessen Abnahmekriterien
fünf Schutzpunkte **einzeln** führen. **Bis dahin nimmt `SearchDiagnosisRequest` keine Space-ID
entgegen** (Integrationstest auf `403`). Schutzregel und Funktion werden im selben Arbeitsschritt
geliefert; „im ADR als Randbedingung, damit sie nicht verloren geht" ist die Formulierung, mit der
Zusagen verloren gehen.

1. **Suchbereich** = im Space assoziierte Bibliotheken ∩ für die Gruppe lesbare Bibliotheken,
   getragen von einer optionalen Space-ID im Profilkontext.
2. **Die Mindestgruppengröße gilt für die Schnittmenge** aus aktiven Mitgliedern der Gruppe und
   Zugang zum Space — **Zugang auf jedem Weg** (direkt, über diese oder eine andere Gruppe; gerechnet
   gegen „Mitglieder, die über G im Space sind", wäre sie bei einer Gruppe, die selbst nicht
   Space-Mitglied ist, strukturell null) —, **geprüft zum Zeitpunkt des Laufs**. Liegt sie darunter,
   antwortet der Endpunkt mit `403` und dem Hinweis, dass für diese Sicht der Personenkontext mit
   Vollmacht zu wählen ist. **Ist die Schnittmengen-Prüfung zu teuer, entfällt der Space-Kontext —
   nicht die Prüfung.**
3. **Gezählt werden aktive Konten**, nicht Mitgliedschaftszeilen: Eine Gruppe mit 20 Mitgliedern,
   von denen 18 gesperrt sind, ist ein Zwei-Personen-Kontext. Dieselbe Zählweise wie beim
   handlungsfähigen Verantwortlichen.
4. **Profil-Läufe mit Space-Kontext werden protokolliert** (ausführende Person, Profil, Space,
   Zeitpunkt — eine Zeile je Lauf, keine je Abfrage). Profil-Läufe ohne Space-Kontext bleiben
   unprotokolliert; die in `hybrid-retrieval.md` offen gelassene allgemeine Protokollpflicht bleibt
   offen.
5. **Die Suchdiagnose bleibt `SYSTEM_ADMIN` vorbehalten; keine Fähigkeit öffnet sie.** Die
   Begründung, mit der `hybrid-retrieval.md` die Diagnosesperre (Leitplanke (e)) bei Profil-Läufen
   nicht anwendet, trägt nur, solange die Seite Systemverwaltern vorbehalten ist; **wird sie je
   geöffnet, schaltet dieselbe Änderung die Sperre auch für Profil-Läufe scharf.** Leitplanke (b)
   bleibt unverändert: Die Diagnose ist kein Zugriffshistorien-Nachweis — dafür gibt es die
   Stichtagsauskunft, und beide bleiben getrennt.

**Die Mindestgruppengröße ist ein anderes Maß als die der Nutzungstransparenz** in
`spaces-and-assets.md` („Zahl der tatsächlich nutzenden Personen"): Für ein Rechteprofil zählt der
Rechtekontext, nicht die Nutzung. Beide Lesarten stehen hier nebeneinander. Sie hat eine **erzwungene
Untergrenze** — siehe „Zahlen, die dieser ADR setzt".

### 8. Rechtehistorie, Stichtagsauskunft und Aufbewahrung

**Audit verfällt, Historie bleibt.** Ein Audit-Ereignis sagt, *dass* jemand etwas getan hat, und wird
nach der Protokollfrist gelöscht (`chk_audit_retention_settings_months`: 12–120 Monate). Eine
Historienzeile sagt, *in welchem Zeitraum* ein Recht galt, und trägt die Stichtagsauskunft. Jede
Änderungsart des Epics wird genau einer Seite zugeordnet:

| Änderungsart | Tabelle der Stichtagsauskunft | Personenspalte | Audit |
| --- | --- | --- | --- |
| Asset-Grant | `asset_grant_history` (heute) | `RESTRICT` | ja |
| Gruppenmitgliedschaft | `group_membership_history` (heute) | `RESTRICT` | ja |
| Reichweitenfelder am Asset | `library_visibility_history` (heute) | — (Akteur `SET NULL`) | ja |
| **Space-Mitgliedschaft** (Person oder Gruppe, mit Rolle) | **neu**, #1815 | `RESTRICT` | ja |
| **Eigentum** an Asset und Space | **neu** (`asset_ownership_history`), #1819 | `RESTRICT` | ja |
| **Fähigkeit** | **neu** (`capability_grant_history`), #1813 | `RESTRICT` | ja, Governance-Ereignis |
| **Systemrolle** | **neu** — weil `effectiveRole` `SYSTEM_ADMIN` als `OWNER` führt | `RESTRICT` | ja |
| **Kontozustand** (aktiv/gesperrt) | **neu**, #1818 — Beleg der Kontenmenge zum Stichtag, zusammen mit `users` (siehe Nachtrag unten) | `RESTRICT` | ja |
| Verantwortliche interner Gruppen | **keine** — Betriebsrecht der Gegenwart | — | ja |
| Vollmachten | keine (ADR-0016, Nachtrag) | — | ja |
| Nachfolgevorgänge, Sichtungsvermerke | keine | — | ja |

Alle Historientabellen tragen `valid_from`/`valid_to` und folgen ADR-0016: Subjektspalten `RESTRICT`,
Objektspalten ohne Fremdschlüssel. **Die Personenspalten der fünf neuen Tabellen werden `RESTRICT`
angelegt, nicht von vornherein pseudonymisiert** — der vorhandene Pseudonym-Mechanismus
(`audit_actor_pseudonyms`, `CASCADE` auf `users`) liegt im eigenen Privilegienmodell des Protokolls
(ADR-0015) und ist ohne eigene Entscheidung nicht übertragbar; zwei Modelle nebeneinander machten
#391/#395 nicht kleiner, sondern zweiteilig.

> **Damit wird eine Zusage der Spezifikation aufgehoben, und dieser ADR benennt sie.**
> `security-and-compliance.md` sagt heute: „Aufbewahrung und Löschschicksal der Historie folgen
> derselben Logik wie das Protokoll: Sie unterliegt einer Höchstdauer, **und der Personenbezug ist ab
> dem Schreibzeitpunkt pseudonymisiert**." Der erste Halbsatz wird mit diesem ADR eingelöst, **der
> zweite nicht**: Die Personenspalten bleiben `RESTRICT`, und die Pseudonymisierung wird an den
> Personen-Einstieg und die Kontolöschung gekoppelt (#391/#395). Der Satz wird entsprechend
> umgeschrieben, nicht nur ergänzt — wie der SCIM-Satz in `access-control.md` (Entscheidung 3);
> zuständig sind #1808 und #1824. ADR-0016 führt genau diese beiden Punkte als offene Folgefragen:
> Die Höchstdauer wird hier bejaht, die Pseudonymisierung ab Schreibzeitpunkt **verneint**.

**Die Kontozustandshistorie bekommt keinen zweiten Lesepfad** („Verlauf" am Konto in der
Benutzerverwaltung) neben der Stichtagsauskunft.

> **Nachtrag mit der Umsetzung (#1818, PR #1866): Die Kontozustandstabelle ist der Beleg der
> Kontenmenge nur *zusammen mit* `users`.** Die Kette eines Kontos beginnt dort mit seiner **ersten
> Zustandsänderung** und reicht von da lückenlos bis `users.created_at` zurück; ein nie gesperrtes
> Konto hat **keine Zeile**. Grund: Ein offenes Intervall je Konto ab der Anlage machte über die
> `RESTRICT`-Personenspalte jedes Konto dauerhaft unlöschbar und höbe damit die Kontolöschung aus
> #1537 aus — dieselbe Überlegung, aus der die Bestandseinträge in den Changesets 044 und 052 den
> persönlichen Space aussparen. Für die Stichtagsauskunft heißt das: Die Kontenmenge zum Stichtag
> ist `users` (angelegt vor dem Stichtag), korrigiert um die Intervalle dieser Tabelle. **#1813 muss
> diese Regel kennen** — eine leere Tabelle heißt „niemand war je gesperrt", nicht „niemand war
> aktiv".

**Der Lesepfad, den es heute nicht gibt.** `readableLibraryIdsAsOf` ist gebaut, aber ohne Endpunkt.
Die Stichtagsauskunft ist eine Funktion der Rolle `AUDITOR` mit zwei Einstiegen:

- **Objekt-Einstieg** („wer durfte Bibliothek Z am 3. März lesen") — ohne weitere Vollmacht, aber mit
  verpflichtendem, begrenztem Zeitfenster und Seitenobergrenze; eine zu weite Anfrage wird
  **abgelehnt, nicht zurechtgestutzt** (Vorbild `AuditFrom`: höchstens 92 Tage). Und **genau ein
  benanntes Objekt je Abfrage** — keine Sammelabfrage über einen Space, eine Organisationseinheit
  oder einen Bibliotheksfilter: Sonst setzt ein `AUDITOR` aus dreißig Objektabfragen desselben
  Referats das Rechteprofil jeder Person dieses Referats zusammen, ohne Vollmacht.
- **Personen-Einstieg** („worauf hatte Person X am 3. März Zugriff") — **nur mit einer eigenen,
  befristeten, begründeten Vollmacht nach dem Muster des Vorfallsbereichs**
  (`audit_incident_scope_grants`: Person, Zeitraum, Zweck, Vier-Augen-Freigabe zweier `AUDITOR`).
- **Jeder Abruf ist selbst ein Protokollereignis, einschließlich abgewiesener Versuche** — analog
  `AUDIT_LOG_ACCESSED`.

**Aufbewahrungshöchstdauer als Vorbedingung.** `security-and-compliance.md` sagt eine Höchstdauer mit
automatischer Löschung zu und stellt selbst fest, dass sie nicht umgesetzt ist. Sie wird
Governance-Einstellung mit **erzwungener Ober- und Untergrenze**; Änderungen sind
Governance-Ereignisse. **Ohne sie wird keine weitere personenbezogene Historienquelle angeschlossen**
— #1813, #1815, #1818 und #1819 hängen an dieser Entscheidung wie an diesem ADR. Der
Namensschnappschuss aus Entscheidung 2 ist nur unter dieser Bedingung zulässig. ADR-0016 bleibt
unberührt: Die Historie überlebt die Löschung des **Objekts**, nicht die Höchstdauer — zwei Achsen.

> **Schnitt, falls die Vorbedingung abgelehnt wird oder sich das Issue verzögert** — damit die Wahl
> bewusst ist und nicht in einem Sub-Issue fällt: #1815 und #1819 schreiben ihre Historientabellen
> trotzdem (dort ist die Nachweislücke am größten, und ein späterer Rückbau auf Audit ist nicht
> nachholbar); #1813 und #1818 bleiben bei Audit; und **in keinem Fall** gibt es vor der Höchstdauer
> einen Personen-Einstieg, einen Namensschnappschuss oder eine Kontozustandshistorie.

**Pseudonymisierung (#391/#395) ist Voraussetzung des Personen-Einstiegs und der Kontolöschung, nicht
des Schreibens von Historienzeilen.** Die Begründung ist **nicht** „Historienzeilen ohne Lesepfad
erzeugen keinen Auswertungspfad" — diese Prämisse trägt ab dem Moment nicht mehr, in dem diese
Entscheidung den Lesepfad baut. Die zutreffende Begründung: Der Auswertungspfad **entsteht mit dieser
Entscheidung** und wird durch Höchstdauer, Vollmacht, Abrufprotokoll, Zeitfenster/Seitenobergrenze
und die Ein-Objekt-Regel begrenzt; die Pseudonymisierung an den Personen-Einstieg zu koppeln ist der
stärkere Hebel, weil eine fehlende Auswertungsfunktion auffällt, während eine Schreibblockade unter
Termindruck per Ausnahme fällt.

**Verfahren:** Vor dem Rollout legt die Dienststelle die Auskunft über die Datenerhebung vollständig
vor — einschließlich der neuen Tabellen; die Personalvertretung erhält einen Testzugang, um die
Zusagen nachzuvollziehen; Änderungen an Mindestgruppengröße, Abgleichintervall, Plausibilitätsschwelle
und Aufbewahrungsfristen sind Governance-Ereignisse und der Personalvertretung zugänglich.

### 9. Sichtbarkeit von Gruppen und die Herleitung „warum sehe ich das"

**Der Ist-Stand, gegen den diese Entscheidung eine Bestandsänderung ist:** Heute kann **jeder
`MANAGER` an jede Gruppe der Organisation freigeben** — `requireGrantableGroup` prüft nur Organisation
und `isDissolved()`, und der Freigabedialog lässt eine Gruppe per ID benennen, auch wenn
`GET /api/v1/admin/groups` dem Aufrufer verschlossen ist. Eine Sichtbarkeitsregel ist deshalb nur dann
eine Regel, wenn sie **im Service** durchgesetzt wird; in der Auswahlliste allein wäre sie Kosmetik.

**Interne Gruppen sind Opt-in.** Verantwortliche geben eine Gruppe **zur Verwendung frei**; erst dann
ist sie für andere Rechtevergebende wählbar. Das ist das Gegenstück zu `listed` bei Assets:
Auffindbarkeit ist eine bewusste Handlung. Drei Festlegungen:

1. **Die Migration setzt „freigegeben" für jede interne Gruppe, die am Migrationstag einen Grant
   trägt, Space-Mitglied ist oder ein Asset besitzt** — ein Bibliotheksverwalter verliert keine
   Möglichkeit, die er benutzt hat. „Vorgabe nicht freigegeben" gilt damit **nur prospektiv**.
2. **Die Durchsetzung liegt in `requireGrantableGroup` bzw. seinem Nachfolger im Paket aus
   Entscheidung 12 und gilt für jeden Weg, auch die Eingabe per ID.** Eine nicht freigegebene interne
   Gruppe ist für einen Aufrufer, der weder Mitglied noch Verantwortlicher noch `SYSTEM_ADMIN` ist,
   „nicht gefunden" (`404`, wie über die Organisationsgrenze).
3. **Die Bestandsänderung wird ausgesprochen:** Ein `MANAGER` kann eine neue interne Gruppe erst dann
   als Empfänger wählen, wenn deren Verantwortliche sie freigegeben haben.

**Die Regel — „wer ein Recht gibt, sieht, an wen":**

| Wer | Name und Herkunft | Mitgliederzahl | Mitgliederliste |
| --- | --- | --- | --- |
| **Verantwortliche** einer internen Gruppe | ja | voll | ja |
| **`SYSTEM_ADMIN`** | ja | voll | ja — **der Abruf ist ein Audit-Ereignis** |
| **Wer der Gruppe an einem Objekt ein Recht einräumt oder verwaltet** (`MANAGER`/`OWNER` des Assets, `ADMIN`/Eigentümer des Space) — **solange die Gruppe dort ein Recht hält** | ja | voll | ja, **außer bei geschützten Gruppen** |
| **Rechtevergebende** in der Subjekt-Auswahl | Anbietergruppen: ja. Interne Gruppen: nur, wenn **zur Verwendung freigegeben** | Zahl **aktiver Konten**; unterhalb der Mindestgruppengröße **„kleine Gruppe" statt Zahl** | nein |
| **Mitglied** der Gruppe | seine eigenen Gruppen mit Verantwortlichen | Größe | nein |
| **Sonstige** | nichts | — | — |

Die Ausweitung „Grant-Geber sieht die Mitgliederliste" hat damit **genau vier Begrenzungen**, und
alle vier stehen hier: (a) nur **solange die Gruppe dort ein Recht hält**; (b) nur für Gruppen, die
**zur Verwendung freigegeben** sind; (c) **Vorgabe ist nicht freigegeben** — mit dem ausdrücklichen
Hinweis, dass die Bestandsmigration das nur prospektiv einlöst; (d) **die Ausnahme geschützter
Gruppen**.

**Geschützte Gruppen.** Für Gruppen der Personalvertretung, der Schwerbehindertenvertretung, der
Gleichstellung und für Personalvorgänge — die Stellen, die `hybrid-retrieval.md`, Leitplanke (e), für
Bibliotheken benennt — gilt dieselbe Sonderstellung:

- **Das Kennzeichen setzt und löst die zuständige Stelle selbst, nicht die Administration**
  (Audit-Ereignis wie `LIBRARY_DIAGNOSTICS_LOCK_CHANGED`). Bei internen Gruppen sind das die
  Verantwortlichen. **Bei Anbietergruppen benennt die Systemverwaltung eine oder mehrere
  Ansprechstellen** (Personen, die Mitglied der Gruppe sind) **ohne Pflegerechte an der Gruppe** — ein
  Verwaltungsakt mit Audit-Ereignis, der die Gruppe nicht verändert. Nur Ansprechstellen setzen und
  lösen dort das Kennzeichen. Damit bleibt der Grundsatz für beide Herkünfte gewahrt.
- Eine geschützte Gruppe ist **nicht über Suche auffindbar**, sondern nur über ihre vollständige
  Bezeichnung wählbar; in fremden Listen erscheint sie als **„geschützte Gruppe" ohne Namen** (die
  namenlose Zeile ist nötig, weil ein Space-`ADMIN` sonst eine Mitgliedschaft nicht beenden könnte,
  die er nicht sieht); der Grant-Geber sieht statt der Mitgliederliste die **Ansprechstelle**.
- **Die Herleitung zeigt Dritten — auch Space-`ADMIN` und Eigentümer — bei einer geschützten Gruppe
  keine Gruppenableitung**, sondern nur die effektive Rolle. Sonst wäre die Namenlosigkeit wertlos:
  Steht bei Frau S. „Rolle über eine geschützte Gruppe" und wirkt im Space genau eine solche Gruppe,
  ist sie benannt. Die **eigene** Herleitung der betroffenen Person bleibt vollständig.

**Die Herleitung für die eigene Person.** Flach zu *sein* und das flach zu *zeigen* sind zwei
Zusagen. Für jede Bibliothek und jeden Space, den eine Person sieht, zeigt die Oberfläche ihr **den
eigenen Weg** zur effektiven Rolle: direkter Grant; Grant über Gruppe — mit Name, Herkunft,
Mechanismus (Token/Abgleich) und Zeitpunkt des Grants; organisationsweite Freigabe; Eigentum;
Fähigkeit. **Ohne Vollmacht, ohne Protokoll**; die Mitglieder der Gruppe werden dabei nicht
offengelegt. **Gegenüber anderen** nennt die Herleitung den Gruppennamen nur dort, wo die Person die
Mitgliedschaft verwaltet — in der Space-Mitgliederliste für `ADMIN`/Eigentümer, die nach der Regel
oben ohnehin die Mitgliederliste dieser Gruppe sehen und die diese Liste heute schon allein sehen
(`SpaceAccessPolicy`; `MEMBER` und `CURATOR` erhalten nur `roleCounts`).

**Signal bei Gruppenzuwachs.** Die Gruppe bleibt Subjekt am Grant, der Leserkreis wächst also mit,
ohne dass der Grant-Geber es entscheidet; eine Zustimmungspflicht ist durch die Vorentscheidung
ausgeschlossen. Was bleibt, ist ein **passives Signal**: Jeder Grant und jede Space-Mitgliedschaft an
eine Gruppe speichert die **Zahl aktiver Mitglieder zum Zeitpunkt der Erteilung**; die Freigabeansicht
zeigt beide Zahlen („Referat 50: 23 bei Erteilung, heute 41"). Keine Mail, kein Vorgang — eine Zeile,
die jemand liest, der für die Freigabe geradesteht. **Für beide Zahlen gilt die
„kleine Gruppe"-Unterdrückung, einschließlich der Werte, die sich aus dem Vergleich errechnen ließen;
für geschützte Gruppen entfällt das Signal ganz** (dort ist die Größe die eigentliche Auskunft), und
der Grant-Geber hat die Ansprechstelle. Die gespeicherten Zahlen sind Teil der Grant-Historie und
unterliegen deren Höchstdauer.

> **Nachtrag zur Ablage der Zahl (Koordinator, 22.09.2026, mit #1820/#1815 eingelöst).** Die Zahl
> liegt **am wirksamen Recht**, nicht in der Rechtehistorie: `asset_grants.member_count_at_grant`
> (#1820) und `space_memberships.member_count_at_grant` (#1815). Sie verschwindet damit mit dem
> Grant beziehungsweise der Mitgliedschaft und überdauert sie nie — das ist datensparsamer als der
> Satz oben, der sie in die Historie legt und dort bis zur Höchstdauer (12–120 Monate) führt. Der
> Preis ist benannt: Ein Grant, der länger als die Höchstdauer besteht, trägt die Zahl vom Tag
> seiner Erteilung unbefristet weiter. Vertretbar, weil es eine unterhalb der Mindestgruppengröße
> unterdrückte Aggregatzahl ist, die niemanden benennt. Die Übertragung (Entscheidung 10) nimmt
> sie mit; der Eigentümer-Grant einer gruppengehörenden Bibliothek trägt keine — Eigentum ist keine
> Freigabe.

### 10. Die Übertragungsoperation

**Eine allgemeine, protokollierte Operation auf der Gruppenachse** überträgt Grants,
Space-Mitgliedschaften, Eigentum und (bei internen Gruppen) Verantwortliche — mit Vorschau,
Bestätigung, Audit-Ereignis und einem sauberen Schnitt in der Rechtehistorie. Sie löst vier Anlässe
mit derselben Mechanik: **Reorganisation** (Referat 50 → 52), **Anbieterablösung**,
**Mechanismuswechsel** und **Nachfolgeübernahme**.

- **Quelle und Ziel:** Gruppe → Gruppe (Regelfall, voller Umfang), Gruppe → Person und Person →
  Person (Nachfolge, Abgabe der Verantwortung). **Bei einer Person als Quelle ist der Umfang auf
  Eigentum an Assets und Spaces sowie Verantwortung für interne Gruppen beschränkt; Grants und
  Space-Mitgliedschaften einer Person sind weder übertragbar noch in der Vorschau aufzählbar.** Sonst
  wäre die Vorschau eine Abfrage „alle Wirkungen der Person X" für `SYSTEM_ADMIN`, ohne Vollmacht und
  ohne Begründung — die personenbezogene Rechteübersicht, die Entscheidung 8 für die Vergangenheit
  unter eine Vier-Augen-Vollmacht stellt, für die Gegenwart als Formularaufruf daneben. Fachlich wird
  sie nicht gebraucht: Eine Nachfolge betrifft Eigentum und Verantwortung; die Grants einer
  ausgeschiedenen Person enden mit dem Konto.
- **Das Ziel muss wirksam sein** (es darf leer sein: im Token-Modus entsteht die Gruppe des neuen
  Anbieters erst mit der ersten Anmeldung); Organisationsgrenze wie überall.
- **Umfang wählbar** (bei Gruppen): alle Wirkungen oder eine Teilmenge — nur Grants, nur Eigentum,
  nur die Wirkungen an einem Anbieter (die Arbeitsliste je Anbieter aus Entscheidung 2).
- **Wer:** `SYSTEM_ADMIN` organisationsweit; für den Umfang „Eigentum und Verantwortung, die ich
  selbst trage" auch die Person selbst. Ein `MANAGER` ändert Grants an seinem Asset weiterhin
  einzeln; die Massenoperation bleibt ein Verwaltungsakt.
- **Historienschnitt:** je betroffener Zeile ein `valid_to` für die Quelle und ein `valid_from` für
  das Ziel mit demselben Zeitstempel und einer gemeinsamen Vorgangskennung; die Stichtagsauskunft
  zeigt an jedem Tag genau ein Subjekt.
- **Die Vorschau ist Pflicht und selbst ein Protokollereignis — auch bei Abbruch;** Bestätigung ist
  ausdrücklich; Audit trägt Quelle, Ziel, Umfang und Zahl der Zeilen. Die betroffenen Objekte tragen
  in ihrer Freigabeansicht den **Vorgang** („übertragen am …, Vorgang …"), bei einer Gruppe als Quelle
  auch deren Namen, bei einer **Person** als Quelle **nicht** deren Namen — ein an vielen Objekten
  wiederholter Hinweis auf das Ausscheiden einer benannten Person außerhalb jeder Protokollfrist wäre
  sonst die Folge.
- **Nicht enthalten:** die Rücknahme von Mitgliedschaften nach einem Vorfall (Entscheidung 2, bewusst
  Handarbeit) und eine automatische Auslösung durch den Verzeichnisabgleich — eine Reorganisation im
  Verzeichnis erzeugt eine aufgelöste Gruppe und einen Eintrag in der Betriebsliste; die Übertragung
  bleibt eine Entscheidung.
- **Abhängigkeiten, schmal:** nach #1811 und #1815. **#1819 hängt real daran** (die „Übernahme durch
  eine Person oder Gruppe" wird aus #1819 herausgeschnitten). **#1812 hängt nicht daran** (siehe die
  zeitliche Einschränkung in Entscheidung 2), **#1816 ebenfalls nicht** (der Mechanismuswechsel ist
  mit „eingefroren plus Differenzbericht" bereits frei von stillem Entzug; die Übertragung ist dort
  Komfort, kein Schutz).

### 11. Migration und ihre Fehlerfälle

Heute bleiben Gruppen **gelöschter** Anbieter zurück, mit `external_id = oidc:<uuid>:<name>` auf eine
Anbieterzeile, die es nicht mehr gibt. Für diese Zeilen ließe sich `provider_id` mit `RESTRICT` nicht
setzen; Liquibase bräche ab, die Anwendung startete nicht, und **die Baseline hat bewusst keine
Rollback-Blöcke** (ADR-0034). Deshalb:

| Fall | Behandlung im Changeset (#1812) | Delta-Test |
| --- | --- | --- |
| Token-Gruppe, deren Anbieter-UUID in `oidc_providers` fehlt | Umwandlung in eine **interne Gruppe** (`kind = AD_HOC`, `provider_id = NULL`, `external_id = NULL`, Beschreibung mit Herkunftsvermerk), **ohne Verantwortliche**; je Zeile ein Audit-Ereignis unter einem Systemprozess-Akteur `migration`. Dass sie damit in der Liste offener Nachfolgen erscheint, ist eine **Anforderung an #1819** — die Liste entsteht erst dort, #1812 legt nur den Zustand an | Fixture mit einer solchen Waisen-Gruppe samt Grant; nach der Migration ist sie intern, ihr Grant unverändert |
| `ORG_UNIT`-Gruppe in einer Installation **ohne Standardanbieter** (die Anbietermenge darf leer sein; die `LOCAL`-Zeile ist nie Standard) | dieselbe Umwandlung; `dissolved` bleibt als Beschreibungsvermerk erhalten | Fixture ohne `is_default`-Zeile |
| `ORG_UNIT`-Gruppen im Betriebsmodus `dev` (dort gibt es keine Anbieterzeile) | **#1816 hat entschieden: keine synthetische Anbieterzeile, also kein Abgleich im `dev`-Modus.** Jede Zeile von `oidc_providers` ist ein Vertrauensanker der Anmeldung, aus dem die Registry einen Token-Prüfer baut; eine Zeile, die niemanden anmeldet und nur zwei Einstellungsspalten hält, wäre der falsche Preis. Weil der Lauf Issuer und Herkunft aus der Zeile liest statt aus dem Betriebsmodus, ist er dort trotzdem fahrbar, sobald jemand eine gewöhnliche Anbieterzeile anlegt. Damit zieht **Changeset 054** (#1816) die strenge Form der Prüfregel nach (`kind = AD_HOC ⇔ provider_id IS NULL`) und wandelt die verbliebenen anbieterlosen `ORG_UNIT`-Gruppen — nur zwischen 041 und 054 im `dev`-Modus entstehbar — nach dem Muster von 041 in interne Gruppen um; die Migration bricht im `dev`-Modus nicht ab | Suite unter `local,dev`; Delta-Test 054 (Umwandlung, Audit-Zeile, Mitgliedschaften und Grants unangetastet) |
| Reihenfolge der Eindeutigkeit (`uk_groups_organization_external_id` ist heute `(organization_id, external_id)` und der Nebenläufigkeitsschutz von `TokenGroupSynchronizer`) | erst den neuen Teilindex `(organization_id, provider_id, kind, external_id)` anlegen, **dann** den alten Schlüssel fallen lassen, **dann** das Präfix schneiden. Unter dem alten `(organization_id, external_id)` kollidierten zwei gleichnamige Gruppen zweier Anbieter im Moment des Schnitts; das Changeset ist eine Transaktion, ein Abbruch lässt also kein Fenster ohne Schlüssel zurück. `TokenGroupSynchronizer.MAX_NAME_LENGTH` ändert sich mit dem Präfix (213 → 255) | Fixture mit gleichnamigen Gruppen zweier Anbieter |
| Vorabprüfung durch den Betrieb | `docs/handbuch/deployment.md` erhält vor dem Update eine Prüfabfrage (Zahl der Waisen-Gruppen, Zahl der `ORG_UNIT`-Gruppen, Vorhandensein eines Standardanbieters) | — |

**Das Muster „ein Migrationsfehler, den man vorher kennt, ist ein Testfall" gilt für jedes Changeset
des Epics.** #1813 und #1815 haben keinen vergleichbaren Fall, weil sie nur Zeilen hinzufügen.

### 12. Paketschnitt: `io.opaa.permission`, typunabhängige Grants

Das Berechtigungsmodell bekommt ein **eigenes Paket** (`io.opaa.permission`) für Rechtesubjekt
(`PermissionSubject`), Grants, die Rechteformel (heute `LibraryAccessService`), die Herleitung und die
Rechtehistorie (`PermissionHistoryService` samt Historien-Entities und `PermissionHistoryClock`).

**Der Zyklus `io.opaa.library` ↔ `io.opaa.group` wird damit aufgelöst.** Er besteht heute in beide
Richtungen: `library` → `group` (`AssetGrant` → `PermissionSubject`; `AssetGrantService` → `Group`,
`GroupRepository`; `KnowledgeLibraryService` → `Group`, `GroupRepository`, `GroupMembershipResolver`;
`LibraryAccessService` → `GroupMembershipResolver`; `PermissionHistoryService` → die
`GroupMembershipHistory`-Familie) und `group` → `library` (`GroupService` → `AssetGrantRepository`,
`KnowledgeLibraryRepository`, `PermissionHistoryService`; `TokenGroupSynchronizer` und
`DirectorySyncPlanExecutor` → `PermissionHistoryService`). Nach dem Umbau hängen **beide Fachpakete
nur noch in Richtung des neuen Pakets**; ein Architekturtest hält die Richtung fest. `io.opaa.space`
nutzt dieselben Bausteine (`GroupMembershipResolver`, `PermissionSubject`) und wird mit #1815 zum
dritten Konsumenten — ohne den gemeinsamen Ort müsste die Herleitung in drei Fachpaketen parallel
gepflegt werden.

**Typunabhängigkeit der Grants ist Vorgabe für #1726.** `asset_grants.library_id` ist heute
`NOT NULL` mit Fremdschlüssel auf `knowledge_libraries`; die Tabelle wird auf Asset-Typ plus Asset-ID
umgestellt. Fremdschlüssel-Garantien, die dabei entfallen, werden **benannt und durch Prüfungen
ersetzt**. #1726 (Prompt-Bibliothek) Phase 1 baut auf diesem Fundament auf — **ein Umbau an
`asset_grants` statt zwei**. Der Altwert `'USER'` in `chk_asset_grant_history_role` (aus `AssetRole`
mit #330 entfernt) wird im selben Zug geprüft und entfernt, sofern keine Zeile ihn trägt.

**Die Fähigkeitstabellen (Entscheidung 5) leben im selben Paket**, tragen aber eine eigene Tabelle —
das ist der Grund, warum #1813 von #1811 abhängt.

### 13. Randbedingungen

**Alles je Organisation (#1442).** `groups.organization_id`, `capability_grants.organization_id`, die
Verantwortlichen einer Gruppe gehören derselben Organisation an, und die Liste offener Nachfolgen ist
eine Sicht je Organisation. Eine Stelle bleibt offen und gehört zu **#1442**: `oidc_providers` trägt
keine `organization_id` (ADR-0025: „Alle Anbieter provisionieren in `Organization.DEFAULT_ID`"). Der
Fremdschlüssel `groups.provider_id` ist damit heute organisationsübergreifend; sobald Anbieter einer
Organisation zugeordnet werden, kommt die Prüfregel „Gruppe und Anbieter in derselben Organisation"
hinzu — als zusammengesetzter Fremdschlüssel, wie ihn `fk_group_memberships_user_organization` heute
für Mitgliedschaften zieht.

**Verhältnis zu den angrenzenden ADRs:**

| ADR | Verhältnis |
| --- | --- |
| [ADR-0016](0016-loeschschicksal-rechtehistorie.md) | gilt unverändert. Entscheidung 8 wendet seine Systematik auf fünf neue Tabellen an (Subjektspalten `RESTRICT`, Objektspalten ohne FK) und ergänzt eine **zweite Achse**, die ADR-0016 nicht kennt: die Aufbewahrungshöchstdauer. Sie **beantwortet außerdem zwei seiner drei offenen Folgefragen**: den lesbaren Namensschnappschuss bejahend (Entscheidung 2, unter der Bedingung der Höchstdauer) und die **Pseudonymisierung des Personenbezugs ab Schreibzeitpunkt verneinend** (`RESTRICT`, Kopplung an den Personen-Einstieg statt an das Schreiben). Der Nachtrag vom 11.09.2026 (Vollmachten sind Betriebsrechte der Gegenwart, `CASCADE`) trägt die Einordnung der Verantwortlichen in Entscheidung 4 |
| [ADR-0025](0025-mehrere-oidc-anbieter.md) | gilt unverändert. Entscheidung 2 gibt der dort eingeführten Gruppenherkunft aus Token-Claims einen echten Fremdschlüssel statt einer Zeichenkette; die Identitätsregel `(issuer, subject)` bleibt und ist der Grund, warum der Keycloak-Konnektor ohne Abbildungsregel auskommt |
| [ADR-0033](0033-lokale-benutzerverwaltung.md) | gilt unverändert. Lokale Konten bekommen Gruppen ausschließlich über interne Gruppen (Entscheidung 4); die Wächter des Anbieter-Schalters und der Schutz des letzten anmeldefähigen Systemverwalters bleiben unangetastet (Entscheidungen 2 und 6); das Muster „abgeleiteter Zustand statt Statusspalte" trägt Entscheidung 6 |
| [ADR-0021](0021-single-instance-betrieb.md) | trägt die Zusage „Entzug wirkt ohne Neuanmeldung" (Entscheidung 5) und den Feststellungslauf (Entscheidung 6) |
| [ADR-0018](0018-quellkonfiguration-in-der-bibliothek.md) | Entscheidung 6 samt Nachtrag #484 wird **mit der Annahme dieses ADR abgelöst** (Entscheidung 5); der Rest gilt unverändert |
| [ADR-0006](0006-openapi-dto-generation.md) | jede API-Änderung dieses Epics beginnt in `opaa-api.yaml`; das `403` der Fähigkeitsprüfung ist eine **eigene Entscheidung der Operation** und wird dort deklariert |
| [ADR-0034](0034-liquibase-changeset-stil-und-schema.md) | jedes Changeset des Epics ist reines PostgreSQL-SQL ohne Schemaqualifizierung, mit eigenem Delta-Test |

## Zahlen, die dieser ADR setzt

| Größe | Wert | Herkunft |
| --- | --- | --- |
| **Mindestgruppengröße für Rechteprofile — erzwungene Untergrenze** | **5** | Eine Gruppe von vier ist in einem Referat eine Person mit Namen. Die Untergrenze steht als **Zahl** in der Konfigurationstabelle des Handbuchs — eine Validierung ohne nachlesbaren Wert ist nicht überprüfbar. Nach oben frei; ein Haus kann den Schutz nicht abschalten |
| **Mindestgruppengröße — Voreinstellung** | **5**, gleich der Untergrenze | `spaces-and-assets.md` führt Voreinstellung und Untergrenze als **zwei** Werte („Das Produkt setzt eine Voreinstellung und erzwingt eine Untergrenze"); die Begründung oben trägt nur die Untergrenze, die Voreinstellung ist eine eigene Wahl. **Sie liegt am erzwungenen Minimum und ist nur nach oben änderbar** — das Produkt schreibt damit keinen strengeren Schutz vor, als es selbst durchsetzt, und verdeckt umgekehrt nicht, wo die harte Grenze liegt. Ein Haus mit kleinen Einheiten hebt sie an; absenken kann sie niemand |
| **Alterungsschwelle der Nachfolgeliste** | **12 Monate**, konfigurierbar | orientiert an `DiagnosticImpersonationGrant.MAX_VALIDITY_MONTHS` |
| **Plausibilitätsschwelle des Abgleichs** | **30 %** | unverändert der gebaute Vorgabewert (`opaa.directory-sync.change-threshold-fraction = 0.3`) |
| **Abgleichintervall** | Vorgabe **6 Stunden**, je Anbieter einstellbar | wie in `access-control.md` genannt |
| **Feststellungsintervall** | Vorgabe **stündlich** | Entscheidung 6 |
| **Aufbewahrungshöchstdauer der Rechtehistorie** | Grenzen **12–120 Monate**, ausgeliefert **36** | Die Grenzen sind dieselben wie `chk_audit_retention_settings_months`, weil `security-and-compliance.md` („Aufbewahrung und Löschschicksal der Historie folgen derselben Logik wie das Protokoll") es so verlangt. **Derselbe Satz trägt auch den ausgelieferten Wert:** Das Nachweisprotokoll wird mit **36** geseedet (`001-baseline.yaml`), und die Begründung dort ist ausdrücklich — 3 Jahre decken „den üblichen Abstand zwischen Vorgang und Prüfung", 10 Jahre sind die Obergrenze, weil „was länger liegt, keiner Prüfung mehr dient". **120 wäre der falsche Auslieferungswert gewesen:** Personalrat D1 verlangt die Höchstdauer als Vorbedingung weiterer Quellen, und eine ausgelieferte Obergrenze begrenzt im Auslieferungszustand nichts |
| **Personenspalten in Historientabellen** | **2 → 7** | heute `group_membership_history.user_id` und `asset_grant_history.subject_user_id`; dazu fünf neue Tabellen |
| **`RESTRICT`-Personenspalten insgesamt (Löschschuld)** | **11 → mindestens 16** | Gezählt wird **jede `RESTRICT`/`NO ACTION`-Fremdschlüsselspalte auf `users`** — Subjekt **und** Akteur —, in Grant- **wie** Historientabellen; nicht nur eine Spalte je neuer Historientabelle. Heute sind es elf Spalten in acht Tabellen (`spaces`, `chats`, `knowledge_libraries`, `asset_grants` ×2, `space_asset_associations`, `asset_grant_history`, `group_membership_history`, `audit_incident_scope_grants` ×3). Jede der fünf neuen Historientabellen bringt mindestens eine; eine neue Grant-Tabelle daneben bringt ihre eigenen mit — #1813 zum Beispiel drei (`capability_grant_history.subject_user_id`, `capability_grants.subject_user_id`, `capability_grants.granted_by_user_id`), also 11 → 14. **„16" ist deshalb eine Größenordnung, keine Zielzahl:** nach Abschluss des Epics sind es ≥ 16. Autorität ist `UserDeletionBlockerCoverageIntegrationTest`, der die Liste aus `pg_constraint` ableitet, nicht diese Tabelle. **Die Zahl ist das Maß der aufgeschobenen Löschschuld**; `countDeletionBlockers` wächst mit jeder neuen Tabelle, und #391/#395 stellt die Historienspalten in einem Zug um |

## Randbedingungen aus der Stakeholder-Runde

Die Berichte liegen unverändert in
[`discussion-berechtigungsmodell-stakeholder.md`](../discussions/discussion-berechtigungsmodell-stakeholder.md);
die Antwort auf jede einzelne Auflage steht in §12 des Konzeptpapiers. **Diese Zusagen sind Teil der
Entscheidung und nicht in ein Sub-Issue verschiebbar:**

**Personalrat, Sichtbarkeit (A1–A6):** Die Herleitung nennt den Gruppennamen Dritten nur dort, wo sie
die Mitgliedschaft verwalten (A1, **geändert** gegenüber der Forderung, weil die Space-Mitgliederliste
heute schon nur `ADMIN`/Eigentümer/`SYSTEM_ADMIN` zugänglich ist) · „kleine Gruppe" statt Zahl
unterhalb der Mindestgruppengröße (A2) · geschützte Gruppen (A3, **geändert**: namenlose Zeile in
fremden Listen, weil ein Space-`ADMIN` sonst eine Mitgliedschaft nicht beenden könnte, die er nicht
sieht) · Mitglieder sehen ihre Verantwortlichen, Aufnahme und Entfernung werden angezeigt (A4) ·
Hinweis beim Anlegen einer internen Gruppe (A5) · der Mitgliederlisten-Abruf durch `SYSTEM_ADMIN` ist
ein Audit-Ereignis (A6).

**Personalrat, Diagnose (B1–B6) — die Bedingungen, ohne die Empfehlung 7 nicht mitbestimmungsfähig
war:** Mindestgruppengröße für Profile (B1) · sie gilt für die **Schnittmenge**, zum Zeitpunkt des
Laufs, sonst `403` (B2) · Protokoll für Profil-Läufe mit Space-Kontext (B3) · die Suchdiagnose bleibt
`SYSTEM_ADMIN` vorbehalten, und eine Öffnung schaltet die Diagnosesperre auch für Profil-Läufe scharf
(B4) · **erzwungene Untergrenze** (B5) · Leitplanke (b) bleibt (B6).

**Personalrat, Kontosperre (C1–C4):** Sperren aus dem Abgleich unterliegen Schwelle und
Bestätigungsweg (C1) · sie sind rückholbar, die Historie bleibt ohne Bruch (C2) · die Person erhält
Grund und Ansprechstelle (C3) · der Mechanismuswechsel entzieht nichts still (C4).

**Personalrat, Historie (D1–D7):** Aufbewahrungshöchstdauer als **Vorbedingung** jeder weiteren
personenbezogenen Quelle (D1) · Personen-Einstieg nur mit Vier-Augen-Vollmacht (D2) · **jeder** Abruf
ist ein Ereignis, auch der abgewiesene (D3) · verpflichtendes Zeitfenster und Seitenobergrenze, eine
zu weite Anfrage wird abgelehnt statt zurechtgestutzt (D4) · Namensschnappschuss nur mit D1 (D5) ·
Verantwortlichkeit ist kein Historienartefakt (D6) · Pseudonymisierung (D7, **geändert zu**
Voraussetzung des Personen-Einstiegs und der Kontolöschung, mit der in Entscheidung 8 genannten
Begründung).

**Personalrat, Lebenszyklus und Governance (E1–E7, F1–F4):** objektbezogene Nachfolgeliste (E1) ·
„Nachfolge offen" lässt Arbeit unberührt (E2) · Nachfolgevorgänge unter der Protokollfrist (E3) ·
Bestandsinformation statt Aktivität (E4) · Fähigkeitsvergabe als Governance-Ereignis (E5) · Fähigkeit
≠ Vollmacht (E6) · kein Lesebypass, Unterschied zu `effectiveRole` ausgesprochen (E7) · Auskunft über
die Datenerhebung vor dem Rollout, Testzugang, Protokollpflicht für Wertänderungen, einzelne
begründete Ausweisung jeder Auflage (F1–F4).

**Personalrat, zweite Sichtung (Z1–Z7):** Space-Kontext als eigenes Issue mit den fünf Schutzpunkten
einzeln, bis dahin keine Space-ID (Z1) · geschützte Gruppen zeigen Dritten keine Gruppenableitung
(Z2) · Ansprechstellen an Anbietergruppen (Z2b) · korrigierte D7-Begründung und **genau ein benanntes
Objekt je Abfrage** (Z3) · `RESTRICT` für alle fünf neuen Tabellen, mit den Zahlen im ADR (Z3b) ·
Person als Quelle nur Eigentum und Verantwortung, Vorschau als Protokollereignis auch bei Abbruch,
Objekthinweis nennt den Vorgang statt der Person (Z4) · Kennzeichnung am Objekt ohne Eigentümer,
Grund und Datum (Z5) · „kleine Gruppe" für **beide** Zahlen des Zuwachssignals, kein Signal bei
geschützten Gruppen (Z6) · **keine Auswertungsachse über die handelnde Person** in der Betriebsliste
(Z7).

**Die vier Begrenzungen der Mitgliederlisten-Ausweitung** stehen vollständig in Entscheidung 9.

**Betrieb, Auflage 2.5** (`409` beim Anbieterlöschen nur mit Übertragungsoperation) ist **übernommen
mit zeitlicher Einschränkung**; sie steht als eigener Absatz in Entscheidung 2.

**Referatsleitung, Auflage 4** („ich gebe frei, ohne zu wissen, an wen") ist übernommen: **Wer ein
Recht gibt, sieht, an wen** — mit der einen Ausnahme geschützter Gruppen, bei denen er die
Ansprechstelle sieht (Entscheidung 9).

**Zurückgewiesen wurden drei Auflagen, jede mit Begründung** (kein stillschweigendes Übergehen):
eine Historientabelle für Verantwortliche (Betrieb 4.2 — Verantwortung trägt kein Leserecht; eine
weitere `RESTRICT`-Spalte ohne Rechtebezug wäre der falsche Preis); Pull als Vorbedingung für Grants
ab Referatsebene (Referatsleitung 3 — der Mechanismus ist eine Anbietereinstellung, kein Grant-Geber
kann ihn wählen; **die angebotene Alternative ist übernommen**); das Anlegen interner Gruppen bis zur
ausdrücklichen Vergabe zu sperren (Sachbearbeitung 4a — die Auslieferung darf Verhalten nicht ändern;
**als Handbuchempfehlung übernommen**).

## Zuschnitt der Umsetzungs-Issues (gegen diesen ADR geprüft)

| Issue | Folgt aus diesem ADR | Zu korrigieren |
| --- | --- | --- |
| #1811 Paketschnitt | `io.opaa.permission` mit Subjekt, Grant, Formel, Herleitung, Rechtehistorie; Zyklus aufgelöst; typunabhängige Grants; Architekturtest (12) | Vorbehaltsvermerk entfernen; Liquibase 038–040; Zyklus in beide Richtungen benannt; #1813 hängt daran, weil die Fähigkeitsauswertung dort lebt |
| #1812 Gruppenherkunft | **eine** Spalte `provider_id` mit `RESTRICT`, kein `origin`-Feld; `source_path`; Kennzeichen „extern"; Löschen verweigert mit Zählung und Arbeitsliste; Migrationsfälle (2, 11) | Alternative „aufgelöst" **entfällt**; Liquibase 041; Migrationsfälle als Fixtures; `409` **ohne Ausweg** vor (b) — ausdrücklich im Issue; **hängt nicht** an (b) |
| #1813 Fähigkeiten | eigene Tabelle mit `ALL_ACCOUNTS`; vier Fähigkeiten; Governance-Ereignis; Historie mit Zeitspanne; Klartextzeile; `403` als eigene Entscheidung (5) | Liste um `CREATE_CONNECTOR_LIBRARY` und `CREATE_INTERNAL_GROUP` ergänzen; „Alle Konten" als **dritte Subjektart**, nicht als Gruppenobjekt; Liquibase 042; **nach (a)** |
| #1814 Gruppenverantwortliche | Freigabe zur Verwendung **mit Durchsetzung im Service**, Schutzkennzeichen, Abgabe der Verantwortung, Anzeige der Verantwortlichen, Benachrichtigung (4, 9) | Verantwortliche **ohne** Historientabelle; API ohne `/admin`; `404` statt `403` |
| #1815 Gruppen als Space-Mitglieder | Subjektspalten, beste Rolle, Historie, **handlungsfähige Gruppe zählt als `ADMIN`**, Mitgliederzahl bei Erteilung (6, 8, 9) | offene Frage „zählt eine Gruppe als `ADMIN`" ist entschieden; `409` statt `400` für den letzten `ADMIN`; Eigentümerwechsel durch jeden wirksamen `ADMIN`; Liquibase 043–044; **nach (a)** |
| #1816 Abgleich je Anbieter | **Umgesetzt (PR #1855).** Plan-Lebenszyklus (vier Festlegungen), Mechanismuskonflikt mit `409`, Zeitplan je Anbieter, Bindung an die **aktivierte Anbieterzeile** (3, 11) | `dev`-Modus entschieden: **keine synthetische Zeile, kein Abgleich**; `TrustedProvider` entfallen, `is_default` nicht mehr die Bindung; Liquibase 047–049 und 054; **hing nicht** an (b) |
| #1817 Erster Konnektor | **Keycloak Admin REST API**; direkte Mitglieder; `CredentialsEncryptor`; Verbindungstest und Allowlist (3) | Typ ist nicht mehr offen; Mitgliederzahl je Gruppe im Differenzbericht; `SettingsEncryptor` → `CredentialsEncryptor` |
| #1818 Kontostatus | Schwelle und Bestätigungsweg, Rückholbarkeit, Kontozustandshistorie, Grund und Ansprechstelle für die Person (3, 8) | **nach #1817**, weil der Kontostatus den Konnektor braucht; Historie nur nach (a), sonst Audit |
| #1819 Lebenszyklus | Feststellungslauf, vollständige Liste in beide Richtungen, Betriebsliste mit drei Reitern, Kennzeichnung ohne Eigentümer und Datum, Alterungsschwelle mit Sichtungsvermerk (6) | „Nachfolge offen" **abgeleitet**, kein Flag; Frist, Eskalation und Mail bleiben draußen; **Übernahme herausgeschnitten nach (b)** |
| #1820 Freigabedialog und Space-Mitglieder | externe Anbieter sichtbar abgehoben mit Zwischenfrage; „kleine Gruppe"; Herleitung ohne Gruppenableitung bei geschützten Gruppen; Durchsetzung **auch für die Eingabe per ID** (2, 7, 9) | Rückfall „UUID von Hand" fällt unter die Durchsetzungsregel; Mitgliederzahl zählt aktive Konten |
| #1821 Verwaltung | Arbeitsliste je Anbieter, Klartextzeile der Fähigkeiten, Betriebsliste, Ansprechstellen an Anbietergruppen (2, 5, 6, 9) | zusätzlich zur Sicht je Gruppe die Sicht je Anbieter |
| #1822 Herleitung und Stichtagsauskunft | Vollmacht für den Personen-Einstieg, **Ein-Objekt-Regel**, Zeitfenster und Seitenobergrenze, Abrufereignis auch bei Abweisung (8, 9) | Erweiterung (d); Pseudonymisierung (#391/#395) ist **Blocker des Personen-Einstiegs** |
| #1823 Demo- und Entwicklungsdaten | Gruppen mit Herkunft in beiden Realms, interne Gruppe, Gruppe als Space-Mitglied | Vorbehaltsvermerk entfernen; gleichnamige Gruppe in beiden Realms |
| #1824 Handbuchkapitel | Begriffe der Tabelle oben; Genauigkeit der Historie je Mechanismus; Pull als Empfehlung; `CREATE_CONNECTOR_LIBRARY` als erster Einschränkungskandidat; Untergrenze und Schwellen als Zahlen; „Nachfolge offen" lässt Arbeit unberührt; Verfahrensteil (F1–F3) | Vorabprüfung für das Update ergänzen (11) |
| **neu (a)** | **Aufbewahrungshöchstdauer der Rechtehistorie** — klein, Phase 2, **vor #1815**; Vorbedingung von #1813, #1815, #1818, #1819 (8) | anzulegen; Liquibase 045 |
| **neu (b)** | **Übertragungsoperation** — nach #1811 und #1815; #1819 hängt daran, #1812 und #1816 nicht (10) | anzulegen |
| **neu (c)** | **Space-Kontext im Rechteprofil** — nach #1815; bis dahin keine Space-ID im Diagnose-Request (7) | anzulegen |
| **(d)** | **Erweiterung von #1822** um Vollmacht, Ein-Objekt-Regel, Zeitfenster und Abrufereignis (8) | **kein eigenes Issue** — Nachschärfung von #1822. Die vier Punkte sind Parameter desselben Endpunkts und eine Zeile in derselben Methode; sie nachzuziehen hieße, den Lesepfad zuerst ungehärtet zu bauen — die Reihenfolge, gegen die Personalrat Z1 gerichtet ist. **Neu anzulegen sind damit drei Issues: (a), (b), (c)** |

**Reihenfolge der Wellen:** #1811 → (a), #1812 → #1813, #1815 → #1814, #1816, (b) → #1817, #1822,
#1823 → #1818, #1819, (c) → #1820 → #1821 → #1824. **(a) ist Vorbedingung von #1813, #1815, #1818
und #1819, nicht von #1812** — beide können nebeneinander laufen; #1824 steht als letzte Welle allein,
weil es die Oberflächen aus #1820 und #1821 beschreibt.

## Bewusst nicht entschieden

- **Der Verwaltungszugriff auf Originale ohne Grant und ohne Protokolleintrag** — #1828 entscheidet;
  dieser ADR hält nur fest, dass „wie heute, Spezifikation zurücknehmen" der Grundlinie widerspräche.
- **Der Rollen-Claim als Entzugspfad für Systemrollen** — #1830; dieselbe Klasse von Lücke wie
  #1807 für Gruppen, aber ein eigener Pfad mit eigener Wirkung.
- **Der `dev`-Modus ohne Anbieterzeile:** ob der Verzeichnisabgleich dort über eine synthetische
  Anbieterzeile läuft oder entfällt, entscheidet **#1816**. Die Migration darf dort nicht abbrechen —
  das ist entschieden.
- **Die Schlüsselrotation von `CredentialsEncryptor`:** Das Javadoc hält fest, dass eine Rotation im
  Bestand nicht vorgesehen ist (ein Wechsel soll `enc:v2:` bringen). Ein regelmäßig zu wechselndes
  Dienstkonto-Passwort für #1817 ist damit an eine ungelöste Frage gekoppelt. Ob daraus eine harte
  Anforderung folgt (BSI IT-Grundschutz ORP.4 ist der naheliegende Baustein, **zu prüfen**),
  entscheidet der Informationssicherheitsbeauftragte des jeweiligen Hauses.
- **Anbieter je Organisation** (`oidc_providers` ohne `organization_id`) — #1442.
- **Mehrfachzugehörigkeit** zu Organisationseinheiten und ihre Aggregation in Auswertungen.
- **Befristete Grants und Rezertifizierung** (#241, nicht geplant): Das Modell hindert sie nicht.
- **Die Obergrenze der Freigabe für Konnektorbibliotheken** (#797): Dieser ADR trennt nur die
  Fähigkeit ab.
- **Die allgemeine Autoren-Benachrichtigung** bei wesentlicher Erweiterung des Leserkreises: Das
  passive Zuwachssignal ist ihr kleinster Vorläufer, nicht ihr Ersatz.
- **Die allgemeine Protokollpflicht für Profil-Läufe ohne Space-Kontext.**
- **Das Lastverhalten** des abgeleiteten Zustands „Nachfolge offen" und der Schnittmengen-Prüfung.
  Falls die Ableitung teuer wird, ist die Antwort der Feststellungslauf mit materialisiertem
  Ergebnis, **nicht** ein gespeichertes Flag.
- **Ob Häuser mit bezahlter Entra-P1/P2-Lizenz die SCIM-Ablehnung mittragen** — eine
  Beschaffungsfrage, keine des Modells.

## Konsequenzen

### Positiv

- **Die drei Fragen des Epics sind beantwortet** und in Datenmodell, Mechanismus und Lebenszyklus
  aufgelöst: Subjekte sind Nutzer und Gruppen, flach; Herkunft ist ein Fremdschlüssel; Synchronisation
  ist je Anbieter genau eine von zwei Quellen.
- **Eine Reorganisation ist ein Vorgang statt einer Handarbeit an 200 Objekten**, und sie hinterlässt
  eine lesbare Historie statt eines `UPDATE` auf der Datenbank.
- **Das Löschen eines Anbieters kann keine wirkende Gruppe mehr verwaisen lassen** —
  `ON DELETE RESTRICT` trägt das strukturell, nicht eine Prüfung im Dienst.
- **Bestandsinstallationen verhalten sich nach der Migration identisch.** Die Fähigkeiten werden an
  „Alle Konten" ausgeliefert, interne Bestandsgruppen mit Wirkung als freigegeben.
- **Die Frage „warum sehe ich das" hat erstmals eine Antwort im Produkt**, ohne Vollmacht und ohne
  Protokoll — und ohne die Mitglieder einer Gruppe offenzulegen.
- **Der Paketschnitt beendet den dichtesten Paketzyklus des Backends außerhalb der
  `indexing`-Familie** und gibt #1726 ein Fundament, das nicht zweimal gebaut werden muss.
- **Die Mitbestimmungsfähigkeit ist an Produkteigenschaften gebunden, nicht an Zusagen:** erzwungene
  Untergrenze, Schnittmengenprüfung zum Zeitpunkt des Laufs, Vier-Augen-Vollmacht, Abrufprotokoll,
  Ein-Objekt-Regel, Aufbewahrungshöchstdauer.

### Negativ

- **Die Löschschuld wächst von elf auf mindestens sechzehn `RESTRICT`-Personenspalten** (siehe die
  Zahlentabelle: gezählt wird jede Personenspalte, auch die der Grant-Tabellen). Eine Kontolöschung
  bleibt blockiert, bis #391/#395 die Pseudonymisierung liefern — jetzt an mehr Stellen als vorher.
- **Zwischen #1812 und der Übertragungsoperation ist das Löschen eines Anbieters mit wirkenden
  Gruppen eine Sackgasse** mit dem einzigen Ausweg „Wirkungen entfernen". Deaktivieren bleibt
  jederzeit möglich.
- **Eine Bestandsänderung trifft Bibliotheksverwalter:** Eine neu angelegte interne Gruppe ist nicht
  mehr ohne Zutun ihrer Verantwortlichen als Empfänger wählbar.
- **Sieben neue Verwaltungsobjekte** (Verantwortliche, Ansprechstellen, Freigabe zur Verwendung,
  Schutzkennzeichen, Fähigkeiten, Nachfolgevorgänge, Übertragungsvorgänge) sind sieben neue Dinge, die
  gepflegt, erklärt und im Handbuch beschrieben werden müssen.
- **Der Vorgabeweg bleibt der ungeschützte.** Token ist die Vorgabe, hat aber keine der vier
  Schutzmechaniken; das Produkt macht die Folge sichtbar („Freigaben ohne Empfänger"), beseitigt sie
  aber nicht.
- **Die Aufbewahrungshöchstdauer ist eine Vorbedingung, die vier Issues blockiert.** Verzögert sie
  sich, greift der ausformulierte Schnitt — mit einer schlechteren Nachweislage für Fähigkeiten und
  Kontozustand.

### Neutral

- **`kind` bleibt**, obwohl `origin` das schönere Wort wäre. Der Enum ist API-Typ, Prüfbedingung,
  Abfragefilter und Kriterium des Geltungsbereichs der Vollmacht; der Gewinn rechtfertigt den Umbau
  nicht.
- **SCIM ist nicht abgelehnt, sondern zurückgestellt.** Die Pull-Schnittstelle schließt einen
  späteren SCIM-Server nicht aus; sie ist nur nicht der erste Schritt.
- **Die Werte in „Zahlen, die dieser ADR setzt" sind Vorgaben, keine Festlegungen der Fachlichkeit** —
  mit der Auflage, dass Mindestgruppengröße und Aufbewahrungshöchstdauer **erzwungene** Grenzen haben.
- **Die Aufbauorganisation bleibt `parentGroupId`** als Anzeige- und Aggregationsachse und trägt keine
  Mitgliedschaft. Das ist der Stand von `spaces-and-assets.md` und ändert sich nicht.

## Verworfene Alternativen

- **Freie Rollen als drittes Rechtesubjekt** (GitLab Custom Roles, Jira-Projektrollen): ein eigenes
  Verwaltungsobjekt mit eigener Pflege und für jede Auskunft „warum sieht X das?" eine
  Herleitungsstufe mehr. GitLabs Zuschnitt — Basisrolle plus Rechte, auf zehn gedeckelt, nur im
  teuersten Tier — belegt den Preis. Vorentscheidung vom 10.09.2026 (1).
- **Gruppenschachtelung** (Entra): drei Funktionen, drei verschiedene Antworten auf „gilt die
  Untergruppe mit?". Ein Berechtigungsmodell, das in der Prüfung erklärt werden muss, darf diese Frage
  nicht dreimal verschieden beantworten (1).
- **Standortgebundene Gruppen** (SharePoint): OPAA-Gruppen gehören der Organisation und wirken an
  beliebig vielen Assets und Spaces — die Voraussetzung dafür, dass „Referat 50 darf lesen" einmal
  gepflegt und überall wirksam ist (1).
- **Die Super-Gruppe mit Inhaltszugriff** (`confluence-administrators`): OPAA trennt Verwaltung und
  Inhalt; `readableLibraryIds` kennt keinen Bypass (1).
- **„Read/Write" ins Verzeichnis** (Confluence-Option): Ein zweiter Schreibweg in das Verzeichnis
  einer Verwaltung wäre ein Prüfungsbefund (1).
- **Herkunft beim heutigen Modell belassen:** Anbieterbezug ohne Integrität, `ORG_UNIT`-Gruppen ganz
  ohne Anbieterbezug, Namenskollisionen nur über Parsen des Präfixes erkennbar (2).
- **`origin`-Enum plus Abschaffung von `kind`:** ein Wort statt einer Ableitung, für einen Umbau an
  API-Typ, `chk_groups_kind`, Abfragefiltern und dem Geltungsbereich der Vollmacht (2).
- **Präfix im gespeicherten Gruppennamen** („Haus A/Referat 50"): verändert den Namen der Quelle,
  bricht bei Umbenennung des Anbieters, ist in Freitextsuchen und Auditeinträgen inkonsistent (2).
- **Erzwungene Namenseindeutigkeit für interne Gruppen:** Ein `409` beim Anlegen verriete jedem
  Inhaber des Anlegerechts, dass eine Gruppe dieses Namens existiert (2, 9).
- **Gruppen eines deaktivierten Anbieters weiter wählbar lassen, mit Hinweis:** Die Freigabe wirkt für
  niemanden — und mit der Wiederaktivierung schlagartig für alle, ohne erneute Entscheidung (2).
- **Gruppen eines gelöschten Anbieters auflösen** statt das Löschen zu verweigern: erzeugte dauerhaft
  tote Gruppen, verlangte `ON DELETE SET NULL` und eine Zusatzregel für Gruppen ohne Anbieter — genau
  die Zustandsklasse, die #1812 abschafft (2).
- **SCIM als erster Synchronisationsweg:** Push liefert Deltas, für die es keinen „leeren Lauf" und
  keine Schwelle je Lauf gibt; dazu ein eingehender, dauerhaft erreichbarer Schreibpfad mit
  Bearer-Token und eine Bereitstellungslizenz, die nur Entra-/Okta-Häuser haben (3).
- **Token und Pull am selben Anbieter:** dieselbe Verzeichnisgruppe entstünde zweimal, mit zwei
  Wahrheiten über die Mitgliedschaft und zwei Entzugszeitpunkten; zusammenführen lässt sich das nicht
  verlässlich, weil der Token keine stabile Kennung liefern muss (2, 3).
- **LDAP oder Microsoft Graph als erster Konnektor:** beide brauchen eine konfigurierbare
  Abbildungsregel zwischen Verzeichnis-Identität und `sub`; bei Keycloak fallen beide zusammen (3).
- **Den Leerergebnis-Schutz in den Bestätigungsweg verlegen:** „die Quelle hat nicht geantwortet, wie
  sie soll" darf niemand wegklicken (3).
- **Pull als Vorbedingung für Grants ab Referatsebene** (Referatsleitung 3): Der Mechanismus ist eine
  Anbietereinstellung; kein Grant-Geber kann ihn wählen, und die Vorbedingung machte Freigaben an
  Token-Gruppen in Häusern ohne Konnektor unmöglich (3).
- **Interne Gruppen weiterhin nur durch die Systemverwaltung pflegen:** Jede Mitgliederänderung bleibt
  ein Ticket, und Querschnittsgruppen entstehen nicht, weil der Weg zu lang ist (4).
- **Selbstbeitritt zu Gruppen:** Wer einer Gruppe beitreten kann, an der ein Grant hängt, gibt sich
  selbst Rechte. Selbstbeitritt gehört zu Spaces (`visibility = OPEN`), die keine Rechte an Assets
  tragen (4).
- **Gruppen als Verantwortliche von Gruppen:** Schachtelung durch die Hintertür (4).
- **Automatische Abgabe der Verantwortung beim Referatswechsel:** setzte eine Auswertung der
  Gruppenmitgliedschaften einer Person voraus — ein Referatswechsel ist im Verzeichnis kein Ereignis,
  sondern ein Gruppenwechsel (4).
- **Eine Historientabelle für Gruppenverantwortliche** (Betrieb 4.2): Verantwortung trägt kein
  Leserecht; innerhalb der Protokollfrist ist die Prüferfrage beantwortbar, außerhalb ist sie keine
  Rechtefrage — und eine weitere `RESTRICT`-Spalte gegen `users` ohne Rechtebezug wäre der falsche
  Preis (4, 8).
- **Das Anlegen interner Gruppen bis zur ausdrücklichen Vergabe sperren** (Sachbearbeitung 4a): Die
  Auslieferung darf Verhalten nicht ändern; als Handbuchempfehlung übernommen (4, 5).
- **Fähigkeit als Grant auf ein Pseudo-Asset „Organisation":** Asset-Grants tragen eine **gestufte**
  Rolle mit `atLeast`, eine Fähigkeit ist ein **Mengenelement**; die Codierung erzwänge eine
  künstliche Rangordnung oder bräche `atLeast`. Und „Alle Konten" als synthetisches Gruppenobjekt
  tauchte in jeder Auswahl auf und dopplete `visibility = ORGANIZATION` (5).
- **Reichweitenentscheidungen am Objekt als globale Fähigkeit doppeln** („organisationsweit sichtbar
  machen", „Fremdzugang freigeben"): zwei Prüfstellen für dieselbe Frage (5).
- **Eine neue Systemrolle „Bibliotheksverwalter" oder „Gruppenverwalter":** genau die Rechtebündel,
  die die Vorentscheidung ausschließt (5).
- **„Nachfolge offen" als gespeichertes Flag:** müsste an jedem Auslöser gesetzt und zurückgenommen
  werden und driftete beim ersten vergessenen Pfad (6).
- **Gestufte Zuständigkeit ohne vollständige Liste:** Ohne Zeitelement ist die Stufung keine Stufung,
  sondern eine feste Zuweisung — ein Fall der Stufe 2 erreicht die Stufe 3 nie (6).
- **„Aktivste Mitglieder" als Adressaten der Nachfolge** (M365-Richtlinie): setzt eine
  personenbezogene Aktivitätsauswertung voraus, die `security-and-compliance.md` ausschließt (6).
- **Vorgesetzte aus dem `manager`-Attribut des Verzeichnisses:** fachlich richtig, aber das Attribut
  ist unzuverlässig gepflegt, und OPAA hat es nicht (6).
- **Pflichtsichtung mit Frist** (Referatsleitung 6.2): geändert zu Alterungsschwelle mit
  Hervorhebung und freiwilligem Sichtungsvermerk — eine Frist, die niemand durchsetzt, ist keine (6).
- **Kennzeichnung „Nachfolge offen" in Suchtreffern und Quellenverweisen:** Der Zustand betrifft die
  Zuständigkeit, nicht die Richtigkeit des Inhalts; die Kennzeichnung dort machte jede Antwort zu
  einer Zustandsauswertung (6).
- **Leere wirksame Gruppen vom Erteilen ausschließen:** sperrte eine frisch angelegte Gruppe, das Ziel
  einer Anbieterablösung im Token-Modus und jede Keycloak-Abteilung mit nur Untergruppen aus (6).
- **Den Space-Kontext im Rechteprofil sofort liefern:** Die Mindestgruppengröße hinge an der Gruppe
  statt an der Schnittmenge; ein Profil „Referat 50" (23 Mitglieder) in einem Space, in dem aus
  Referat 50 genau eine Person Mitglied ist, wäre ein Personenkontext ohne dessen Schutzmechanik (7).
- **Die Mindestgruppengröße gegen „Mitglieder, die über G im Space sind" rechnen:** bei einer Gruppe,
  die selbst nicht Space-Mitglied ist, strukturell null (7).
- **Die Historie erweitern ohne Aufbewahrungshöchstdauer:** der heutige Zustand, auf acht Quellen
  ausgedehnt — Bedingung D1 verletzt, keine Dienstvereinbarung (8).
- **Beim heutigen Historienstand bleiben:** Mit #1815 wird eine Space-Zeile zur Berechtigung für
  Hunderte; „beweisen Sie, dass Frau K. im März 2026 **keinen** Zugriff hatte" wäre nach Ablauf der
  Protokollfrist unbeantwortbar (8).
- **Die fünf neuen Personenspalten von vornherein pseudonymisieren:** Der vorhandene Mechanismus liegt
  im eigenen Privilegienmodell des Protokolls (ADR-0015); zwei Modelle nebeneinander machten
  #391/#395 zweiteilig statt kleiner (8).
- **Pseudonymisierung als Voraussetzung jedes weiteren Historien*schreibens*** (D7 im Wortlaut): eine
  Schreibblockade fällt unter Termindruck per Ausnahme, eine fehlende Auswertungsfunktion fällt auf
  (8).
- **Ein zweiter Lesepfad auf die Kontozustandshistorie** („Verlauf" am Konto): umginge die
  Vier-Augen-Vollmacht des Personen-Einstiegs (8).
- **Sammelabfragen der Stichtagsauskunft** über Space, Organisationseinheit oder Bibliotheksfilter:
  dreißig Objektabfragen desselben Referats ergäben das Rechteprofil jeder Person darin, ohne
  Vollmacht (8).
- **Eine zu weite Stichtagsabfrage zurechtstutzen statt abzulehnen:** der Aufrufer erführe nicht, dass
  seine Frage eine andere war als die beantwortete (8).
- **Sichtbarkeit interner Gruppen als Opt-out:** Der Schutz hinge daran, dass ihn jemand setzt — und
  die Gruppen, die ihn brauchen, entstehen von Leuten, die an Freigabedialoge nicht denken (9).
- **Sichtbarkeit nur in der Auswahlliste durchsetzen:** Kosmetik, solange der Freigabedialog eine
  Gruppe per ID benennen lässt (9).
- **Die Herleitung nennt Dritten nie den Gruppennamen** (A1 im Wortlaut): Ein Space-`ADMIN`, der eine
  Gruppe aufgenommen hat, könnte sonst nicht sehen, was er getan hat — und die Space-Mitgliederliste
  ist ohnehin nur `ADMIN`, Eigentümer und `SYSTEM_ADMIN` zugänglich (9).
- **Geschützte Gruppen in keiner fremden Liste zeigen** (A3 im Wortlaut): Ein Space-`ADMIN` könnte
  eine Mitgliedschaft nicht beenden, die er nicht sieht; die namenlose Zeile ist das Minimum (9).
- **Eine Zustimmungspflicht des Grant-Gebers bei Gruppenzuwachs:** durch die Vorentscheidung
  ausgeschlossen — die Gruppe bleibt Subjekt am Grant, damit Änderungen durchschlagen (9).
- **Keine Übertragungsoperation:** Bei einer Reorganisation mit 200 Assets Handarbeit an jedem
  Objekt; endet erfahrungsgemäß in einem `UPDATE` auf der Datenbank, und die Rechtehistorie ist ab dem
  Tag wertlos (10).
- **Einen Sonderfall je Anlass bauen** (Mechanismuswechsel, Anbieterablösung, Nachfolge): dreimal
  dieselbe Historienschreibung mit drei Fehlerquellen (10).
- **Eine Person als Quelle mit „allen Wirkungen":** Die Vorschau wäre die personenbezogene
  Rechteübersicht für `SYSTEM_ADMIN`, ohne Vollmacht und ohne Begründung — dasselbe, was Entscheidung
  8 für die Vergangenheit unter eine Vier-Augen-Vollmacht stellt (10).
- **Den Objekthinweis mit dem Namen der ausgeschiedenen Person versehen:** ein an vielen Objekten
  wiederholter Hinweis auf ihr Ausscheiden, außerhalb jeder Protokollfrist (10).
- **Die Übertragung automatisch durch den Verzeichnisabgleich auslösen:** Eine Reorganisation im
  Verzeichnis erzeugt eine aufgelöste Gruppe und einen Eintrag in der Betriebsliste; die Übertragung
  bleibt eine Entscheidung (10).
- **`provider_id` mit `RESTRICT` ohne Migrationsvorkehrung:** Waisen-Gruppen gelöschter Anbieter
  ließen Liquibase abbrechen, die Anwendung startete nicht, und die Baseline hat keine
  Rollback-Blöcke (11).
- **Den alten Eindeutigkeitsschlüssel vor dem neuen fallen lassen:** zwei gleichnamige Gruppen zweier
  Anbieter kollidierten **während** des Updates (11).

## Referenzen

- #1295 (Epic), #1443 (Ausarbeitungsauftrag), #1809 (Konzeptpapier), #1810 (dieser ADR)
- [Konzeptpapier, dritte Fassung](../discussions/discussion-berechtigungsmodell-gruppen-und-faehigkeiten.md)
  — Systemvergleich, Optionstabellen, Anhang A (Quellen) und Anhang B (Abweichungen zwischen
  Spezifikation und gebautem Stand)
- [Stakeholder-Berichte](../discussions/discussion-berechtigungsmodell-stakeholder.md) — Betrieb und
  Informationssicherheit, Personalrat (zwei Sichtungen), Referatsleitung, Sachbearbeitung,
  Code-Review
- [ADR-0006](0006-openapi-dto-generation.md) — OpenAPI-first
- [ADR-0015](0015-eigentuemertrennung-protokollablage.md) — Privilegienmodell des Protokolls
- [ADR-0016](0016-loeschschicksal-rechtehistorie.md) — Löschschicksal der Rechtehistorie, Nachtrag zu
  Vollmachten
- [ADR-0018](0018-quellkonfiguration-in-der-bibliothek.md) — Entscheidung 6 mit Nachtrag #484, mit
  der Annahme dieses ADR abgelöst
- [ADR-0019](0019-minimale-benachrichtigungsinfrastruktur.md) — Benachrichtigungen ohne Mail
- [ADR-0021](0021-single-instance-betrieb.md) — Single-Instance-Annahme
- [ADR-0025](0025-mehrere-oidc-anbieter.md) — mehrere OIDC-Anbieter, Identität `(issuer, subject)`,
  Gruppen aus Token-Claims
- [ADR-0033](0033-lokale-benutzerverwaltung.md) — lokale Konten, abgeleiteter Kontozustand,
  Verfügbarkeitswächter
- [ADR-0034](0034-liquibase-changeset-stil-und-schema.md) — Changeset-Stil und Schemaportabilität
- `docs/features/access-control.md`, `docs/features/spaces-and-assets.md`,
  `docs/features/hybrid-retrieval.md` (Berechtigungs-Leitplanken),
  `docs/features/security-and-compliance.md`
- Vergleichssysteme: Confluence/Jira Data Center, Microsoft Entra ID und SharePoint, GitLab,
  Nextcloud; SCIM 2.0 ([RFC 7644](https://www.rfc-editor.org/rfc/rfc7644.html)), Keycloak Admin REST
  API, Microsoft Graph, LDAP/Active Directory — vollständige Fundstellen in Anhang A des
  Konzeptpapiers
