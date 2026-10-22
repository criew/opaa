# ADR-0016: Löschschicksal der Rechtehistorie

## Status

Akzeptiert · zuletzt geändert 2026-10-21 · ergänzt durch ADR-0036 (Entscheidung 8) und ADR-0039 (Entscheidung 3)

## Kontext

Die Rechtehistorie hält Grants, Gruppenmitgliedschaften und Reichweite als Zeitintervalle, damit die
Rechtemenge einer Person zu einem Stichtag rekonstruierbar ist, auch die Negativfrage „hatte Person X
am Tag Y keinen Zugriff auf Z?“
([security-and-compliance.md](../features/security-and-compliance.md#nachweisbarkeit-historisierung-von-rechten)).
Fremdschlüssel mit `ON DELETE CASCADE` auf die historisierten Fachobjekte und auf die betroffene
Person hätten genau das zerstört: Eine Gruppenlöschung nach dem Widerruf ihres Grants hätte die
geschlossenen Intervalle mitgelöscht und eine Stichtagsanfrage für den März fälschlich mit „nicht
enthalten“ beantwortet; eine Kontolöschung hätte die Mitgliedschaftshistorie der ausgeschiedenen
Person entfernt, obwohl Prüfungen gerade sie betreffen. Eine Historie, die dieselbe Löschung
überleben muss, die sie belegen soll, ist sonst keine Historie.

## Entscheidung

### 1. Fachobjekte: kein Fremdschlüssel

Die Spalten der Historientabellen, die auf das historisierte Objekt zeigen (`asset_id` in
`asset_grant_history`, `asset_visibility_history` und `asset_ownership_history`, `group_id` in
`group_membership_history`) und die Gruppen-Subjektspalten (`subject_group_id`, `owner_group_id`),
sind reine UUID-Werte ohne Fremdschlüssel. Eine Asset- oder
Gruppenlöschung wird weder blockiert noch räumt sie die Historie ab; nur der Rücksprung auf einen
aktuellen Namen entfällt. Vor der Löschung schließt die Anwendung die offenen Intervalle des Objekts
mit eigener Ursache (`ASSET_DELETED` über `AssetShellService#registerDeleted`, `GROUP_DELETED` in
`GroupService#deleteGroup`), sonst meldete eine Rekonstruktion für „jetzt“ Zugriff auf ein gelöschtes
Objekt.

### 2. Konten: `RESTRICT` auf jeder Subjektspalte

Die Subjektspalten bleiben echte Fremdschlüssel auf `users` mit `ON DELETE RESTRICT`:
`asset_grant_history.subject_user_id`, `group_membership_history.user_id`,
`asset_ownership_history.owner_user_id`, `capability_grant_history.subject_user_id` und
`space_membership_history.subject_user_id`. Die Kontolöschung (`LocalUserService#delete`) prüft
dieselben Tabellen vorab und lehnt mit `409` ab, solange das Konto in Rechte- oder Nachweisbeständen
referenziert ist; die Anleitung lautet „sperren statt löschen“. `RESTRICT` ist die Übergangslösung bis
zu einer Pseudonymisierung des Personenbezugs, die die Historie selbst unangetastet lässt; eine
Kaskade ist für diese Tabellen ausgeschlossen. `account_state_history.user_id` steht dagegen auf `ON
DELETE CASCADE`: Ein Zustandsintervall nennt für sich keinen Zugang, es kürzt nur Zeiträume, die in
einer der Tabellen oben stehen und die Löschung schon verweigern (ADR-0036, Entscheidung 8).

### 3. Akteur: `ON DELETE SET NULL`

Wo eine Historientabelle den handelnden Akteur führt (`actor_user_id`, alle außer
`account_state_history`), steht die Spalte mit `ON DELETE SET NULL`. Die Ursache (`cause`, `NOT
NULL`) bleibt, nur der Akteur entfällt; „jede Rechteänderung trägt ihren auslösenden Vorgang“ ist
damit ohne Berührung der Subjektspalten erfüllt.

### 4. Bestandssätze aktiver Rechte kaskadieren

Eine personenbezogene Tabelle ist entweder Historienartefakt (Entscheidungen 1–3) oder Bestandssatz
eines Rechts der Gegenwart; wer eine neue anlegt, ordnet sie ausdrücklich zu. Die Diagnose-Vollmacht
(`diagnostic_impersonation_grants`) ist ein Bestandssatz: Alle Personen- und Gruppenspalten
(`holder_user_id`, `granted_by_user_id`, `revoked_by_user_id`, `scope_group_id`) stehen auf `ON DELETE
CASCADE`, weil mit dem Konto der Inhaberin der Gegenstand entfällt und `SET NULL` am zusammengesetzten
Schlüssel mit `organization_id` scheitert. Die Kaskade trifft bewusst auch noch gültige Vollmachten
Dritter; deshalb widerruft die Kontolöschung vorher ausdrücklich jede noch gültige Vollmacht, die das
Konto erteilt hat, je Vollmacht ein `DIAGNOSTIC_IMPERSONATION_REVOKED` in derselben Transaktion (seit
#1697); die Kaskade ist nur das Netz dahinter. Erteilung und Widerruf stehen im `audit_log` und
überleben die Löschung; der Bestandssatz nicht. Dass „hatte Person X die Befugnis?“ nach der Löschung
über keinen Weg mehr beantwortbar ist, ist die in Kauf genommene Folge.

### 5. Die Space-Zuordnung wird nicht historisiert

Die Zuordnung eines Assets zu einem Space ist eine harte Nutzungsgrenze (ADR-0039 Entscheidung 4),
gewährt aber keinen Zugriff; „wer konnte lesen?“ beantwortet allein die Rechtehistorie. Die früheren
`listed`-Intervalle sind entfallen (ADR-0039 Entscheidung 3).

## Konsequenzen

**Einfacher:** Die Negativfrage bleibt über die Lebensdauer eines Objekts hinaus korrekt beantwortbar;
`deleteLibrary`/`deleteGroup` bleiben sofort wirksam.

**Schwieriger:** Eine Historienzeile lässt sich nach der Objektlöschung nicht mehr per Join auf einen
Namen zurückführen. Eine Kontolöschung scheitert praktisch bei jedem Konto, das je ein Recht hatte.
Tests, die Konten löschen, entfernen die eigene Historie vorher über die als Test-Hilfsmittel
markierten `deleteBy…In`-Methoden der Historien-Repositories.

## Verworfene Alternativen

- `ON DELETE CASCADE` auf Fachobjekt- und Subjektspalten — löscht genau den Beleg, den ein Prüfer
  braucht.
- `RESTRICT` für Diagnose-Vollmachten — blockierte die Kontolöschung, um die es geht, wegen eines
  Rechts, das ohne Inhaberin niemand ausüben kann.

## Offen

- Namens-Schnappschuss an Historienzeilen, damit ein Prüfbericht ohne Join auf ein gelöschtes Objekt
  lesbar bleibt.
- Pseudonymisierung des Personenbezugs der Rechtehistorie, damit eine Kontolöschung ohne
  Historienverlust möglich wird.
- Korrelation historisierter Verzeichnislauf-Änderungen mit dem konkreten Lauf; `directory_sync_status`
  hält nur den jeweils letzten Lauf.
