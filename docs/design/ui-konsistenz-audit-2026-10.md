# UI-Konsistenz-Audit (#3265) — Befundliste

**Datum:** 28.10.2026 · **Prüfgegenstand:** `main` nach #3272 (Anlege-Knöpfe), #3271, #3274 und
#3275 · **Prüfmaßstab:** [Design-Guidelines](./guidelines.md), [UI-Prüfliste](./ui-pruefliste.md) ·
**Teil von:** Epic #3269 (UI-Review vom 28.10.2026)

## 1 · Umfang und Methode

Geprüft wurden fünf Kategorien aus dem UI-Review quer über alle Bereiche (Spaces, Chat, Katalog,
Wissens- und Prompt-Bibliotheken, Einstellungen, Systemverwaltung, Revision):

| Kategorie | Frage |
|---|---|
| K-1 Hinzufügen-Aktionen | Knopf oder Textaktion, Plus-Icon, Beschriftung, Position |
| K-2 Drei-Punkte-Menüs | Icon, Tooltip, zugänglicher Name, Menüaufbau |
| K-3 Link oder Knopf | Semantik (`href` oder `button`) und Optik |
| K-4 Badges und Chips | Radius, Gewicht, Größe, Farbe (Leitlinie 5.5) |
| K-5 Erklärtexte | Länge von Seitenbeschreibungen und Hilfetexten (Prüfliste P13) |

**Methode.** Code-Analyse aller Produktivdateien unter `frontend/src/` (Skripte über JSX-Knöpfe,
Links, Textaktionen, Chips, `borderRadius`-Werte und lange Textstellen), dazu Durchgänge im
Browser mit Mock-Daten (`VITE_ENABLE_MOCKS=true`, Chromium, 1280 px) auf Spaces, Space-Einstellungen
(Stammdaten, Mitglieder, Inhalte), Katalog, Bibliotheks- und Prompt-Detailseiten, Konten, Gruppen,
Modellen, Identitätsanbietern, Anlegerechten, Zugängen und Einstellungen. Im Browser wurden alle
Menü-Auslöser (`aria-haspopup`) und alle Elemente mit Rahmen unter 30 px Höhe ausgelesen
(berechneter Radius, Gewicht, Größe).

