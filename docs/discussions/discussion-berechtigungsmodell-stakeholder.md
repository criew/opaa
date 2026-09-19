# Diskussion: Berechtigungsmodell — Stakeholder-Bewertungen und Code-Review zum Konzeptpapier #1809

Sechs Berichte zum Konzeptpapier
[`discussion-berechtigungsmodell-gruppen-und-faehigkeiten.md`](discussion-berechtigungsmodell-gruppen-und-faehigkeiten.md)
(Issue #1809, Epic #1295), erstellt nach dem Verfahren in
[`docs/AGENT-ORGANIZATION.md`](../AGENT-ORGANIZATION.md#stakeholder-review): vier Bewertungen der
**ersten Fassung** vom 19.09.2026 (Betrieb, Personalrat, Referatsleitung, Sachbearbeitung), der
Code-Review der **zweiten Fassung** und die zweite Sichtung des Personalrats zur zweiten Fassung.
Welche Befunde übernommen, geändert oder zurückgewiesen wurden, steht im Abschnitt
„Stakeholder-Bewertung" des Konzeptpapiers. Die Berichte sind unverändert übernommen; Zeilenangaben und
Zitate beziehen sich auf den jeweils bewerteten Stand.

---

## Teil 1: Betriebs- und Informationssicherheitsverantwortlicher (erste Fassung)

# Stakeholder-Bewertung: Betriebs- und Informationssicherheitsverantwortlicher

**Gegenstand:** `docs/discussions/discussion-berechtigungsmodell-gruppen-und-faehigkeiten.md`
(Entwurf 19.09.2026, PR #1825, Issue #1809, Epic #1295), Stand des Arbeitsbaums
`opaa-1809` (`5413393a`), Code gegengelesen auf demselben Stand.
**Nicht bewertet:** die Vorentscheidungen aus Abschnitt 0 und dem Epic (flache Subjekte, keine
Schachtelung, Gruppe bleibt Subjekt am Grant, Fähigkeiten statt ADR-0018/6, Mandantenfähigkeit als
Randbedingung). Ich bewerte die sieben Empfehlungen und die Entscheidungsvorlage.

---

## 1. Gesamturteil

**Tragfähig mit Auflagen.** Das Papier ist die erste Vorlage in diesem Projekt, die den Übergang
mitdenkt statt nur den eingeschwungenen Zustand — Migrationsschritte, Auslieferungswerte und
Verweigerungsfälle stehen ausdrücklich da. Es scheitert aber an drei Stellen genau dort, wo meine
Arbeit anfängt: Der **ausgelieferte Regelbetrieb (Token-Abgleich) hat keine der Schutzmechaniken**,
die für den erst noch zu bauenden Pull-Weg ausführlich begründet werden; die **Migration hat einen
belegbaren Abbruchfall**, den sie nicht behandelt; und die **Stichtagsauskunft ist nicht
geschlossen**, weil für Space-Mitgliedschaft, Eigentum, Systemrolle und Verantwortliche keine
Historientabelle existiert, während der Audit-Pfad nach 12–120 Monaten gelöscht wird
(`chk_audit_retention_settings_months`, `001-baseline.yaml:1304`).

---

## 2. Was funktioniert

Das nehme ich ausdrücklich zur Kenntnis, weil es mir Arbeit spart:

1. **„Nachfolge offen" als abgeleiteter Zustand** (§7.1). Das ist die einzige Variante, die eine
   Rücksicherung übersteht: Nach dem Einspielen einer Sicherung von vorgestern ist der Zustand am
   Datenbestand wieder korrekt errechnet. Ein gespeichertes Flag wäre zurückgespielt worden und hätte
   Objekte als „verwaist" markiert, deren Eigentümer längst wieder da ist — oder umgekehrt.
2. **Der Bestätigungsweg für Läufe über der Schwelle** (§4.2, #1816). Er behebt eine belegte
   Sackgasse im gebauten Stand: `DirectorySyncPlanExecutor` dokumentiert selbst den Fall „3 aktive
   Gruppen, eine legitime Auflösung = 33 % > 30 %, kein Übersteuern je Lauf, keine Aussicht auf
   Besserung" (Kommentar ab Zeile 454). Heute steht eine kleine Behörde damit dauerhaft auf
   `ABORTED_THRESHOLD`.
3. **Auslieferung an „Alle Konten" für `CREATE_SPACE`/`CREATE_LIBRARY`/`CREATE_CONNECTOR_LIBRARY`
   und an niemanden für `CREATE_INTERNAL_GROUP`** (§6.1/§6.4). Vier Migrationszeilen je Organisation,
   danach verhält sich die Installation exakt wie vorher. Das ist die Abnahmebedingung des Epics
   („Bestehende Freigaben funktionieren nach der Umstellung unverändert") sauber eingelöst.
4. **Ablehnung von SCIM im ersten Schritt** (§4.2). Ein eingehender, dauerhaft erreichbarer
   Schreibpfad mit Bearer-Token wäre für mich ein eigener Prüfgegenstand mit eigener Freigabe. Pull
   nutzt die vorhandene Allowlist und öffnet nichts.
5. **Fähigkeit ≠ Befugnis** (§6.2). Dass „Sicht als" und Vorfallsbereich nie an Gruppen oder „Alle
   Konten" vergebbar sind, ist die richtige Grenze — und deckt sich mit dem gebauten Stand
   (`DiagnosticImpersonationGrantService#grant`: nur `SYSTEM_ADMIN` vergibt, Frist ≤ 12 Monate).
6. **`ON DELETE RESTRICT` statt Aufräum-Automatik** (§3.1/§3.4). Eine Garantie, die die Datenbank
   trägt, ist mir zehnmal lieber als eine, die ein Service verspricht.

---

## 3. Bewertung je Empfehlung

### 3.1 Empfehlung 1 — Vergleich mit anderen Systemen (§2)

**Urteil: tragfähig.**

Die Auswahl der Vergleichssysteme trifft das, was in Verwaltungshäusern tatsächlich steht, und jede
Übernahme/Ablehnung ist begründet. Zwei Auflagen, beide betrieblich:

- **Auflage 1.1 — Zeile „Reorganisation" in die Vergleichsmatrix (§2.6).** Die Matrix vergleicht
  Subjekte, Schachtelung, Synchronisation und den Verlust des letzten Verantwortlichen, aber nicht
  den Fall, der in meinem Haus mindestens jährlich eintritt: Referat 50 wird aufgelöst, seine
  Aufgaben gehen an Referat 52 und ein neues Sachgebiet. Alle vier Vergleichssysteme haben dazu eine
  Antwort (gute oder schlechte); OPAA hat laut Papier nur den Sonderfall in §4.4 („Grants auf
  Verzeichnisgruppe übertragen") und sonst keine. Ich brauche die Zeile, weil daraus die fehlende
  Operation folgt (Abschnitt 5 dieser Bewertung).
- **Auflage 1.2 — die Ablehnung der Super-Gruppe hat eine Betriebsfolge, die genannt gehört (§2.1).**
  Dass `SYSTEM_ADMIN` in `readableLibraryIds` keinen Lesezugriff hat, ist richtig und bleibt. Es
  heißt aber: Im Störungsfall („ein Nutzer meldet, die Suche liefere ein Dokument, das sie nicht
  sehen dürfte") kann ich den Sachverhalt ohne Befugnis nicht nachstellen. Der ADR sollte
  festhalten, dass die Suchdiagnose mit Rechteprofil (§8) der dafür vorgesehene Weg **ist** — sonst
  baut sich irgendwann jemand einen Umweg.

### 3.2 Empfehlung 2 — Gruppenherkunft, Option B (§3)

**Urteil: tragfähig mit Auflagen.** Option B ist richtig; die Migration ist unvollständig
beschrieben, und zwei Folgeentscheidungen widersprechen einander.

- **Auflage 2.1 (blockierend) — Waisen-Gruppen gelöschter Anbieter.** Das Papier nennt den heutigen
  Defekt selbst (Anhang B Nr. 12, §1: „wird der Anbieter gelöscht, bleibt eine Gruppe zurück"). Genau
  diese Zeilen kann der Backfill nicht auflösen: `external_id = oidc:<uuid>:<name>` zeigt auf eine
  `oidc_providers`-Zeile, die es nicht mehr gibt, und `provider_id` mit FK `RESTRICT` lässt sich
  nicht setzen. Ergebnis: Liquibase bricht ab, die Anwendung startet nicht, und die Baseline hat
  bewusst keine Rollback-Blöcke (`AGENTS.md`, Abschnitt Liquibase-Baseline). Das ist ein Ausfall zur
  Feierabendzeit, kein Migrationshinweis. Verlangt: Das Changeset behandelt den Fall ausdrücklich
  (Vorschlag: Umwandlung in eine interne Gruppe `AD_HOC` mit `external_id = NULL`, dokumentiert in
  der Historie, plus ein Protokolleintrag je umgewandelter Zeile), und der Delta-Test von #1812 hat
  genau diesen Fall als Fixture. Dasselbe gilt für `ORG_UNIT`-Gruppen in einer Installation **ohne**
  Standardanbieter — nach `OidcProviderService` (Javadoc, Z. 45–55) darf die Anbietermenge leer sein,
  die `LOCAL`-Zeile ist nie Standard. Der Satz „das ist die einzige Quelle, aus der sie entstanden
  sein können" (§3.1) stimmt genetisch, aber nicht zum Migrationszeitpunkt.
- **Auflage 2.2 — Reihenfolge und Doppelrolle der Eindeutigkeit.** Heute ist
  `uk_groups_organization_external_id UNIQUE (organization_id, external_id)`
  (`001-baseline.yaml:172`), und `TokenGroupSynchronizer` nutzt sie ausdrücklich als
  Nebenläufigkeitsschutz („advisory lock … `uk_groups_organization_external_id` backs that").
  Schneidet die Migration das Präfix, kollidieren zwei gleichnamige Gruppen zweier Anbieter
  **während** des Updates, wenn der alte Index nicht vorher fällt; und der neue Schlüssel
  `(organization_id, provider_id, kind, external_id)` muss den Nebenläufigkeitsschutz weiter tragen.
  Beides gehört in #1812 und in den Delta-Test. (Nebenbei: `MAX_NAME_LENGTH = 213` in
  `TokenGroupSynchronizer` leitet sich aus dem Präfix ab und ändert sich mit.)
- **Auflage 2.3 — Widerspruch zwischen §3.4 und §7.4.** §3.4 sagt, die Gruppen eines **deaktivierten**
  Anbieters seien „in Auswahlfeldern weiter wählbar, aber mit Hinweis". §7.4 sagt, aufgelöste und
  leere Gruppen seien kein neues Grant-Ziel. Beide Zustände sind betrieblich derselbe: eingefrorene
  Mitgliedschaft, kein Mitglied kann sich anmelden. Situation: Anbieter wegen eines Vorfalls
  deaktiviert; drei Tage später gibt ein Bibliotheksverwalter eine Bibliothek für „Referat 50 (Anbieter
  deaktiviert)" frei, weil der Hinweis wie eine Randnotiz aussieht. Die Freigabe wirkt für niemanden —
  bis der Anbieter wieder aktiv ist, dann schlagartig für alle, ohne erneute Entscheidung. Verlangt:
  Gruppen eines deaktivierten Anbieters sind **kein neues Grant-Ziel** und kein neues Space-Mitglied,
  bestehende Grants bleiben unangetastet.
- **Auflage 2.4 — Eindeutiger Name interner Gruppen verrät Namen (§3.3).** Das Papier will
  Gruppennamen je Organisation eindeutig **und** (§5.3) die Mitgliedschaft in einer Gruppe
  „Disziplinarverfahren 2026" vor Kollegen verbergen. Mit delegierter Anlage (§5) kann jeder Inhaber
  von `CREATE_INTERNAL_GROUP` die Existenz eines Namens durch einen Anlegeversuch erfragen: `409`
  = existiert. Das ist keine Randnotiz, sondern genau der Schutzzweck, den §5.3 aufschreibt.
  Verlangt: Entweder die Eindeutigkeit fällt (Warnung statt Fehler, Auflösung über die Herkunfts-
  und ID-Anzeige) oder der Konflikt wird für Nicht-Systemverwalter nicht offengelegt — und dann
  gehört in den ADR, dass die Eindeutigkeit damit nur eine Anzeigehilfe ist, keine Garantie.
- **Zur ausdrücklich gestellten Frage „Deaktivieren lässt alles stehen, Löschen verweigert mit 409":**
  Das **Deaktivieren** ist praktikabel und richtig — mit Auflage 2.3. Die **409-Verweigerung ist in
  der vorgeschlagenen Form nicht praktikabel**, siehe 3.2.1 unten.

**3.2.1 Der 409 im Betrieb.** Der einzige realistische Grund, einen Anbieter zu löschen, ist sein
Ersatz: Keycloak-Instanz A wird durch B abgelöst, Haus fusioniert, Partnerportal wird abgeschaltet.
Genau dann tragen seine Gruppen Grants — sonst würde ich nicht löschen wollen, sondern könnte es
einfach. Die Meldung „3 Gruppen tragen 12 Berechtigungen an 7 Bibliotheken und sind Mitglied in 2
Spaces" (§3.4) sagt mir, dass ich blockiert bin, und die einzige angebotene Auflösung ist, diese 12
Berechtigungen **zu entfernen** — also die Rechte zu zerstören, die ich eigentlich auf die neuen
Gruppen umziehen will. Bei 200 Assets ist das die Situation aus meinem Rollenauftrag: „jemand trifft
Entscheidungen für tausende Objekte einzeln". Was dann passiert, weiß ich aus Erfahrung: Jemand
schreibt ein `UPDATE` auf die Datenbank, und die Rechtehistorie ist ab dem Tag wertlos.
**Auflage 2.5 (blockierend): Der 409 ist nur zulässig, wenn es eine protokollierte
Übertragungsoperation gibt** (Abschnitt 5 dieser Bewertung). Zusätzlich muss die Meldung nicht nur
zählen, sondern auf eine **Arbeitsliste je Anbieter** verweisen (#1821 liefert die Sicht je Gruppe —
je Anbieter fehlt sie).

- **Auflage 2.6 — Rücknahme von Mitgliedschaften nach einem Vorfall.** §3.4 behandelt die
  Deaktivierung als Notweg. Sie nimmt aber keine Mitgliedschaft zurück, die ein kompromittierter
  Anbieter vorher gesetzt hat (`TokenGroupSynchronizer` schreibt sie unter dem Akteur
  `identity-provider`). Situation: Am 12.03. fällt auf, dass seit dem 05.03. manipulierte Tokens
  Gruppenansprüche gesetzt haben. Ich deaktiviere — und die Mitgliedschaften vom 05.–12.03. bleiben
  stehen, wirksam, sobald der Anbieter wieder läuft. Verlangt: mindestens eine gezielte,
  protokollierte Rücknahme „alle Mitgliedschaften dieses Anbieters ab Zeitpunkt T zurücknehmen"
  (die Historie mit `validFrom`/`validTo` in `group_membership_history` gibt das her), oder der ADR
  hält ausdrücklich fest, dass dieser Fall Handarbeit bleibt.

### 3.3 Empfehlung 3 — Synchronisation: Token oder Pull, Keycloak zuerst (§4)

**Urteil: tragfähig mit Auflagen.** Die Wahl Keycloak ist die richtige und am besten begründete
Entscheidung des Papiers (Mitgliedskennung = `sub`, keine Abbildungsregel, kein ADR-0025-Fehler).
Die Auflagen betreffen den **Vorgabeweg**, nicht den neuen.

- **Auflage 3.1 (blockierend) — der Token-Modus hat keine der Schutzmechaniken, die den Pull-Modus
  tragen.** §4.2 macht Token zur Vorgabe. `TokenGroupSynchronizer` (Javadoc): „the user is a member
  of exactly the groups the token names". Es gibt dort **keinen** Trockenlauf, **keine**
  Plausibilitätsschwelle, **keinen** Leerergebnis-Schutz und **keinen** Bestätigungsweg — alle vier
  sind Eigenschaften von `DirectorySyncPlanExecutor`. Konkrete Situation: Am Montag benennt das
  Identitätsmanagement den Gruppenanspruch `Ref50` in `Referat 50` um (kein Fehler, eine
  Pflegemaßnahme). Ab der ersten Anmeldung ist jede Person Mitglied der **neuen** Gruppe und wird aus
  der alten entfernt; die 40 Freigaben, die an der alten Gruppen-ID hängen, wirken für niemanden mehr.
  Der Nutzer meldet „ich finde nichts mehr", ich sehe im Audit 200 Einzelereignisse
  `GROUP_MEMBER_REMOVED` unter dem Akteur `identity-provider` und keinen einzigen Hinweis darauf,
  dass eine wirksame Gruppe leergelaufen ist. Anhang B Nr. 9 kennt die Ursache (`external_id` ist
  bei Token-Gruppen namensbasiert und nicht stabil gegen Umbenennung), zieht daraus aber keine
  Konsequenz. Verlangt, in dieser Reihenfolge: (a) der ADR benennt ausdrücklich, dass Umbenennungen
  im Token-Modus einen Gruppenwechsel ohne Schutzmechanik auslösen; (b) es gibt ein sichtbares
  Signal, wenn eine Gruppe **mit Wirkung** (Grants, Space-Mitgliedschaft, Eigentum) leer läuft —
  siehe Auflage 6.2; (c) das Handbuch sagt Häusern mit gepflegtem Verzeichnis, dass der Pull-Modus
  der empfohlene ist, nicht die Vorgabe.
- **Auflage 3.2 — Untergruppen in Keycloak (§4.3).** Die zitierte API-Beschreibung
  (`GET /groups/{id}/members`, „Returns a list of users, members of the group") sagt nicht, ob
  Mitglieder von Untergruppen enthalten sind; nach meinem Kenntnisstand sind sie es **nicht** —
  **zu prüfen vor der Festlegung.** Das ist keine Feinheit: Keycloak-Gruppen sind hierarchisch
  (§2.5), Verwaltungen modellieren dort `/Haus/Abteilung 5/Referat 50`, und Rechte werden im Haus
  gern an der Abteilung vergeben. Liefert der Konnektor nur direkte Mitglieder, ist die Abteilung in
  OPAA leer, obwohl sie im Verzeichnis 120 Personen hat — und §7.2 erklärt sie zur leeren Gruppe.
  Verlangt: #1817 entscheidet ausdrücklich, ob transitiv oder direkt gelesen wird, und der
  Differenzbericht des ersten Laufs nennt die Zahl der Mitglieder je Gruppe, damit ich das vor dem
  Anwenden sehe.
- **Auflage 3.3 — Gleichnamige Gruppen **eines** Anbieters (§3.3 + §4.3).** Die Herkunftsanzeige
  („Referat 50 · Verzeichnis Haus A") löst die Kollision zwischen zwei Anbietern, nicht die innerhalb
  eines Anbieters. In Keycloak heißt jede zweite Untergruppe „Leitung" oder „Sachbearbeitung". Zwei
  Einträge „Leitung · Verzeichnis Haus A · 4 Mitglieder" im Freigabedialog sind eine Fehlfreigabe,
  die niemand bemerkt. Verlangt: Der Herkunftszusatz trägt den **Pfad** der Quelle, nicht nur den
  Anbieternamen.
- **Auflage 3.4 — Zugangsdaten des Dienstkontos.** §4.3 nennt das Dienstkonto mit
  `view-users`/`query-groups`, sagt aber nicht, wo seine Anmeldedaten liegen. Es gibt dafür bereits
  `io.opaa.security.CredentialsEncryptor` (AES-256-GCM, Schlüssel aus
  `OPAA_CREDENTIALS_ENCRYPTION_KEY`, Format `enc:v1:`). Verlangt: derselbe Pfad, keine zweite
  Mechanik, keine Ablage in `application.yml`. Und ausdrücklich in den ADR: Der Kommentar in
  `CredentialsEncryptor` hält fest, dass eine **Schlüsselrotation im Bestand nicht vorgesehen** ist
  („no key id in the format … a future key rotation is expected to bump this to `enc:v2:`") — ein
  regelmäßig zu wechselndes Dienstkonto-Passwort für den Verzeichniszugriff ist damit an eine
  ungelöste Frage gekoppelt. Ob mein Haus daraus eine harte Anforderung ableitet (BSI IT-Grundschutz
  ORP.4, Identitäts- und Berechtigungsmanagement — konkreter Baustein **zu prüfen**), entscheidet
  der ISB, aber die Frage muss im ADR stehen und nicht erst beim Audit auftauchen.
- **Auflage 3.5 — Lebenszyklus des ausstehenden Plans (§4.2).** Der Bestätigungsweg ist richtig,
  aber unvollständig beschrieben. Vier Festlegungen fehlen, jede mit einem eigenen Schadensfall:
  1. **Der Leerergebnis-Schutz bleibt ein harter Abbruch** und wandert **nicht** in den
     Bestätigungsweg. `ABORTED_EMPTY_RESULT` bedeutet „die Quelle hat nicht geantwortet, wie sie
     soll" — das darf niemand wegklicken.
  2. **Ein neuer Lauf ersetzt den ausstehenden Plan.** Sonst staut sich bei 6-Stunden-Intervall alle
     sechs Stunden ein weiterer Plan, und ich bestätige irgendwann den ältesten.
  3. **Ein Plan wird beim Bestätigen gegen einen frischen Schnappschuss neu gerechnet** oder verfällt
     nach einer festen Zeit. Sonst wende ich am Freitag den Stand vom Dienstag an — ein Entzug, den
     das Verzeichnis inzwischen zurückgenommen hat, wird trotzdem vollzogen. Das ist besonders
     nach einer **Rücksicherung** relevant: Der erste Lauf danach überschreitet regelmäßig die
     Schwelle, weil sich zwei Tage Verzeichnisänderungen auf einmal zeigen.
  4. **Ein ausstehender Plan ist ein lauter Zustand.** Prüferfrage: „Herr Kollege ist am 01.06.
     ausgeschieden, warum hatte sein Konto am 01.09. noch Leserechte auf die Personalbibliothek?"
     Antwort „der Abgleich lag seit dem 01.06. als unbestätigter Plan" ist ein Prüfbefund. Verlangt:
     Alter des ausstehenden Plans in der Statuszeile (`directory_sync_status` trägt bereits
     `last_run_at`/`last_outcome`) und sichtbar auf der Verwaltungsübersicht, nicht nur in einer
     Unterseite.
- **Auflage 3.6 — Nebenwirkung auf „Sicht als".** `DiagnosticImpersonationGrantService#grant` lehnt
  jeden Geltungsbereich ab, der keine `ORG_UNIT`-Gruppe ist („keine Ad-hoc-Gruppe und keine Gruppe
  aus dem Identitätsanbieter"). Mit der Empfehlung „je Anbieter genau ein Mechanismus" (§3.2) folgt:
  In einem Haus, das im Token-Modus bleibt, entstehen **nie** `ORG_UNIT`-Gruppen — die Befugnis
  „Sicht als" ist dort dauerhaft nicht vergebbar. Das steht nirgends im Papier. Verlangt: Der ADR
  entscheidet das bewusst (Geltungsbereich auf Anbietergruppen erweitern **oder** die Einschränkung
  im Handbuch benennen), statt es als Nebenwirkung eintreten zu lassen.

### 3.4 Empfehlung 4 — Interne Gruppen mit Verantwortlichen (§5)

**Urteil: tragfähig mit Auflagen.** Die Delegation ist betrieblich zwingend — Option A ist die
Wirklichkeit, in der ich heute jede Mitgliederänderung als Ticket bekomme. Drei Lücken:

- **Auflage 4.1 (blockierend) — die Sichtbarkeit der Gruppen**liste** ist nicht entschieden.** §5.3
  regelt nur die **Mitglieder**liste. Heute verlangt jede Methode in `GroupController` `SYSTEM_ADMIN`,
  auch `GET /api/v1/groups` — eine Bibliotheksverwalterin kann eine Gruppe also gar nicht finden, um
  sie freizugeben; `AssetGrantService` nimmt nur eine Gruppen-ID entgegen. #1820 braucht für den
  Freigabedialog eine Auswahlliste, und damit wird entschieden, wer **Gruppennamen** sieht. Der Name
  „Disziplinarverfahren 2026" ist das schutzwürdige Datum, nicht erst die Mitgliederliste (vgl. §5.3
  und Auflage 2.4). Verlangt: Der ADR legt fest, wer welche Gruppen listen darf (Vorschlag:
  Anbietergruppen für alle wählbar, interne Gruppen nur für Mitglieder, Verantwortliche und
  Systemverwaltung — mit der Folge, dass eine interne Gruppe erst nach ausdrücklicher Freigabe durch
  ihre Verantwortlichen allgemein wählbar wird).
- **Auflage 4.2 — Verantwortliche brauchen eine Historie, nicht nur Audit-Ereignisse.** §5.3 sagt
  „Ernennung und Entlassung sind eigene Ereignisse". Audit-Ereignisse werden nach der eingestellten
  Frist gelöscht (12–120 Monate, `001-baseline.yaml:1304`); die drei Historientabellen
  (`asset_grant_history`, `group_membership_history`, `library_visibility_history`) überleben nach
  ADR-0016. Prüferfrage im vierten Jahr: „Wer war am 14.03.2027 verantwortlich für die Gruppe, über
  die dieser Zugriff lief?" Verlangt: Verantwortliche mit `valid_from`/`valid_to` wie
  `group_membership_history`.
- **Auflage 4.3 — Wildwuchs interner Gruppen.** Wird `CREATE_INTERNAL_GROUP` an „Alle Konten"
  vergeben (§5.3 stellt das ausdrücklich frei), entstehen bei 1000 Konten in zwei Jahren einige
  hundert Gruppen, von denen die meisten nie ein Grant tragen. Jede erscheint in jeder Auswahl, jede
  ist ein Rechtesubjekt, keine wird je gelöscht — das Aufräumen bleibt an mir hängen und wird nicht
  gemacht. Verlangt (nichts Automatisches, nur Sichtbarkeit): eine Liste interner Gruppen ohne
  Wirkung und ohne aktives Mitglied, mit Alter, in derselben Verwaltungssicht wie die offenen
  Nachfolgen. Löschen bleibt eine Handlung der Verantwortlichen oder der Systemverwaltung.

### 3.5 Empfehlung 5 — Globale Fähigkeiten, eigene Tabelle (§6)

**Urteil: tragfähig mit Auflagen.** Die Trennung von Rang (Rolle) und Menge (Fähigkeit) ist sauber
begründet, und die Migration erhält das Verhalten. Auflagen:

- **Auflage 5.1 — Historientabelle mit `valid_from`/`valid_to` ausdrücklich festschreiben.** §6.3
  sagt „eigene Historientabelle nach ADR-0016". Die Stichtagsauskunft braucht die **Zeitspanne**, wie
  `group_membership_history` sie hat — ein reines Ereignisprotokoll zwingt mich zum Nachspielen.
  Verlangt: im ADR benannt, im Delta-Test geprüft.
- **Auflage 5.2 — „Alle Konten" ist nur dann nachweisbar, wenn die Kontenmenge zum Stichtag
  rekonstruierbar ist.** Die Auskunft „am 01.03. durfte X Konnektorbibliotheken anlegen" lautet bei
  `ALL_ACCOUNTS`: „jedes Konto durfte es". Für den Nachweis brauche ich, dass X am 01.03. ein aktives
  Konto hatte — der Kontozustand ist nach ADR-0033 abgeleitet. Verlangt: Der ADR hält fest, aus
  welcher Quelle die Kontenmenge zum Stichtag belegt wird; wenn das nicht geschlossen ist, ist es
  eine bewusst offene Stelle und keine Überraschung im Audit.
- **Auflage 5.3 — der ausgelieferte Wert muss sichtbar bleiben.** `CREATE_CONNECTOR_LIBRARY` an „Alle
  Konten" ist für die Migration richtig und für den Dauerbetrieb falsch: Konnektorbibliotheken
  erreichen Serverpfade und hinterlegte Zugangsdaten (§6.1 sagt es selbst). Ein ausgelieferter Wert,
  den niemand sieht, bleibt zehn Jahre stehen. Verlangt: In der Verwaltungsübersicht steht der
  aktuelle Zustand jeder Fähigkeit als Klartextzeile („Alle Konten dürfen Konnektorbibliotheken
  anlegen"), damit ich ihn bei der Einführung bewusst bestätige oder ändere. Das ist eine
  Anzeigezeile, kein Assistent.
- **Hinweis (keine Auflage):** Dass „Entzug ohne Neuanmeldung wirkt" (§6.4), trägt, weil je Anfrage
  aus der Datenbank ausgewertet und der Cache nach dem Commit invalidiert wird — und weil ADR-0021
  einen einzigen Prozess voraussetzt. Sollte diese Annahme je fallen, fällt die Zusage mit. Gehört
  als Satz in den ADR.

### 3.6 Empfehlung 6 — Lebenszyklus (§7)

**Urteil: tragfähig mit Auflagen.** Der abgeleitete Zustand ist richtig (siehe Abschnitt 2), die
Schutzregeln in §7.4 sind es auch. Die **Adressatenstufung ohne Frist** genügt einem Prüfer in der
vorgeschlagenen Form **nicht** — aber aus einem anderen Grund, als das Papier annimmt.

- **Auflage 6.1 (blockierend) — ohne Zeitelement gibt es keine Stufung, sondern eine feste
  Zuweisung.** §7.3 setzt drei Stufen und streicht gleichzeitig Frist und Eskalation. Damit erreicht
  ein Fall der Stufe 2 (Asset einer internen Gruppe, Adressat sind ihre Verantwortlichen) die Stufe 3
  (Systemverwaltung als Auffang) **nie** — auch dann nicht, wenn die Verantwortlichen zwei Jahre
  nichts tun. Der Auffang fängt nur das auf, was von vornherein niemandem zugeordnet war. Prüferfrage:
  „Nennen Sie mir alle Datenbestände ohne handlungsfähigen Verantwortlichen." Wenn die Liste der
  Systemverwaltung Stufe 1 und 2 gar nicht enthält, kann ich sie nicht beantworten. **Verlangt, und
  das ist billig:** Die Liste der Systemverwaltung enthält **alle** offenen Nachfolgen ab dem ersten
  Tag, mit Spalte „derzeitiger Adressat" und Spalte „Alter". Keine Frist, keine Mail, keine
  Eskalationslogik — nur Vollständigkeit. Damit ist die Stufung eine Zuständigkeitsangabe und die
  Auffangzuständigkeit jederzeit handlungsfähig, und ich habe die eine Liste, die der Prüfer sehen
  will.
- **Auflage 6.2 (blockierend) — „Gruppe leer" erzeugt kein Signal für Grants.** §7.2, Zeile 3:
  Grants an die leere Gruppe „bleiben, wirken für niemanden"; nur **Eigentum** führt zu „Nachfolge
  offen". Das heißt: In der Freigabeliste einer Bibliothek steht weiterhin „Referat 50 — VIEWER",
  und niemand liest damit. Zwei Prüfsituationen, beide schlecht: (a) Ich bescheinige einem Referat
  Zugriff, den es nicht hat. (b) Nach dem Umbenennungsfall aus Auflage 3.1 sind 40 Freigaben tot und
  nichts zeigt es an. Verlangt: Eine wirksame Gruppe, die leer wird, während sie Grants trägt oder
  Space-Mitglied ist, erscheint in derselben Betriebsliste wie die offenen Nachfolgen (Bezeichnung
  etwa „Freigaben ohne Empfänger"), mit Alter und Zahl der betroffenen Objekte.
- **Auflage 6.3 — wer schreibt den Zeitpunkt der Erstfeststellung?** §7.1 hält richtig fest, dass nur
  der **Vorgang** gespeichert wird („wann der Zustand erstmals festgestellt wurde"). Wenn der Zustand
  abgeleitet ist, gibt es keinen Auslöser, der schreibt — außer jemand ruft die Liste auf. Dann ist
  „Alter" in Wahrheit „seit dem letzten Hinsehen", und die einzige Ersatzgröße für die gestrichene
  Frist ist wertlos. Verlangt: ein benannter, regelmäßiger Auswertungslauf (Intervall in der
  Konfigurationstabelle des Handbuchs), der den Vorgang anlegt und schließt; die Ableitung bleibt die
  Wahrheit, der Lauf schreibt nur den Zeitstempel.
- **Auflage 6.4 — Space-Mitgliedschaften brauchen eine Historie.** Heute hat `space_memberships`
  keine (`001-baseline.yaml:130 ff.`, bestätigt in §1); Änderungen sind nur Audit-Ereignisse
  (`SPACE_MEMBER_ADDED/_ROLE_CHANGED/_REMOVED` in `SpaceService`). Mit #1815 wird eine
  Space-Mitgliedschaft zur **Massenberechtigung** (eine Zeile = 120 Personen). Damit wird das Fehlen
  der Historie zur Nachweislücke, und zwar genau im Objekt, über das künftig der meiste Zugriff läuft.
  Dasselbe gilt für den **Eigentumswechsel** (`knowledge_libraries.owner_user_id`/`owner_group_id`,
  `spaces.owner_id`) — den §7.3 neu erlaubt („jeder wirksame ADMIN darf übertragen", die eine
  Verhaltensänderung des Papiers) und für den es keine Historientabelle gibt, obwohl Eigentum in
  `LibraryAccessService#effectiveRole` `OWNER` bedeutet. Verlangt: Beides bekommt eine
  Historientabelle nach ADR-0016, sonst ist die Stichtagsauskunft nach Ablauf der Audit-Frist nicht
  mehr herstellbar.
- **Zustimmung ausdrücklich:** „Eine Kontosperre wird nie abgelehnt" (§7.4) ist die richtige
  Priorität und muss so bleiben. Und dass eine Gruppe als wirksames `ADMIN` zählt, solange sie ein
  aktives Mitglied hat, beantwortet die offene Frage aus #1815 sauber.

### 3.7 Empfehlung 7 — Diagnose im Gruppenkontext (§8)

**Urteil: tragfähig mit Auflagen.**

- **Auflage 7.1 — Mindestgruppengröße an **aktiven** Konten messen.** §7.2 hält fest, dass
  Mitgliedschaften bei einer Kontosperre **stehen bleiben**. Eine Gruppe mit 20 Mitgliedern, von
  denen 18 gesperrt sind, ist faktisch ein Zwei-Personen-Kontext — passiert jedes Jahr bei
  Projektgruppen. Zählt §8.2 Punkt 3 Mitgliedschaftszeilen, ist die Schwelle wirkungslos. Das Papier
  definiert den handlungsfähigen Verantwortlichen in §7.1 bereits über „mindestens ein **aktives**
  Mitglied" — dieselbe Zählweise gehört in §8.2 und in die Mitgliederzahl, die §3.3 neben dem
  Gruppennamen anzeigen will („23 Mitglieder"): Ich soll nicht an eine Gruppe freigeben, die nur auf
  dem Papier lebt.
- **Auflage 7.2 — die Profilliste ist eine Gruppenliste** und fällt damit unter Auflage 4.1. Wer ein
  Rechteprofil auswählen darf, sieht Gruppennamen.
- **Hinweis:** Die Feststellung in §8.1, dass `target_ref` die Gruppen-ID trägt und damit „kein
  Personenbezug im Protokoll" eine Struktureigenschaft ist, ist für meine Auskunft gegenüber dem
  Datenschutzbeauftragten der wertvollste Satz des Abschnitts. Er sollte wörtlich in den ADR.

### 3.8 Entscheidungsvorlage für den ADR (§10)

**Urteil: tragfähig mit Auflagen.** Sie beantwortet alle sieben Fragen in je einem Satz, und die
Abnahmekriterien des Epics (ADR beantwortet Subjekte, Herkunft, Synchronisation; Bestandsfreigaben
laufen unverändert) sind damit erfüllbar. Was fehlt, sind drei Festlegungen, die keine der sieben
Fragen berührt, die aber jede Installation betreffen:

- **Auflage 8.1 — ein Abschnitt „Migration und ihre Fehlerfälle".** Mindestens: Waisen-Gruppen
  gelöschter Anbieter, Installation ohne Standardanbieter, Reihenfolge der Eindeutigkeits-Constraints,
  und die Feststellung, dass die Baseline keine Rollback-Blöcke hat — also eine
  **Vorabprüfung** im Handbuch (`docs/handbuch/deployment.md`), die ich vor dem Update fahren kann.
- **Auflage 8.2 — die Übertragungsoperation** (Abschnitt 5 dieser Bewertung).
- **Auflage 8.3 — die Nachweisgrundlage klar benennen.** Der Satz „Rechtehistorie und Audit für jede
  hier genannte Änderung" (§10, übergreifende Festlegungen) verwischt den Unterschied, auf den es im
  Audit ankommt: Audit-Ereignisse verfallen nach 12–120 Monaten, Historientabellen nicht. Der ADR
  sollte je Änderungsart **die Tabelle** nennen, die die Stichtagsauskunft trägt — heute sind das
  drei (`asset_grant_history`, `group_membership_history`, `library_visibility_history`), nach diesem
  Papier müssten es sechs sein (plus Fähigkeiten, Space-Mitgliedschaft, Eigentum) und sieben mit den
  Verantwortlichen.

---

## 4. Die drei Punkte, die mir am wichtigsten sind

1. **Der Vorgabeweg ist der ungeschützte (Auflagen 3.1 und 6.2).** Das ganze Papier begründet
   sorgfältig die Schutzmechanik eines Abgleichs, den heute niemand fährt (`NoOpDirectoryClient`),
   und lässt den Weg, den jede Installation fährt, ohne jede Schutzmechanik. Eine Umbenennung im
   Verzeichnis entzieht im Token-Modus organisationsweit Rechte, ohne Schwelle, ohne Trockenlauf,
   ohne Bestätigung und — weil leer gewordene Gruppen mit Grants kein Signal erzeugen — **ohne
   sichtbare Spur außer 200 Einzelereignissen im Protokoll**. Das ist der Vorfall, den ich am
   Montagmorgen bekomme, und ich kann ihn mit dem vorgeschlagenen Modell nicht erkennen, nur
   nachträglich erklären.
2. **Die Migration hat einen belegbaren Abbruchfall, den das Papier selbst beschreibt, aber nicht
   löst (Auflage 2.1).** Anhang B Nr. 12 hält fest, dass heute Gruppen gelöschter Anbieter
   zurückbleiben; §3.1 will genau für diese Gruppen einen Fremdschlüssel mit `RESTRICT` setzen. Ohne
   ausdrückliche Behandlung startet die Anwendung nach dem Update nicht, und die Baseline hat keine
   Rollback-Blöcke. Ein Migrationsfehler, den man vorher kennt, ist ein Testfall — keiner, den man
   nachts findet.
3. **Die Stichtagsauskunft ist nicht geschlossen (Auflagen 4.2, 5.1, 6.4, 8.3).** Ich kann heute für
   Bibliotheksfreigaben und Gruppenmitgliedschaften belegen, wer wann was durfte. Für
   Space-Mitgliedschaft, Eigentum, Systemrolle und künftig Verantwortliche und Fähigkeiten geht das
   nur über das Audit-Protokoll — also nur innerhalb der Aufbewahrungsfrist und nur durch
   Nachspielen. Ausgerechnet #1815 macht die Space-Mitgliedschaft zur Massenberechtigung. Die Frage
   „beweisen Sie mir, dass Frau K. im März 2026 **keinen** Zugriff auf den Space Personal hatte" kann
   ich dann nicht beantworten, und das ist die Frage, die tatsächlich gestellt wird.

---

## 5. Die eine Änderung

**Eine protokollierte Übertragungsoperation „Rechte einer Gruppe auf eine andere Gruppe übertragen".**
Ein Vorgang, der Grants, Space-Mitgliedschaften, Asset-Eigentum und (bei internen Gruppen)
Verantwortliche von Gruppe A nach Gruppe B umhängt — mit Vorschau („12 Berechtigungen an 7
Bibliotheken, Mitglied in 2 Spaces, Eigentümerin von 3 Bibliotheken"), Bestätigung, einem
Audit-Ereignis und einem sauberen Schnitt in der Rechtehistorie (A endet, B beginnt, gleicher
Zeitpunkt, gleicher Vorgangsbezug).

Das Papier erfindet diese Operation bereits — aber nur als Sonderfall für einen Sonderfall (§4.4,
„die Verwaltung bietet je Token-Gruppe ‚Grants auf Verzeichnisgruppe übertragen' an"). Als
allgemeine Operation löst dieselbe Mechanik vier Probleme auf einmal:

- **Reorganisation** (Referat 50 → Referat 52): der Regelfall in meinem Haus, den das Papier
  nirgends behandelt, und ohne diese Operation Handarbeit an hunderten Objekten.
- **Anbieterablösung:** macht den 409 aus §3.4 von einer Sackgasse zu einem Arbeitsauftrag.
- **Wechsel des Mechanismus** Token → Pull: ist dann kein Sonderweg mehr.
- **Nachfolge** (§7.3, Stufe 3): „Übernahme durch eine Person oder Gruppe" ist derselbe Vorgang.

Ohne sie ist jede Verweigerung mit `409` eine Aufforderung, Rechte von Hand zu zerstören und neu zu
vergeben — und irgendwann wird das jemand mit einem `UPDATE` auf der Datenbank tun. Genau dann ist
die Rechtehistorie, für die dieses Projekt ADR-0016 geschrieben hat, nichts mehr wert.

---

## 6. Was ich nicht beurteilen kann

- **Ob `GET /admin/realms/{realm}/groups/{id}/members` Mitglieder von Untergruppen liefert** und ob
  sich das zwischen Keycloak-Versionen unterscheidet. Ich halte es für nicht-transitiv, habe es aber
  nicht geprüft — die Entscheidung in §4.3 hängt daran (Auflage 3.2).
- **Das Lastverhalten des abgeleiteten Zustands „Nachfolge offen"** bei mehreren hundert Assets,
  tausend Konten und fünfzig Gruppen. Das Papier nennt keine Zahlen, und ich habe nicht gemessen.
  Falls die Ableitung teuer wird, entsteht Druck zum gespeicherten Flag — und damit zurück zu dem
  Zustand, der eine Rücksicherung nicht übersteht.
- **Ob der Prüfer meines Hauses die Stufung nach Auflage 6.1 akzeptiert.** Ich kenne die Prüfliste
  nicht. Ich kann nur sagen, was ich vorlegen können muss: eine vollständige Liste mit Alter und
  Adressat. Ob darüber hinaus eine verbindliche Bearbeitungsfrist organisatorisch gefordert wird,
  entscheidet der ISB, nicht dieses Papier.
- **Welche konkrete BSI-Grundschutz-Anforderung** auf das Dienstkonto des Verzeichniskonnektors und
  seine Passwortrotation anzuwenden ist (Baustein ORP.4 ist der naheliegende Ort) — **zu prüfen**,
  bevor jemand daraus eine Anforderung zitiert.
- **Die Höhe von Intervall (6 h), Schwelle (30 %) und Mindestgruppengröße.** §11 lässt sie
  ausdrücklich offen; ich kann sie erst nach einem Pilotbetrieb mit echtem Verzeichnis beurteilen.
- **Ob Häuser mit bezahlter Entra-P1/P2-Lizenz die SCIM-Ablehnung mittragen.** Betrieblich ist Pull
  für mich der bessere Weg; ob die Verweigerung eines fertig bezahlten Bereitstellungswegs
  beschaffungsseitig durchsetzbar ist, ist keine Frage, die ich entscheide.

---

## Teil 2: Personalrat (erste Fassung)

# Bewertung aus Sicht des Personalrats

**Gegenstand:** `docs/discussions/discussion-berechtigungsmodell-gruppen-und-faehigkeiten.md`
(Entwurf vom 19.09.2026, PR #1825, Issue #1809, Epic #1295)
**Perspektive:** Personalvertretung — Eignung zur Leistungs- und Verhaltenskontrolle, Sichtbarkeit
unter Kollegen, Aufbewahrung, Bedingungen für eine Dienstvereinbarung
**Nicht bewertet:** die Vorentscheidungen aus Abschnitt 0 des Papiers und dem Epic-Body. Sie standen
nicht zur Debatte.

---

## 1. Gesamturteil

**Tragfähig mit Auflagen** — das Modell selbst (Nutzer und Gruppe, flach, keine freien Rollen, keine
Schachtelung) ist für uns das günstigste der diskutierten, und an mehreren Stellen argumentiert das
Papier ausdrücklich zu unseren Gunsten. Aber es vergrößert schweigend den personenbezogenen
Datenbestand, den OPAA dauerhaft über jede und jeden Beschäftigten führt, und es lässt genau die drei
Fragen offen, die eine Dienstvereinbarung beantworten muss: **wer die entstehende Rechtehistorie
abrufen darf, wie lange sie bleibt, und ob der Abruf protokolliert wird.**

Die Zahlen dazu sind nachprüfbar: Die Wörter *Stichtag*, *Aufbewahrung* und *Pseudonymisierung*
kommen im gesamten Papier **null Mal** vor. Die einzige Stelle, an der es die Ausweitung der Historie
anordnet, ist ein Nebensatz in der Entscheidungsvorlage (Abschnitt 10, letzter Absatz:
„Rechtehistorie und Audit für jede hier genannte Änderung (Grant, Mitgliedschaft, Verantwortlicher,
Fähigkeit, Übernahme), mit Herkunftstext statt bloßer ID"). Das ist der folgenreichste Satz des
Papiers für die Beschäftigten, und er hat keine eigene Nummer.

Sechs der sieben Empfehlungen sind mit benannten Auflagen mitbestimmungsfähig. **Empfehlung 7
(Diagnose im Gruppenkontext) ist in der vorliegenden Fassung nicht mitbestimmungsfähig**, weil ihre
eigene Schutzregel (Mindestgruppengröße, Abschnitt 8.2 Punkt 3) durch ihre eigene Erweiterung
(Space-Kontext, Abschnitt 8.2 Punkt 1) wieder aufgehoben wird.

---

## 2. Urteil je Empfehlung

### Empfehlung 1 — Vergleich mit anderen Systemen (Abschnitt 2, ADR-Entscheidung 1)

**Mitbestimmungsfähig mit Auflage.**

Wir begrüßen drei Verwerfungen ausdrücklich, weil sie unsere Arbeit erleichtern statt sie zu machen:

- **Die Super-Gruppe wird nicht übernommen** (Abschnitt 2.1): „`SYSTEM_ADMIN` liest in der Suche
  nichts, was ihm nicht freigegeben ist (`readableLibraryIds` ohne Bypass), und ‚Sicht als' folgt aus
  keiner Rolle. Diese Trennung ist eine Zusage an den Personalrat und bleibt." Dass das Papier diese
  Zusage von sich aus benennt, ist selten und richtig.
- **„Aktivste Mitglieder" als Adressaten der Nachfolge werden verworfen** (Abschnitt 2.2, erneut
  Abschnitt 7.3) mit der Begründung, das setze „eine personenbezogene Aktivitätsauswertung voraus,
  die `security-and-compliance.md` (‚kein personenbezogener Auswertungspfad') ausschließt". Das ist
  exakt der Punkt, an dem Microsoft eine Bequemlichkeit über eine Zusage gestellt hat, und das Papier
  tut es nicht.
- **Kein Schreibweg ins Verzeichnis** (Abschnitt 2.1): keine zweite Quelle der Wahrheit über
  Beschäftigtendaten.

**Auflage:** Die Zusage „`SYSTEM_ADMIN` hat keinen Lesebypass" muss im ADR als **prüfbare Invariante**
stehen, nicht als Vergleichsergebnis — mit Nennung der Stelle, die sie trägt
(`LibraryAccessService#readableLibraryIds`), und mit der Feststellung, dass eine spätere
Fähigkeit sie nicht aushebeln darf. Abschnitt 6.2 formuliert die Hälfte davon bereits („Eine
Fähigkeit öffnet einen Anlegepfad, nie einen Inhalt"); dieser Satz gehört wörtlich in den ADR.
Zur Erinnerung an die Gegenseite: `LibraryAccessService#effectiveRole` gibt `SYSTEM_ADMIN` sehr wohl
`OWNER` auf jede Bibliothek — die Zusage gilt also für die Suche, nicht für die
Bibliotheksverwaltung. Der ADR muss diesen Unterschied aussprechen, sonst lesen wir ihn beim nächsten
Streitfall verschieden.

---

### Empfehlung 2 — Gruppenherkunft (Abschnitt 3, ADR-Entscheidung 2)

**Mitbestimmungsfähig mit Auflage.**

Technisch unstrittig; zwei Nebenwirkungen sind es nicht.

**(a) Der Namens-Schnappschuss in der Historie ist eine neue Dauerdatenspur.** Abschnitt 3.3, letzter
Absatz: „Auditeinträge und Rechtehistorie tragen zusätzlich zur Gruppen-ID die Herkunft als Text
(‚Referat 50 (Verzeichnis Haus A)'), damit ein Prüfer sie liest, ohne die Anbietertabelle zu joinen."
ADR-0016 hatte genau das offen gelassen („Ein lesbarer Namens-Schnappschuss wäre eine mögliche
Erweiterung, ist aber nicht Teil dieser Entscheidung").

Die Folge für Beschäftigte: Heute ist eine alte Historienzeile nach Löschung der Gruppe nur noch eine
UUID — sie belegt, *dass* jemand ein Recht hatte, ohne zu sagen, *als was*. Mit dem Schnappschuss
steht der Gruppenname dauerhaft und im Klartext in der Zeile, auch nachdem die Gruppe gelöscht wurde.
Ausgerechnet das Beispiel, mit dem das Papier an anderer Stelle für den Schutz argumentiert — die
Gruppe „Disziplinarverfahren 2026" (Abschnitt 5.3) — bliebe damit als Wortlaut dauerhaft an jedem
ihrer früheren Mitglieder hängen. Die Gruppe lässt sich löschen, ihr Name nicht.

**Auflage:** Der Namens-Schnappschuss wird nur zusammen mit einer Aufbewahrungshöchstdauer für die
Rechtehistorie beschlossen (siehe Bedingung D1). Solange die Historie unbefristet ist, bleibt es bei
der ID.

**(b) Interne Gruppennamen werden für einen weiten Kreis sichtbar.** Abschnitt 3.3 empfiehlt
Namenseindeutigkeit je Organisation für interne Gruppen; #1820 zeigt in der Subjekt-Auswahl Name,
Herkunft **und Mitgliederzahl** („Referat 5 Projektteam · intern · 6 Mitglieder"). Diese Auswahl steht
jedem offen, der eine Freigabe vergeben darf — und die Fähigkeit `CREATE_LIBRARY` wird nach
Abschnitt 6.1 an „Alle Konten" ausgeliefert.

**Auflage:** Beim Anlegen einer internen Gruppe weist die Oberfläche darauf hin, dass **Name und
Herkunft für alle Rechtevergebenden sichtbar** werden. Zusätzlich zur Auflage aus Empfehlung 4 zur
Mitgliederzahl.

Nicht zu beanstanden: „je Anbieter genau ein Gruppenmechanismus" (3.2), „Deaktivieren lässt alles
stehen" (3.4), „Löschen wird verweigert, solange Gruppen wirken" (3.4). Insbesondere Abschnitt 4.4
(„Nichts wird stillschweigend entzogen; der Wechsel ist ein Verwaltungsakt mit Bericht") ist eine
Zusage, die wir in die Dienstvereinbarung übernehmen möchten.

---

### Empfehlung 3 — Synchronisation (Abschnitt 4, ADR-Entscheidung 3)

**Mitbestimmungsfähig mit Auflage.**

Pull statt SCIM ist für uns die bessere Wahl (kein eingehender Schreibpfad auf Beschäftigtendaten),
und die gebaute Schutzmechanik — Leerergebnis-Schutz, Plausibilitätsschwelle 30 %, Trockenlauf,
last-known-good — ist mehr, als die verglichenen Produkte bieten. Das Papier hält sie unverändert
(Abschnitt 4.5).

**Der ungedeckte Punkt ist die automatische Kontosperre.** Abschnitt 2.3 übernimmt von GitLab
„Kontosperre durch den Abgleich (#1818) als Regelweg des Ausscheidens". Ob die Plausibilitätsschwelle
und der Bestätigungsweg aus #1816 auch für Kontosperren gelten, sagt das Papier an keiner Stelle — die
gesamte Schutzmechanik ist dort an Mitgliedschaftsentzügen beschrieben.

**Situation, in der Beschäftigte Nachteile hätten:** Im Verzeichnis wird eine Organisationseinheit
umgezogen oder ein Attribut umgestellt. Der 6-Stunden-Lauf liest 40 Konten als deaktiviert. Die
Mitgliedschaftsentzüge werden von der 30-%-Schwelle abgefangen und warten auf Bestätigung — die
Kontosperren laufen durch. Vierzig Beschäftigte stehen am nächsten Morgen vor einem gesperrten Zugang
mitten im laufenden Vorgang, sehen nur „Zugang verweigert" und wissen nicht, an wen sie sich wenden.

**Auflagen:**
1. Kontosperren aus dem Abgleich unterliegen **derselben Schwelle und demselben Bestätigungsweg** wie
   Mitgliedschaftsentzüge. Der ADR sagt das ausdrücklich; das Abnahmekriterium zu #1818 prüft es.
2. Eine Sperre aus dem Abgleich ist **rückholbar** und hinterlässt keine Lücke in der Historie
   (Abschnitt 7.2 sagt das für Mitgliedschaften bereits zu: „bleiben stehen (reversibel, Historie ohne
   Bruch)").
3. Die betroffene Person erhält bei der nächsten Anmeldung **Grund und Ansprechstelle**, nicht nur
   eine Abweisung.

Die Wahl des ersten Konnektors (Keycloak) bewerten wir nicht — das ist Betriebssache.

---

### Empfehlung 4 — Gruppenverantwortliche (Abschnitt 5, ADR-Entscheidung 4)

**Mitbestimmungsfähig mit Auflage.** Hier liegen die meisten unserer Befunde.

Der Grundansatz ist richtig: Delegation ohne zweites Rechtesubjekt, Verzeichnisgruppen bleiben
schreibgeschützt, jede Änderung mit dem Verantwortlichen als Akteur im Audit, Selbstbeitritt
(Option C) ausdrücklich verworfen. Und Abschnitt 5.3 enthält den einzigen Absatz des Papiers, der
unsere Perspektive von sich aus einnimmt: Mitgliederlisten sehen nur Verantwortliche und
`SYSTEM_ADMIN`, „ein Mitglied sieht die Gruppen, denen es angehört … und deren Größe, nicht die
übrigen Mitglieder. […] hält die Mitgliedschaft in einer Gruppe ‚Disziplinarverfahren 2026' vor
Kollegen verborgen."

Dieser Schutz hält an drei Stellen nicht.

**(a) Die Sichtbarkeit der Verantwortlichen ist einseitig.** Verantwortliche sind „nicht automatisch
Mitglied", dürfen aber „weitere Verantwortliche ernennen und entlassen". Nirgends steht, dass die
Mitglieder erfahren, wer für ihre Gruppe verantwortlich ist.

*Situation:* Frau S. ist Mitglied der internen Gruppe „Projekt Gecko". Ein Kollege wird von einem
anderen Verantwortlichen zum Verantwortlichen ernannt, sieht ab sofort die vollständige
Mitgliederliste und entfernt Frau S. — ihr Leserecht auf die Projektbibliothek endet nach
Abschnitt 7.2 „sofort". Sie erfährt nicht, dass sie entfernt wurde, und nicht, von wem. Das Audit hält
es fest, aber das Audit liest nur der `AUDITOR`.

**Auflage — Symmetrie der Sichtbarkeit:** Wer die Mitgliederliste einer Gruppe sehen darf, ist für
deren Mitglieder namentlich sichtbar. `GET /api/v1/me/groups` liefert je Gruppe die Verantwortlichen.
Aufnahme und Entfernung werden der betroffenen Person in der Oberfläche angezeigt (keine Mail — das
Papier schließt Mail im ersten Schritt bewusst aus, Abschnitt 7.3).

**(b) Die Mitgliederzahl ist für jeden sichtbar, die Mitgliederliste nicht.** Abschnitt 5.3 schützt
die Liste; #1820 zeigt jedem Rechtevergebenden die Zahl. Bei „Referat 50 · 23 Mitglieder" ist das
harmlos. Bei „Schwerbehindertenvertretung · intern · 2 Mitglieder" ist die Zahl zusammen mit dem
Namen bereits die Auskunft.

*Situation:* Eine Sachbearbeiterin legt eine Upload-Bibliothek an (`CREATE_LIBRARY` ist an „Alle
Konten" ausgeliefert), öffnet den Freigabedialog, tippt „Schwer" — und liest die Größe der
Schwerbehindertenvertretung ab. Sie hat nichts falsch gemacht; das Produkt hat es ihr gezeigt.

**Auflage:** Unterhalb der Mindestgruppengröße wird in der Subjekt-Auswahl **keine Zahl** ausgegeben,
sondern „kleine Gruppe" — nach demselben Muster, das `monitoring-and-governance.md` für Auswertungen
festlegt („Werden unterdrückt, nicht angezeigt — einschließlich der Werte, die sich aus anderen
errechnen ließen"). Die volle Zahl sehen Verantwortliche und `SYSTEM_ADMIN`.

**(c) Die Gruppe als Space-Mitglied hebt den Listenschutz praktisch auf.** Dies ist der schwerste
Befund dieses Abschnitts, und das Papier sieht ihn nicht: #1815 macht Gruppen zu Space-Mitgliedern,
#1820 verlangt „Space-Mitgliederliste zeigt Gruppen als eigene Zeilen mit Rolle; bei einer Person ist
erkennbar, ob ihre Rolle direkt oder über eine Gruppe kommt". Damit steht der **Gruppenname** in der
Mitgliederliste jedes Space, in dem die Gruppe ein Recht hält — sichtbar für alle Space-Mitglieder.

*Situation:* Eine interne Gruppe „BEM-Begleitung" wird Mitglied des Spaces „Personalentwicklung",
damit ihre Mitglieder dort lesen können. In der Mitgliederliste steht ab sofort eine Zeile
„BEM-Begleitung · Mitglied". Zeigt die Herleitung zusätzlich je Person, über welche Gruppe ihre Rolle
kommt, liest jeder Kollege im Space die Zugehörigkeit direkt ab. Die Zusage aus Abschnitt 5.3 ist
damit an der Stelle aufgehoben, an der die Gruppe tatsächlich wirkt.

**Auflagen:**
1. Die **Herleitung** („warum sehe ich das", Epic-Body, UI-Folgen) nennt gegenüber anderen als der
   betroffenen Person **nie den Gruppennamen**, sondern nur „über eine Gruppenmitgliedschaft". Den
   Klarnamen sehen: die betroffene Person selbst, die Verantwortlichen dieser Gruppe, `SYSTEM_ADMIN`.
2. Für die Bestände und Gruppen der in `hybrid-retrieval.md`, Leitplanke (e), benannten Stellen —
   **Personalvertretung, Schwerbehindertenvertretung, Gleichstellung, Personalvorgänge** — gilt für
   Gruppen dieselbe Sonderstellung wie für Bibliotheken: nicht auffindbar und nicht in fremden
   Mitgliederlisten aufgeführt, außer für ihre Verantwortlichen. Die Sperre setzt und löst „die
   jeweils zuständige Stelle selbst, nicht die Administration" — derselbe Satz, derselbe Grund.
3. Der Abruf einer **Mitgliederliste durch `SYSTEM_ADMIN`** (nicht durch die Verantwortlichen, die sie
   ohnehin pflegen) ist ein eigenes Audit-Ereignis. Das ist die Bedingung dafür, dass wir der Sicht
   „wo wirkt diese Gruppe" (#1821) zustimmen können: Sie ist objektbezogen und damit unproblematisch,
   sie ist aber einen Klick von der personenbezogenen Rechteübersicht entfernt.

**(d) Eine Klarstellung, die das Papier schuldig bleibt.** „Vor Kollegen verborgen" ist nicht
„verborgen". Abschnitt 5.3 schlägt selbst vor, `CREATE_INTERNAL_GROUP` an eine Gruppe
„Referatsleitungen" zu vergeben; wer anlegt, wird erster Verantwortlicher und sieht die Liste. Das ist
fachlich vertretbar — aber es muss ausgesprochen werden, statt als Kollegenschutz beschrieben zu
werden.

**(e) Verantwortlichkeit gehört nicht in die Rechtehistorie.** Abschnitt 10 ordnet Historisierung für
„Verantwortlicher" mit an. Eine Verantwortlichkeit trägt **kein Leserecht** (Abschnitt 5.3:
Verantwortliche sind nicht automatisch Mitglied) und ist für die Frage „wer konnte am Tag X was
lesen" ohne Bedeutung. Sie ist ein Betriebsrecht der Gegenwart wie die Diagnose-Vollmacht und gehört
nach dem Nachtrag zu ADR-0016 vom 11.09.2026 auf die `CASCADE`-Seite: Ereignis im Audit, das der
Aufbewahrungsfrist unterliegt — **nicht** Historienzeile mit `ON DELETE RESTRICT`, die eine
Kontolöschung für immer blockiert.

---

### Empfehlung 5 — Globale Fähigkeiten (Abschnitt 6, ADR-Entscheidung 5)

**Mitbestimmungsfähig mit Auflage.** Die unproblematischste der sieben.

Positiv, und wir sagen das ausdrücklich, weil wir sonst das Gegenteil sagen: Die Auslieferung ist hier
ausnahmsweise die **offene** („Alle Konten" für `CREATE_SPACE`, `CREATE_LIBRARY`,
`CREATE_CONNECTOR_LIBRARY`; Migration mit vier Zeilen je Organisation, „Nach der Migration verhält
sich jede Installation wie vorher", Abschnitt 6.4). Niemand verliert durch die Umstellung ein Recht,
das er heute hat. Das ist der seltene Fall, in dem die Voreinstellung uns nicht gegen die Dienststelle
arbeiten lässt.

Ebenfalls richtig, Abschnitt 6.2: **„Sicht als" und Vorfallsbereich bleiben Befugnisse und werden nie
Fähigkeiten.** Die Abgrenzung — *Fähigkeit* = unbefristet, an Nutzer/Gruppe/Alle, ohne Gegenstand;
*Befugnis* = befristet, an genau eine Person, mit Gegenstand und Begründungspflicht — ist genau die
Trennung, die wir brauchen. Sie gehört wörtlich in den ADR und in die Dienstvereinbarung.

**Auflagen:**
1. Der **Entzug** einer Fähigkeit von „Alle Konten" ist eine Änderung der Arbeitsbedingungen für alle
   Beschäftigten. `CAPABILITY_GRANTED`/`_REVOKED` (Abschnitt 6.3) muss im ADR ausdrücklich als
   **Governance-Ereignis** benannt werden, damit es im „Auszug für die Personalvertretung"
   (`security-and-compliance.md`) und in der Liste der Governance-Einstellungen erscheint — nicht nur
   als technisches Audit-Ereignis unter vielen.
2. Abschnitt 6.4 verspricht: „bei fehlender Fähigkeit erklärt statt versteckt" (#1820). Das bleibt so.
   Eine Schaltfläche, die stumm verschwindet, erzeugt Rückfragen an uns statt an die Systemverwaltung.
3. Die Historie der Fähigkeitsvergabe (Abschnitt 6.3: „eigene Historientabelle nach ADR-0016,
   Subjektspalten `RESTRICT`") fällt unter Bedingung D1. Sie **verschärft** das
   Kontolöschungsproblem, weil sie eine weitere `RESTRICT`-Spalte gegen `users` zieht.

---

### Empfehlung 6 — Lebenszyklus (Abschnitt 7, ADR-Entscheidung 6)

**Mitbestimmungsfähig mit Auflage.**

Drei Festlegungen sind aus unserer Sicht gut:

- **„Eine Kontosperre wird nie abgelehnt"** (Abschnitt 7.4). Der Zugang wird nie an offenen
  Eigentumsfragen festgemacht — das verhindert, dass jemand beim Ausscheiden noch tagelang „im System
  bleiben muss".
- **Keine Frist, keine Eskalation, keine Mail** (Abschnitt 7.3). Eine Eskalation „nach oben" wäre
  genau die Vorgesetztenkette, die als Auswertungsachse in ein Berechtigungssystem nicht gehört. Die
  Begründung des Papiers ist eine andere (kein Adressat), das Ergebnis ist dasselbe.
- **„Hinweis statt Auswertung"** (Abschnitt 7.3, letzter Absatz): Die Nachfolgeliste darf die
  Verzeichnisgruppen der ausgeschiedenen Person nennen — „das ist Bestandsinformation, keine
  Aktivitätsauswertung". Die Abgrenzung ist sauber gezogen und die M365-Alternative ausdrücklich
  verworfen.

**Auflagen:**
1. Die **Liste offener Nachfolgen ist eine Liste über Objekte, nicht über Personen.** Einstieg über
   das Objekt; der frühere Eigentümer wird je Zeile genannt (sonst ist die Nachfolge nicht
   beurteilbar), aber es gibt **keine Abfrage „was gehörte Person X"** und keine Sortierung oder
   Filterung nach Person. Das ist dieselbe Regel, die `security-and-compliance.md` für die Berichte
   bereits zieht: „Einstieg über das Objekt und einen Zeitraum, nie über eine Person."
2. „Nachfolge offen" friert die Reichweite ein (Abschnitt 7.2). Das darf **nicht** dazu führen, dass
   Beschäftigte ihre laufende Arbeit nicht fortsetzen können; das Papier sagt „Das Objekt bleibt
   nutzbar, Rechte bleiben" — dieser Satz gehört in den ADR und ins Handbuch, sonst wird aus einem
   Verwaltungszustand ein Arbeitshindernis, dessen Druck bei den Betroffenen landet.
3. Die **Nachfolgevorgänge** (Abschnitt 7.1: „Gespeichert wird nur der Vorgang: wann der Zustand
   erstmals festgestellt wurde, wer ihn beendet hat") unterliegen der Aufbewahrungsfrist des
   Protokolls, nicht der Rechtehistorie. Sie sagen nichts über Leserechte aus.

---

### Empfehlung 7 — Diagnose im Gruppenkontext (Abschnitt 8, ADR-Entscheidung 7)

**Nicht mitbestimmungsfähig in der vorliegenden Fassung.**

Das ist keine pauschale Ablehnung. Abschnitt 8.2 Punkt 3 ist der beste Absatz des Papiers:

> „Kleine Gruppen sind Personen. Eine Token-Gruppe mit einem Mitglied oder eine interne Gruppe
> ‚Projektleitung X' mit zwei Mitgliedern ist als Rechteprofil de facto ein Personenkontext — ohne
> Befugnis, ohne Begründung, ohne Protokoll. […] Gruppen unterhalb der Mindestgruppengröße sind kein
> wählbares Rechteprofil."

Dem stimmen wir vorbehaltlos zu. Das Problem ist, dass **Punkt 1 desselben Abschnitts diese Regel
wieder aufhebt.**

**(a) Der Space-Kontext umgeht die Mindestgruppengröße.** Punkt 1 erweitert das Profil um „Gruppe G im
Space S": Suchbereich = im Space assoziierte Bibliotheken ∩ für G lesbare Bibliotheken. Die Schwelle
aus Punkt 3 hängt an **G**, nicht am Schnitt.

*Situation:* Eine Systemverwalterin wählt das Rechteprofil „Referat 50" (23 Mitglieder, weit über
jeder denkbaren Schwelle) und als Space den Projektraum „Vergabeverfahren Rathaussanierung", in dem
aus Referat 50 genau eine Person Mitglied ist. Der Lauf zeigt, was diese eine Person in diesem Space
findet und was nicht — ohne Befugnis „Sicht als", ohne Pflichtbegründung, ohne Protokolleintrag und
ohne Abzug diagnosegesperrter Bibliotheken. Alle vier Schutzmechanismen des Personenkontexts
(`hybrid-retrieval.md`, Leitplanken (c), (d), (e), (f)) greifen nicht, weil formal ein Profil gewählt
wurde. Das ist exakt die Umgehung, die Punkt 3 schließen wollte — eine Ebene höher.

**Auflage (zwingend):** Die Mindestgruppengröße gilt für die **Schnittmenge**, nicht für die Gruppe.
Ein Profil „Gruppe G im Space S" ist nur wählbar, wenn mindestens *Mindestgruppengröße* viele
aktive Mitglieder von G auch Zugang zu S haben. Andernfalls `403` mit dem Hinweis, dass für diese
Sicht der Personenkontext mit Befugnis zu wählen ist. Geprüft wird das zum Zeitpunkt des Laufs, nicht
bei der Anlage des Profils.

**(b) Die Protokollpflicht für Profil-Läufe wird zum zweiten Mal vertagt.** Punkt 4 stellt fest, sie
bleibe „die in `hybrid-retrieval.md` ausdrücklich nicht mitgetroffene Entscheidung", und nennt
Punkt 3 als „das Mindestmaß, ohne das sie irgendwann unausweichlich wird". Sie ist jetzt
unausweichlich: Mit dem Space-Kontext richtet sich ein Profil-Lauf erstmals auf einen konkreten, oft
sehr kleinen Personenkreis.

**Auflage:** Profil-Läufe **mit** Space-Kontext werden protokolliert (ausführende Person, Profil,
Space, Zeitpunkt). Profil-Läufe ohne Space-Kontext bleiben unprotokolliert. Das ist die kleinste
Regel, die die Lücke schließt, und sie kostet eine Zeile je Lauf statt je Abfrage.

**(c) Die Begründung für die Nichtgeltung der Diagnosesperre trägt nur, solange die Seite
Systemverwaltern vorbehalten ist.** Die Klarstellung vom 02.09.2026 in `hybrid-retrieval.md` lässt
diagnosegesperrte Bibliotheken bei Profil-Läufen bewusst **nicht** abziehen, mit der Begründung, die
Administrationsseite sei Systemadministratoren vorbehalten und `effectiveRole` lasse diese Rolle
ohnehin als `OWNER` durch. Das Papier führt Fähigkeiten ein, die Rechte an Nicht-Systemverwalter
vergeben, und sagt zur Diagnoseseite nichts.

**Auflage:** Der ADR stellt ausdrücklich fest, dass die Suchdiagnose `SYSTEM_ADMIN` vorbehalten bleibt
und **keine** Fähigkeit sie öffnet. Wird sie später geöffnet, schaltet dieselbe Änderung die Sperre
(e) auch für Profilläufe scharf — sonst erreicht ein Profil die Bestände der Personalvertretung, der
Schwerbehindertenvertretung und der Gleichstellung, die dort standardmäßig gesperrt sind.

**(d) Zwei Definitionen der Mindestgruppengröße.** `spaces-and-assets.md` (Nutzungstransparenz) legt
fest: „Die Mindestgruppengröße bemisst sich an der Zahl der tatsächlich **nutzenden** Personen, nicht
an der Größe der Organisationseinheit." Abschnitt 8.2 Punkt 3 meint die **Mitgliederzahl**. Für ein
Rechteprofil ist die Mitgliederzahl das richtige Maß — es geht um einen Rechtekontext, nicht um
Nutzung. Aber der ADR muss es aussprechen, sonst haben wir beim ersten Streitfall zwei Lesarten
derselben Zahl.

**(e) Positiv und beizubehalten:** Leitplanke (b) aus `hybrid-retrieval.md` — „Die Diagnose
beantwortet den Jetzt-Zustand, nicht die Vergangenheit. Sie ist ausdrücklich kein
Zugriffshistorien-Nachweis — die Frage ‚worauf hatte X am 3. März Zugriff?' beantwortet sie nicht und
darf nicht mit ihr beantwortet werden." Dieser Satz ist für uns tragend und darf durch das Epic nicht
aufgeweicht werden. Siehe dazu Abschnitt 3 dieser Bewertung.

---

### Entscheidungsvorlage für den ADR (Abschnitt 10)

**Mitbestimmungsfähig mit Auflage.**

Die sieben nummerierten Entscheidungen geben die Abschnitte 2 bis 8 zutreffend wieder; unsere
Auflagen dazu stehen oben. Zu beanstanden ist der **letzte Absatz**, der die übergreifenden
Festlegungen trägt:

> „Dazu die übergreifenden Festlegungen: alles je Organisation (Abschnitt 9); Rechtehistorie und Audit
> für jede hier genannte Änderung (Grant, Mitgliedschaft, Verantwortlicher, Fähigkeit, Übernahme), mit
> Herkunftstext statt bloßer ID; die Typunabhängigkeit der Grants (#1811) als Vorgabe für #1726."

Dieser Nebensatz erweitert die historisierten Quellen von drei (Grants, Gruppenmitgliedschaften,
Reichweitenfelder am Asset — so `security-and-compliance.md`) auf mindestens sechs und fügt jeder
Zeile einen dauerhaften Klartext-Namen hinzu. Er ist die einzige Stelle des Papiers, die den
personenbezogenen Dauerdatenbestand vergrößert, und er hat keine eigene Nummer, keine Begründung und
keine Gegenprüfung.

**Auflage:** Aus dem Nebensatz wird **Entscheidung 8** des ADR, mit eigenem Text, der die drei Fragen
beantwortet, die die Dienstvereinbarung braucht: **wer abruft, wie lange gespeichert wird, ob der
Abruf protokolliert wird** (Bedingungen D1 bis D3 unten).

---

## 3. Was nicht funktioniert — die drei Querschnittsbefunde

Die folgenden drei Punkte gehören zu keiner einzelnen Empfehlung, sondern zum Papier als Ganzem. Sie
sind der Grund für unser „mit Auflagen".

### 3.1 Die Stichtagsauskunft wird gefüllt, aber nicht geregelt

Das Papier erhöht die Zahl der historisierten Rechtequellen. Über die Auskunft, für die diese Daten
entstehen, sagt es **kein Wort**: *Stichtag* kommt null Mal vor, *Aufbewahrung* null Mal,
*Pseudonymisierung* null Mal. Auch *Herleitung* und „warum sehe ich das" kommen nicht als eigener
Gegenstand vor — die einzige Fundstelle (Zeilen 125/126) ist ein Argument **gegen** Projektrollen
(„für jede Auskunft ‚warum sieht X das?' eine Herleitungsstufe mehr"), keine Festlegung, wie die
Herleitung aussieht und wer sie sieht. Der Epic-Body nennt sie ausdrücklich als zu klärende UI-Folge.

Der Stand, den wir dazu vorfinden:

- **Zweck ist personenbezogen formuliert.** ADR-0016: „damit die vollständige Rechtemenge **einer
  Person** zu einem beliebigen Stichtag rekonstruierbar ist".
- **Ein Lesepfad existiert heute nicht.** In `opaa-api/src/main/resources/openapi/opaa-api.yaml` gibt
  es keinen Endpunkt für die Rechtehistorie; im Backend gibt es Schreib- und Repository-Klassen
  (`PermissionHistoryService`, `AssetGrantHistoryRepository`, `GroupMembershipHistoryRepository`),
  aber keinen Controller.
- **Also gilt unser Standardbefund:** Die Daten fallen an, die Auswertung ist nicht geregelt. Was
  gespeichert ist, wird irgendwann abgefragt.

*Situation:* 2032 fragt eine Amtsleitung die Systemverwaltung: „Herr K. behauptet, er habe die
Fachanweisung nie sehen können. Zeigen Sie mir, worauf Herr K. seit 2026 Zugriff hatte." Wird die
Auskunft so gebaut, wie ADR-0016 sie formuliert, ist die Antwort ein Zugehörigkeits- und
Rechteprofil über sechs Jahre — jede Gruppe, jede Fähigkeit, jede Verantwortlichkeit, mit
Klartext-Namen, weil der Herkunfts-Schnappschuss aus Abschnitt 3.3 sie mitführt. Die einzige Regel,
die dagegen steht („Einstieg über das Objekt und einen Zeitraum, nie über eine Person"), steht in
`security-and-compliance.md` nur beim **Bericht „Rechteänderungen an einem Objekt"** — nicht bei der
Stichtagsrekonstruktion selbst.

Für uns ist dies der Punkt, an dem eine Dienstvereinbarung entsteht oder nicht entsteht.

### 3.2 Was die Historie über ausgeschiedene Personen behält: alles, unbefristet

`security-and-compliance.md` sagt zu, die Historie unterliege „einer Höchstdauer, und der
Personenbezug ist ab dem Schreibzeitpunkt pseudonymisiert". Dasselbe Dokument stellt wenige Absätze
später fest, dass beides **nicht umgesetzt** ist:

> „**Aufbewahrungshöchstdauer und Pseudonymisierung der Historie selbst.** Die oben zugesagte
> Pseudonymisierung ab Schreibzeitpunkt ist noch nicht umgesetzt; die Subjektspalten der
> Rechtehistorie sind stattdessen `ON DELETE RESTRICT` gegen die Nutzertabelle — eine Kontolöschung
> ist damit blockiert, solange Rechtehistorie zu diesem Konto existiert."

ADR-0016 beschreibt die Folge unmissverständlich: „eine Kontolöschung ist heute nicht möglich, solange
Rechtehistorie zu diesem Konto existiert — praktisch bei jedem Konto, das je ein Recht hatte oder
Mitglied einer Gruppe war."

*Situation:* Eine Kollegin geht 2027 in den Ruhestand. Ihr Konto lässt sich nicht löschen. Ihr
Klarname bleibt mit allen Gruppenzugehörigkeiten und Rechtezeiträumen unbefristet in der Datenbank —
und mit Abschnitt 3.3 künftig zusätzlich mit den Klartext-Namen dieser Gruppen. Ein Löschbegehren
läuft technisch ins Leere.

Das Papier verschärft diesen Zustand an zwei Stellen, ohne ihn zu erwähnen: Abschnitt 6.3 zieht für
`capability_grants` eine weitere `RESTRICT`-Spalte gegen `users`, und Abschnitt 10 ordnet
Historisierung auch für Verantwortlichkeiten an.

### 3.3 Die Voreinstellung ist hier ausnahmsweise nicht das Problem — die Konfigurierbarkeit ist es

Unser stehender Einwand lautet, Konfigurierbarkeit verlagere Verantwortung auf die Dienststelle. Hier
liegt es umgekehrt: Abschnitt 11 stellt fest, dass „die Werte für Intervall (6 Stunden), Schwelle
(30 %) und Mindestgruppengröße Vorgaben sind, keine Entscheidungen dieses Papiers". Die
Mindestgruppengröße ist nach Abschnitt 8.2 aber die **einzige** Schutzregel zwischen einem
Rechteprofil und einem Personenkontext. Eine Dienststelle, die sie auf 1 oder 2 stellt, schaltet
diesen Schutz aus, und das Produkt lässt es zu.

`spaces-and-assets.md` sieht dafür bereits das richtige Mittel vor: „Das Produkt setzt eine
Voreinstellung **und erzwingt eine Untergrenze**." Diese erzwungene Untergrenze muss auch für die
Profilauswahl gelten und im ADR stehen.

---

## 4. Die eine Änderung

Wenn wir genau eine Sache ändern dürften:

> **Bevor eine weitere personenbezogene Zeile in die Rechtehistorie geschrieben wird, legt der ADR
> fest, wer die Stichtagsauskunft abrufen darf, wie lange die Historie aufbewahrt wird und dass jeder
> Abruf selbst ein Protokollereignis ist.**

Konkret: Aus dem Nebensatz in Abschnitt 10 wird eine eigene Entscheidung 8, und die Umsetzungs-Issues
#1813 (Fähigkeiten-Historie) und #1814 (Verantwortlichen-Historie) hängen von ihr ab — so wie sie
heute von #1810 und #1811 abhängen.

Der Grund für diese Wahl: Alle anderen Auflagen lassen sich später nachziehen. Eine unbefristet
gespeicherte Datenspur lässt sich nicht nachträglich nicht erzeugen.

---

## 5. Was wir nicht beurteilen können

- **Ob das Vorhaben im konkreten Haus mitbestimmungspflichtig ist.** Die einschlägige Frage ist die
  Mitbestimmung bei technischen Einrichtungen, die zur Überwachung von Leistung oder Verhalten
  **geeignet** sind; die Eignung genügt, auf die Absicht kommt es nicht an. Welche Norm gilt, hängt
  davon ab, ob es sich um eine Bundes- oder Landesbehörde handelt — Personalvertretungsrecht ist in
  Bund und Ländern unterschiedlich geregelt. Die Prüfung gehört zur Rechtsstelle, nicht zu uns.
- **Ob die Rechtehistorie datenschutzrechtlich als Protokolldatenbestand mit eigener Rechtsgrundlage
  tragfähig ist.** Das ist Sache des behördlichen Datenschutzbeauftragten. Wir sagen nur, was
  technisch möglich wird.
- **Die angemessene Höhe der Mindestgruppengröße.** Sie folgt aus dem tatsächlichen Zuschnitt der
  Einheiten des jeweiligen Hauses. Wir fordern nur, dass es eine erzwungene Untergrenze gibt.
- **Die technische Machbarkeit und die Kosten der Schnittmengen-Prüfung** aus Empfehlung 7 Auflage
  (a). Falls sie zu teuer ist, ist die Alternative, den Space-Kontext ganz zu streichen — nicht, die
  Prüfung wegzulassen.
- **Die Wahl des ersten Konnektors** (Keycloak vor LDAP vor Graph) und die Frage Pull vs. SCIM aus
  Betriebssicht. Wir stellen nur fest, dass Pull keinen eingehenden Schreibpfad auf Beschäftigtendaten
  öffnet, und halten das für den günstigeren Weg.
- **Ob die in Abschnitt 5.3 unterstellte Delegationslast real ist** — ob also Gruppenverantwortliche
  tatsächlich pflegen, was heute liegen bleibt. Das beurteilt die Sachbearbeitung besser als wir.

---

## 6. Bedingungen für eine Zustimmung

Punkte, die in einer Dienstvereinbarung stehen müssten. Jeder ist so formuliert, dass sich seine
Einhaltung an einem Abnahmekriterium oder einem Test feststellen lässt.

### A — Herleitung und Sichtbarkeit unter Kollegen

| Nr. | Bedingung | Prüfbar an |
|---|---|---|
| A1 | Die Herleitung („warum sehe ich das") nennt gegenüber anderen als der betroffenen Person nie den Gruppennamen, sondern nur „über eine Gruppenmitgliedschaft". Klarname nur für: die betroffene Person, die Verantwortlichen der Gruppe, `SYSTEM_ADMIN`. | #1820, Abnahmekriterium + Komponententest |
| A2 | Unterhalb der Mindestgruppengröße gibt die Subjekt-Auswahl **keine Mitgliederzahl** aus, sondern „kleine Gruppe". | #1820, UI-Referenz + Test |
| A3 | Gruppen der Personalvertretung, Schwerbehindertenvertretung, Gleichstellung und für Personalvorgänge sind nicht auffindbar und erscheinen in keiner fremden Mitglieder- oder Space-Liste; die Kennzeichnung setzt und löst die zuständige Stelle selbst, nicht die Administration. | #1814/#1820; analog `LIBRARY_DIAGNOSTICS_LOCK_CHANGED` |
| A4 | Mitglieder einer internen Gruppe sehen deren Verantwortliche namentlich (`GET /api/v1/me/groups`); Aufnahme und Entfernung werden der betroffenen Person in der Oberfläche angezeigt. | #1814, Abnahmekriterium |
| A5 | Beim Anlegen einer internen Gruppe weist die Oberfläche darauf hin, dass Name und Herkunft für alle Rechtevergebenden sichtbar werden. | #1821, Text |
| A6 | Der Abruf einer Mitgliederliste durch `SYSTEM_ADMIN` ist ein eigenes Audit-Ereignis (nicht der Abruf durch Verantwortliche). | `AuditEventType`, Integrationstest |

### B — Diagnose

| Nr. | Bedingung | Prüfbar an |
|---|---|---|
| B1 | Gruppen unterhalb der Mindestgruppengröße sind kein wählbares Rechteprofil (Übernahme von Abschnitt 8.2 Punkt 3, unverändert). | `SearchDiagnosisService`, Test |
| B2 | Beim Profil mit Space-Kontext gilt die Mindestgruppengröße für die **Schnittmenge** aus Gruppenmitgliedern und Space-Zugang, geprüft zum Zeitpunkt des Laufs; sonst `403` mit Verweis auf den Personenkontext. | Nacharbeit an `SearchDiagnosisService` nach #1815, Test |
| B3 | Profil-Läufe **mit** Space-Kontext werden protokolliert (ausführende Person, Profil, Space, Zeitpunkt). | Audit-Ereignis, Test |
| B4 | Die Suchdiagnose bleibt `SYSTEM_ADMIN` vorbehalten; keine Fähigkeit öffnet sie. Wird sie geöffnet, gilt die Diagnosesperre (Leitplanke (e)) im selben Schritt auch für Profil-Läufe. | ADR-Text + Rechteprüfung im Controller |
| B5 | Die Mindestgruppengröße hat eine vom Produkt **erzwungene Untergrenze**; sie ist nicht auf 1 oder 2 einstellbar. | Konfigurationsvalidierung, Test |
| B6 | Leitplanke (b) bleibt unverändert: Die Diagnose ist kein Zugriffshistorien-Nachweis. | `hybrid-retrieval.md`, unverändert |

### C — Synchronisation und Kontosperre

| Nr. | Bedingung | Prüfbar an |
|---|---|---|
| C1 | Kontosperren aus dem Verzeichnisabgleich unterliegen derselben Plausibilitätsschwelle und demselben Bestätigungsweg wie Mitgliedschaftsentzüge. | #1818 in Verbindung mit #1816, Abnahmekriterium |
| C2 | Eine Sperre aus dem Abgleich ist rückholbar; die Historie bleibt ohne Bruch. | #1818, Test |
| C3 | Die betroffene Person erhält bei der nächsten Anmeldung Grund und Ansprechstelle, nicht nur eine Abweisung. | #1818, UI-Text |
| C4 | Beim Wechsel des Gruppenmechanismus wird nichts stillschweigend entzogen; der Wechsel ist ein Verwaltungsakt mit Differenzbericht (Übernahme von Abschnitt 4.4, unverändert). | #1816 |

### D — Rechtehistorie, Stichtagsauskunft, Aufbewahrung

| Nr. | Bedingung | Prüfbar an |
|---|---|---|
| D1 | **Aufbewahrungshöchstdauer** für die Rechtehistorie mit automatischer Löschung nach Ablauf, als Governance-Einstellung mit erzwungener Obergrenze. Ohne sie wird keine weitere personenbezogene Historienquelle angeschlossen — betrifft #1813 (Fähigkeiten) und #1814 (Verantwortliche). | Neue Entscheidung 8 im ADR; Migrationstest |
| D2 | Der **Personeneinstieg** in die Stichtagsauskunft („worauf hatte Person X am Tag Y Zugriff") ist nur mit einer eigenen, befristeten und begründeten Befugnis zulässig — nach dem Muster des Vorfallsbereichs (`audit_incident_scope_grants`: Person, Zeitraum, Zweck, Vier-Augen-Freigabe zweier `AUDITOR`). Der objektbezogene Einstieg („wer durfte Bibliothek Z im März lesen") bleibt ohne Befugnis möglich. | ADR-Entscheidung 8; Rechteprüfung im künftigen Controller |
| D3 | **Jeder Abruf der Rechtehistorie ist selbst ein Protokollereignis**, einschließlich abgewiesener Versuche — analog `AUDIT_LOG_ACCESSED` („Any read, evaluation or export of audit data, including rejected attempts"). | `AuditEventType`, Integrationstest |
| D4 | Die Stichtagsabfrage trägt ein **verpflichtendes, begrenztes Zeitfenster** und eine Seitenobergrenze; eine zu weite Anfrage wird abgelehnt, nicht zurechtgestutzt — wie bereits für das Protokoll festgelegt (`AuditFrom`: höchstens 92 Tage, „not wide enough to serve as a disguised full-history extract"; Seitenindex über 49 wird abgelehnt). | OpenAPI-Spezifikation, `TransportStatusCodeSpecificationTest` |
| D5 | Der **Herkunfts-Namensschnappschuss** in der Historie (Abschnitt 3.3) wird nur zusammen mit D1 beschlossen. Ohne Aufbewahrungsfrist bleibt es bei der ID. | ADR-Entscheidung 2 |
| D6 | **Verantwortlichkeit an einer internen Gruppe ist ein Betriebsrecht der Gegenwart**, kein Historienartefakt: Audit-Ereignis unter der Protokollfrist, keine `RESTRICT`-Historienzeile. Sie trägt kein Leserecht und ist für die Stichtagsfrage ohne Bedeutung. | ADR-Entscheidung 4 + 8; Changeset zu #1814 |
| D7 | Die **Pseudonymisierung** der Historie (#391/#395) ist benannte Voraussetzung jedes weiteren personenbezogenen Historienpfads, nicht ein unbefristetes Follow-up. | ADR-Entscheidung 8, mit Issue-Verweis |

### E — Nachfolge und Fähigkeiten

| Nr. | Bedingung | Prüfbar an |
|---|---|---|
| E1 | Die Liste offener Nachfolgen ist objektbezogen. Kein Personeneinstieg, keine Sortierung oder Filterung nach Person — dieselbe Regel wie für die Berichte („Einstieg über das Objekt und einen Zeitraum, nie über eine Person"). | #1819, Abnahmekriterium |
| E2 | „Nachfolge offen" lässt laufende Arbeit unberührt: Das Objekt bleibt nutzbar, bestehende Rechte bleiben; nur die Reichweite ist eingefroren. | #1819, Abnahmekriterium (bereits vorhanden) |
| E3 | Keine automatische Frist, keine Eskalation an Vorgesetzte, keine Mail im ersten Schritt (Übernahme von Abschnitt 7.3, unverändert). | ADR-Entscheidung 6 |
| E4 | Die Nachfolgeliste nennt Bestandsinformation (Verzeichnisgruppen der ausgeschiedenen Person), nie Aktivitätsdaten; „aktivste Mitglieder" bleibt verworfen (Übernahme von Abschnitt 7.3, unverändert). | ADR-Entscheidung 6 |
| E5 | `CAPABILITY_GRANTED`/`_REVOKED` ist ein **Governance-Ereignis** und erscheint im Auszug für die Personalvertretung, nicht nur im technischen Audit. | ADR-Entscheidung 5; `security-and-compliance.md` |
| E6 | Fähigkeiten öffnen nie einen Inhalt, nur einen Anlegepfad; „Sicht als" und Vorfallsbereich werden nie Fähigkeiten (Übernahme von Abschnitt 6.2, unverändert). | ADR-Entscheidung 5; Enum-Prüfbedingung |
| E7 | `SYSTEM_ADMIN` behält in der Suche keinen Lesebypass (`readableLibraryIds`); der Unterschied zu `effectiveRole` wird im ADR ausgesprochen. | ADR-Entscheidung 1; bestehender Test |

### F — Verfahren

| Nr. | Bedingung |
|---|---|
| F1 | Vor dem Rollout legt die Dienststelle die **Auskunft über die Datenerhebung** vollständig vor (welche personenbeziehbaren Felder, in welcher Granularität, zu welchem Zweck, wie lange) — einschließlich der in diesem Epic neu entstehenden Tabellen. |
| F2 | Vor dem Rollout erhält die Personalvertretung einen **Testzugang**, um die Zusagen A1 bis A6 und B1 bis B6 selbst nachzuvollziehen, statt sie zu glauben. Beides ist in `security-and-compliance.md` bereits vorgesehen; wir nehmen es beim Wort. |
| F3 | Änderungen an den Werten für Mindestgruppengröße, Abgleichintervall, Plausibilitätsschwelle und Aufbewahrungsfristen sind protokollpflichtig und der Personalvertretung zugänglich. |
| F4 | Die Auflagen aus diesem Bericht, denen der Maintainer nicht folgt, werden im Papier (Abschnitt 12.2, „Übernommen / zurückgewiesen") **einzeln und begründet** als zurückgewiesen ausgewiesen. Eine stillschweigende Nichtübernahme werten wir als offenen Punkt. |

---

## 7. Fundstellenverzeichnis der Kritikpunkte

| Befund | Fundstelle |
|---|---|
| Historie-Ausweitung ohne Regelung | Papier, Abschnitt 10, letzter Absatz |
| *Stichtag*, *Aufbewahrung*, *Pseudonymisierung* kommen nicht vor | Papier, gesamtes Dokument (je 0 Treffer) |
| Herleitung nur als Argument gegen Projektrollen, nicht als Festlegung | Papier, Zeilen 125–126 |
| Namens-Schnappschuss in der Historie | Papier, Abschnitt 3.3, letzter Absatz; Abgrenzung in ADR-0016, „Schwieriger" |
| Kontosperre durch den Abgleich ohne eigene Schwelle | Papier, Abschnitt 2.3; Schutzmechanik in Abschnitt 4.2 |
| Mitgliederliste geschützt, Mitgliederzahl nicht | Papier, Abschnitt 5.3 gegen #1820, UI-Referenz |
| Gruppenname in der Space-Mitgliederliste | Papier, Abschnitt 5.3 gegen #1815/#1820 |
| Verantwortliche ohne Gegensichtbarkeit | Papier, Abschnitt 5.3, Aufzählung „Verantwortliche dürfen" |
| Verantwortlichkeit in der Rechtehistorie | Papier, Abschnitt 10 gegen ADR-0016, Nachtrag vom 11.09.2026 |
| Weitere `RESTRICT`-Spalte gegen `users` | Papier, Abschnitt 6.3, Option 2 |
| Space-Kontext hebt die Mindestgruppengröße auf | Papier, Abschnitt 8.2, Punkt 1 gegen Punkt 3 |
| Protokollpflicht für Profil-Läufe erneut vertagt | Papier, Abschnitt 8.2, Punkt 4; Abschnitt 11 |
| Diagnosesperre gilt nicht für Profil-Läufe | `hybrid-retrieval.md`, Klarstellung vom 02.09.2026 zu Leitplanke (e) |
| Zwei Definitionen der Mindestgruppengröße | Papier, Abschnitt 8.2, Punkt 3 gegen `spaces-and-assets.md`, Nutzungstransparenz |
| Mindestgruppengröße ohne erzwungene Untergrenze im Papier | Papier, Abschnitt 11, letzter Punkt gegen `spaces-and-assets.md` |
| Aufbewahrung und Pseudonymisierung nicht umgesetzt | `security-and-compliance.md`, „Noch offen, bewusst nicht Teil dieser Ausbaustufe" |
| Kontolöschung blockiert | ADR-0016, „Konsequenzen / Schwieriger" |
| Kein Lesepfad für die Historie vorhanden | `opaa-api.yaml` (kein Endpunkt), `io.opaa.library` (kein Controller) |
| Vorbild für D3 | `AuditEventType.AUDIT_LOG_ACCESSED` |
| Vorbild für D4 | `opaa-api.yaml`, Parameter `AuditFrom`, `AuditPage` |
| Vorbild für D2 | `audit_incident_scope_grants` (Person, Zeitraum, Zweck, Vier-Augen-Freigabe) |

---

*Diese Bewertung ist beratend. Sie ersetzt weder die rechtliche Prüfung der Mitbestimmungspflicht noch
die Stellungnahme des behördlichen Datenschutzbeauftragten. Wir lehnen nichts pauschal ab: Sechs der
sieben Empfehlungen sind mit den oben benannten Auflagen zustimmungsfähig, und mehrere Festlegungen des
Papiers — die Verwerfung der Super-Gruppe, die Verwerfung der Aktivitätsauswertung bei der Nachfolge,
die Trennung von Fähigkeit und Befugnis, die Mindestgruppengröße beim Rechteprofil — würden wir
andernfalls selbst fordern müssen.*

---

## Teil 3: Referatsleitung (erste Fassung)

# Stakeholder-Bewertung: Referatsleitung

Zu: Diskussionspapier „Berechtigungsmodell — Gruppenherkunft, Synchronisation, globale Fähigkeiten
und Lebenszyklus" (Issue #1809, Epic #1295, Entwurf vom 19.09.2026, PR #1825).
Der Rahmen aus Abschnitt 0 des Papiers gilt als gesetzt und wird hier nicht neu verhandelt.

## Gesamturteil

**Tragfähig mit Auflagen.** Das Modell beantwortet die Kernfrage „wer haftet für eine falsche
Freigabe" für Personen und für interne Gruppen sauber — für Verzeichnisgruppen und
referatsübergreifende Querschnittsgruppen bleibt eine Verantwortungslücke offen, die das Papier an
zwei Stellen selbst benennt (Abschnitt 7.3, Abschnitt 11), aber nicht schließt, sondern ausdrücklich
auf später verschiebt.

---

## Urteile je Empfehlung

### 1. Vergleich mit anderen Systemen (Abschnitt 2)

**Tragfähig.** Die Trennung „`SYSTEM_ADMIN` liest nichts, was ihm nicht freigegeben ist" (Abschnitt
2.1, Abschnitt 6.2) ist aus meiner Sicht der wichtigste Einzelpunkt im ganzen Papier: Sie verhindert,
dass die Systemverwaltung faktisch zur Super-Gruppe wird, die mein Referatswissen mitlesen kann, ohne
dass ich das freigegeben habe. Ebenso richtig ist der Verzicht auf das Entra-Muster „aktivste
Mitglieder" als Nachfolge-Adressat (Abschnitt 2.2, Abschnitt 7.3) — eine personenbezogene
Aktivitätsauswertung wäre für mich als Referatsleitung ein Mitbestimmungsproblem, kein Komfortgewinn.
Keine Auflage.

### 2. Gruppenherkunft (Abschnitt 3)

**Mit Auflage.** `provider_id` als echter Fremdschlüssel und „Löschen wird verweigert, solange
Gruppen wirken" (Abschnitt 3.4) sind genau das, was ich für eine Stichtagsauskunft brauche: Eine
Gruppe kann nicht durch das Löschen ihres Anbieters in einen Zustand fallen, dessen Herkunft sich
später nicht mehr rekonstruieren lässt. Was fehlt: Das Papier beschreibt in Abschnitt 3 nur, **wie**
eine Gruppe angezeigt wird, nicht, was passiert, wenn ihre **Mitgliederzahl wächst**. Da die Gruppe
laut Rahmenentscheidung als Subjekt am Grant bleibt „damit Gruppenänderungen durchschlagen" (Abschnitt
0, Punkt 2), wächst der Leserkreis jeder Freigabe an diese Gruppe automatisch mit — ohne dass irgendwer
das entscheidet oder auch nur erfährt. Abschnitt 11 nennt das ausdrücklich: „Benachrichtigung der
Autoren bei wesentlicher Erweiterung des Leserkreises durch Gruppenzuwachs … bewusst ausgenommen; das
Modell ändert daran nichts." Für mich heißt das: Wenn ich eine Bibliothek an „Referat 50" freigebe und
zwei Jahre später zwei Abteilungen zusammengelegt werden, verdoppelt sich mein Leserkreis, ohne dass
ich es merke — bis ein Prüfer fragt, warum eine fachfremde Person Zugriff hatte.
**Auflage:** Der ADR muss festlegen, dass jede Freigabe an eine Gruppe (Space-Mitgliedschaft wie
Asset-Grant) mindestens eine passive Signalquelle für den Grant-Geber bekommt — und sei es nur eine
Zeile „Referat 50: 23 → 41 Mitglieder seit letzter Ansicht" in der jeweiligen Verwaltungssicht der
Bibliothek/des Space. Es muss keine Zustimmungspflicht sein (das würde die Vorentscheidung „Gruppe
bleibt Subjekt" aufweichen), aber eine Nulllösung ist für eine Stelle, die für den Inhalt geradesteht,
nicht hinnehmbar.

### 3. Synchronisation (Abschnitt 4)

**Mit Auflage.** Token als Vorgabe plus optionaler zeitgesteuerter Pull ist vernünftig, weil er ohne
Konfiguration funktioniert. Für die Nachvollziehbarkeit über Jahre ist aber relevant: Bei reinem
Token-Abgleich wird eine Mitgliedschaftsänderung erst **beim nächsten Login der betroffenen Person**
historisiert (Abschnitt 4.1, Zeile „T"). Für eine Person, die selten oder nie in OPAA arbeitet, aber
über eine Gruppe Leserechte auf mein Referatswissen trägt, entsteht eine Lücke zwischen dem Datum der
tatsächlichen Verzeichnisänderung und dem Datum, an dem OPAA sie bemerkt. Wird mein Referat zwei Jahre
später gefragt „wer konnte am 3. März auf dieses Dokument zugreifen", kann ich das für Token-Gruppen
nur so genau beantworten, wie die letzten Logins der Mitglieder es zulassen — nicht auf den Tag genau.
**Auflage:** Für jede Gruppe, die Grant-Subjekt auf einer Bibliothek mit Freigabestufe „Referat/Space"
oder höher ist, sollte der ADR den zeitgesteuerten Pull (Abschnitt 4.2, „P") zur Vorbedingung machen,
nicht zur Kür — sonst ist die in Abschnitt 4.5 zugesagte Rechtehistorie für genau die Fälle lückenhaft,
in denen ich sie am nötigsten habe. Alternativ: Der Fall „Stichtagsauskunft für eine Token-Gruppe ohne
Pull" gehört mit seiner tatsächlichen Genauigkeit (nur Login-Zeitpunkte) explizit ins Handbuch, damit
ich weiß, was ich zusagen darf und was nicht.

### 4. Pflege interner Gruppen / Gruppenverantwortliche (Abschnitt 5)

**Mit Auflage.** „Letzter Verantwortlicher kann sich nicht entfernen" und die namentliche
Audit-Zuordnung jeder Mitgliederänderung (Abschnitt 5.3) sind genau das, was ich für Verantwortungsklarheit
brauche, solange die Gruppe **innerhalb** eines Referats bleibt. Für die im Auftrag ausdrücklich
genannten **referatsübergreifenden Querschnittsgruppen mit Verantwortlichen aus mehreren Referaten**
öffnet das Papier aber eine Lücke, die es selbst nicht schließt: Verantwortliche „ernennen und entlassen
weitere Verantwortliche" (Abschnitt 5.3) ohne jeden Bezug zu den Referaten, deren Ressourcen über diese
Gruppe erreichbar werden. Wenn meine Bibliothek an die Gruppe „Projekt Gecko" freigegeben ist und ein
Verantwortlicher aus einem anderen Referat dort einen neuen Kollegen aufnimmt, erfahre ich das nicht —
und ich kann es nicht einmal nachträglich prüfen: Laut Abschnitt 5.3 sieht „die vollständige
Mitgliederliste" nur, wer selbst Verantwortlicher oder `SYSTEM_ADMIN` ist. Als Eigentümerin der
Bibliothek, die diese Gruppe als Grant-Ziel trägt, sehe ich in der Freigabeansicht laut Abschnitt 3.3
nur die **Anzahl** („23 Mitglieder"), nicht die Namen. Das heißt konkret: Ich gebe frei, ohne je
verlässlich zu wissen, wer aktuell lesen kann — genau die Frage, an der ich laut meinem Rollenprofil
ein Konzept messe.
**Auflage:** Wer als Bibliotheks- oder Space-`OWNER`/`MANAGER` einer Gruppe eine Rolle einräumt, muss
mindestens die aktuelle Mitgliederliste dieser Gruppe einsehen können — unabhängig davon, ob er selbst
Verantwortlicher ist. Die in Abschnitt 5.3 begründete Geheimhaltung (Beispiel „Disziplinarverfahren
2026") ist für **interne**, im Verzeichnis nicht abgebildete Sondergruppen nachvollziehbar, darf aber
nicht pauschal für jede Gruppe gelten, die als Grant-Subjekt auf meinem Bestand sitzt.

### 5. Globale Fähigkeiten (Abschnitt 6)

**Tragfähig mit Auflage.** Die Trennung Fähigkeit (unbefristet, ohne Gegenstand) vs. Befugnis
(befristet, personengebunden, mit Gegenstand) in Abschnitt 6.2 ist sauber und verhindert, dass „Sicht
als" oder der Vorfallsbereich über eine Gruppe an beliebig viele Personen durchgereicht werden — das
wäre sonst mein größtes Kontrollproblem gewesen. Auffällig ist die Auslieferung von
`CREATE_CONNECTOR_LIBRARY` an „Alle Konten" (Abschnitt 6.1): Das Papier begründet selbst, dass
Konnektorbibliotheken „Serverpfade und Zugangsdaten erreichen" und ein Haus sie „typisch enger vergeben"
will als Upload-Bibliotheken — liefert sie aber trotzdem offen aus, mit dem alleinigen Argument
Bestandskompatibilität. Für mich bedeutet das: Jede Person in der Organisation — auch aus einem
fremden Referat — kann eine Konnektorbibliothek auf eine externe Quelle anlegen und sie anschließend an
eine Gruppe freigeben, an der auch meine Mitarbeitenden hängen, ohne dass irgendjemand die fachliche
Qualität dieser Quelle geprüft hat. Das ist exakt der Fall „Werkzeuge aus anderen Referaten, deren
fachliche Qualität ich nicht geprüft habe" aus meinem Bewertungsraster.
**Auflage:** Der ADR sollte die Empfehlung ergänzen, `CREATE_CONNECTOR_LIBRARY` im Betriebshandbuch
ausdrücklich als ersten Kandidaten zu nennen, den eine Organisation nach der Migration auf eine benannte
Gruppe einschränkt — nicht nur als technische Möglichkeit erwähnen (wie in Abschnitt 6.1 knapp
angedeutet), sondern als Handlungsempfehlung mit Begründung, sonst bleibt die im Papier selbst erkannte
Risikodifferenzierung folgenlos.

### 6. Lebenszyklus (Abschnitt 7)

**Mit Auflage — das ist der gewichtigste Punkt der Bewertung.** Die Fragen aus meinem Auftrag treffen
hier den größten Widerspruch im Papier:

- *Wer ist Adressat, wenn eine Gruppe als einziger Space-Eigentümer leer wird?* Antwort laut Abschnitt
  7.3, gestufte Zuständigkeit: Ist die Gruppe eine **Verzeichnisgruppe** (kein interner Verantwortlicher
  existiert), fällt der Fall in Stufe 3 — „Systemverwaltung als Auffangzuständigkeit über die Liste
  offener Nachfolgen". Das ist der Regelfall für genau die Gruppen, die laut Abschnitt 3.4 „Gruppen-
  Eigentum ist weiterhin der Regelfall für zentral gepflegte Bestände" am häufigsten Space- oder
  Bibliothekseigentümer sind.
- *Reicht „Systemverwaltung über die Liste offener Nachfolgen, ohne Frist"?* **Nein, nicht für den
  Regelfall Verzeichnisgruppe.** Das Papier widerlegt sich hier selbst: In derselben Tabelle (Abschnitt
  7.3) wird genau diese Lösung als Schwäche der ersten Option benannt — „im dritten Jahr eine Liste,
  die niemand abarbeitet, weil die Systemverwaltung den Fachbezug nicht kennt" — und trotzdem als
  Auffangzuständigkeit für den mit Abstand häufigsten Fall (Verzeichnisgruppe ohne Verantwortliche)
  gewählt. Für mich als Referatsleitung heißt das: Ein Space oder eine Bibliothek meines Referats kann
  über Jahre als „Nachfolge offen" weiterlaufen — nutzbar, durchsuchbar, mit eingefrorener aber
  bestehender Leseberechtigung —, ohne dass ich, deren fachlicher Inhalt mich betrifft, davon je
  erfahre. Genau das ist der Fall aus meinem Bewertungsraster: „Wenn niemand namentlich für eine
  gemeinsame Wissensquelle zuständig ist, veraltet sie, und irgendwann beruft sich jemand auf eine
  überholte Vorschrift" — nur dass hier nicht einmal „niemand zuständig" sichtbar ist, weil das Objekt
  weiterläuft wie gewohnt.
- Positiv: Der Schutz des letzten wirksamen `ADMIN` (Abschnitt 7.4) und die Regel „eine Kontosperre wird
  nie abgelehnt" sind richtig priorisiert — Zugang vor Zuständigkeit ist die richtige Reihenfolge.

**Auflage:** Zwei Ergänzungen, bevor das für Verzeichnisgruppen-Eigentum produktiv geht:
1. Jedes Objekt im Zustand „Nachfolge offen" trägt eine für **jeden Nutzer sichtbare** Kennzeichnung
   dort, wo er auf den Inhalt trifft (Suchergebnis, Chat-Quellenverweis, Bibliotheksübersicht) — nicht
   nur in der Verwaltungsliste der Systemverwaltung. Sonst zitiert ein Mitarbeiter meines Referats eine
   Quelle, ohne zu wissen, dass sie seit zwei Jahren niemand mehr fachlich verantwortet.
2. Eine erste Alterungsschwelle (Vorschlag: an der bestehenden 12-Monats-Frist für „Sicht als"-Befugnisse
   orientiert) löst keine automatische Eskalation aus, aber eine **Pflichtsichtung** durch die
   Systemverwaltung mit dokumentiertem Ergebnis („geprüft, weiterhin offen" oder „übernommen"). Das ist
   kein Widerspruch zu „keine Frist, keine Eskalation" aus Abschnitt 7.3 — es ersetzt keine fachliche
   Zuständigkeit, sondern verhindert nur, dass eine Zeile in der Liste zehn Jahre unberührt bleibt.

### 7. Diagnose im Gruppenkontext (Abschnitt 8)

**Tragfähig.** Dass das Rechteprofil eine Gruppe bleibt und Gruppen unterhalb der Mindestgruppengröße
kein wählbares Profil sind (Abschnitt 8.2, Punkt 3), schließt die Umgehung „Personensicht über eine
Zwei-Personen-Gruppe tarnen" — für mich weniger ein Kontrollpunkt als ein Vertrauenspunkt gegenüber dem
Personalrat, den ich als Referatsleitung mittrage. Keine Auflage aus meiner Perspektive.

---

## Zur Entscheidungsvorlage für den ADR (Abschnitt 10)

Die Vorlage übernimmt die Formulierungen der Einzelabschnitte nahezu wörtlich — das ist ehrlich, denn
sie übernimmt damit auch deren Lücken. Punkt 6 der Vorlage schreibt „keine Frist, keine Eskalation,
keine Mail im ersten Schritt" fest, ohne den in Abschnitt 7.3 selbst notierten Einwand
(„eine Liste, die niemand abarbeitet") noch einmal gegen diese Festlegung zu halten. Wenn dieser ADR
verabschiedet wird, ohne die Auflagen zu Punkt 2, 4 und 6 aufzunehmen, bekommt „Nachfolge offen" für
Verzeichnisgruppen-Eigentum den Status einer akzeptierten Dauerlücke — das sollte der ADR als bewusste
Entscheidung ausweisen, nicht stillschweigend vererben.

---

## Die drei Punkte, die ich vor einer Einführung geklärt haben will

1. **Sichtbarkeit der tatsächlichen Mitgliederliste für den Grant-Geber.** Wer als Bibliotheks- oder
   Space-Verantwortlicher einer Gruppe eine Rolle einräumt, muss wissen können, wer aktuell darüber
   Zugriff hat — nicht nur eine Mitgliederzahl (Abschnitt 3.3, Abschnitt 5.3). Ohne das gebe ich frei,
   ohne zu wissen, an wen.
2. **Ein Signal bei Gruppenzuwachs**, und sei es nur ein passiver Hinweis in der Freigabeverwaltung,
   bevor Verzeichnisgruppen breit als Space-Mitglied und Bibliotheksberechtigte eingeführt werden
   (Abschnitt 3, Abschnitt 11). Die heutige Antwort — „das Modell ändert daran nichts" — ist für eine
   Stelle, die für den Inhalt einer Freigabe haftet, keine Antwort.
3. **Eine für Nutzer sichtbare Kennzeichnung und eine erste Pflichtsichtung** für Objekte im Zustand
   „Nachfolge offen", bevor Verzeichnisgruppen zum Regelfall des Bibliotheks- und Space-Eigentums
   werden (Abschnitt 7.3). Die reine Auffangliste bei der Systemverwaltung ohne Frist reicht nicht,
   solange das Papier selbst begründet, warum sie nicht reicht.

## Was ich nicht beurteilen kann

Ob der zeitgesteuerte Pull über die Keycloak Admin REST API (Abschnitt 4.3) technisch die genannte
6-Stunden-Frist zuverlässig einhält, und ob die Freigabe-Obergrenze für Konnektorbibliotheken (#797,
Abschnitt 6.1, bewusst ausgeklammert in Abschnitt 11) so bemessen wird, dass sie meine Auflage zu
Punkt 5 tatsächlich trägt — das sind technische bzw. noch nicht getroffene Entscheidungen außerhalb
dieses Papiers.

---

## Teil 4: Sachbearbeitung (erste Fassung)

# Stakeholder-Bewertung: Sachbearbeiterin

Zu: `discussion-berechtigungsmodell-gruppen-und-faehigkeiten.md` (Entwurf 19.09.2026, Issue #1809,
Epic #1295). Der Rahmen aus Abschnitt 0 des Papiers und den Vorentscheidungen des Epics (flache
Subjekte, keine Schachtelung, Gruppe bleibt am Grant, globale Fähigkeiten kommen) ist gesetzt und wird
hier nicht in Frage gestellt. Bewertet werden die sieben Empfehlungen (Abschnitte 2–8) und die
Entscheidungsvorlage (Abschnitt 10) aus der Perspektive: Aufwand im Tagesgeschäft, Verständlichkeit
der Begriffe, Folgen von Fehlbedienung.

## Gesamturteil

**Tragfähig mit Auflagen.** Das Grundprinzip — Freigabe an eine Gruppe statt an einzelne Personen,
mit erklärender Fehlermeldung statt versteckter Schaltfläche — trifft genau meinen Alltag. Aber an
drei Stellen, die das Papier selbst als Kernfragen benennt (Kollisionsanzeige, Gruppenverantwortliche,
Nachfolge), bleibt die Lösung auf dem Papier stehen bleiben, wo im Amt eine Person vor einem Bildschirm
sitzt und in drei Sekunden die richtige Wahl treffen muss.

## Bewertung je Empfehlung

### 1. Vergleich mit anderen Systemen (Abschnitt 2) — **mit Auflage**

Was hier für mich ankommt, ist nicht der Vergleich selbst, sondern die daraus übernommenen
Grundsätze: flach, keine Schachtelung, Aufbauorganisation nur Anzeige (2.6). Das hält die Antwort auf
„warum sehe ich das" auf einer Stufe (2.1, Zeile 125 f.: „für jede Auskunft ‚warum sieht X das?' eine
Herleitungsstufe mehr" — als Argument *gegen* Zwischenkonstrukte). Im Alltag heißt das aber auch:
Wenn ich eine Bibliothek „Vergaberichtlinien 2026" für „Referat 50" freigebe, gilt das nur für die
Gruppe, die genau so heißt — nicht automatisch für ein separat gepflegtes Projektteam „Referat 50
Vergabe AG", falls es das als eigene interne Gruppe gibt. Eine Kollegin aus diesem Projektteam meldet
sich: „ich sehe die Bibliothek nicht" — und ich muss erst herausfinden, dass es zwei verschiedene
Gruppen gibt, bevor ich das reparieren kann. Das ist kein Fehler des Modells, aber eine Konsequenz, die
in der Empfehlung nicht ausgesprochen wird.

### 2. Gruppenherkunft und Kollisionsanzeige (Abschnitt 3) — **mit Auflage**

Die Kollisionsanzeige selbst (3.3, UI-Referenz in #1820: „Referat 50 · Verzeichnis Haus A ·
23 Mitglieder" neben „Referat 50 · Verzeichnis Partner · 8 Mitglieder") ist der richtige Ansatz — Name
bleibt, Herkunft steht daneben, keine Umbenennung der Quelle. Mein Problem: Der Unterschied zwischen
„Verzeichnis Haus A" und „Verzeichnis Partner" ist genau der Unterschied zwischen **internem** und
**externem** Empfänger. Wenn ich beim Freigeben eines internen Vergabevermerks in der Ergebnisliste
das falsche „Referat 50" anklicke — zwei Einträge mit fast identischem Text, ein Fußwort
unterschiedlich —, gebe ich einen internen Vorgang an die Gruppe eines Partnerportals frei. Das
Papier beschreibt keine zusätzliche Hürde für diesen konkreten Fall (etwa eine optische Abhebung oder
eine Rückfrage bei Gruppen aus einem als „extern" markierten Anbieter); die einzige Absicherung ist,
dass ich den richtigen Text lese. Nach der Erfahrungsregel meiner Rolle wird ein Text, den ich zum
zehnten Mal sehe, nicht mehr zu Ende gelesen.

Positiv: Eindeutige Namen für **interne** Gruppen je Organisation (3.3, dritte Zeile) verhindert
wenigstens, dass ich zwischen zwei selbst angelegten „Projekt Gecko" wählen muss — das reduziert die
Fehlerquelle für den Teil, den das Haus selbst kontrolliert.

Das „Verweigern" beim Löschen eines Anbieters (3.4) ist für mich als Sachbearbeiterin nicht direkt
spürbar (Verwaltungsvorgang), aber gut: Es verhindert, dass Gruppen entstehen, die für immer in meinen
Auswahllisten als „irgendwie kaputt" herumliegen, ohne dass ich weiß, warum.

### 3. Synchronisation (Abschnitt 4) — **mit Auflage**

Für mich als Sachbearbeiterin ist die technische Wahl (Token vs. Pull, Keycloak zuerst) unsichtbar —
sichtbar ist nur die Wirkung: Wann sieht eine neu in eine Verzeichnisgruppe aufgenommene Kollegin die
Bibliothek, die dieser Gruppe freigegeben ist? Bei zeitgesteuertem Abgleich (Vorgabe 6 Stunden, 4.2)
kann das bis zum nächsten Lauf dauern. Konkreter Fall: Eine neue Kollegin startet montags im Referat
50, wird morgens ins Verzeichnis aufgenommen; ich habe „Referat 50" bereits Zugriff auf unsere
Arbeitsbibliothek gegeben und sage ihr, sie solle gleich reinschauen. Sie sieht nichts. Ich weiß nicht,
ob das ein Fehler ist oder nur eine Frage der Zeit — das Papier sieht eine Statuszeile für den Abgleich
nur in der **Verwaltungs**-Oberfläche vor (#1821: „letzter Status"), nicht irgendwo, wo ich als
Sachbearbeiterin nachschauen könnte. Ich rufe also die IT an, obwohl in ein paar Stunden alles von
selbst funktioniert hätte.

### 4. Pflege interner Gruppen / Gruppenverantwortliche (Abschnitt 5) — **mit Auflage**

Das Grundprinzip ist genau das, was mir Arbeit abnimmt: Ich muss für eine Mitgliederänderung in meiner
Projektgruppe kein Ticket mehr schreiben, sondern pflege es selbst als Verantwortliche (5.3). Die
Sichtbarkeitsregel für Mitgliederlisten — ich sehe nur die Gruppen, denen ich selbst angehöre, und
deren Größe, nicht die übrigen Mitglieder anderer Gruppen (5.3, letzter Punkt) — schützt genau den
Fall, den ich aus dem Alltag kenne: eine Gruppe „Disziplinarverfahren 2026", deren Mitgliederliste
nicht jeder Kollege sehen soll. Das ist gut zu Ende gedacht.

Zwei Lücken, die das Papier selbst nicht schließt:

- **Anlegen ist gesperrt, bis jemand die Fähigkeit vergibt** (5.3: `CREATE_INTERNAL_GROUP`,
  ausgeliefert „an niemanden"). Für eine Neuinstallation heißt das: Bis die Systemverwaltung diese
  Fähigkeit jemandem gibt, kann *niemand* eine interne Gruppe anlegen — auch keine Referatsleitung.
  Das ist als Startzustand nachvollziehbar begründet, aber im Alltag bedeutet es: Am ersten Tag, an
  dem ich ein Projektteam „Gecko" quer über drei Referate brauche, lande ich trotzdem wieder bei
  einem Ticket an die IT — nur einmalig statt bei jeder Mitgliederänderung. Das ist ein echter
  Fortschritt gegenüber heute, aber keine vollständige Selbstständigkeit.
- **Referatswechsel einer Verantwortlichen ist im Lebenszyklus (Abschnitt 7) nicht vorgesehen.**
  Abschnitt 7.2 kennt „Konto gesperrt" und „letzter Verantwortlicher ausgeschieden", aber nicht: Ich
  bleibe im Amt, wechsle aber vom Referat 50 ins Referat 30, bin aber weiterhin — technisch
  unverändert — Verantwortliche der internen Gruppe „Vergabestelle Intern" meines alten Referats.
  Niemand entzieht mir das automatisch, weil das Konto ja aktiv bleibt. Konkret: Ich bekomme drei
  Monate nach meinem Wechsel noch eine Anfrage „kannst du Herrn Schulz in die Gruppe aufnehmen?",
  obwohl ich fachlich gar nicht mehr zuständig bin — und weil ich es technisch trotzdem kann, mache
  ich es vielleicht sogar, um niemandem Umstände zu machen. Das Papier hat für „Kontosperre" und
  „Gruppe leer" eine saubere Antwort, aber nicht für den in der Verwaltung häufigsten Fall:
  Personalwechsel bei fortbestehendem Konto.

### 5. Globale Fähigkeiten (Abschnitt 6) — **alltagstauglich**

Das ist der Punkt, der meine Frage „was passiert, wenn ‚Alle Konten' nicht mehr Spaces anlegen
dürfen" am direktesten beantwortet, und die Antwort gefällt mir: `GET /api/v1/me` liefert meine
Fähigkeiten, und ohne die Fähigkeit „Space anlegen" zeigt die Oberfläche statt der Schaltfläche eine
Erklärung samt Ansprechperson (6.4; Abnahmekriterium in #1820: „zeigt die Oberfläche statt der
Schaltfläche eine Erklärung"). Konkreter Fall: Die Verwaltung entzieht „Alle Konten" das Recht,
Connector-Bibliotheken (Dateisystem, Webverzeichnis) anzulegen, und gibt es nur der Gruppe
„IT-Koordinatoren" (Zeile in 6.1). Ich will für mein Projekt eine Webverzeichnis-Quelle einbinden,
klicke auf „Neue Bibliothek" und sehe statt eines grauen, unerklärten Knopfs eine Meldung, warum das
nicht geht und an wen ich mich wende. Das ist genau das Gegenteil von „stiller Fehlschlag" — gut.

Eine Unschärfe bleibt in den Begriffen, siehe unten (Fähigkeit/Befugnis/Rolle).

### 6. Lebenszyklus / „Nachfolge offen" (Abschnitt 7) — **mit Auflage**

Der Begriff „Nachfolge offen" selbst ist verständlich formuliert — besser als eine technische
Statuscode. Das Prinzip dahinter (7.2, letzte Spalte: nichts wird gelöscht, Zugriff bleibt, nur die
Reichweite ist eingefroren) ist genau richtig für den Alltag: Eine Bibliothek, deren Eigentümer
ausgeschieden ist, bleibt für die, die schon Zugriff haben, benutzbar — kein Blackout am Montagmorgen.

Die Lücke liegt in der Zuständigkeit für den Auffangfall (7.3): Für einen Space mit mehreren
`ADMIN`s oder ein Asset im Eigentum einer Gruppe mit Verantwortlichen funktioniert die Nachfolge von
selbst. Aber ein Space mit **einem** persönlichen Eigentümer, der ausscheidet, landet bei der
Systemverwaltung — „keine Frist, keine Eskalation, keine Mail" (7.3, ausdrücklich so entschieden).
Konkreter Fall: Ein Kollege legt den Space „Sonderprüfung 2025" an, ist alleiniger `ADMIN`, geht zum
Jahresende in den Ruhestand. Sein Konto wird gesperrt, der Space ist ab sofort „Nachfolge offen" —
aber niemand aus dem verbliebenen Team bekommt davon eine Nachricht. Erst wenn im Februar jemand
versucht, eine neue Kollegin als Mitglied hinzuzufügen, und das nicht geht, merkt das Team überhaupt,
dass etwas offen ist — und dann weiß niemand, dass es die Systemverwaltung lösen muss, weil die
Oberfläche das (nach heutigem Stand des Papiers) nur als abgelehnte Aktion zeigt, nicht als aktive
Liste, auf die mein Team selbst schauen kann. Das Papier begründet den Verzicht auf Frist/Eskalation
nachvollziehbar (7.3: „eine Zahl ohne Wirkung"), aber für mich als Betroffene bedeutet das: Ich merke
das Problem erst, wenn ich es brauche, nicht wenn es entsteht.

### 7. Diagnose im Gruppenkontext (Abschnitt 8) — **kann ich nur indirekt beurteilen**

Das Diagnosewerkzeug selbst bediene ich nicht — das ist Sache von IT-Support oder Systemverwaltung
(„Sicht als" braucht eine Befugnis, Abschnitt 6.2). Relevant für mich ist nur die Kehrseite: Wenn ich
anrufe und sage „ich verstehe nicht, warum ich Bibliothek Y sehe/nicht sehe", muss die Person am
anderen Ende schnell und ohne meinen Namen extra rechtfertigen zu müssen eine Antwort finden können.
Dass das Rechteprofil der Diagnose eine Gruppe ist statt einer Person (8.1) hilft dabei tatsächlich —
weniger Hürden für den Support heißt für mich schnellere Antworten. Die in 8.2 Punkt 3 vorgeschlagene
Mindestgruppengröße für wählbare Profile ist mir sachlich einleuchtend, wirkt sich aber nicht direkt
auf meinen Alltag aus.

## Vertiefung: „Warum sehe ich das" — die eigentliche Alltagsfrage bleibt unbeantwortet

Der häufigste Anruf, den ich in dieser Rolle mache, ist nicht „warum sehe ich das nicht", sondern
„warum sehe ich **das** — habe ich das versehentlich freigegeben?" oder umgekehrt „ich sehe eine
Bibliothek, von der ich nicht wusste, dass sie für mich freigegeben ist — wer war das und über
welchen Weg?". Das Papier erwähnt „warum sieht X das?" nur einmal, und zwar als **Argument gegen**
Projektrollen (Abschnitt 2.1, Zeilen 125 f.: eine Indirektionsstufe mehr bei Rollen). Es beschreibt an
keiner Stelle eine tatsächliche Sicht für die betroffene Person selbst, die zeigt: „Sie sehen diese
Bibliothek, weil Sie Mitglied der Gruppe Referat 50 (Verzeichnis Haus A) sind, die am 03.03.2026 die
Rolle Viewer erhalten hat." Was es gibt:

- Für **Verwaltende** einer Space-Mitgliederliste: erkennbar, ob eine Person „direkt oder über eine
  Gruppe" ihre Rolle hat (#1820, Umfang) — das hilft mir nur, wenn ich selbst Space-`ADMIN` bin und
  in die Mitgliederliste schaue, nicht wenn ich selbst die betroffene Person bin, die eine Bibliothek
  sieht.
- Für **Systemverwaltung**: je Gruppe die Zahl der Grants und Space-Mitgliedschaften, „wo wirkt diese
  Gruppe" (#1821) — das beantwortet die Frage aus Sicht der Gruppe, nicht aus meiner Sicht als
  einzelne Nutzerin, die vor einer Bibliothek steht und eine Erklärung will.

Für mich bedeutet das: Sehe ich eine Bibliothek, von der ich nicht weiß, warum, bleibt mein einziger
Weg ein Ticket an die IT — trotz eines Modells, das ausdrücklich mit dem Argument angetreten ist, die
Herleitung flach und damit einfach zu halten (2.1). Flach zu **sein** und mir das flach **zu zeigen**
sind zwei verschiedene Zusagen; das Papier löst nur die erste ein.

## Die drei Begriffe/Abläufe, die am ehesten zu Fehlbedienung führen

1. **Auswahl bei Namenskollision** (Abschnitt 3.3): Zwei fast identische Zeilen in einer Trefferliste
   („Referat 50 · Verzeichnis Haus A" / „Referat 50 · Verzeichnis Partner"), die sich nur in einem
   Wort unterscheiden — eines davon intern, eines extern. Ein Fehlklick gibt einen internen Vorgang
   an ein Partnerportal frei, und nichts in der beschriebenen Oberfläche weist mich vor dem Klick
   noch einmal ausdrücklich darauf hin, dass ich gerade eine Gruppe außerhalb des eigenen Hauses
   auswähle.
2. **„Fähigkeit" vs. „Befugnis" vs. „Rolle"** (Abschnitt 6.2): Drei sprachlich ähnliche Wörter für drei
   rechtlich verschiedene Dinge — unbefristetes Anlegerecht ohne Gegenstand („Fähigkeit"), befristete,
   personengebundene Erlaubnis mit Gegenstand und Begründungspflicht („Befugnis"), gestufte
   Berechtigung an einem Objekt (VIEWER/EDITOR/MANAGER, „Rolle"). Im Amtsdeutsch ist „Befugnis" das
   Alltagswort für alle drei zusammen; wer eine Fehlermeldung mit einem dieser drei Begriffe bekommt,
   wird sie ohne Schulung nicht sauber den anderen beiden zuordnen können.
3. **Fortbestehende Gruppenverantwortlichkeit nach Referatswechsel** (Abschnitt 5, im Abgleich mit
   Abschnitt 7.2): Der Lebenszyklus kennt „Konto gesperrt" und „letzter Verantwortlicher
   ausgeschieden", aber nicht den in der Verwaltung häufigsten Fall — dieselbe Person, anderes
   Referat, Konto bleibt aktiv. Ohne einen ausdrücklichen Übergabeschritt bleibe ich fachlich
   zuständig, obwohl ich es nicht mehr bin, und pflege möglicherweise weiter eine Gruppe, die nicht
   mehr „meine" ist.

## Die eine Änderung

Ich würde in Abschnitt 3.3/#1820 verlangen, dass Gruppen aus einem Anbieter, der nicht der
Standardanbieter des eigenen Hauses ist (also: Partnerportale, fremde Organisationen), in jeder
Auswahlliste sichtbar **anders markiert** sind als interne oder Haus-eigene Gruppen — nicht nur durch
einen Textzusatz, sondern durch etwas, das ich auch beim schnellen Klicken wahrnehme (Farbe, Symbol,
oder eine Zwischenfrage „Sie geben für eine externe Gruppe frei — fortfahren?"). Das schließt den
Fehler mit der größten Reichweite (versehentliche externe Freigabe), den das Papier an keiner Stelle
technisch, sondern nur durch Lesesorgfalt absichert.

## Was ich nicht beurteilen kann

- Ob die Vorgabewerte für den Verzeichnisabgleich (6 Stunden, Abschnitt 4.2/11) im Alltag als „schnell
  genug" oder „zu langsam" empfunden werden, hängt von der tatsächlichen Häufigkeit von
  Neueinstellungen und Referatswechseln in meiner Behörde ab — das kann ich nicht pauschal sagen.
- Ob die vorgeschlagene Mindestgruppengröße für Diagnoseprofile (Abschnitt 8.2, Punkt 3) in der Praxis
  hoch genug ist, um kleine Teams tatsächlich vor Personenbezug zu schützen, ist eine
  Datenschutzfrage, die außerhalb meiner Rolle liegt.
- Wie oft in meinem Haus tatsächlich Referatswechsel bei aktiven Konten vorkommen und wie stark sich
  die unter Punkt 4 beschriebene Lücke deshalb im Alltag bemerkbar macht, ist eine Annahme über meinen
  Arbeitsalltag, die ich nicht verallgemeinern kann — sie kann in manchen Häusern selten, in anderen
  (etwa bei turnusmäßigen Rotationen) sehr häufig sein.
- Ob die Systemverwaltung die Liste offener Nachfolgen (Abschnitt 7.3) in der Praxis regelmäßig genug
  durchsieht, um die von mir beschriebene Wartezeit kurz zu halten, ist eine Frage der Organisation
  der Systemverwaltung, nicht des Modells selbst.

---

## Teil 5: Code-Review der zweiten Fassung (Runde 1)

# Review PR #1825 (Konzeptpapier #1809) — Runde 1, Befunde des Code-Reviewers

Alle Befunde am Code auf origin/main bestätigt. Gut: die vier Widersprüche sind durchgängig aufgelöst; Vorentscheidungen des Epics gehalten; die eine Verhaltensänderung (Space-Eigentum durch jeden wirksamen ADMIN übertragbar) ist in §7.3 Punkt 2 deklariert.

## Wichtig

1. **§3.4 Z. 401 „Deaktivieren … ohne Bedingung"** — falsch. `OidcProviderService#setEnabled` (:294–306) weist ab: Standardanbieter mit weiteren aktiven Anbietern (409), letzter aktiver OIDC-Anbieter ohne `acknowledgeLastProvider`, und `LocalAdminAvailabilityGuard#requireLoginCapableAdminWithoutProvider` (:109–114, `LOCAL_ADMIN_REQUIRED`). Vorschlag: „Deaktivieren bleibt der Notweg; abgewiesen nur, wenn danach kein anmeldefähiger Systemverwalter bliebe (ADR-0033) — diese eine Bedingung bleibt."

2. **§7.4 Z. 920 „Eine Kontosperre wird nie abgelehnt"** — zu absolut. `LocalUserService#lock` (:306–307), Inaktivitätssperre (:323–324), Ablaufen (:272–273) rufen `requireAnotherLoginCapableAdmin` → 409; #1349 (Sub-Issue dieses Epics) verlangt genau das. Muster wie #1599/#1639 (Handbuchsatz gegen Last-Admin-Guard). Vorschlag: „nie wegen offener Eigentums- oder Zuständigkeitsfragen abgelehnt; Schutz des letzten Systemverwalters (#1349, ADR-0033) bleibt die einzige Ausnahme."

3. **§0 Z. 66 / §7.4 Z. 924 „nur wirksame Gruppen" mit „≥ 1 aktives Konto"** sperrt (a) die frisch angelegte, leere interne Gruppe aus (Verantwortliche sind laut §5.3 nicht automatisch Mitglied → Ablauf „Gecko anlegen und freigeben" scheitert, §9b suggeriert das Gegenteil); (b) das Übertragungsziel bei Anbieterablösung — im Token-Modus entsteht die Anbietergruppe erst mit der ersten Anmeldung (`TokenGroupSynchronizer#findOrCreate`), §9c Z. 1183 verlangt „Ziel muss wirksam sein" → Auflage 2.5 wird wieder Sackgasse; (c) §4.3 Keycloak-Abteilung nur mit Untergruppen = dauerhaft leere Gruppe, nie Grant-Ziel. Heute prüft `AssetGrantService#requireGrantableGroup` (:464–476) nur `isDissolved()`. Vorschlag: Wirksamkeit fürs Erteilen = nicht aufgelöst + Anbieter aktiviert; „kein aktives Mitglied" = Warnung + Eintrag in „Freigaben ohne Empfänger" (§7.5) — oder die drei Ausnahmen ausdrücklich in §0.

4. **§9b Z. 1103 / Entscheidung 9** unterschätzt den Ist-Stand: `requireGrantableGroup` prüft keine Sichtbarkeit; `LibraryGrantsDialog.tsx:128–133` (`manualGroupEntry`) lässt jeden MANAGER eine Gruppe per ID benennen — heute kann jeder MANAGER an jede Gruppe der Organisation freigeben. Folgen für den ADR: (a) „Vorgabe: nicht freigegeben" nimmt Bibliotheksverwaltern eine bestehende Möglichkeit (Bestandsänderung — aussprechen); (b) nur in der Auswahlliste umgesetzt wäre der Schutz (Betrieb 4.1, Personalrat A2/A3) kosmetisch — Durchsetzung gehört in `requireGrantableGroup` bzw. dessen Nachfolger in #1811 und in die Abnahmekriterien von #1814/#1820. Pfad heißt `/api/v1/admin/groups` (`GroupController.java:33`).

5. **§12 Z. 1346 „liegen vollständig als Berichte vor"** — nur im Sitzungs-Scratchpad, nicht im Repository. Präzedenz Epic #1529: `docs/discussions/discussion-lokale-benutzerverwaltung-stakeholder.md` (Berichte unverändert, ADR trägt die Antwort). Ohne Originale ist Personalrat F4 nicht nachprüfbar. Vorschlag: die vier Berichte (und die zweite Personalrats-Sichtung) als `docs/discussions/discussion-berechtigungsmodell-stakeholder.md` in denselben PR, §12 verlinkt darauf. Quellen: scratchpad/epic1295/stakeholder-*.md.

6. **§9a Entscheidung 8 (und §9b Entscheidung 9) als Feststellung statt Wahl.** §2–8 und 9c liefern Optionen + Bewertung + Empfehlung; §9a nicht — acht historisierte Quellen, zwei Einstiege, Vier-Augen-Vollmacht, Höchstdauer und Z. 1079 „Ohne sie wird keine weitere personenbezogene Historienquelle angeschlossen — #1813, #1815, #1818, #1819 hängen daran" stehen ohne Alternative. Damit hängen vier Issues an einer Governance-Einstellung, die es nicht gibt (`security-and-compliance.md` „Noch offen": Höchstdauer, Pseudonymisierung). Vorschlag: §9a und §9b je Optionstabelle im Stil von §3.4/§9c; Entscheidung 8 mit Aussage, welche Issues bei Ablehnung der Vorbedingung wie geschnitten würden (z. B. „Höchstdauer erst Phase 3, bis dahin kein Personen-Einstieg, kein Namensschnappschuss"). Dasselbe kleiner in §9b: Vorgabe „nicht freigegeben" ohne Gegenoption.

## Nits

1. §8.1 Z. 953: `readableLibraryIdsForGroup` (:215–225) = `findReadableLibraryIdsByGroupGrant` ∪ `findIdsByOrganizationIdAndVisibility` — kein Eigentumsterm; Eigentum wirkt nur über den MANAGER-Grant aus `createLibrary` (`KnowledgeLibraryService.java:300–306`), der entziehbar ist.
2. §4.2 Z. 507: der „Bestätigungsweg" ist nicht gebaut (Executor bricht mit `ABORTED_THRESHOLD` ab; #1816 baut ihn) — „drei gebaute plus der vorgeschlagene vierte". Anhang B Nr. 5 und §1 sagen es richtig.
3. §6.2 Z. 746 / Entscheidung 5: „AUDITOR besitzt keine Fähigkeit" → gemeint „die Rolle verleiht keine Fähigkeit" (unter ALL_ACCOUNTS besitzt das Konto sie).
4. §12.2 A3: „Übernommen, konkretisiert" ist eine Änderung (Original: erscheint in keiner fremden Liste; Papier: erscheint namenlos als „geschützte Gruppe") — nach F4 als „geändert, weil …" ausweisen.
5. Abhängigkeiten der Übertragungsoperation: **#1816 → aufgebläht** (Mechanismuswechsel ist mit „eingefroren + Differenzbericht" schon still-entzugsfrei, Personalrat C4); **#1812 → real als Freigabebedingung**, verkettet aber die ganze Verzeichniskette hinter ein großes Issue — sauberer: „#1812 liefert provider_id + RESTRICT + 409; der 409 bleibt bis zur Übertragungsoperation ohne Ausweg, und das steht im ADR"; **#1819 → real**, es fehlt nur der Satz, dass die Übernahme dort herausgeschnitten wird.
6. Kleinigkeiten: Pfad `/api/v1/groups` → `/api/v1/admin/groups` (Z. 1102); §7.4 „409 (heute nur für den Eigentümer)" — heute `ValidationException` → 400 (`GlobalExceptionHandler.java:422–425`); §0 „vier Wörter", Tabelle hat fünf; §0 schreibt §9 der Stakeholder-Runde zu, §9 stand schon in der ersten Fassung; §4.3 Beweiskette Keycloak: `GroupResource` delegiert an `session.users().getGroupMembersStream(...)` (Z. 349) — Aussage richtig, Beleg nachziehen.

## Vorbestehend (nicht dieser PR; für #1808 notiert)
- `LibraryAccessService.java:182` Javadoc „rejectOrgUnit covers only ORG_UNIT" — überholt (geht bereits in die Nachbesserung von #1826).
- ADR-0019 steht auf „Vorgeschlagen", obwohl `notifications` gebaut ist; §5.3 stützt sich darauf.

---

## Teil 6: Personalrat, zweite Sichtung (zweite Fassung)

> Anmerkung: Der im Bericht genannte absolute Pfad ist der Arbeitsbaum der Sitzung; gemeint ist
> `docs/discussions/discussion-berechtigungsmodell-gruppen-und-faehigkeiten.md` in der zweiten
> Fassung. Der Berichtstext ist unverändert.

# Zweite Bewertung aus Sicht des Personalrats

**Gegenstand:** `/home/devtank42/projects/opaa-1809/docs/discussions/discussion-berechtigungsmodell-gruppen-und-faehigkeiten.md`
(zweite Fassung vom 19.09.2026, 1536 Zeilen, Issue #1809, Epic #1295)
**Erste Bewertung:** `…/scratchpad/epic1295/stakeholder-personalrat.md` (28 Bedingungen A1–F4)
**Prüfauftrag dieser Runde — und nur dieser:**
1. Sind B1–B6 so umgesetzt, dass Empfehlung 7 mitbestimmungsfähig ist?
2. Tragen wir die Änderungen an A1 und D7 mit?
3. Entstehen durch die **neuen** Elemente Möglichkeiten der Leistungs- oder Verhaltenskontrolle, die
   die erste Bewertung nicht kannte?

Nicht erneut geprüft: die 24 übrigen Bedingungen, die Vorentscheidungen aus Abschnitt 0, die
Empfehlungen 1 bis 6 im Übrigen.

---

## Votum vorab

**Empfehlung 7 ist mitbestimmungsfähig — unter einer Bedingung (Z1).**

B1 bis B6 sind sämtlich umgesetzt, vier davon wörtlich, zwei schärfer als gefordert. Der Widerspruch,
an dem die erste Fassung scheiterte, ist an der richtigen Stelle aufgelöst: Die Schwelle hängt jetzt
an der Schnittmenge und wird zum Zeitpunkt des Laufs geprüft.

Die eine Bedingung betrifft nicht den Inhalt, sondern die **Kopplung**: Schutzregel und Fähigkeit
müssen im selben Arbeitsschritt geliefert werden. Heute tragen die Punkte 1, 4, 5, 7 und 8 aus
Abschnitt 8.2 kein Umsetzungs-Issue, sondern stehen als „Randbedingung" im ADR für eine Nacharbeit,
die es als Ticket nicht gibt (Zeilen 1010–1012). Das ist die Konstruktion, bei der eine Zusage
zwischen zwei Ausbaustufen verloren geht.

A1 tragen wir mit **einem** verbleibenden Einwand (Z2, geschützte Gruppen in der Herleitung).
D7 tragen wir mit **einem** verbleibenden Einwand (Z3, die Begründung „kein Lesepfad" ist durch
Entscheidung 8 überholt).

Aus den neuen Elementen ergeben sich **vier** Befunde, die wir nicht kennen konnten (Z4 bis Z7).
Einer davon — die Vorschau der Übertragungsoperation mit einer **Person** als Quelle — ist der
gewichtigste Einzelbefund dieser Runde: Er schafft die personenbezogene Rechteübersicht, die
Entscheidung 8 für die Vergangenheit unter eine Vier-Augen-Vollmacht stellt, für die Gegenwart ohne
jede Vollmacht neu.

---

## 1. Frage 1: Sind B1 bis B6 umgesetzt?

Alle Fundstellen in Abschnitt 8.2 (Zeilen 960–1012) und Entscheidung 7 (Zeilen 1258–1265).

### Übersicht

| Nr. | Gegenstand | Urteil |
|---|---|---|
| B1 | Mindestgruppengröße als Profilschwelle | **erfüllt** (unverändert übernommen, durch Punkt 5 geschärft) |
| B2 | Schwelle an der Schnittmenge, zum Zeitpunkt des Laufs, sonst `403` | **erfüllt** (wörtlich, einschließlich unserer Rückfallregel) |
| B3 | Protokoll für Profil-Läufe mit Space-Kontext | **erfüllt** |
| B4 | Diagnose bleibt `SYSTEM_ADMIN`; keine Fähigkeit öffnet sie | **erfüllt** |
| B5 | Erzwungene Untergrenze der Mindestgruppengröße | **erfüllt**, mit einem Nachsatz zur Höhe |
| B6 | Leitplanke (b) unverändert | **erfüllt**, ausdrücklich gestärkt |

### B1 — erfüllt

> „Kleine Gruppen sind Personen. […] Gruppen unterhalb der **Mindestgruppengröße** sind deshalb kein
> wählbares Rechteprofil." (Abschnitt 8.2, Punkt 3, Zeilen 971–974)

Unverändert übernommen. Punkt 5 (Zeilen 984–991) geht darüber hinaus und schließt eine Lücke, die wir
selbst nicht gesehen haben: Gezählt werden **aktive Konten**, nicht Mitgliedschaftszeilen. Abschnitt
7.2 lässt Mitgliedschaften gesperrter Konten stehen; eine Gruppe mit 20 Zeilen, davon 18 gesperrt,
wäre nach unserer eigenen Formulierung ein zulässiges Profil gewesen und faktisch ein
Zwei-Personen-Kontext. Der Befund stammt vom Betrieb (Auflage 7.1); er gehört inhaltlich uns, und wir
übernehmen ihn.

Ebenfalls erledigt ist unser Punkt 7d (zwei Lesarten der Mindestgruppengröße): Punkt 5 spricht beide
aus und ordnet zu — Rechtekontext für das Profil, Nutzung für die Nutzungstransparenz in
`spaces-and-assets.md`.

### B2 — erfüllt, wörtlich

> „**Aufgelöst:** Die Mindestgruppengröße gilt für die **Schnittmenge** aus aktiven Mitgliedern der
> Gruppe und Zugang zum Space, geprüft **zum Zeitpunkt des Laufs**; liegt sie darunter, antwortet der
> Endpunkt mit `403` und dem Hinweis, dass für diese Sicht der Personenkontext mit Vollmacht zu wählen
> ist. Ist die Schnittmengen-Prüfung zu teuer, entfällt der Space-Kontext — nicht die Prüfung."
> (Abschnitt 8.2, Punkt 4, Zeilen 979–983)

Das ist unsere Auflage Wort für Wort, einschließlich der Rückfallregel, die wir unter „was wir nicht
beurteilen können" nur angeboten hatten. Entscheidung 7 (Zeilen 1258–1262) trägt sie in einem Satz
mit, sodass sie nicht in der Begründung hängen bleibt.

**Eine Präzisierung für den ADR, kein Einwand:** „Zugang zum Space" muss die Mitgliedschaft auf
**jedem** Weg meinen — direkt, über diese Gruppe, über eine andere Gruppe. Wird die Schnittmenge
gegen „Mitglieder, die über G im Space sind" gerechnet, ist sie bei einer Gruppe, die selbst gar
nicht Space-Mitglied ist, strukturell null, und die Regel wird zur Attrappe oder zur Sperre aller
Profile. Ein Satz im ADR genügt.

### B3 — erfüllt

> „Profil-Läufe **mit** Space-Kontext werden protokolliert (ausführende Person, Profil, Space,
> Zeitpunkt — eine Zeile je Lauf, keine je Abfrage); Profil-Läufe ohne Space-Kontext bleiben
> unprotokolliert." (Abschnitt 8.2, Punkt 7, Zeilen 997–1000)

Die vier Felder, die wir genannt haben, und die Mengenangabe, die wir genannt haben. Dass die
allgemeine Protokollpflicht offen bleibt (Zeilen 1000–1002, Abschnitt 11 Zeile 1329), ist korrekt
ausgewiesen und nicht mehr eine stillschweigende Vertagung — sie steht auf der Liste des bewusst
Offenen und wird dort beim nächsten Anlass wieder aufgerufen.

### B4 — erfüllt

> „Die Suchdiagnose bleibt `SYSTEM_ADMIN` vorbehalten; keine Fähigkeit öffnet sie. […] Wird sie je
> geöffnet, schaltet dieselbe Änderung die Sperre auch für Profil-Läufe scharf." (Abschnitt 8.2,
> Punkt 8, Zeilen 1003–1006; Entscheidung 7, Zeilen 1263–1265)

Beide Hälften sind da: die Reservierung und die Junktim-Klausel für den Fall der Öffnung. Wir halten
fest, was dieser Satz **nicht** bedeutet — und was auch schon in unserer ersten Bewertung stand: Die
Diagnosesperre schützt die Bestände der Personalvertretung nicht gegen `SYSTEM_ADMIN`, weil
`LibraryAccessService#effectiveRole` dieser Rolle ohnehin `OWNER` auf jede Bibliothek gibt. Die Zusage
„kein Lesebypass" gilt für die **Suche** (`readableLibraryIds`), nicht für die Bibliotheksverwaltung.
Das Papier spricht den Unterschied in Entscheidung 1 aus (Zeilen 1211–1214); dabei muss es bleiben.

### B5 — erfüllt, mit einem Nachsatz

> „Das Produkt setzt eine Voreinstellung **und erzwingt eine Untergrenze** […]; Änderungen des Werts
> sind Governance-Ereignisse." (Abschnitt 8.2, Punkt 6, Zeilen 992–996; Abschnitt 11, Zeile 1334)

Die Regel ist da, und die Protokollpflicht für Wertänderungen (unsere F3) ist mitgenommen.

**Nachsatz:** Eine erzwungene Untergrenze von 1 erfüllt den Satz und schützt nichts. Unsere Bedingung
lautete „nicht auf 1 oder 2 einstellbar". Der ADR sollte die Grenze als **Zahl** in die
Konfigurationstabelle des Handbuchs schreiben, nicht nur ihre Existenz zusichern. Das ist kein
Hinderungsgrund — die Höhe selbst beurteilen wir weiterhin nicht, sie folgt aus dem Zuschnitt des
jeweiligen Hauses —, aber eine Validierung ohne nachlesbaren Wert ist nicht überprüfbar, und
Überprüfbarkeit ist der ganze Zweck einer Dienstvereinbarung.

### B6 — erfüllt und gestärkt

> „Leitplanke (b) bleibt unverändert: Die Diagnose ist kein Zugriffshistorien-Nachweis — dafür gibt es
> die Stichtagsauskunft (Abschnitt 9a), und beide bleiben getrennt." (Zeilen 1006–1008)

Das ist mehr, als wir verlangt haben. Leitplanke (b) sagt: „Wer eine solche Auskunft braucht, braucht
ein anderes Werkzeug mit eigener Rechtsgrundlage." Das Papier benennt dieses Werkzeug jetzt, regelt
es (Entscheidung 8) und fordert die Trennung ausdrücklich ein. Genau so hatten wir uns die Auflösung
vorgestellt: nicht die Diagnose aufweichen, sondern das zweite Werkzeug sauber bauen.

### Z1 — die eine Bedingung

B2 und B3 sind Schutzregeln **zu** einer Fähigkeit, die erst nach #1815 gebaut wird. Sie hängen
heute an keinem Ticket:

> „Punkte 1, 4, 5, 7 und 8 sind Nacharbeiten an `SearchDiagnosisService` nach #1815 und stehen im ADR
> als Randbedingung, damit sie nicht verloren gehen" (Zeilen 1010–1012)

„Damit sie nicht verloren gehen" ist die Formulierung, mit der Zusagen verloren gehen. Der
Space-Kontext ist eine Erweiterung, die jemand irgendwann als kleine Sache nachzieht; die
Schnittmengen-Prüfung ist der teure Teil derselben Sache.

> **Z1 (Bedingung für unser Ja zu Empfehlung 7):** Der Space-Kontext im Rechteprofil erhält ein
> eigenes Umsetzungs-Issue des Epics, dessen Abnahmekriterien die Punkte 1, 4, 5, 7 und 8 aus
> Abschnitt 8.2 einzeln aufführen. Solange dieses Issue nicht umgesetzt ist, nimmt
> `SearchDiagnosisRequest` **keine** Space-ID entgegen. Prüfbar an: Issue-Zuschnitt,
> OpenAPI-Spezifikation, Integrationstest auf `403`.

Damit kann die Fähigkeit nicht vor ihrer Schutzregel ankommen. Das ist dieselbe Kopplung, die das
Papier bei D1 selbst gewählt hat („ohne sie wird keine weitere personenbezogene Historienquelle
angeschlossen") — wir verlangen nur die gleiche Bauart an der gleichen Art von Stelle.

---

## 2. Frage 2: Die Änderungen an A1 und D7

### 2.1 A1 — wir tragen die Änderung mit, mit einem Einwand

**Was geändert wurde.** A1 verlangte: Die Herleitung nennt gegenüber anderen als der betroffenen
Person nie den Gruppennamen. Das Papier erweitert den Kreis um Space-`ADMIN` und Eigentümer:

> „**Gegenüber anderen** nennt die Herleitung den Gruppennamen nur, wo die Person die Mitgliedschaft
> verwaltet — also in der Space-Mitgliederliste für `ADMIN`/Eigentümer („Rolle über Gruppe Referat
> 50"), die nach der Regel oben ohnehin die Mitgliederliste dieser Gruppe sehen." (Zeilen 1146–1151)

**Wir tragen das mit, aus zwei nachgeprüften Gründen.**

Erstens ist unsere Prämisse zu Befund 4(c) tatsächlich falsch gewesen, und das Papier weist es
zutreffend nach (Zeilen 1112–1118). Wir haben im Quelltext nachgesehen:
`SpaceAccessPolicy#requireMemberListViewer` verlangt `effectiveRole(space, caller).atLeast(ADMIN)`;
`SpaceResponseMapper` gibt allen übrigen Mitgliedern nur `roleCounts`. Die Space-Mitgliederliste ist
also heute schon nicht „für alle Space-Mitglieder sichtbar". Unser Bild von der Zeile
„BEM-Begleitung · Mitglied", die jeder Kollege im Space liest, trifft nicht zu. Eine Bewertung, die
auf einer falschen Prämisse beruht, korrigieren wir; das ist kein Nachgeben.

Zweitens ist die Begründung in der Sache richtig: Wer eine Gruppe in einen Space aufgenommen hat,
muss sehen können, was er getan hat. Eine Herleitung, die ihm „über eine Gruppenmitgliedschaft"
anzeigt, während er dieselbe Gruppe eine Zeile höher namentlich als Space-Mitglied liest, ist keine
Datensparsamkeit, sondern eine Unbrauchbarkeit. Und der Kreis, der dadurch dazukommt, sieht nach
Entscheidung 9 ohnehin die vollständige Mitgliederliste der Gruppe — der Gruppenname in der
Herleitung ist für ihn kein neues Datum.

**Der verbleibende Einwand betrifft genau die Ausnahme, auf der die Änderung ruht.**

Die neue Regel „wer ein Recht gibt, sieht, an wen" hat eine ausdrückliche Ausnahme für **geschützte
Gruppen** (Zeilen 1133–1139): nicht auffindbar, in fremden Listen „als ‚geschützte Gruppe' ohne
Namen", und der Grant-Geber sieht statt der Mitgliederliste die Verantwortlichen. Für diese Gruppen
gilt die Begründung der A1-Änderung — „sehen ohnehin die Mitgliederliste" — gerade **nicht**. Das
Papier zieht die Konsequenz nicht: Es sagt an keiner Stelle, was die **personenbezogene** Herleitung
bei einer geschützten Gruppe anzeigt.

*Situation, in der Beschäftigte Nachteile hätten:* Im Space „Personalentwicklung" ist eine geschützte
Gruppe Mitglied; in der Mitgliederliste steht die Zeile „geschützte Gruppe · Mitglied" ohne Namen —
so gewollt. Daneben stehen die Personen, und bei Frau S. steht „Rolle über eine geschützte Gruppe".
Ein zweiter Space-`ADMIN`, der die Gruppe nicht aufgenommen hat, liest daraus: Frau S. ist Mitglied
der einen geschützten Gruppe, die in diesem Space wirkt. Gibt es im Space nur eine solche Gruppe —
der Regelfall —, ist die Namenlosigkeit der Gruppenzeile wertlos. Die Schutzregel A3, die wir für
Personalvertretung, Schwerbehindertenvertretung, Gleichstellung und Personalvorgänge erreicht haben,
wird dann an der Personenzeile aufgehoben, nicht an der Gruppenzeile.

> **Z2:** Bei einer als geschützt gekennzeichneten Gruppe zeigt die Herleitung gegenüber Dritten —
> auch gegenüber Space-`ADMIN` und Eigentümer — **gar keine** Gruppenableitung, sondern nur die
> effektive Rolle. Sichtbar bleibt die Gruppenzeile als „geschützte Gruppe" mit ihren
> Verantwortlichen als Ansprechstelle. Die eigene Herleitung der betroffenen Person bleibt
> vollständig. Prüfbar an: #1820/#1822, Abnahmekriterium und Komponententest.

**Ein zweiter, kleinerer Befund an derselben Stelle** — wir nennen ihn, weil er die Ausnahme trägt,
auf der A1 ruht: Das Schutzkennzeichen setzen nach Abschnitt 9b „die zuständige Stelle selbst (ihre
Verantwortlichen)". Verantwortliche haben nach Abschnitt 5.3 (Zeile 684) aber **nur interne
Gruppen**; Anbietergruppen bleiben schreibgeschützt und haben keine. Eine aus dem Verzeichnis
gelieferte Gruppe „Personalrat" oder „Schwerbehindertenvertretung" — in einem Haus mit gepflegtem
Verzeichnis der Normalfall — kann damit von niemandem als geschützt gekennzeichnet werden: die
Verantwortlichen gibt es nicht, und die Systemverwaltung darf es nach derselben Regel nicht. A3 läuft
für genau die Gruppen leer, für die wir sie verlangt haben.

> **Z2b:** Der ADR benennt, wer das Schutzkennzeichen an einer **Anbietergruppe** setzt und löst. Die
> Lösung muss dem Grundsatz aus `hybrid-retrieval.md`, Leitplanke (e), folgen — „die jeweils
> zuständige Stelle selbst, nicht die Administration"; wie sie technisch aussieht (benannte Personen
> je Anbietergruppe ohne Pflegerechte, Antrag mit Bestätigung, o. ä.), beurteilen wir nicht. Bleibt
> die Frage offen, ist A3 für Verzeichnisgruppen nicht eingelöst und muss als solche im Papier
> ausgewiesen werden (F4).

### 2.2 D7 — wir tragen die Änderung mit, mit einem Einwand

**Was geändert wurde.** D7 verlangte die Pseudonymisierung als benannte Voraussetzung **jedes
weiteren personenbezogenen Historienpfads**. Das Papier schwächt das ab:

> „**Pseudonymisierung** (#391/#395) ist benannte Voraussetzung für den **Personen-Einstieg** der
> Stichtagsauskunft und für die Kontolöschung, die heute an `RESTRICT` scheitert (ADR-0016) — nicht
> für das Schreiben von Historienzeilen. […] Historienzeilen ohne Lesepfad erzeugen keinen
> Auswertungspfad, und die Kontolöschung ist heute für jedes Konto blockiert, das je ein Recht hatte;
> eine weitere `RESTRICT`-Spalte ändert daran nichts, solange der Lesepfad die Vollmacht verlangt."
> (Abschnitt 9a, Zeilen 1085–1091)

**Wir tragen das mit — aber aus einem anderen Grund, als das Papier angibt.**

Der Grund, aus dem wir zustimmen: Die Änderung **koppelt** die Pseudonymisierung an etwas, das die
Dienststelle haben will. Solange #391/#395 nicht erledigt ist, gibt es keinen Personen-Einstieg in
die Stichtagsauskunft. Das ist ein stärkerer Hebel als unsere eigene Formulierung, die nur das
Schreiben blockiert hätte — eine Blockade, die ein Projekt unter Termindruck durch eine
Ausnahmegenehmigung auflöst, während eine fehlende Auswertungsfunktion auffällt. Zusammen mit D1
(Höchstdauer als harte Vorbedingung jeder weiteren Quelle, Zeilen 1076–1083) bleibt eine überprüfbare
Grenze bestehen. D1 ist unverändert übernommen, und D1 war die tragende Bedingung; das haben wir in
unserer „einen Änderung" selbst so gewichtet.

**Der Einwand betrifft die Begründung, nicht die Entscheidung — und die Begründung ist falsch.**

„Historienzeilen ohne Lesepfad" gibt es ab Entscheidung 8 nicht mehr. Dieselbe Entscheidung, die D7
abschwächt, **baut den Lesepfad**: `PermissionHistoryService#readableLibraryIdsAsOf` bekommt mit
#1822 einen Endpunkt, und der **Objekt-Einstieg** steht der Rolle `AUDITOR` ohne jede Vollmacht offen
(Zeilen 1065–1067). Die Prämisse trägt also genau ab dem Moment nicht mehr, in dem sie aufgeschrieben
wird.

*Situation:* „Wer durfte Bibliothek Z am 3. März lesen" liefert eine Namensliste. Eine `AUDITOR`-Rolle
fragt dieselbe Frage nacheinander für dreißig Bibliotheken desselben Referats und hat daraus das
Rechteprofil jeder Person dieses Referats zum 3. März zusammengesetzt — ohne Vollmacht, ohne
Vier-Augen-Freigabe, ohne die Begründungspflicht, die D2 für den Personen-Einstieg verlangt. D3 (jeder
Abruf ein Ereignis) macht das im Nachhinein sichtbar, D4 (92 Tage, Seitenobergrenze) begrenzt die
einzelne Abfrage — beides begrenzt nicht die Zahl der Abfragen.

Das ist keine Kritik an D2 oder D4, die unverändert übernommen sind. Es ist der Punkt, an dem die
Begründung von D7 ersetzt werden muss, damit die Abschwächung trägt:

> **Z3:** Der ADR ersetzt die Begründung „Historienzeilen ohne Lesepfad erzeugen keinen
> Auswertungspfad" durch die zutreffende: Der Auswertungspfad **entsteht mit Entscheidung 8** und wird
> durch D1 bis D4 begrenzt. Dazu ergänzt er die eine Regel, die die Umgehung des Personen-Einstiegs
> über den Objekt-Einstieg schließt: Der Objekt-Einstieg beantwortet **genau ein benanntes Objekt je
> Abfrage** — keine Sammelabfrage über einen Space, eine Organisationseinheit oder einen
> Bibliotheksfilter. Prüfbar an: OpenAPI-Spezifikation zu #1822, Integrationstest.

**Ein zweiter Punkt zur Abschwächung, den wir nicht bestreiten, aber festhalten.** Der Satz „eine
weitere `RESTRICT`-Spalte ändert daran nichts" stimmt für die Frage „blockiert oder nicht". Er stimmt
nicht für den **Aufwand der späteren Reparatur**: Entscheidung 8 legt fünf neue Historientabellen mit
Personenspalte an (Space-Mitgliedschaft, Eigentum, Fähigkeit, Systemrolle, Kontozustand, Zeilen
1048–1052). Jede davon ist eine weitere Spalte, die eine künftige Pseudonymisierung umstellen muss.
Die Wahrscheinlichkeit, dass #391/#395 erledigt wird, sinkt mit jeder. Ein Mechanismus existiert
bereits (`audit_actor_pseudonyms` mit `ON DELETE CASCADE` auf `users`, Baseline-Changeset).

> **Z3b:** Der ADR nennt **je neuer Historientabelle** ausdrücklich, ob ihre Personenspalte als
> `RESTRICT` gegen `users` oder von vornherein pseudonymisiert angelegt wird, und hält fest, dass die
> Zahl der `RESTRICT`-Spalten das Maß der aufgeschobenen Löschschuld ist. Ob der bestehende
> Pseudonym-Mechanismus wiederverwendbar ist — er liegt im eigenen Rechtemodell des Protokolls
> (ADR-0015) —, beurteilen wir nicht; die Entscheidung muss aber getroffen und aufgeschrieben werden,
> nicht implizit bleiben.

---

## 3. Frage 3: Neue Möglichkeiten der Leistungs- und Verhaltenskontrolle

Geprüft wurden die in der zweiten Fassung neu hinzugekommenen Elemente. Vier Befunde, die unsere
erste Bewertung nicht kennen konnte.

### Z4 — Die Vorschau der Übertragungsoperation mit einer Person als Quelle (Entscheidung 10)

**Der gewichtigste Befund dieser Runde.**

Abschnitt 9c (Zeilen 1162–1200) führt eine allgemeine Übertragungsoperation ein. Als Quelle zulässig
ist ausdrücklich auch eine **Person**:

> „**Quelle und Ziel:** Gruppe → Gruppe (Regelfall), Gruppe → Person und Person → Person (Nachfolge,
> Abgabe der Verantwortung)." (Zeilen 1182–1183)
> „**Umfang wählbar:** alle Wirkungen der Quelle oder eine Teilmenge (nur Grants, nur Eigentum, …)"
> (Zeilen 1184–1185)
> „**Vorschau ist Pflicht** […] („12 Berechtigungen an 7 Bibliotheken, Mitglied in 2 Spaces,
> Eigentümerin von 3 Bibliotheken")" (Zeilen 1167–1168, 1192–1194)
> „**Wer:** `SYSTEM_ADMIN` organisationsweit" (Zeilen 1186–1188)

Zusammengelesen ergibt das: eine Abfrage „alle Wirkungen der Person X", beantwortet mit Zahl und
Aufzählung der betroffenen Objekte, für `SYSTEM_ADMIN`, ohne Vollmacht, ohne Begründungspflicht, ohne
Vier-Augen-Freigabe.

**Warum das neu ist.** Wir haben nachgesehen: Eine solche Sicht gibt es heute nicht. Die
OpenAPI-Spezifikation kennt unter `/api/v1/admin/` keinen Endpunkt, der die Rechte einer Person
auflistet; der Einstieg ist überall das Objekt oder die Gruppe
(`/api/v1/admin/groups/{groupId}/members`). Entscheidung 8 stellt den **historischen**
Personen-Einstieg unter eine Vier-Augen-Vollmacht nach dem Muster des Vorfallsbereichs (D2,
Zeilen 1068–1072). Entscheidung 10 schafft den **gegenwärtigen** Personen-Einstieg daneben neu — und
schwächer geschützt als den historischen, obwohl er dasselbe Datum liefert, nur aktueller. E1 (die
Nachfolgeliste ist objektbezogen, „keine Abfrage ‚was gehörte Person X'", Zeilen 903–908) ist damit
an einer anderen Stelle wieder aufgehoben — dasselbe Muster wie der
Widerspruch, an dem die erste Fassung bei Empfehlung 7 scheiterte.

*Situation, in der Beschäftigte Nachteile hätten:* Eine Amtsleitung bittet die Systemverwaltung
darum, „den Referatswechsel von Herrn K. vorzubereiten". Die Systemverwaltung öffnet die
Übertragungsoperation mit Quelle „Herr K.", Umfang „alle Wirkungen", und liest die Vorschau: sieben
Bibliotheken mit Namen, zwei Spaces, drei Eigentümerschaften. Sie bricht ab, ohne zu übertragen. Es
ist kein Vorgang entstanden, keine Vollmacht war nötig, und wenn die Vorschau nicht selbst
protokolliert wird, ist auch kein Ereignis entstanden. Die Auskunft, für die Entscheidung 8 zwei
`AUDITOR` und einen begründeten Vorgang verlangt, war ein Formularaufruf.

**Was fachlich wirklich gebraucht wird, ist enger.** Eine Nachfolge betrifft **Eigentum und
Verantwortung** — das sind die Dinge, die herrenlos werden. Die **Grants** einer ausgeschiedenen
Person müssen nicht übertragen werden; sie enden mit dem Konto. Der Umfang „alle Wirkungen" ist bei
einer Person als Quelle also nicht nur riskant, sondern über den Zweck hinaus.

> **Z4:** Bei einer **Person** als Quelle ist der Umfang der Übertragungsoperation auf **Eigentum an
> Assets und Spaces sowie Verantwortung für interne Gruppen** beschränkt. Grants und
> Space-Mitgliedschaften einer Person sind weder übertragbar noch in der Vorschau aufzählbar. Die
> Vorschau ist auch bei Abbruch ein Protokollereignis (wie jeder Abruf der Stichtagsauskunft nach D3).
> Für eine **Gruppe** als Quelle bleibt der volle Umfang. Prüfbar an: Umsetzungs-Issue zur
> Übertragungsoperation, OpenAPI-Spezifikation, Integrationstest auf den abgewiesenen Umfang.

Damit bleibt der Nutzen der Operation, den der Betrieb zu Recht als „die eine Änderung" bezeichnet
hat — die Reorganisation Referat 50 → 52 mit einem Vorgang —, vollständig erhalten. Er liegt auf der
Gruppen-, nicht auf der Personenachse.

**Nachgeordnet, gleiche Stelle:** „die betroffenen Objekte tragen den Vorgang in ihrer Freigabeansicht
(„übertragen von Referat 50 am 14.03.2026")" (Zeilen 1192–1194). Bei einer Person als Quelle wird
daraus „übertragen von Frau M. am 14.03.2026" — ein dauerhafter, an vielen Objekten wiederholter
Hinweis auf das Ausscheiden einer benannten Person, außerhalb jeder Protokollfrist. Er sollte den
Vorgang nennen, nicht die Person, oder derselben Aufbewahrungshöchstdauer unterliegen wie die
Historie (D1).

### Z5 — Die Kennzeichnung „Nachfolge offen" am Objekt macht eine Kontosperre für Kollegen sichtbar

Abschnitt 7.3, Punkt 5 (Zeilen 895–902) ist neu; er geht auf die Referatsleitung zurück:

> „Ein Asset oder Space im Zustand ‚Nachfolge offen' trägt die Kennzeichnung in seiner Übersicht und
> Detailansicht **für jeden, der es lesen darf**, samt Adressat („Nachfolge offen seit 14.03.2026 —
> zuständig: Systemverwaltung")."

Für ein Asset im Eigentum einer **Person** ist dieser Zustand nach Abschnitt 7.2 eindeutig: Er tritt
ein, wenn das Konto der Eigentümerin gesperrt wird. Die Kennzeichnung ist damit für jeden
Leseberechtigten eine datierte Auskunft über den Kontostatus einer benannten Kollegin — denn wem die
Bibliothek gehört, steht daneben.

*Situation:* Frau M. betreut vier Upload-Bibliotheken. Ihr Konto wird gesperrt — im Verzeichnis ist
ein Attribut gesetzt worden, aus welchem Anlass auch immer: Ausscheiden, Abordnung, längere Abwesenheit,
ein laufendes Verfahren. Am nächsten Morgen tragen alle vier Bibliotheken für jeden Leseberechtigten
den Vermerk „Nachfolge offen seit 19.09.2026". Der Vermerk sagt nicht, warum. Genau deshalb wird er
ausgelegt. Das Produkt hat einen Verwaltungszustand zu einer Statusmeldung über eine Person gemacht.

Die Absicht ist unstrittig richtig — niemand soll eine Quelle zitieren, die seit zwei Jahren niemand
fachlich verantwortet, und der Ausschluss von Suchtreffern und Quellenverweisen (Zeilen 899–902) ist
eine gute Abwägung. Es geht nur um die Formulierung der Anzeige.

> **Z5:** Die Kennzeichnung am Objekt nennt den **Zustand und den Adressaten**, nie den bisherigen
> Eigentümer und nie den Grund. Der Zeitpunkt der Erstfeststellung ist für den allgemeinen
> Leseberechtigten nicht erforderlich — er gehört in die Betriebsliste, in der er fachlich gebraucht
> wird. Die Detailansicht, die den Eigentümer ohnehin führt, stellt die beiden Angaben nicht in
> denselben Satz. Prüfbar an: #1819, Oberflächentext und Abnahmekriterium.

### Z6 — Das Zuwachssignal hebt A2 an der Freigabeansicht auf

Abschnitt 9b, Zeilen 1153–1160:

> „Jeder Grant und jede Space-Mitgliedschaft an eine Gruppe speichert die **Zahl aktiver Mitglieder zum
> Zeitpunkt der Erteilung**; die Freigabeansicht zeigt beide Zahlen („Referat 50: 23 bei Erteilung,
> heute 41")."

Wir haben in A2 erreicht, dass die Subjekt-Auswahl unterhalb der Mindestgruppengröße „kleine Gruppe"
statt einer Zahl ausgibt (übernommen, Zeile 1123). Beim Zuwachssignal fehlt dieselbe Unterdrückung.
Für eine geschützte Gruppe — die nach Entscheidung 9 in fremden Listen **ohne Namen** erscheint und
deren Mitgliederliste der Grant-Geber ausdrücklich **nicht** sieht — steht in der Freigabeansicht dann
„geschützte Gruppe: 2 bei Erteilung, heute 3". Der Name ist geschützt, die Größe nicht, und bei diesen
Gruppen ist die Größe die eigentliche Auskunft. Das ist genau der Fall, den A2 abwenden sollte, an
einer Stelle, die es bei der ersten Bewertung noch nicht gab.

Hinzu kommt eine Nebenwirkung, die unabhängig von der Gruppengröße ist: Da Grants historisiert werden,
entsteht über die Zeit eine datierte **Reihe** von Mitgliederzahlen je Gruppe — ein Personalbestand
einer Organisationseinheit im Zeitverlauf, der die Gruppe selbst überdauert. Unter D1 verfällt er; ohne
D1 nicht. Das ist ein weiteres Argument dafür, dass D1 die tragende Bedingung bleibt.

> **Z6:** Die Unterdrückungsregel aus A2 gilt für **beide** Zahlen des Zuwachssignals: unterhalb der
> Mindestgruppengröße „kleine Gruppe" statt einer Zahl, einschließlich der Werte, die sich aus dem
> Vergleich errechnen ließen. Für geschützte Gruppen entfällt das Signal ganz; der Grant-Geber erhält
> stattdessen die Verantwortlichen als Ansprechstelle, wie in Abschnitt 9b für die Mitgliederliste
> bereits festgelegt. Prüfbar an: #1815/#1820, Komponententest.

### Z7 — Feststellungslauf, Sichtungsvermerk und Betriebsliste erzeugen eine Arbeitsspur über die Systemverwaltung

Die Systemverwaltung besteht ebenfalls aus Beschäftigten. Die neuen Elemente aus Abschnitt 7.1
(benannter, stündlicher Feststellungslauf, der Erstfeststellung und Ende schreibt: „Gespeichert wird
nur der **Vorgang**: wann der Zustand erstmals festgestellt wurde, **wer ihn beendet hat**", Zeilen
819–823) und Abschnitt 7.3, Punkt 4 (Alterungsschwelle mit Sichtungsvermerk: „geprüft am …, weiterhin
offen, Grund", Zeilen 889–894) ergeben zusammen mit der vollständigen Betriebsliste eine
datierte Bearbeitungsspur je handelnder Person: wer wie viele Nachfolgen erledigt hat, wie schnell,
welche Einträge unter seiner Zuständigkeit die Alterungsschwelle erreicht haben, und wer wie oft nur
gesichtet statt erledigt hat.

Das ist ein kleiner Kreis von Beschäftigten, und die Daten entstehen aus einem legitimen Zweck. Aber es
ist der klassische Fall: Betriebsdaten, aus denen sich Bearbeitungsleistung einzelner Personen ableiten
lässt, ohne dass es jemand beabsichtigt hat. Unsere Regel E1 („Einstieg über das Objekt, nie über eine
Person") haben wir für den **früheren Eigentümer** formuliert; sie schützt den handelnden Verwalter
nicht.

> **Z7:** E1 gilt für die gesamte Betriebsliste und in beide Richtungen: kein Einstieg, keine
> Sortierung, keine Filterung und keine Zählung nach der **handelnden** Person — weder nach dem, der
> einen Vorgang beendet hat, noch nach dem, der einen Sichtungsvermerk gesetzt hat. Die Angabe steht am
> Vorgang und ist dort lesbar; sie ist keine Auswertungsachse. Prüfbar an: #1819, Abnahmekriterium; kein
> API-Parameter für Sortierung oder Filterung nach Akteur.

### Geprüft, ohne Befund

- **Kontosperren unter Schwelle und Bestätigungsweg (Abschnitt 4.3a, Zeilen 599–606).** C1 bis C3
  sind vollständig und wörtlich umgesetzt; der Fall der vierzig am Morgen ausgesperrten Beschäftigten
  ist beantwortet. Der neue Bestandteil — die **Kontozustandshistorie** (Zeile 1052) — ist eine
  neue personenbezogene Dauerdatenspur (jede Sperre und Entsperrung mit Datum, über Jahre). Sie ist
  jedoch sachlich zwingend: Ohne sie ist „Alle Konten" zum Stichtag nicht rekonstruierbar und eine
  rückholbare Sperre nicht bruchfrei. Entscheidend ist, dass sie unter Entscheidung 8 fällt — D1
  (Höchstdauer), D2 (Personen-Einstieg nur mit Vier-Augen-Vollmacht), D3 (jeder Abruf ein Ereignis).
  Das ist sie ausdrücklich (Tabelle Zeilen 1042–1055). **Ein Nachsatz:** Die Kontozustandshistorie darf
  nicht neben der Stichtagsauskunft noch einen zweiten, ungeschützten Lesepfad in der
  Benutzerverwaltung bekommen („Verlauf" am Konto). Der ADR sollte das ausschließen; sonst ist D2
  umgangen wie in Z3.
- **Erweiterter Geltungsbereich der Vollmacht „Sicht als" (Abschnitt 4.3b, Zeilen 607–616).** Neu und
  folgenreich: Heute lehnt `DiagnosticImpersonationGrantService#grant` jeden Geltungsbereich ab, der
  keine `ORG_UNIT`-Gruppe ist. In einem Haus im Token-Modus entstehen keine `ORG_UNIT`-Gruppen — die
  Vollmacht ist dort **nicht vergebbar**, der Personenkontext also faktisch abgeschaltet. Die
  Erweiterung auf jede Anbietergruppe macht ihn dort erstmals fahrbar. Wir widersprechen dem **nicht**:
  Eine Token-Gruppe „Referat 50" ist dieselbe Organisationseinheit, und eine Schutzwirkung, die nur aus
  einer unfertigen Konnektorlage folgt, ist keine. Die Kopplung an die Mindestgruppengröße ist die
  richtige Ergänzung, und sie ist im Papier enthalten. **Ein Nachsatz, gleiche Bauart wie B2:** Das
  Papier sagt nicht, **wann** die Mindestgruppengröße geprüft wird. Bei einer Token-Gruppe ändert sich
  die Mitgliedschaft mit jeder Anmeldung; eine Gruppe, die bei Erteilung sieben aktive Mitglieder
  hatte, kann ein halbes Jahr später eines haben — und die Vollmacht wäre dann eine personengebundene
  Erlaubnis ohne die Schutzmechanik des Personenkontexts. Die Prüfung gehört **zum Zeitpunkt der
  Nutzung**, nicht nur zum Zeitpunkt der Erteilung. Genau diese Unterscheidung war der Kern von B2.
- **Alterungsschwelle (12 Monate) und Sichtungsvermerk als Ersatz der Pflichtsichtung.** Gegenüber
  einer erzwungenen Frist ist das die für die Beschäftigten günstigere Lösung: kein Automatismus, keine
  Eskalation nach oben, kein Druck, der bei den Betroffenen landet. E3 bleibt gewahrt. Der Befund zur
  Arbeitsspur steht oben unter Z7, er betrifft nicht die Schwelle selbst.
- **Feststellungslauf statt gespeichertem Flag (Abschnitt 7.1).** Fachlich eine Verbesserung
  („Alter" heißt sonst „seit dem letzten Hinsehen"), ohne eigenen Personenbezug. Die Nachfolgevorgänge
  unterliegen ausdrücklich der Protokollfrist, nicht der Rechtehistorie (E3, Zeilen 832–833) — so
  gefordert, so übernommen.
- **Erweiterter Kreis für Mitgliederlisten (Entscheidung 9).** Neu ist, dass jeder, der einer Gruppe an
  einem Objekt ein Recht einräumt, ihre **vollständige Mitgliederliste** sieht — in der ersten Fassung
  waren es nur Verantwortliche und `SYSTEM_ADMIN`. Das ist eine spürbare Ausweitung, und sie wiegt
  schwerer als der A1-Punkt, über den ausdrücklich gestritten wurde. Wir tragen sie mit, weil die
  Begründung der Referatsleitung richtig ist („ich gebe frei, ohne zu wissen, an wen"), weil sie an eine
  Bedingung geknüpft ist („solange die Gruppe dort ein Recht hält", Zeile 1122), weil interne Gruppen
  erst nach ausdrücklicher **Freigabe zur Verwendung** überhaupt wählbar sind (Vorgabe: nicht
  freigegeben) und weil geschützte Gruppen ausgenommen sind. Diese vier Begrenzungen tragen die
  Ausweitung — sie müssen deshalb alle vier im ADR stehen, nicht nur die Regel. Fällt eine weg, ist die
  Ausweitung neu zu bewerten.

---

## 4. Was wir weiterhin nicht beurteilen können

- Ob das Vorhaben im konkreten Haus mitbestimmungspflichtig ist. Die einschlägige Frage bleibt die
  Mitbestimmung bei technischen Einrichtungen, die zur Überwachung von Leistung oder Verhalten
  **geeignet** sind — die Eignung genügt. Welche Norm gilt, hängt davon ab, ob Bund oder Land; die
  Prüfung gehört zur Rechtsstelle.
- Ob die Rechtehistorie und die neue Kontozustandshistorie datenschutzrechtlich als
  Protokolldatenbestände mit eigener Rechtsgrundlage tragfähig sind — Sache des behördlichen
  Datenschutzbeauftragten.
- Die angemessene Höhe der Mindestgruppengröße, der Aufbewahrungshöchstdauer und der Alterungsschwelle.
  Wir verlangen nur erzwungene Grenzen und nachlesbare Werte.
- Die Kosten der Schnittmengen-Prüfung (B2) und die technische Frage, ob der bestehende
  Pseudonym-Mechanismus für die Rechtehistorie wiederverwendbar ist (Z3b).
- Ob der Kreis, der nach Entscheidung 9 Mitgliederlisten sieht, in der Praxis so eng bleibt, wie die
  vier Begrenzungen es vorsehen. Das zeigt der Testzugang nach F2, nicht das Papier.

---

## 5. Zusammenfassung der verbleibenden Bedingungen

| Nr. | Bedingung | Gewicht | Prüfbar an |
|---|---|---|---|
| **Z1** | Space-Kontext im Rechteprofil erhält ein eigenes Umsetzungs-Issue mit den Punkten 1, 4, 5, 7, 8 als Abnahmekriterien; bis dahin nimmt `SearchDiagnosisRequest` keine Space-ID an | **Bedingung unseres Ja zu Empfehlung 7** | Issue, OpenAPI, Test auf `403` |
| **Z2** | Bei geschützten Gruppen zeigt die Herleitung Dritten keine Gruppenableitung | hoch | #1820/#1822, Test |
| **Z2b** | Wer das Schutzkennzeichen an einer **Anbietergruppe** setzt, wird benannt — sonst ist A3 für Verzeichnisgruppen nicht eingelöst und als solches auszuweisen | hoch | ADR, Abschnitt 9b |
| **Z3** | Begründung von D7 korrigieren; Objekt-Einstieg beantwortet genau ein benanntes Objekt je Abfrage | hoch | ADR, OpenAPI zu #1822, Test |
| **Z3b** | Je neuer Historientabelle ausweisen: `RESTRICT` oder pseudonymisiert | mittel | ADR, Entscheidung 8 |
| **Z4** | Person als Quelle der Übertragung: Umfang auf Eigentum und Verantwortung begrenzt; Vorschau ist Protokollereignis auch bei Abbruch | **hoch — neuer Personeneinstieg** | Issue zur Übertragung, OpenAPI, Test |
| **Z5** | Kennzeichnung „Nachfolge offen" am Objekt nennt Zustand und Adressat, nicht Eigentümer und nicht Grund | mittel | #1819, Oberflächentext |
| **Z6** | A2-Unterdrückung gilt für beide Zahlen des Zuwachssignals; für geschützte Gruppen entfällt es | mittel | #1815/#1820, Test |
| **Z7** | E1 gilt auch für die handelnde Person: keine Sortierung, Filterung oder Zählung nach Akteur in der Betriebsliste | mittel | #1819, kein API-Parameter |
| — | Nachsatz B5: erzwungene Untergrenze als **Zahl** in der Konfigurationstabelle | niedrig | Handbuch |
| — | Nachsatz 4.3b: Mindestgruppengröße der „Sicht als"-Vollmacht wird **zum Zeitpunkt der Nutzung** geprüft | mittel | #1150-Nacharbeit, Test |
| — | Nachsatz Kontozustand: kein zweiter Lesepfad („Verlauf" am Konto) neben der Stichtagsauskunft | mittel | ADR, OpenAPI |

Die 28 Bedingungen der ersten Bewertung sind damit erledigt: 26 übernommen, 2 geändert (A1, D7) und
von uns mitgetragen. F4 ist eingelöst — Abschnitt 12.2 weist jede Antwort einzeln und begründet aus,
einschließlich der Zurückweisungen gegenüber anderen Perspektiven.

---

## 6. Gesamturteil

**Tragfähig mit Auflagen. Empfehlung 7 ist mitbestimmungsfähig unter der Bedingung Z1.**

Die zweite Fassung hat die Arbeit gemacht: Der Widerspruch bei der Diagnose ist aufgelöst, die
Rechtehistorie hat eine eigene Entscheidung mit Höchstdauer, Vollmacht und Abrufprotokoll bekommen,
und die Sichtbarkeitsfrage ist an einer Stelle statt an vieren entschieden. Zwei unserer Bedingungen
wurden geändert, beide mit einer nachvollziehbaren Begründung, eine davon mit einer nachweislich
falschen Prämisse unsererseits — die korrigieren wir.

Was wir mitgeben: Jede der drei Ausweitungen dieser Fassung — der Kreis für Mitgliederlisten, die
Übertragungsoperation, die Kennzeichnung am Objekt — ist aus einem guten Grund entstanden, und jede
hat an ihrem Rand eine Stelle, an der ein bereits zugesagter Schutz nicht mitgewandert ist. Das ist
kein Vorwurf, sondern die Regelmäßigkeit, wegen der es unsere Rolle gibt: Schutzregeln wandern nicht
von selbst mit den Funktionen, denen sie zugeordnet waren. Z2, Z4 und Z6 sind genau diese drei
Stellen.

Der Punkt, den wir am stärksten gewichten, ist Z4. Entscheidung 8 hat die personenbezogene
Rechteauskunft für die Vergangenheit sorgfältig eingehegt — Vier-Augen-Vollmacht, Zweck, Zeitfenster,
Abrufprotokoll. Entscheidung 10 stellt dieselbe Auskunft für die Gegenwart als Formularvorschau daneben.
Wird das nicht korrigiert, haben wir eine Dienstvereinbarung, die den langen Weg regelt, während der
kurze offen steht.

---

*Diese Bewertung ist beratend. Sie ersetzt weder die rechtliche Prüfung der Mitbestimmungspflicht noch
die Stellungnahme des behördlichen Datenschutzbeauftragten. Die Zusagen dieses Papiers prüfen wir vor
dem Rollout am Testzugang nach F2 selbst nach — das ist der Unterschied zwischen einer Zusage und
einer überprüfbaren Regelung.*
