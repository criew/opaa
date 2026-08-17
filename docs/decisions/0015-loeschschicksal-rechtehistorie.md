# ADR-0015: Löschschicksal der Rechtehistorie — Historie überlebt Fachobjekt- und Kontolöschung

## Status

Vorgeschlagen

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

## Offene Folgefragen (nicht Gegenstand dieser Entscheidung)

- Ein lesbarer Namens-Schnappschuss an den Historienzeilen (Bibliotheks-/Gruppenname zum
  Schreibzeitpunkt), damit ein Prüfbericht ohne Join auf ein möglicherweise gelöschtes Fachobjekt
  lesbar bleibt.
- Aufbewahrungshöchstdauer und Pseudonymisierung des Personenbezugs ab Schreibzeitpunkt für die
  Rechtehistorie selbst (`docs/features/security-and-compliance.md` fordert beides; #238 setzt es
  noch nicht um — Follow-up-Issue nötig).
- Korrelation historisierter Verzeichnislauf-Änderungen mit dem konkreten Lauf (`DirectorySyncStatus`
  hält nur den jeweils letzten Lauf je Organisation) — Follow-up-Issue.
