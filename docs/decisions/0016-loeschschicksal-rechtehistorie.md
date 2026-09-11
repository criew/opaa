# ADR-0016: Löschschicksal der Rechtehistorie — Historie überlebt Fachobjekt- und Kontolöschung

## Status

Akzeptiert

## Kontext

#238 historisiert AssetGrants, Gruppenmitgliedschaften und Bibliotheks-Sichtbarkeit als
Zeitintervalle, damit die vollständige Rechtemenge einer Person zu einem beliebigen Stichtag
rekonstruierbar ist — insbesondere die Negativfrage „hatte Person X am Tag Y KEINEN Zugriff auf
Bibliothek Z" (siehe `docs/features/spaces-and-assets.md#nachweisbarkeit-historisierung-von-rechten`
und `docs/features/security-and-compliance.md#nachweisbarkeit-historisierung-von-rechten`).

Die erste Fassung der Migration (`018-permission-history.yaml`) hat die Historientabellen mit
Fremdschlüsseln auf die Fachobjekte versehen, die sie historisieren — `library_id`/`subject_group_id`
mit `ON DELETE CASCADE` auf `knowledge_libraries`/`groups`, und `group_membership_history.user_id`
mit `ON DELETE CASCADE` auf `users`. Das Code-Review zu PR #427 hat zwei Fehlerszenarien konkret
belegt:

1. **Bibliotheks-/Gruppenlöschung löscht die Historie mit.** Eine Gruppe „Projekt Z" erhält im
   Januar einen Grant auf `Personalvorgänge`; im April wird der Grant widerrufen; im Mai wird die
   Gruppe gelöscht (zulässig, weil sie keinen aktiven Grant mehr hält). Mit `ON DELETE CASCADE`
   verschwinden dabei auch die bereits geschlossenen Historienintervalle. Eine Stichtag-Anfrage für
   den 3. März — als der Grant noch aktiv war — antwortet danach fälschlich „nicht enthalten". Das
   ist keine fehlende Auskunft, sondern eine falsche, und trifft exakt den Vorgang, den ein Prüfer
   untersucht. Dieselbe Lücke besteht bei `deleteLibrary`, einer regulären OWNER-Operation.
2. **Kontolöschung löscht die Mitgliedschaftshistorie.** `docs/features/security-and-compliance.md`
   verlangt ausdrücklich: „Beim Löschen eines Kontos entfällt die Zuordnung; die Historie selbst
   bleibt unverändert bestehen … sonst wäre für ausgeschiedene Personen nichts mehr belegbar, obwohl
   Prüfungen gerade sie häufig betreffen." Ein `ON DELETE CASCADE` auf der Subjektspalte tut exakt
   das Gegenteil und war zudem inkonsistent zu `asset_grant_history.subject_user_id`, das von Anfang
   an `RESTRICT` war.

Eine Historie, die dieselbe Löschung überlebt haben muss, die sie belegen soll, ist an der
entscheidenden Stelle keine Historie.

## Entscheidung

**Die Rechtehistorie überlebt jede Löschung eines Fachobjekts (Bibliothek, Gruppe, Grant) und jede
künftige Kontolöschung — mit zwei unterschiedlichen Mechanismen, je nachdem, was gelöscht wird:**

1. **Fachobjekte (Bibliothek, Gruppe): kein Fremdschlüssel.** `asset_grant_history.library_id`,
   `asset_grant_history.subject_group_id` und `group_membership_history.group_id` sind reine
   UUID-Wertespalten ohne Fremdschlüssel-Constraint. Eine Bibliotheks- oder Gruppenlöschung wird
   dadurch weder blockiert noch räumt sie die Historie mit ab. Die Zeile bleibt über ihre eigene
   `library_id`/`group_id` als Identifikator lesbar, auch wenn das Fachobjekt nicht mehr existiert —
   nur der Rücksprung auf einen aktuellen Namen/Zustand entfällt.