**Nicht als Befund gewertet:** Bereiche, die parallele PRs zum Zeitpunkt des Audits umbauen —
Belegfenster (#3273), Eingabehinweis der Chat-Eingabe (#3277); Quellformulare der
Wissensbibliotheken (UI-Lab, #3084).

**Fundstellen** nennen Datei und Zeile unter `frontend/src/` zum Stand dieses PRs.

## 2 · Ergebnis in Kürze

| Kategorie | Befunde | In diesem PR behoben | Folge-Issue |
|---|---|---|---|
| K-1 Hinzufügen-Aktionen | 6 | – (Regel in Leitlinie 5.1) | #3285 |
| K-2 Drei-Punkte-Menüs | 5 | Icon und Tooltip in 13 Komponenten (11 Dateien geändert) | #3284 |
| K-3 Link oder Knopf | 6 | – (Regel in Leitlinie 5.1) | #3287 |
| K-4 Badges und Chips | 7 | Pill und Gewicht 500 an 7 Etikett-Bauten | #3283 |
| K-5 Erklärtexte | 4 | – (Regel in Leitlinie 6) | #3286 |

## 3 · Befunde

### K-1 · Hinzufügen-Aktionen

Die Anlege-Knöpfe über Übersichten sind seit #3272 einheitlich (Plus-Icon, Objekt in der
Beschriftung, oben rechts). Die Befunde betreffen das Hinzufügen innerhalb von Seiten.

| # | Fundstelle | Befund | Bezug |
|---|---|---|---|
| K1-1 | `space/components/SpaceMembersSection.tsx:250`, `group/components/MyGroupsSection.tsx:264`, `group/components/GroupMembersDialog.tsx:180`, `space/pages/SpaceCreatePage.tsx:290`, `asset/components/AssetRightsFields.tsx:103`, `asset/components/AssetGrantsSection.tsx:399` | Dieselbe Handlung „Person oder Gruppe hinzufügen" heißt „Hinzufügen", „Mitglied hinzufügen", „Aufnehmen", „Vormerken" und „Freigeben" | 5.1 (Verb), P19 |
| K1-2 | `group/components/MyGroupsSection.tsx:243–265`, `group/components/GroupMembersDialog.tsx:160–181` | Formularzeile mit `UserPicker` und kleinem Primärknopf nachgebaut statt `SubjectFormRow` (Space-Mitglieder, Freigaben, Assistenten) | 5.2 Personen- und Gruppensuche |
| K1-3 | `space/components/SpaceMembersSection.tsx:228`, `group/components/MyGroupsSection.tsx:254`, `group/components/GroupMembersDialog.tsx:171` | Absenden ohne Auswahl deaktiviert | P28 |
| K1-4 | `group/pages/GroupManagementPage.tsx:72` ↔ `group/components/MyGroupsSection.tsx:318`; `identity-provider/pages/OidcProviderManagementPage.tsx:73` ↔ `:135`; `space/pages/SpacesOverviewPage.tsx:79` ↔ `app/shell/Sidebar.tsx:246` | Ein Objekt, zwei Anlege-Beschriftungen: „Gruppe anlegen"/„Neue Gruppe", „Neuer Anbieter"/„Anbieter anlegen" (Kopf und Leerzustand derselben Seite), „Neuer Space"/„Neuen Space anlegen" | 5.1 Anlege-Knöpfe |
| K1-5 | `space/components/SpaceMembersSection.tsx:196` | „Mitglied hinzufügen" als Eyebrow-Überschrift (Mono, Versalien) über der Formularzeile; „Meine Gruppen" ohne Überschrift | 5.12, 3 |
| K1-6 | `library/components/metadata/AddValueButton.tsx:42`, `library/components/LibrarySourceSection.tsx:121` | Hinzufügen im Abschnitt als Textknopf bzw. Knopf statt `TextAction` (sonst: „Freigeben" `AssetGrantsSection.tsx:424`, „In Space verwenden" `AssetSpacesSection.tsx:38`, „Neuer Ordner" `LibraryDocumentsSection.tsx:339`, „Feld anlegen" `library/components/metadata/LibraryMetadataFieldsSection.tsx:244`) | Lücke → 5.1 ergänzt |

### K-2 · Drei-Punkte-Menüs

| # | Fundstelle | Befund | Bezug | Stand |
|---|---|---|---|---|
| K2-1 | `model/components/ModelRowMenu.tsx`, `identity-provider/components/ProviderRowMenu.tsx`, `external-access/components/ExternalAccessTokenAdminSection.tsx`, `chat/components/ChatList.tsx` (Chat-Liste der Seitenleiste), `space/components/SpaceMemberList.tsx` (Space-Mitglieder), `library/components/LibraryDocumentsSection.tsx` (Ordner), `asset/pages/CatalogPage.tsx` (Kachel) | Kein Tooltip „Weitere Aktionen" am Nur-Icon-Knopf; bei Konten, Gruppen und Chats im Kopf vorhanden | 5.1 Nur-Icon-Schaltflächen | behoben |
| K2-2 | `LocalUserRowMenu.tsx`, `ProviderAccountRowMenu.tsx`, `GroupRowMenu.tsx`, `ChatRowMenu.tsx`, `ModelRowMenu.tsx`, `ProviderRowMenu.tsx`, `ExternalAccessTokenAdminSection.tsx`, `ChatList.tsx` | Senkrechte Punkte (`MoreVert`) in Verwaltungslisten und Chat-Liste, waagrechte (`MoreHoriz`) in Bibliothek, Space-Mitgliedern, Katalog und `RowMenu` — „das Menü sieht anders aus" | 5.4 („⋯"), Lücke → 5.6 ergänzt | behoben |
| K2-3 | alle oben, dazu `asset/components/AssetDetailHeader.tsx:165` | Zugänglicher Name in sieben Formen („Aktionen für „X"", „Weitere Aktionen für ‚X'", „Optionen für Ordner X", „Aktionen für Dokument X", „Aktionen für das Token „X" von Y", nur „Weitere Aktionen") | 5.1, P19 | #3284 |
| K2-4 | `ModelRowMenu.tsx`, `ProviderRowMenu.tsx`, `LocalUserRowMenu.tsx` (`"true"`); `ExternalAccessTokenAdminSection.tsx`, `LibraryDocumentsSection.tsx` (fehlt) | `aria-haspopup` uneinheitlich (`"menu"`, `"true"`, ohne) | 5.6 | #3284 |
| K2-5 | `shared/ui/RowMenu.tsx`, `asset/pages/CatalogPage.tsx` | Menüaufbau: in `RowMenu` ist jeder Eintrag ohne `danger: false` rot, andernorts nur „Löschen" am Ende mit Trennlinie; nur das Katalogmenü trägt Icons vor den Einträgen; zwölf eigene Menü-Nachbauten | 5.6 | #3284 |

### K-3 · Link oder Knopf

Die **Semantik** stimmt überall: Navigation ist `href` oder Router-Link, jede Textaktion mit
Handlung ist `component="button"`. Befunde betreffen die Optik und Nachbauten.

| # | Fundstelle | Befund | Bezug |
|---|---|---|---|
| K3-1 | `asset/components/AssetSpacesSection.tsx:38` | „In Space verwenden" in der Bibliothek sieht aus wie ein Link, öffnet aber einen Dialog. Das ist das Muster des Abschnittskopfs (`DetailSection`, „Freigeben", „Feld anlegen"), war aber nirgends als Regel beschrieben | Lücke → 5.1 ergänzt |
| K3-2 | `app/areas/SettingsPage.tsx:183`, `directory-sync/components/PendingPlanHint.tsx:63`, `succession/components/SuccessionRow.tsx:162`, `space/components/SpaceKnowledgeNotice.tsx:40` ↔ `capability/components/CapabilityAccessPanel.tsx:216`, `connection/components/ProfileChangePreview.tsx:60`, `library/components/LibraryRunList.tsx:273` | Gleichartige Verweise einmal als Umriss-Knopf, einmal als Textlink | P19 |
| K3-3 | `library/components/LibraryRunList.tsx:273`, `external-access/components/OwnTokensLink.tsx:8`, `model/components/EmbeddingChangeSection.tsx:180` | Pfeile in Linktexten uneinheitlich („›", „→") | P19 |
| K3-4 | `library/components/LibraryCreateFooter.tsx:55`, `library/components/setup/SourceSetupRows.tsx:366` ↔ `space/pages/SpaceCreatePage.tsx:426` | „Abbrechen" im Bibliotheksassistenten als Textlink (Variante F), in allen anderen Assistenten und Dialogen als stiller Knopf | Lücke, Entscheidung in #3287 |
| K3-5 | `TEXT_LINK` in `library/components/LibraryReviewStep.tsx`, `LibraryCreateFooter.tsx`, `setup/SummaryRow.tsx`, `setup/SourceSetupRows.tsx`, `sourceKit/SelectionDialog.tsx`; eigene Links in `principal/components/GroupMembersDisclosure.tsx`, `space/components/SpaceMemberList.tsx:276` | Textaktionen je Thema nachgebaut statt `TextAction` | 5.1 (ergänzt) |
| K3-6 | `asset/components/UseInSpaceButton.tsx:170`; `library/components/LibrarySourceSection.tsx:366` | `UseInSpaceButton` wird nicht mehr verwendet (toter Code); „Vollabgleich starten" startet einen Lauf, steht aber als Textaktion neben „Ändern" | 5.1 |

### K-4 · Badges und Chips

Leitlinie 5.5 verlangt Pill-Radius, 12 px, Gewicht 500 und keine Signalfarben. Der
MUI-`Chip` war über das Theme schon rund; alle selbst gebauten Etiketten waren eckig (4 px)
und normal (400).

| # | Fundstelle | Befund | Bezug | Stand |
|---|---|---|---|---|
| K4-1 | `shared/ui/MetaBadge.tsx:26` (Rollen, Herkunft, Gruppen „extern", Kontenhinweise, Space-Rollen u. a., 50 Verwendungen), `shared/ui/GlobalBadge.tsx:25`, `asset/components/assetMarks.tsx:76, 104, 131` (Art, „Privat", „Wird gelöscht" auf Kachel und Detailseite), `chat/components/ChatActionMenu.tsx:172`, `chat/components/ChatInput.tsx:521` | Eckig (4 px) und Gewicht 400 neben runden Chips mit Gewicht 500 (Kacheln, Benutzergruppen ↔ Anlegerechte, „Alle Konten") | 5.5 | behoben: `badgeShape` in `shared/theme/tokens.ts`, Vorschlagslisten nutzen `MetaBadge` |
| K4-2 | `shared/theme/theme.ts` (`MuiChip` 11 px), `MetaBadge` und Asset-Marken 10,5 px, `GlobalBadge` 9,5 px, `capability/components/AccessBadge.tsx:32` 12,5 px | Vier Schriftgrößen, keine davon die 12 px der Leitlinie | 5.5 | #3283 |
| K4-3 | `connection/components/ConnectedAccountsSection.tsx:112–123`, `search-admin/components/LibraryStatusTable.tsx:325, 332`, `search-admin/components/DiagnosisStagePanel.tsx:119`, `library/components/metadata/DocumentMetadataPanel.tsx:152`, `chat/components/ChatScopeChipBar.tsx:137` | Chips in Signalfarben (`warning`, `error`) | 5.5 „keine Signalfarben" | #3283 |
| K4-4 | `identity-provider/components/ProviderList.tsx:172, 346` ↔ `space/pages/SpacePage.tsx:158` | „Standard" einmal gefüllt in Akzentfarbe, einmal als Akzent-Umriss | 5.5, P5 | #3283 |
| K4-5 | `prompt/pages/PromptLibraryDetailPage.tsx:341` ↔ `library/pages/LibraryDetailPage.tsx:371` | Eigene Rolle im Kopf der Prompt-Bibliothek in Akzentfarbe, der Wissensbibliothek neutral | 5.5, P19 | #3283 |
| K4-6 | `app/shell/Sidebar.tsx:221` ↔ `chat/pages/ChatPage.tsx:324`, `chat/components/ChatSearchResults.tsx:121` | „Archiviert" gefüllt in der Space-Auswahl, als Umriss im Chat | 5.5 | #3283 |
| K4-7 | `user/components/AccountList.tsx` (Spalten Zustand, Rolle) | „Passwortwechsel ausstehend" und „Vom Anbieter geführt" werden in der Zelle abgeschnitten, ohne Tooltip | P22 | #3283 |

### K-5 · Erklärtexte

| # | Fundstelle | Befund | Bezug |
|---|---|---|---|
| K5-1 | `space/components/ModelInstructionField.tsx:26` | Space-Einstellungen, Stammdaten: Hilfetext „Anweisung an das Modell" mit vier Aussagen, drei Zeilen bei 1280 px | P13 (eine Zeile) |
| K5-2 | `space/components/SpaceGeneralSection.tsx:197, 225`, `space/pages/SpacePage.tsx:289`, `space/pages/SpaceCreatePage.tsx:340` | Gefahrenbereich, Bibliotheksliste und Assistentenschritt erklären Zustände in zwei bis drei Sätzen | P13 |
| K5-3 | 22 Seitenbeschreibungen ab 200 Zeichen (ohne Installationsassistent), längste: `group/pages/ProviderGroupWorklistPage.tsx` (481), `library/components/FullSyncRhythmSection.tsx` (431), `succession/pages/SuccessionPage.tsx` (321), `directory-sync/components/DirectorySyncThresholdSection.tsx` (311); `user/pages/UserManagementPage.tsx` drei Zeilen | Seitenbeschreibung erklärt Hintergrund und Ausnahmen statt des Zwecks | Lücke → Leitlinie 6 ergänzt |
| K5-4 | Fließtexte über 300 Zeichen, längste: `search-admin/components/IndexStatusSection.tsx` (569), `connection/components/ConnectionProfilePanel.tsx` (417), `identity-provider/pages/OidcProviderManagementPage.tsx:92` (404), `app/areas/ConnectionSettingsTab.tsx` (384) | Handbuchwissen in der Oberfläche | Leitlinie 6, Wörterbudget der Prüfliste |

Die vollständige Liste mit Zeichenzahlen steht in #3286.

## 4 · Nebenbefunde außerhalb der fünf Kategorien

Beim Durchgang aufgefallen, nicht Gegenstand dieses Audits:

- **Schwebende Beschriftungen** in den Filterfeldern der Kontenliste („Herkunft", „Zustand",
  „Rolle", „Auflage", `user/components/AccountFilterBar.tsx`) — Leitlinie 5.2 verlangt die
  Beschriftung oberhalb, ohne schwebendes Label.
- **„Neue Gruppe" deaktiviert** ohne Anlegerecht (`group/components/MyGroupsSection.tsx:315`) mit
  erklärendem Hinweis darunter — vertretbar, aber abweichend von P28.

## 5 · Leitlinienlücken

| Lücke | Ergebnis |
|---|---|
| Hinzufügen innerhalb einer Detailseite war nur im Code (`DetailSection`) beschrieben | Leitlinie 5.1 „Hinzufügen in einem Abschnitt" ergänzt |
| Keine Regel, wann eine Handlung wie ein Link aussehen darf | Leitlinie 5.1 „Link oder Schaltfläche" ergänzt |
| Form des „⋯"-Auslösers (Ausrichtung, Tooltip) nur in 5.4 für den Katalog | Leitlinie 5.6 „Weitere Aktionen hinter ‚⋯'" ergänzt |
| 5.5 nannte nicht, dass auch selbst gebaute Etiketten gemeint sind | Leitlinie 5.5 „Ein Etikett, eine Form" ergänzt |
| Länge von Erklärtexten nur im UI-Lab geregelt (Prüfliste P13) | Leitlinie 6 „Erklärtexte knapp" ergänzt |
| „Abbrechen" als Textlink oder Knopf | offen, Entscheidung in #3287 |
| Etikett-Schriftgröße: Leitlinie 12 px, Code 9,5–12,5 px | offen, Entscheidung in #3283 |
| Zugänglicher Name des „⋯"-Auslösers | offen, Festlegung in #3284 |

## 6 · Folge-Issues

Alle als Sub-Issues von Epic #3269:

| Issue | Inhalt | Kategorie |
|---|---|---|
| #3283 | Etiketten vereinheitlichen — eine Größe, keine Signalfarben | K-4 |
| #3284 | Drei-Punkte-Menüs auf einen gemeinsamen Baustein umstellen | K-2 |
| #3285 | Hinzufügen-Aktionen und Mitglieder-Formulare vereinheitlichen | K-1 |
| #3286 | Erklärtexte kürzen — Seitenbeschreibungen und Hilfetexte | K-5 |
| #3287 | Link oder Knopf — Navigation, Abbrechen und Textaktionen | K-3 |
