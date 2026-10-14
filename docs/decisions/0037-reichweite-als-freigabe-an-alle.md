# ADR-0037: Organisationsweite Reichweite als Freigabe an „Alle Konten", `visibility` entfällt

## Status

Akzeptiert · zuletzt geändert 2026-10-03 · geändert durch
[ADR-0039](0039-ein-katalog-und-ausdrueckliche-space-zuordnung.md) (Entscheidung 4 und die
`listed`-Teile von 5, 6, 7 und 9) · ändert
[ADR-0036](0036-berechtigungsmodell-gruppen-und-faehigkeiten.md) (Entscheidungen 5 und 12) · betrifft
[ADR-0032](0032-zeitquelle-rechtehistorie.md) und die Freigabe-Obergrenze aus #797

## Kontext

Ein Asset trug neben seiner Rechteliste zwei Angaben über seine Reichweite: `assets.visibility`
(`PRIVATE` | `SHARED` | `ORGANIZATION`, von denen nur `ORGANIZATION` wirkte — Lesezugriff für jeden der
Organisation) und `assets.listed` (Auffindbarkeit im Katalog). `PRIVATE` und `SHARED` unterschied
nichts im Code, und Spezifikation und Javadoc meinten mit `SHARED` Verschiedenes.

Kosten: „Wer erreicht dieses Asset?" wurde an zwei Stellen beantwortet (Grants und Schale), die
Rechtehistorie führte beides getrennt, die Rechteformel reichte `organizationWide` als Sonderparameter
durch sechs Signaturen, und „erweitert die Reichweite" gab es in zwei Fassungen. Die Oberfläche zwang zu
einer Stufenwahl, die aus den Grants ablesbar war. Die Freigabe-Obergrenze (#797) deckelte ein Enum mit
Ordinalvergleich. Für „alle Konten der Organisation" als Rechtesubjekt gab es bereits das Muster
`CapabilitySubjectType.ALL_ACCOUNTS` der Anlegerechte (ADR-0036, Entscheidung 5).

## Entscheidung

### 1. `visibility` entfällt; organisationsweite Reichweite ist eine gewöhnliche Freigabe

Ein Asset hat genau eine Rechteliste. Empfänger sind Personen, Gruppen und **„Alle Konten"** (alle
Konten der Organisation des Assets): sichtbar in derselben Liste, in derselben Grant-Historie, über
denselben Empfänger-Picker, widerrufen über denselben Knopf.

```
lesbare_Assets(u) = { A : Freigabe(u, A) ≥ VIEWER }
                  ∪ { A : Freigabe(g, A) ≥ VIEWER für eine Gruppe g mit u ∈ g }
                  ∪ { A : Freigabe(ALLE, A) ≥ VIEWER und gleiche Organisation }
```

Der einzige Sonderpfad ist ein Zweig in `AssetAccessService#reaches`: Eine Freigabe an „Alle" erreicht
jeden. **Die Rolle ist gedeckelt auf `EDITOR`** (`AssetGrantService#upsertGrant`, `400`): `MANAGER` und
`OWNER` sind Zuständigkeiten, die eine benannte Person oder Gruppe trägt, nie „alle"; außerdem zählt
`AssetGrantRepository#countOtherActiveOwnerGrants` nur die Rolle und hätte einen `OWNER` an alle als
Eigentümer gewertet. `EDITOR` ist eine Nutzung und darum zulässig.

### 2. Die Form: dritte Subjektart, kein synthetisches Gruppenobjekt

„Alle Konten" ist keine Zeile in `groups`, sondern die Subjektart `ALL_ACCOUNTS` einer Freigabe mit
beiden Subjektspalten leer (`chk_asset_grants_subject_type`, `chk_asset_grants_subject`, Teilindex
`uk_asset_grants_all_accounts`) — dieselbe Form und Beschriftung wie bei den Anlegerechten. Damit gelten
ohne eigene Regel: Organisationsgrenze (`fk_asset_grants_asset_organization`), der Verzeichnisabgleich
fasst sie nie an, es gibt keine Mitgliederliste und keinen weiteren Sonderpfad in der Rechteformel.

