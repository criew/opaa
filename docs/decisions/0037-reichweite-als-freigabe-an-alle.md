# ADR-0037: Organisationsweite Reichweite als Freigabe an „Alle Konten", `visibility` entfällt

## Status

**Vorgeschlagen (24.09.2026)** — Issue [#1931](https://github.com/criew/opaa/issues/1931).
Ändert [ADR-0036](0036-berechtigungsmodell-gruppen-und-faehigkeiten.md) (Entscheidung 5 und 12) und
löst die Verteilungsstufe aus [ADR-0006](0006-openapi-dto-generation.md)-Zeiten ab. Betrifft
[ADR-0032](0032-zeitquelle-rechtehistorie.md) (Zeitquelle der Rechtehistorie) und die
Freigabe-Obergrenze aus [#797](https://github.com/criew/opaa/issues/797).

## Kontext

Ein Asset trug bis hierher **zwei** Angaben über seine Reichweite, neben seiner Rechteliste:

| Feld | Werte | gebaute Wirkung |
|---|---|---|
| `assets.visibility` | `PRIVATE` \| `SHARED` \| `ORGANIZATION` | nur `ORGANIZATION` wirkt: Lesezugriff (`VIEWER`) für jeden der Organisation |
| `assets.listed` | `true` \| `false` | Auffindbarkeit im Katalog, unabhängig vom Zugriff (Katalog kommt mit #1904) |

Bei der UI-Durchsicht am 24.09.2026 fiel auf, dass `visibility` drei Werte führt, von denen nur
einer etwas tut.

**`PRIVATE` und `SHARED` werden nirgends unterschieden.** Die Rechteformel
(`AssetAccessService#effectiveRole`, `#readableAssetIds`) kennt genau einen Zweig, der `visibility`
liest, und er prüft auf `== ORGANIZATION`. Grants an Personen und Gruppen wirken unabhängig vom Wert.
`SHARED` ist ein Etikett ohne Verhalten.

**Und es ist ein Etikett, über dessen Bedeutung sich Spezifikation und Code nicht einig sind.** Die
Spezifikation (`spaces-and-assets.md`, „Freigabestufen und Auffindbarkeit") ordnete `SHARED` den
Stufen „Team" und „Fachbereich" der Produktvision zu und unterschied die beiden über die Gruppe, an
die freigegeben wurde. Das Javadoc von `AssetVisibility` beschrieb `SHARED` dagegen als „Reichweite
folgt der Mitgliedschaft der besitzenden Gruppe" — eine Aussage über den **Eigentümer**, nicht über
die Empfänger. Beides lässt sich nicht gleichzeitig meinen, und da nichts von beidem ausgewertet
wird, ist nie aufgefallen, welches gilt.

Daraus folgen drei konkrete Kosten:

1. **Zwei Formeln für dieselbe Frage.** „Wer erreicht dieses Asset?" wird an zwei Stellen
   beantwortet: über die Grants (`asset_grants`) und über ein Feld der Schale. Die Rechtehistorie
   führt beide getrennt (`asset_grant_history` und `asset_visibility_history`), die
   Stichtagsauskunft komponiert sie (`PointInTimeAccessService`), die Rechteformel reicht die
   Schalenangabe als Sonderparameter `organizationWide` durch sechs Methodensignaturen durch, und
   `AssetShellService#widensReach` führt eine zweite Fassung der Regel „erweitert die Reichweite"
   neben der in `AssetGrantService#widensReach`.
2. **Die Bedienoberfläche zwingt zu einer Entscheidung, die aus den Grants ablesbar wäre.** Wer ein
   Asset freigibt, trägt Empfänger in eine Liste ein *und* wählt getrennt davon eine Stufe, deren
   erste zwei Werte nichts bewirken.
3. **Die Freigabe-Obergrenze (#797) hängt an der Stufe.** `visibility_cap` deckelt ein Enum mit
   einem Ordinal-Vergleich (`AssetVisibility#exceeds`), dessen Javadoc ausdrücklich davor warnt,
   dass ein an falscher Stelle eingefügter Wert die Antwort stillschweigend ändert.

Zugleich gibt es im Haus bereits einen ausgebauten Präzedenzfall für „alle Konten der Organisation"
als Rechtesubjekt: Die installationsweiten Anlegerechte (#1813, ADR-0036 Entscheidung 5) führen
`CapabilitySubjectType.ALL_ACCOUNTS` neben `USER` und `GROUP`, mit zwei nullbaren Subjektspalten,
einer Prüfregel für die passende Belegung und einem eindeutigen Teilindex. Das Changeset
`042-create-capability-grants.yaml` begründete damals ausdrücklich, warum das **kein** synthetisches
Gruppenobjekt ist — und nannte als eines von zwei Argumenten, dass ein solches Objekt
`visibility = ORGANIZATION` doppeln würde.

## Entscheidung

### 1. `visibility` entfällt; organisationsweite Reichweite wird eine gewöhnliche Freigabe

Ein Asset hat **genau eine Rechteliste**. Empfänger einer Freigabe sind Personen, Gruppen und
**„Alle Konten"** — alle Konten der Organisation des Assets. „Organisationsweit lesbar" ist
damit eine Freigabe wie jede andere: sichtbar in derselben Liste, geführt in derselben
Grant-Historie, vergeben über denselben Empfänger-Picker, widerrufen über denselben Knopf.

Die Rechteformel schrumpft von drei Wegen auf zwei Quellen:

```
lesbare_Assets(u) =
    { A : Freigabe(u, A)          ≥ VIEWER }
  ∪ { A : Freigabe(g, A)          ≥ VIEWER für eine Gruppe g mit u ∈ g }
  ∪ { A : Freigabe(ALLE, A)       ≥ VIEWER und gleiche Organisation }
```

Alle drei Zeilen lesen dieselbe Tabelle. Der Sonderparameter `organizationWide` verschwindet aus
`effectiveRole`, `effectiveRoles`, `accessPaths`, `readableAssetIds`, `readableAssetIdsForGroup` und
`readableAssetCountsForGroups`; an seine Stelle tritt ein Zweig in `AssetAccessService#reaches`:
eine Freigabe an „Alle" erreicht jeden. Das ist der eine Sonderpfad, den das Modell behält, und er
steht an genau einer Stelle.

**Die Rolle ist gedeckelt: höchstens `EDITOR`** (`AssetGrantService#upsertGrant`, `400` mit
benanntem Grund; die Bedienoberfläche bietet für diesen Empfänger nur `VIEWER` und `EDITOR` an).
`MANAGER` und `OWNER` sind keine Nutzungsrechte, sondern **Zuständigkeiten** — Rechte vergeben,
Eigentum, Nachfolge —, und eine Zuständigkeit trägt eine benannte Person oder Gruppe, nie „alle".
Dieselbe Überlegung hält schon heute eine besitzende Gruppe bei `MANAGER` statt `OWNER`
(`AssetShellService#registerCreated`: ein `OWNER`, der mit jedem Mitglied wächst und nie
herabgestuft werden kann); hier geht sie einen Rang weiter, aus einem zusätzlichen, konkreten
Grund: `AssetGrantRepository#countOtherActiveOwnerGrants` filtert nur auf `role = 'OWNER'` und
zählte einen `ALL_ACCOUNTS`-Grant mit — die letzte benannte Eigentümerrolle wäre damit entfernbar,
und das Asset „gehörte allen". Die abgelöste Stufe `ORGANIZATION` verlieh ausnahmslos `VIEWER`; der
Deckel hält die neue Form daher innerhalb dessen, was die alte konnte, und lässt `EDITOR` als
einzige Erweiterung zu, weil Bearbeiten eine Nutzung ist und keine Zuständigkeit.

### 2. Die Form: dritte Subjektart, kein synthetisches Gruppenobjekt

„Alle Konten" ist **keine Zeile in `groups`**, sondern die Subjektart `ALL_ACCOUNTS` einer
Freigabe, mit beiden Subjektspalten leer — Form und Prüfregeln wie bei den Anlegerechten:

```sql
CONSTRAINT chk_asset_grants_subject_type
  CHECK (subject_type IN ('USER', 'GROUP', 'ALL_ACCOUNTS'))
CONSTRAINT chk_asset_grants_subject
  CHECK (… OR (subject_type = 'ALL_ACCOUNTS' AND subject_user_id IS NULL
                                            AND subject_group_id IS NULL))
CREATE UNIQUE INDEX uk_asset_grants_all_accounts
  ON asset_grants (asset_type, asset_id) WHERE subject_type = 'ALL_ACCOUNTS';
```

Die Form ist keine Erfindung dieses ADR, sondern die **Übernahme eines im Haus bereits
ausgebauten Musters**: `CapabilitySubjectType.ALL_ACCOUNTS` (#1813, Changeset `042`) trägt „alle
Konten der Organisation" seit den Anlegerechten genauso — zwei nullbare Subjektspalten, eine
Prüfregel für die passende Belegung, ein eindeutiger Teilindex. Übernommen ist damit auch die
Beschriftung: Beide Stellen heißen in der Bedienoberfläche **„Alle Konten"**.

**Verworfene Alternative: eine systemseitig angelegte Gruppe je Organisation.** Sie hätte den
Vorzug, dass Fremdschlüssel, Grant-Historie und Picker unverändert blieben. Dagegen steht, dass eine
solche Zeile aus jedem Weg, der Gruppen verarbeitet, einzeln herausgehalten werden müsste: aus der
Gruppenliste und der Gruppenverwaltung, aus dem Verzeichnisabgleich (#1817), aus den
Gruppenverantwortlichen (#1814) und Ansprechstellen (#1875), aus der Rechteübertragung (#1834), aus
der Mitgliederoffenlegung (#1880), aus den Space-Mitgliedschaften (#1815) und aus der Löschprüfung.
Das sind neun Verbote statt einer Modellaussage — und jedes davon ist eine Stelle, an der ein
künftiger Beitrag es vergisst. Hinzu kommt, dass „keine Mitgliederliste" bei einer echten
Gruppenzeile eine Zusage wäre, die niemand erzwingt: `activeMemberCount` lieferte `0` und damit ein
falsches Zuwachssignal (ADR-0036, Entscheidung 9).

Die vier Anforderungen aus #1931 gehen mit der gewählten Form ohne eigene Regel auf:

| Anforderung | Warum sie hält |
|---|---|
| Organisationsgrenze | `asset_grants.organization_id` ist NOT NULL mit Fremdschlüssel; `fk_asset_grants_asset_organization` bindet die Freigabe an die Organisation des Assets. Es gibt keine Zeile, die die Grenze überschreiten *könnte*. |
| Verzeichnisabgleich fasst sie nie an | Der Abgleich arbeitet auf `groups`/`group_memberships`. Dort steht nichts. |
| Keine Mitgliederliste | Es gibt keine Mitgliedschaftszeilen. Kein Aufruf von `activeMemberCount`, kein Zuwachssignal, keine Offenlegung. |
| Kein Sonderpfad in der Rechteformel | Ein Zweig in `reaches`, siehe Entscheidung 1. |

### 3. Eigenes Enum `AssetGrantSubjectType` statt eines dritten Werts an `PermissionSubjectType`

`PermissionSubjectType` (`USER`, `GROUP`) trägt außer den Freigaben auch die **Space-Mitgliedschaft**
(`space_memberships`, `space_membership_history`) und die **Eigentumshistorie**
(`asset_ownership_history`). Ein Space, dessen Mitglied „alle" ist, und ein Asset, das „allen"
gehört, sind Zustände, die das Modell nicht kennt und deren Tabellen sie weder speichern noch
prüfen. Deshalb bekommen die Freigaben ihr eigenes `AssetGrantSubjectType` (`USER`, `GROUP`,
`ALL_ACCOUNTS`) — dieselbe Begründung, die `CapabilitySubjectType` schon trägt, nur in die andere
Richtung gelesen. `PermissionSubject` (das Record) bleibt unverändert USER/GROUP und bedient weiter
Eigentum und Spaces.

`CapabilitySubjectType` bleibt eigenständig. Sein Javadoc wird nachgezogen: Die Begründung „existiert
nur für Anlegerechte" stimmt nicht mehr, die Begründung „gehört nicht an die geteilte Subjektart"
sehr wohl.

### 4. `listed` bleibt — und wird unabhängig von jeder Stufe

`listed` ist der Auslöser des organisationsweiten Katalogs (#1904) und behält Bedeutung und
Vorgabewert (`false`). Beschriftung in der Verwaltung: **„Im Katalog auffindbar, auch ohne
Berechtigung"**, mit dem Erklärtext, dass der **Eintrag** sichtbar wird (Name, Beschreibung,
zuständige Stelle) und nicht der Inhalt.

**Die Regel der Spezifikation „`listed` erst ab Fachbereichsebene" entfällt.** Sie hing an
`visibility` und ist ohne die Stufe nicht formulierbar. `listed` zu setzen erfordert künftig
`MANAGER` am Asset — dieselbe Schwelle wie jede andere Freigabeentscheidung. Die 2×2-Tabelle des
Katalogs (zugänglich/nicht zugänglich × gelistet/nicht gelistet) bleibt unverändert gültig; nur die
Vorbedingung für die rechte obere Zelle fällt weg.

### 5. Die Freigabe-Obergrenze (#797) wird ein Boolean — und wandert an den Freigabe-Pfad

`knowledge_libraries.visibility_cap` (ein Enum mit Ordinal-Vergleich) wird zu
`all_accounts_grant_allowed` (Boolean, ausgeliefert `true`). `listed_cap` bleibt unverändert. Aus
„überschreitet die Obergrenze" wird `verlangt && !erlaubt`; die Ordnungsannahme von
`AssetVisibility#exceeds`, vor der ihr eigenes Javadoc warnte, entfällt ersatzlos.

**Die Klemmprüfung wandert dabei an eine andere Stelle, und das ist der Teil, der leicht zu
übersehen ist.** Bisher prüfte `AssetTypeDefinition#requireReachWithinLimits`, aufgerufen aus
`AssetShellService#changeReach`. Der Freigabe-Pfad läuft dort nie durch. `AssetTypeDefinition`
bekommt deshalb `requireAllAccountsGrantAllowed(Asset)`, aufgerufen aus
`AssetGrantService#upsertGrant`, sobald das Subjekt `ALL_ACCOUNTS` ist — weiter als `409` mit dem
Text der Systemverwaltung, nicht als `403`: Die Rolle der aufrufenden Person steht nicht in Frage,
der verlangte Zustand widerspricht einer Decke. Ohne diesen Schritt wäre #797 nach der Umstellung
wirkungslos. Für `listed` bleibt die Prüfung am Schalen-Pfad und heißt dort
`AssetTypeDefinition#requireListedWithinLimits`.

**Das nachträgliche Absenken der Obergrenze widerruft die Freigabe**, statt ein Enum abzusenken. Die
Maintainer-Festlegung vom 21.09.2026 („bestehende weitergehende Freigaben werden sofort
zurückgenommen, kein Zustand ‚verletzt, aber geduldet‘") bleibt damit gewahrt — der Widerruf läuft
durch den Freigabe-Pfad, schreibt also Grant-Historie, Audit-Ereignis und Cache-Invalidierung wie
jeder andere Widerruf. Er umgeht bewusst die `MANAGER`-Prüfung von `revokeGrant`: Aufrufer ist die
Systemverwaltung, deren `SYSTEM_ADMIN`-Rolle bereits geprüft ist.

### 6. Nachfolge-Sperre: eine Formel statt zweier

`AssetShellService#widensReach` führte eine zweite Fassung der Regel „erweitert die Reichweite"
neben `AssetGrantService#widensReach`. Nach der Umstellung gilt:

- **Eine neue Freigabe an „Alle"** ist eine neue Freigabe wie jede andere und wird von
  `AssetGrantService#requireReachNotFrozenIfWidening` ohne Sonderregel gesperrt.
- **`listed` zu setzen** bleibt die einzige Erweiterung, die der Schalen-Pfad selbst sperrt.

### 7. Was aus der Historisierung wird

**`asset_visibility_history` bleibt bestehen und verliert nur ihre Spalte `visibility`.** Das Issue
sah vor, sie ganz abzulösen; das geht nicht, weil dasselbe Intervall auch `listed` und den
Fremdzugang trägt (`external_access_state`, `external_access_expires_at`, Ursachen
`EXTERNAL_ACCESS_CHANGED`/`EXTERNAL_ACCESS_EXPIRED`, #1731). `listed` bleibt laut Beschluss, der
Fremdzugang stand nie zur Debatte. Es entfallen die Spalte und die beiden Abfragen, die auf
`visibility = 'ORGANIZATION'` filtern.

Den **Namen** der Tabelle und ihrer Klassen lasse ich stehen. Ihn zu ändern hieße, Tabelle, Entität,
Service, Repository, Ursachen-Enum, Prüfregel und die Liste der Aufbewahrungslöschung in demselben
PR umzubenennen, der ohnehin das Rechtemodell umbaut — eine Umbenennung ohne Verhaltensänderung
gehört nicht in denselben Schritt wie eine Modelländerung.

Ebenso bleibt das Audit-Ereignis **`ASSET_VISIBILITY_CHANGED`** bestehen: Es protokolliert künftig
nur noch `listed`. Die Freigabe an „Alle" wird über das bestehende Grant-Ereignis
(`ASSET_GRANT_GRANTED` / `ASSET_GRANT_CHANGED` / `ASSET_GRANT_REVOKED`) protokolliert, mit dem
Empfänger im Nutzinhalt.

**Kein Subjekt im Audit-Eintrag.** `audit_log.subject_kind` kennt `USER` und `GROUP`
(`chk_audit_log_subject`), und die Spalte ist nullable. Eine Freigabe an „Alle" setzt deshalb kein
Subjekt und trägt den Empfänger als `"subjectType": "ALL_ACCOUNTS"` im Nutzinhalt — dasselbe Feld
führt jeder Grant-Eintrag, auch der an eine Person oder eine Gruppe. Das ist sachlich
richtig: Eine Freigabe an alle ist nicht personenbezogen und hat in der Pseudonymtabelle nichts
verloren.

### 8. Der Rückblick muss mitwandern — sonst wird die Negativantwort falsch

`AccessBasis.ORGANIZATION_WIDE` **bleibt** als Wert der Herleitung und der Stichtagsauskunft und
wird künftig aus `subject_type = 'ALL_ACCOUNTS'` abgeleitet statt aus der Schale. Ihn zu streichen
hieße, jeden Bestandseintrag der Stichtagsauskunft umzudeuten.

Damit `PointInTimeAccessService` für Zeiträume **vor** der Migration dieselben Leser nennt wie
heute, überführt das Changeset **jedes abgeschlossene und offene `ORGANIZATION`-Intervall** aus
`asset_visibility_history` in ein Intervall in `asset_grant_history` — nicht nur den aktuellen
Zustand aus `assets`:

```
für jedes Intervall i mit i.visibility = 'ORGANIZATION' und i.valid_to > i.valid_from:
    asset_grant_history: subject_type = 'ALL_ACCOUNTS', role = 'VIEWER', expires_at = NULL,
                         cause = 'BACKFILL', actor_user_id = i.actor_user_id,
                         valid_from = i.valid_from, valid_to = i.valid_to
```

Eine Migration, die nur den lebenden Zustand überführt, ließe den Rückblick auf abgeschlossene
organisationsweite Zeiträume ersatzlos ins Leere laufen — ein Prüfer bekäme für einen Stichtag in
einem solchen Fenster eine **falsche Negativantwort**. Das ist der eigentliche Migrationsaufwand,
nicht der lebende Grant. `cause = 'BACKFILL'` ist der zutreffende Wert: Die Zeile ist nicht als
Vorgang beobachtet, sondern aus einer anderen Tabelle rekonstruiert.

Bewusst in Kauf genommen: `actor_user_id` eines Bestandsintervalls stammt aus der
Sichtbarkeitshistorie, nicht aus einem `granted_by_user_id`, das es nie gab. Wer die Freigabe
erteilt hat, ist für Bestandszeiträume damit so genau wie bisher — nicht genauer, aber auch nicht
ungenauer.

### 9. Was in der Bedienoberfläche an die Stelle der Stufe tritt

- **Empfänger-Picker:** „Alle Konten" als eigener Eintrag neben Personen und Gruppen, mit
  einem **eigenen Bestätigungsschritt**, der die Reichweite ausspricht („Jede Person Ihrer
  Organisation erhält Lesezugriff") — nicht nur mit einem Warnhinweis daneben. Der Schritt ist die
  Gegenmaßnahme dagegen, dass eine Freigabe an alle künftig ein Klick neben „Team Recht" ist, wo sie
  bisher ein eigenes Formularfeld war.
- **Übersichten:** eine aus den Freigaben **abgeleitete** Reichweiten-Badge — „Alle", „N Gruppen,
  M Personen" oder „nur Sie" — statt eines gespeicherten Feldes. Sie ersetzt den Haken „In der
  Organisation geteilt" aus #1916.
- **Verwaltung:** `listed` als Schalter mit der Beschriftung aus Entscheidung 4.

### 10. `SpaceVisibility` bleibt unberührt

`SpaceVisibility` (`PRIVATE` / `DISCOVERABLE` / `OPEN`) ist ein anderes Objekt (der Space, nicht das
Asset), eine andere Frage (wer den Raum findet und betritt, nicht wer den Inhalt liest), eine andere
Tabelle (`spaces.visibility`) und eine andere Stelle der Spezifikation. Kein Codepfad koppelt die
beiden. Diese Entscheidung ändert daran nichts.

## Konsequenzen

**Einfacher:**

- **Eine Frage, eine Antwort.** „Wer erreicht dieses Asset?" wird aus einer Tabelle beantwortet.
  Rechteformel, Rechtehistorie, Stichtagsauskunft und Herleitung lesen dieselbe Quelle.
- **Sechs Methodensignaturen verlieren einen Parameter**, und mit ihm die Möglichkeit, dass ein
  Aufrufer ihn falsch belegt. `LibraryAccessService#effectiveRolesForReadableLibraries` muss die
  Liste nicht mehr ein zweites Mal filtern, um ihn zu bilden.
- **Zwei Fassungen von „erweitert die Reichweite" werden eine.**
- **Ein Enum mit bedeutungstragender Deklarationsreihenfolge verschwindet** — samt der Klasse von
  Fehlern, vor der sein eigenes Javadoc warnte.
- **Die Rücknahme ist ein gewöhnlicher Widerruf** mit sofortiger Wirkung, statt eines eigenen
  Vorgangs mit eigenem Protokoll.
- **Der Katalog (#1904) baut auf zwei Quellen statt auf drei**: lesbar ∪ `listed`.

**Schwieriger oder teurer:**

- **Eine Freigabe an alle ist leichter zu vergeben als bisher die Stufe** — derselbe Dialog, dieselbe
  Liste. Dagegen stehen der Bestätigungsschritt (Entscheidung 9), das Audit-Ereignis (7), die
  weiterhin wirksame Obergrenze (5) und die Nachfolge-Sperre (6). Das Restrisiko bleibt und ist
  bewusst getragen.
- **Die Rolle ist gedeckelt.** `MANAGER` und `OWNER` an „Alle" werden abgewiesen (Entscheidung 1);
  `EDITOR` ist möglich und lässt jedes Konto Dokumente hochladen und löschen. Das ist eine
  Möglichkeit, die die abgelöste Stufe nicht hatte, und sie bleibt bewusst offen: Bearbeiten ist
  eine Nutzung, und wer sie nicht will, vergibt `VIEWER`. Die Migration vergibt `VIEWER`.
- **`asset_grants` wächst** um eine Zeile je organisationsweit freigegebenem Asset. Bei Beständen in
  der Größenordnung von Hunderten ist das kein Argument; die Abfragezahl bleibt gleich, und der
  Teilindex `uk_asset_grants_all_accounts` ist so selektiv wie der abgelöste
  `idx_assets_organization_wide`.
- **Ein drittes Subjekt-Enum.** `PermissionSubjectType`, `CapabilitySubjectType` und
  `AssetGrantSubjectType` stehen nebeneinander, zwei davon mit demselben dritten Wert. Die
  Alternative — ein gemeinsames Enum — erkauft die Ersparnis damit, dass Space-Mitgliedschaft und
  Eigentum einen Wert annehmen könnten, den sie nicht kennen.
- **Die Migration ist nicht rückrollbar im vollen Sinn.** Der Rollback stellt Spalte und Werte
  wieder her, aber `asset_grant_history` hat dann Intervalle, die `asset_visibility_history` nicht
  mehr hat. Vor Produktionsbetrieb ist das hinnehmbar.

**Offen:**

- **Bewertung durch die Personalvertretung.** Eine Freigabe an „Alle" benennt weder Person noch
  Gruppe und ist damit keine personenbezogene Datenquelle im Sinne von `spaces-and-assets.md`,
  „Drei Datenquellen mit Personenbezug"; im Audit wird — wie schon heute bei der organisationsweiten
  Stufe — kein Subjekt pseudonymisiert. Vorzulegen sind gleichwohl der Bestätigungsschritt und die
  Widerrufbarkeit. Der Punkt blockiert die Umsetzung nicht, weil sie am Status quo nichts
  verschlechtert.

## Verworfene Alternativen

### `visibility` behalten und nur `SHARED` streichen

Ein zweiwertiges `PRIVATE` / `ORGANIZATION` wäre die kleinste Änderung: keine Migration der
Historie, keine neue Subjektart, kein Umbau der Bedienoberfläche. Verworfen, weil es die eigentliche
Doppelung nicht auflöst — die Frage „wer erreicht dieses Asset?" bliebe an zwei Stellen beantwortet,
die Rechtehistorie an zwei Tabellen geführt, der Sonderparameter in sechs Signaturen erhalten. Ein
Boolean „organisationsweit" an der Schale ist dasselbe wie eine Freigabe an alle, nur ohne
Freigabe-Historie, ohne Gültigkeitsende, ohne Rollenwahl und ohne Platz in der Liste, in der die
Person sowieso nachsieht, wer Zugriff hat.

### „Alle" als Rolle statt als Empfänger

Statt eines Empfängers hätte man `AssetRole` um einen Wert erweitern können, der „gilt für alle"
bedeutet. Verworfen: `AssetRole` ist eine **Ordnung** (`VIEWER < EDITOR < MANAGER < OWNER`), die
`bestRole` und `requireCallerRoleAtLeast` auswerten. Ein Wert, der keine Stufe dieser Ordnung ist,
bräche sie — dieselbe Klasse von Fehlern, die Entscheidung 5 gerade beseitigt.

### Die Obergrenze als Grenze der Empfängerzahl

Statt eines Booleans „Freigabe an Alle erlaubt" hätte die Obergrenze eine Zahl erreichbarer Konten
deckeln können. Verworfen, und zwar nicht neu: Die Maintainer-Festlegung vom 21.09.2026 zu #797 hat
eine Gruppengrößen-Schwelle für Freigaben ausdrücklich gestrichen, und ADR-0036 (Entscheidung 9)
führt den Gruppenzuwachs bewusst nur als **passives Signal**. Eine Grenze, die von einer Größe
abhängt, die sich außerhalb des Systems ändert, sperrt bei der nächsten Verzeichnisübernahme eine
Arbeit, die gestern erlaubt war.

## Verwandte Dokumente

- [ADR-0036: Berechtigungsmodell, Gruppen und Fähigkeiten](0036-berechtigungsmodell-gruppen-und-faehigkeiten.md)
- [ADR-0032: Zeitquelle der Rechtehistorie](0032-zeitquelle-rechtehistorie.md)
- [Spaces, Assets & Zugangskontrolle](../features/spaces-and-assets.md)
- [Sicherheit & Nachweisbarkeit](../features/security-and-compliance.md)