2. **Konten (Subjektspalten): `RESTRICT`, nicht `CASCADE`.** `asset_grant_history.subject_user_id`
   und `group_membership_history.user_id` bleiben echte Fremdschlüssel auf `users`, mit `ON DELETE
   RESTRICT`. Das blockiert eine Kontolöschung, solange die Person noch in der Rechtehistorie referenziert
   wird — bewusst, bis #391/#395 einen Pseudonymisierungsmechanismus liefern, der die
   Personenbezug-Auflösung regelt, ohne die Historie selbst zu löschen. `RESTRICT` ist damit die
   Übergangslösung, nicht der Zielzustand: Sie verhindert den stillen Verlust der Historie, bis die
   eigentliche Lösung (Pseudonymisierung statt Löschung des Personenbezugs) existiert.
3. **`actor_user_id` bleibt unverändert `ON DELETE SET NULL`.** Der auslösende Vorgang (`cause`,
   `NOT NULL`) bleibt erhalten, nur der handelnde Akteur entfällt — das erfüllt das
   Abnahmekriterium „jede Rechteänderung trägt ihren auslösenden Vorgang" bereits ohne die
   Subjektspalten zu berühren, und wurde im Review ausdrücklich bestätigt.

Diese Asymmetrie ist beabsichtigt: Die Subjektspalte sagt „wen betrifft diese Zeile" — das ist der
Kern dessen, was die Historie nachweisen soll, und darf nicht durch eine Kontolöschung verschwinden.
Die Fachobjekt-Spalten sagen „worauf bezieht sich diese Zeile" — das Fachobjekt kann vergehen, ohne
dass die historisierte Tatsache ihre Aussagekraft verliert.

## Konsequenzen

**Einfacher:**

- Die Negativfrage aus #238 bleibt über die gesamte Lebensdauer eines Fachobjekts hinweg korrekt
  beantwortbar, auch nach dessen Löschung.
- `deleteLibrary`/`deleteGroup` bleiben unveränderte, sofort wirksame Operationen — keine neue
  Blockade, kein neuer Ausnahmefall in den bestehenden Lösch-Guards.