### 3. Eigenes Enum `AssetGrantSubjectType`

`PermissionSubjectType` (`USER`, `GROUP`) trägt auch Space-Mitgliedschaft und Eigentumshistorie; ein
Space mit Mitglied „alle" oder ein Asset, das „allen" gehört, kennt das Modell nicht. Freigaben bekommen
deshalb `AssetGrantSubjectType` (`USER`, `GROUP`, `ALL_ACCOUNTS`); `PermissionSubject` bleibt
`USER`/`GROUP`; `CapabilitySubjectType` bleibt eigenständig.

### 4. `listed`

Entfallen mit #2091, siehe ADR-0039 Entscheidung 3.

### 5. Die Freigabe-Obergrenze (#797) ist ein Boolean am Freigabe-Pfad

`knowledge_libraries.all_accounts_grant_allowed` (ausgeliefert `true`) ersetzt `visibility_cap`; seit
#2091 ist es die ganze Obergrenze. Geprüft wird am Freigabe-Pfad, nicht am Schalen-Pfad:
`AssetTypeDefinition#requireAllAccountsGrantAllowed`, aufgerufen aus `AssetGrantService#upsertGrant`,
antwortet `409` mit dem Text der Systemverwaltung — die Rolle der Person steht nicht in Frage, der
verlangte Zustand widerspricht einer Decke. Das Absenken der Obergrenze **widerruft die Freigabe an
„Alle"** sofort über den Freigabe-Pfad (Grant-Historie, Audit, Cache-Invalidierung), ohne die
`MANAGER`-Prüfung von `revokeGrant`, weil die Systemverwaltung aufruft; einen Zustand „verletzt, aber
geduldet" gibt es nicht.

### 6. Nachfolge-Sperre: eine Formel

Eine Freigabe an „Alle" ist eine neue Freigabe wie jede andere und wird von
`AssetGrantService#requireReachNotFrozenIfWidening` gesperrt; die Reichweitensperre liegt seit #2091
allein am Freigabe-Pfad.

### 7. Historisierung und Audit

`asset_visibility_history` bleibt bestehen und trägt seit #2091 nur noch die Fremdzugangsfreigabe
(`external_access_state`, `external_access_expires_at`); Tabellen- und Klassennamen wurden bewusst nicht
umbenannt. Die Freigabe an „Alle" wird über `ASSET_GRANT_GRANTED`/`_CHANGED`/`_REVOKED` protokolliert
mit `"subjectType": "ALL_ACCOUNTS"` im Nutzinhalt (`ASSET_VISIBILITY_CHANGED` hat seit #2091 keinen
Schreiber mehr; der Enum-Wert bleibt, damit alte Protokollzeilen lesbar bleiben) und **ohne Subjekt im
Audit-Eintrag**
(`audit_log.subject_kind` kennt `USER` und `GROUP`): Eine Freigabe an alle ist nicht personenbezogen und
hat in der Pseudonymtabelle nichts verloren.

### 8. Der Rückblick wandert mit

`AccessBasis.ORGANIZATION_WIDE` bleibt als Wert der Herleitung und der Stichtagsauskunft und wird aus
`subject_type = 'ALL_ACCOUNTS'` abgeleitet. Die Migration überführte jedes abgeschlossene und offene
`ORGANIZATION`-Intervall aus `asset_visibility_history` in ein Intervall in `asset_grant_history`
(`ALL_ACCOUNTS`, `VIEWER`, `cause = 'BACKFILL'`, `actor_user_id` aus der Sichtbarkeitshistorie); nur den
lebenden Zustand zu überführen hätte der Stichtagsauskunft für frühere Zeiträume eine falsche
Negativantwort gegeben.