- `deleteLibrary`/`deleteGroup` schließen zusätzlich die zu diesem Zeitpunkt noch offenen
  Historienintervalle des gelöschten Objekts, mit eigener Ursache (`LIBRARY_DELETED`/
  `GROUP_DELETED`) statt sie unbegrenzt offen zu lassen — ohne diesen Schritt würde eine
  Stichtag-Rekonstruktion für „jetzt" fälschlich weiterhin Zugriff auf ein bereits gelöschtes
  Objekt melden (Überberichtung, die harmlose Richtung, aber unnötig; Nachtrag aus dem
  Code-Review zu PR #427).
- Der Migrationstest kann Lösch-Überleben statt Lösch-Kaskadierung als Vertrag prüfen
  (`Migration018PermissionHistoryTest`).

**Schwieriger:**

- Eine Historienzeile lässt sich nach Löschung des Fachobjekts nicht mehr per SQL-Join auf einen
  aktuellen Bibliotheks-/Gruppennamen zurückführen — nur über die in der Zeile selbst geführten
  Werte (Rolle, Sichtbarkeit, Zeitraum). Ein lesbarer Namens-Schnappschuss wäre eine mögliche
  Erweiterung, ist aber nicht Teil dieser Entscheidung.
- `RESTRICT` auf den Subjektspalten bedeutet: **eine Kontolöschung ist heute nicht möglich, solange
  Rechtehistorie zu diesem Konto existiert** — praktisch bei jedem Konto, das je ein Recht hatte oder
  Mitglied einer Gruppe war. Das ist kein Rückschritt (eine Kontolöschungsfunktion existiert noch
  nicht), aber es legt fest, dass #391/#395 eine Pseudonymisierungslösung liefern müssen, keine
  Kaskadierung — eine Vorgabe, die dort zu beachten ist.
- Integrationstests, die Konten direkt löschen (z. B. `userRepository.deleteById`/`deleteAll` in
  Testaufräumroutinen), müssen ihre eigene Rechtehistorie vor der Kontolöschung explizit entfernen
  (`AssetGrantHistoryRepository#deleteBySubjectUserIdIn`,
  `GroupMembershipHistoryRepository#deleteByUserIdIn` — beide ausdrücklich als Test-Hilfsmittel
  markiert, nicht für Produktionscode).

## Nachtrag (11.09.2026): personenbezogene Berechtigungs-Bestandssätze

Die Entscheidung oben nennt nur die drei Historientabellen aus #238. Mit den **Diagnose-Vollmachten**
(`diagnostic_impersonation_grants`, #1052) ist seither eine weitere personenbezogene Tabelle
entstanden, die auf den ersten Blick dieselbe Frage stellt: `holder_user_id` sagt, wen die Zeile
betrifft — nach der Systematik oben also eine Subjektspalte, für die die Entscheidung `RESTRICT`
verlangt. Tatsächlich stand sie von Anfang an auf `ON DELETE CASCADE`, ohne dass das Changeset diese
Abweichung begründet hätte (Befund in #1509, entdeckt im Review zu #1506).

**Maintainer-Entscheidung vom 11.09.2026:** `CASCADE` bleibt und wird auf alle Personenspalten der
Tabelle ausgeweitet. Begründung im Wortlaut: „Wenn ein Nutzer nicht mehr da ist, brauchen wir die
Information der Vollmacht nicht mehr."

**Warum das die Entscheidung oben nicht aufhebt, sondern abgrenzt.** Die Rechtehistorie und die
Diagnose-Vollmacht beantworten nicht dieselbe Art von Frage:

- Eine **Historienzeile** ist ein Artefakt: Sie existiert, um über einen Zustand der Vergangenheit
  Auskunft zu geben, und zwar gerade über ausgeschiedene Personen — deshalb muss sie die Löschung
  überleben, die sie belegen soll (Szenario 2 oben).
- Eine **Vollmacht** ist ein Betriebsrecht der Gegenwart: eine befristete, bereichsgebundene Erlaubnis,
  „Sicht als" auszuführen. Mit dem Konto **ihres Inhabers** entfällt ihr Gegenstand — es gibt
  niemanden mehr, der sie ausüben könnte. Für die übrigen Spalten trägt dieses Argument nicht; deren
  Kaskade ist eine bewusste Inkaufnahme, siehe die Tabelle unten.

Beide Regeln nebeneinander zu führen ist also kein Widerspruch, sondern der Unterschied zwischen
Historienartefakt und Bestandssatz eines aktiven Rechts. Wer künftig eine personenbezogene Tabelle
anlegt, ordnet sie **ausdrücklich** einer der beiden Arten zu; „nichts Architektonisches wird implizit
festgelegt" gilt hier wie sonst.

**Konkret für `diagnostic_impersonation_grants` (Changeset `003`, #1509):**

| Spalte | Ziel | Löschregel | Begründung |
|---|---|---|---|
| `holder_user_id` | `users` | `CASCADE` (unverändert) | Die Person, um die die Vollmacht geht — mit ihrem Konto entfällt ihr Gegenstand. |
| `granted_by_user_id` | `users` | `RESTRICT` → `CASCADE` | Ein `RESTRICT` würde die Kontolöschung weiterhin blockieren und die Entscheidung leerlaufen lassen. `SET NULL` scheidet aus: Der Schlüssel ist zusammengesetzt und führt `organization_id` mit, die `NOT NULL` ist (die Spalte selbst ist es ebenfalls). |
| `revoked_by_user_id` | `users` | `RESTRICT` → `CASCADE` | Sonst blockiert eine einmal widerrufene — also längst wirkungslose — Vollmacht die Löschung des widerrufenden Kontos dauerhaft. `SET NULL` scheidet aus demselben Grund aus wie oben (`organization_id` im zusammengesetzten Schlüssel), zusätzlich koppelt `chk_…_revocation` die Spalte an `revoked_at`. |
| `scope_group_id` | `groups` | `RESTRICT` → `CASCADE` | Der Geltungsbereich ist der Gegenstand der Vollmacht; `SET NULL` scheidet aus (Spalte und `organization_id` sind `NOT NULL`), und eine Vollmacht ohne existierenden Geltungsbereich erlaubt nichts. **Wirkt heute nicht:** Der Geltungsbereich muss eine Organisationseinheit sein, und `GroupService#deleteGroup` weist Organisationseinheiten vor jedem Fremdschlüsselkontakt ab — die Regel greift erst, wenn es einen Löschpfad für Organisationseinheiten gibt. |
| `organization_id` | `organizations` | `RESTRICT` (unverändert) | Der Mandantenwurzel wird nicht gelöscht; jede andere Tabelle behandelt sie ebenso. |

**Die Kaskade trifft auch Dritte — bewusst.** Über Aussteller-, Widerrufer- und Geltungsbereichsspalte
entfernt sie nicht nur Vollmachten der gelöschten Person, sondern auch **noch gültige Vollmachten
weiterhin existierender Inhaber**: Wird das Konto einer Administratorin gelöscht, die über zwei Jahre
40 Befugnisse ausgestellt hat, verschwinden diese 40 mit — ohne Widerrufsereignis und ohne Anzeige.
Die Richtung ist ungefährlich (Rechte entfallen, sie entstehen nicht), und `RESTRICT` wäre die
Alternative, die genau die Kontolöschung blockiert, um die es hier geht. Die Auflage daraus: **Eine
künftige Kontolöschungsfunktion widerruft betroffene Vollmachten ausdrücklich**, statt sich auf diese
Kaskade zu verlassen — dann entsteht je Vollmacht ein Widerrufsereignis, und die Kaskade greift nur
noch als Netz.

**Was nach einer Kontolöschung belegbar bleibt.** Erteilung und Widerruf schreiben je einen Eintrag ins
`audit_log` (`DIAGNOSTIC_IMPERSONATION_GRANTED`/`_REVOKED`); dessen `actor_ref`/`subject_ref` sind
`varchar` ohne Fremdschlüssel und überleben die Löschung. Der **Bestandssatz** überlebt sie bewusst
nicht. Die Person wird im Ereignis über ihr Pseudonym geführt, und `audit_actor_pseudonyms` hängt nach
ADR-0015/#395 selbst mit `CASCADE` an `users` — nach der Kontolöschung ist die Frage „hatte Person X im
Zeitraum Y die Befugnis?" also über keinen der beiden Wege mehr beantwortbar. Das ist die bewusst in
Kauf genommene Folge dieser Entscheidung, nicht ein übersehener Nebeneffekt.

**Für die Rechtehistorie ändert sich nichts:** Ihre Subjektspalten bleiben `RESTRICT`, und die Vorgabe
an #391/#395 — Pseudonymisierung statt Kaskadierung — bleibt für sie unverändert bestehen.

## Offene Folgefragen (nicht Gegenstand dieser Entscheidung)

- Ein lesbarer Namens-Schnappschuss an den Historienzeilen (Bibliotheks-/Gruppenname zum
  Schreibzeitpunkt), damit ein Prüfbericht ohne Join auf ein möglicherweise gelöschtes Fachobjekt
  lesbar bleibt.
- Aufbewahrungshöchstdauer und Pseudonymisierung des Personenbezugs ab Schreibzeitpunkt für die
  Rechtehistorie selbst (`docs/features/security-and-compliance.md` fordert beides; #238 setzt es
  noch nicht um — Follow-up-Issue nötig).
- Korrelation historisierter Verzeichnislauf-Änderungen mit dem konkreten Lauf (`DirectorySyncStatus`
  hält nur den jeweils letzten Lauf je Organisation) — Follow-up-Issue.