### 9. Bedienoberfläche

„Alle Konten" ist ein eigener Eintrag im Empfänger-Picker mit **eigenem Bestätigungsschritt**, der die
Reichweite ausspricht — die Gegenmaßnahme dagegen, dass eine Freigabe an alle nun ein Klick neben „Team
Recht" ist. Übersichten tragen eine aus den Freigaben **abgeleitete** Reichweite (`reach`,
`AssetReach`: „Alle" oder „N Gruppen, M Personen"; im Katalog öffentlich = Freigabe an „Alle", sonst
geschlossen, `CatalogVisibility`), kein gespeichertes Feld.

### 10. `SpaceVisibility`

Entfallen mit #2156: `spaces.visibility` ist entfernt, jeder Space ist privat und nur seinen Mitgliedern
bekannt.

## Konsequenzen

**Einfacher:** „Wer erreicht dieses Asset?" wird aus einer Tabelle beantwortet; Rechteformel, Historie,
Stichtagsauskunft und Herleitung lesen dieselbe Quelle. Sechs Signaturen verlieren einen Parameter, zwei
Fassungen von „erweitert die Reichweite" werden eine, ein Enum mit bedeutungstragender Reihenfolge
verschwindet. Der Widerruf ist ein gewöhnlicher Widerruf.

**Schwieriger:** Eine Freigabe an alle ist leichter zu vergeben als die Stufe; dagegen stehen
Bestätigungsschritt, Audit, Obergrenze und Nachfolge-Sperre. `EDITOR` an „Alle" lässt jedes Konto
Dokumente hochladen und löschen — bewusst offen, wer es nicht will, vergibt `VIEWER`. Drei
Subjekt-Enums stehen nebeneinander, zwei mit demselben dritten Wert.

**Restrisiko:** Die Migration ist nicht vollständig rückrollbar; vor Produktionsbetrieb hinnehmbar.

## Verworfene Alternativen

- `visibility` behalten und nur `SHARED` streichen — löst die Doppelung nicht auf; ein Boolean an der
  Schale ist eine Freigabe an alle ohne Historie, Rollenwahl und Platz in der Liste.
- „Alle" als Rolle statt als Empfänger — `AssetRole` ist eine Ordnung (`bestRole`,
  `requireCallerRoleAtLeast`); ein Wert außerhalb bräche sie.
- Systemseitig angelegte Gruppe je Organisation — müsste aus jedem Weg, der Gruppen verarbeitet, einzeln
  herausgehalten werden (Gruppenliste, Abgleich, Verantwortliche, Übertragung, Offenlegung,
  Space-Mitgliedschaft, Löschung); `activeMemberCount` lieferte `0`.
- Obergrenze als Grenze der Empfängerzahl — eine Grenze, die von einer außerhalb veränderlichen Größe
  abhängt, sperrt nach der nächsten Verzeichnisübernahme gestern erlaubte Arbeit.

## Offen

- Bewertung durch die Personalvertretung: Eine Freigabe an „Alle" benennt weder Person noch Gruppe;
  vorzulegen sind Bestätigungsschritt und Widerrufbarkeit. Blockiert nichts.

## Änderungsverlauf

| Datum | Issue/PR | Änderung (eine Zeile) | Entscheidungen |
|---|---|---|---|
| 2026-09-24 | #1931 | Erstfassung, umgesetzt mit #1945 | 1–10 |
| 2026-10-02 | #2091 | `listed`, `listed_cap` und der Katalog „lesbar ∪ gelistet" entfallen (ADR-0039) | 4, 5, 6, 7, 9 |
| 2026-10-03 | #2134 | Zuwachssignal entfällt (ADR-0036, Entscheidung 9) | — |
| 2026-10-03 | #2156 | `spaces.visibility` entfernt | 10 |
| 2026-10-14 | #2701 | Neufassung auf den gültigen Endstand | — |
