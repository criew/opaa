# UI-Inventar „Wissensbibliotheken“ (Stand `main`, 24.10.2026)

Grundlage: Code unter `frontend/src/features/library/` (+ `shared/ui`, `features/asset`, `features/connection`), Backend-Konnektoren unter `backend/src/main/java/io/opaa/indexing/source/`, Mock-Modus (MSW). Screenshots: `shots/` (424 Stück, 1440 px, Light + Dark; `scale__*` nur Light). Wiederholbar mit `capture.js` (Kopf des Skripts: Aufruf, Filter `TYPES`, `AREAS=list,wizard,detail,edit,states,scale`, `SCHEMES`).

Pfadkürzel: `C/` = `frontend/src/features/library/components/`, `P/` = `…/library/pages/`.

---

## 1 Aufbau des Bereichs

| Ort | Route | Aufbau |
|---|---|---|
| Liste | `/libraries` → Redirect `/catalog?type=knowledge` | Katalog-Kacheln (`AssetTile`), Filterzeile Suche/Typ/Favoriten, „Neu“. Kein eigener Bibliotheken-Screen. |
| Assistent | `/libraries/new` | `P/LibraryCreatePage.tsx`: Eyebrow „Neue Wissensbibliothek“ (12,5 px) + h2 je Schritt (26 px) + `WizardStepBar` + Fußzeile `C/LibraryCreateFooter.tsx` (Abbrechen links als Text, Zurück outlined, Weiter/„Bibliothek anlegen“ contained, rechts). Breite 720 px, Formulare 640 px. |
| Schritte | — | Upload: Quellart → Name & Beschreibung → Freigaben (3). Konnektor: + „Quelle einrichten“ (4). Private Bibliothek: ohne Freigaben (3). |
| Detail | `/libraries/:id?tab=` | `AssetDetailHeader` (Badges Art/Quellart/Rolle, Name inline editierbar, Kennzahlen, „Jetzt indizieren“ (+ „Vollabgleich starten“), Stern, ⋯) + Umfangszeile (`LibraryScopeHero`) + Laufbalken + Reiter Dokumente · Quelle (nicht bei Upload) · Metadaten (Feature-Flag) · Freigaben · Zuordnungen. |
| Reiter „Quelle“ | — | `C/LibrarySourceSection.tsx`: `PageSection`s **Umfang** (alle Leser) · **Anbindung** (Zugang, Quellverbindung, StoredView, Verbindungstest, „Bearbeiten“) · **Zeitplan** („Bearbeiten“) · **Läufe** (Accordion je Lauf, letzte 10). |
| Dialoge | — | „Quellkonfiguration bearbeiten“ (`EditLibrarySourceDialog`, `sm`) – rendert dasselbe registrierte Formular wie Schritt 2 im Modus `edit`; Zeitplan (`EditLibraryScheduleDialog`); Zugang zuordnen/wechseln (`LibraryConnectionDialog`); Quelle neu verbinden (`ReconnectSourceDialog`); Ordner neu/umbenennen; Textvorschau. |

Registry: `C/sources/registry.ts` – je Quellart `Form`, `validate`, `toPayload`, `StoredView`, `Scope`, `scopeHero`, `firstRunHint`, `runsHint`. Gemeinsamer Rahmen in Schritt 2 (`C/CreateSourceSetupStep.tsx`): [Zugang-Kacheln] → [Quelle verbinden (OAuth)] → **Formular** → h3 „Zeitplan“ + `LibraryScheduleForm` + Schalter „Erste Indizierung sofort …“ + Satz.

---

## 2 Je Quellart

Legende Feedback: **A** = Inline-`Alert` unter dem Knopf, **H** = Helpertext am Feld (rot), **S** = Seiten-Alert oberhalb der Schrittleiste, **T** = reine Textzeile, **N** = `notify()`-Popup.

### 2.1 Upload
| Aspekt | Ist |
|---|---|
| Schritte | Quellart → Name & Beschreibung (Satz „Dokumente laden Sie nach dem Anlegen … hoch“) → Freigaben |
| Felder | Name, Beschreibung (`AssetNameFields`); Eigentümer Radio „Mein Konto/Eine Gruppe“ + Gruppen-Autocomplete; Freigaben vormerken (SubjectPicker + Rolle + „Vormerken“) |
| Validierung | Name leer → „Weiter“ zeigt **S** „Bitte einen Namen angeben“ (nicht am Feld); „Bibliothek anlegen“ ist bei leerem Namen zusätzlich disabled. Gruppe fehlt → **S** |
| Detail | Kein Reiter „Quelle“, kein „Jetzt indizieren“; Upload-Fläche (Drag&Drop + „Dateien auswählen“ contained) im Reiter Dokumente |
| Screens | `wizard__upload__*`, `detail__upload__*` (auch `viewer-*`, `fixture-*`) |

### 2.2 Dateisystem (`PathSourceForm`)
| Aspekt | Ist |
|---|---|
| Gruppierung | h3 „Verbindung zum Dateisystem“ (nur create) |
| Felder (Reihenfolge) | Verzeichnispfad (mono, Platzhalter `/data/dokumente`) · Ausschlussmuster (optional, mehrzeilig, Hilfetext 3 Sätze) |
| Test | „Verbindung testen“ (outlined, medium; im Dialog small) direkt unter dem Hilfetext ohne Abstand; Ergebnis **A** success/warning/error |
| Zugangsdaten | keine; kein Zugang (Profil FORBIDDEN) |
| Validierung | **H** „Verzeichnispfad ist erforderlich“ + Hilfetext darunter (beides sichtbar) |
| Detail/Anbindung | Fettlabel-Zeilen „Verzeichnispfad:“, „Ausschlussmuster:“ + „Verbindung testen“; kein Abschnitt „Umfang“ |

### 2.3 Webverzeichnis / RSS-Feed (`UrlSourceForm`, gemeinsamer `valuesKey: 'generic'`)
| Aspekt | Ist |
|---|---|
| Gruppierung | h3 „Verbindung zum Webverzeichnis/RSS-Feed“; RSS zusätzlich Info-**Alert** (verlinkte Detailseiten) |
| Felder | Adresse (URL) (mono) · „Anmeldedaten (optional)“ (Passwort, Platzhalter `benutzer:passwort`) — im Edit „Neue Zugangsdaten“ |
| Layout | create: Grid 2 Spalten (URL volle Breite, Anmeldedaten halbe); edit: 1 Spalte |
| Test | wie Dateisystem (`SourceConnectionTest`) |
| Zugang | Profile OPTIONAL, Kachelwahl erscheint nur, wenn ein Profil existiert (im Mock: nie) |
| Detail | „Adresse (URL):“ + Satz „Zugangsdaten sind … nie Teil einer API-Antwort“ (caption) + Test |

### 2.4 Confluence (`ConfluenceSourceForm`, gestuft)
| Stufe | Inhalt |
|---|---|
| 1 | h3 „Verbindung zu Confluence“ · Adresse der Confluence-Instanz · „Edition erkennen“ (outlined) → **A** · Zeile „Edition:“ + Chip „Confluence Cloud“ + caption |
| 2 (nach Edition) | h3 „Zugangsdaten des Dienstkontos“ · E-Mail-Adresse (nur Cloud) + API-Token / PAT (2 Spalten) · „Verbindung testen“ → **A** |
| 3 | Warn-**Alert** „Wer diese Bibliothek lesen darf, sieht alles …“ |
| 4 | h3 „Ausgewählte Spaces“ · erst nach erfolgreichem Test: Autocomplete multiple mit Checkbox-Optionen und Chips; Statuszeile „n von m lesbaren Spaces ausgewählt.“ |
| Zeitplan | + Feld „Vollabgleich alle … Tage“ |
| Detail | Umfang mit Space-Chips + ScopeConsequence; Anbindung + Webhook-Abschnitt (`ConfluenceWebhookSection`, Geheimnis im Dialog) |

### 2.5 S3-Objektspeicher (`S3SourceForm`, gestuft)
| Stufe | Inhalt |
|---|---|
| 1 | h3 „Verbindung zum Objektspeicher“ · Radio-Reihe „Anbieter“ (5, bricht um) · Satz · Region (freeSolo-Autocomplete) + Endpoint · Schalter Path-Style |
| 2 | h3 „Zugangsdaten“ · Access Key + Secret Key · Session-Token (optional) |
| 3 | Warn-**Alert** Freigabefolge (immer sichtbar, auch vor Eingaben) |
| 4 | h3 „Geltungsbereiche“ · Zeilen Bucket n (Autocomplete) + Präfix n (optional) + Papierkorb · „+ Bereich“ + „Buckets laden“ (small) · Statuszeile · **A** Bucket-Ergebnis |
| 5 | Accordion „Erweitert: Ein-/Ausschlussmuster, TLS“ (TLS-Feld existiert nicht) |
| 6 | „Verbindung testen“ (outlined, medium) → **A** + Monospace-Liste je Bereich (✓/✗, Fehler in `warning.main`) |
| Detail | Umfang: Bereichs-Chips; Anbindung: Endpoint/Region/Adressstil/Muster + Abschnitt „Ereignisbenachrichtigung“ (`S3EventSection`) |

### 2.6 Google Drive (`GoogleDriveSourceForm`)
| Aspekt | Ist |
|---|---|
| Gruppierung | **keine** h3 „Verbindung zu …“; nur `subtitle2`-h3 „Bereiche (n von höchstens 50)“ |
| Felder | Dienstkonto-Schlüssel (JSON): Datei-Knopf „Schlüsseldatei wählen“ (bricht 2-zeilig um) + Statussatz · Imitiertes Konto (optional) · Bereiche: ScopeConsequence (Info-Icon + Satz) · „Bereiche laden“ → Checkbox-Liste · **schwebendes Label** „Ordner-ID“ + Text-Knopf „Ordner hinzufügen“ · Liste gewählter Bereiche mit Papierkorb |
| Test | „Verbindung testen“ **contained** (zweite Primärfläche neben „Weiter“), Busy-Text „Prüft …“, Listing „Lädt …“ |
| Ergebnis | **A** in `role=status` + Inline-Zusatz „· erreichbar“ in der Bereichsliste |

### 2.7 SharePoint (`SharePointSourceForm`, nur über Zugang)
| Aspekt | Ist |
|---|---|
| Voraussetzung | Zugang REQUIRED; ohne Profil Info-Alert „kein Zugang“ (im Mock: kein Profil → Skript legt eines an) |
| Gruppierung | Satz „Anmeldung über den Zugang …“ · `subtitle2`-h3 „Site finden“ · „Gewählte Dokumentbibliotheken (n von höchstens 50)“ |
| Felder | Adresse der Site + „Site öffnen“ · „Oder nach Sites suchen“ + „Sites suchen“ (Knöpfe neben Feld mit Hilfetext → Versatz) · Trefferliste als Text-Buttons (gewählt = contained) · Checkbox-Liste Dokumentbibliotheken · je Wahl Zeile mit „Ordner wählen“ + Papierkorb · eingerückter FolderPicker (h4, „Ebene: Stamm / …“, Checkbox + „Öffnen“, „Eine Ebene höher“, „Fertig“) |
| Test | contained „Verbindung testen“, „Prüft …“ |
| Screens | `wizard__sharepoint__*`, `edit__sharepoint__*`, `scale__sharepoint__*` |

### 2.8 Nextcloud (`NextcloudSourceForm`)
| Aspekt | Ist |
|---|---|
| Zugangswahl | Kacheln „Eigene Adresse“ / Profil (im Mock vorbelegt: Profil) – Profilkachel zeigt Rohwert „Vorgaben: edition: INTERN“ |
| Felder | h3 „Verbindung zur Nextcloud“ · Adresse · Technischer Nutzer + App-Passwort · Ordner (mehrzeilig, „/“ vorbelegt) · Chips „Gefundene Ordner“ |
| Knöpfe | „Ordner laden“ + „Verbindung testen“ (outlined, nebeneinander, Busy „Wird geprüft …“) |
| Fehlt | Hinweis zur Freigabefolge (ScopeConsequence) im Formular – nur auf der Detailseite |
| Privat | `private-*`: Bibliothek über verbundenes Konto, Reiter Freigaben mit Info-Alert |

### 2.9 Dropbox (`DropboxSourceForm`, OAuth-Zustimmung)
| Aspekt | Ist |
|---|---|
| Ablauf | Zugang (REQUIRED) → Abschnitt „Quelle verbinden“: Erklärsatz, Checkbox „Ich verbinde ein Dienstkonto …“, contained „Quelle verbinden“ (Weiterleitung) → erst danach Formular |
| Validierung | „Weiter“ → **S** „Bitte verbinden Sie zuerst die Quelle.“; „Quelle verbinden“ ohne Häkchen → roter Satz unter Checkbox |
| Formular | h3 „Ordner in Dropbox“ · Schalter „Team-Bereich lesen“ + Satz (`mt: -1`) · Ordner (mehrzeilig) · „Ordner anzeigen unter“ (Pfad) + Chips · „Ordner laden“ + „Verbindung testen“ |
| Detail | Anbindung mit Block „Quellverbindung“ (Verbunden als/am/Verantwortlich, „Neu verbinden“, „Trennen“) |

### 2.10 Windows-Dateifreigabe (`SmbSourceForm`)
Wie Nextcloud (Klon): h3 „Verbindung zur Dateifreigabe“ · Adresse der Freigabe (smb:// oder UNC) · Dienstkonto + Passwort · Ordner + Chips · „Ordner laden“ + „Verbindung testen“. Kein Freigabefolge-Hinweis im Formular.

---

## 3 Vergleichsmatrix (konsistent / inkonsistent)

| Dimension | Konsistent | Inkonsistent |
|---|---|---|
| Abschnittskopf des Formulars | h3 16/600 „Verbindung zu…“ bei Pfad, URL, Confluence, S3, Nextcloud, SMB | GDrive ohne Kopf; SharePoint `subtitle2`-Köpfe „Site finden“; Dropbox „Ordner in Dropbox“; Edit-Dialog: Pfad/URL/NC/SMB/Dropbox blenden Kopf aus, Confluence/S3 nicht |
| Feldbeschriftung | `FieldLabel` oben (5.2) in allen Quellformularen | Zeitplan-Form komplett MUI-Floating (`Zeitplan`, `Uhrzeit`, `Wochentag`, `Vollabgleich alle … Tage`); GDrive „Ordner-ID“ floating |
| Feldbreite / Raster | 640 px Spalte | 2-Spalten nur create bei URL/NC/SMB, immer bei Confluence/S3; GDrive/SharePoint/Dropbox 1 Spalte; Zeitplan volle Breite; Knopf-neben-Feld (SharePoint/GDrive) mit unterschiedlicher Ausrichtung |
| Abstände | Stack 2,5 (20 px) zwischen Stufen | `gap: '14px'`, `mb: 1.75`, `mt: 0.75`, `py: 0.75`, `mt: -1` (außerhalb 4.1) |
| Hilfetext | MUI-`helperText` (12 px, eingerückt 14 px) | daneben `caption`-Sätze (GDrive, Generic StoredView), `Typography 12,5` (S3, Confluence-Status), `body2`-Sätze (Dropbox Team, SharePoint Profil) |
| „Verbindung testen“ | Label überall gleich | Variante outlined (Pfad, URL, Confluence, S3, NC, SMB, Dropbox) vs **contained** (GDrive, SharePoint); Größe small/medium je Modus nur bei Generic; Busy-Texte „Verbindung wird getestet …“ / „Wird geprüft …“ / „Prüft …“ |
| Test-Ergebnis | Inline-Alert unter dem Knopf | zusätzlich je Bereich: S3 Mono-Liste mit ✓/✗; GDrive/SharePoint Suffix „· erreichbar“ in Liste und Fehler im Alert verkettet; Live-Region nur GDrive/SharePoint |
| Listen laden | — | „Edition erkennen“, „Buckets laden“, „Bereiche laden“, „Site öffnen/Sites suchen“, „Ordner laden“; Ergebnis teils **A** success („2 Bereiche gefunden.“), teils stumm |
| Freigabefolge-Hinweis | Inhalt gleich | Confluence/S3: Warn-Alert; GDrive/SharePoint: `ScopeConsequence` (Info-Icon); NC/SMB/Dropbox: keiner im Formular |
| Validierung | Feldbezug über `SourceIssueContext` (H) | immer nur **erste** Meldung (SMB: URL rot, leeres Passwort nicht); ohne Feldbezug → **S** oben (Name, Gruppe, Zugang, Zeitplan); Upload-Name → **S** statt **H**; Dialog: Alert oben + Fokus |
| Zugangsdaten | Passwortfelder, write-only, Edit-Hinweis „Leer lassen …“ | Begriffe: „Anmeldedaten“ (URL create) / „Zugangsdaten“ / „Neue Zugangsdaten“ / „App-Passwort“ / „Passwort“ / „Schlüssel“; Edit: Benutzername/E-Mail nicht vorbelegt (NC, Confluence) |
| Auswahl-UI | — | Autocomplete+Chips (Confluence), freeSolo-Zeilen (S3), Checkbox-Liste (GDrive, SharePoint), Textarea + Chips (NC, SMB, Dropbox), Drill-Down-Picker (SharePoint) |
| Primärknöpfe je Fläche | Wizard: Weiter/Anlegen | +„Verbindung testen“ (GDrive, SharePoint), +„Quelle verbinden“ (Dropbox), Site-Treffer contained; Detail: „Jetzt indizieren“ contained im Kopf |
| Bearbeiten | Quelle/Zeitplan per Dialog | Name/Beschreibung inline im Kopf; Diagnosesperre/Share-Cap inline Schalter; „Bearbeiten“-Knopf vs Block-Aktion „Quelle bearbeiten“ vs Dialogtitel „Quellkonfiguration bearbeiten“ |
| Rückmeldung nach Aktion | — | Löschen ok → **N**, Fehler → Inline-Alert (`P/LibraryDetailPage.tsx:491`); Zugang lösen/zuordnen → **N**; Quelle/Zeitplan speichern → stumm (Dialog schließt); Bulk-Metadaten, Upload-, Lösch-, Ordnerfehler → Inline-Alert über der Liste |
| Leer/Laden/Fehler | Ladezeilen als Text „… werden geladen …“ | keine Skeletons (5.7); Quellarten-Fehler zeigt Rohtext „HTTP 502: Request failed with status code 502“ und Schrittleiste mit 3 Schritten; Detail-Ladefehler ohne Wiederholen, Reiter „Quelle“ verschwindet stumm (`detail__filesystem__detail-failed`) |
| Status-Chips | — | Laufstatus/Ereignisse als farbige Chips (success/warning/error), „Läuft“ = warning |
| Monospace | `fontFamily: 'monospace'` in 15 Dateien | nicht der Token JetBrains Mono (3.1); Detail-Ansichten mischen (S3-Chips mono, URL-Zeile proportional) |
| Gedankenstrich | — | „ - “ statt „ – “ in Nutzertexten (s. 4) |

---

## 4 Konkrete Verstöße

| # | Stelle | Regel | Befund |
|---|---|---|---|
| 1 | `C/LibraryDocumentsNotices.tsx:38,49,56` | §5.9 | Upload-, Lösch-, Ordnerfehler als schließbare Inline-Alerts über der Dokumentliste |
| 2 | `C/LibraryDocumentsSection.tsx:284-292` | §5.9 | Ergebnis der Sammelaktion („Feld gesetzt …“) als Inline-Alert über der Liste |
| 3 | `P/LibraryDetailPage.tsx:491` + `C/useLibraryDeletion.ts:65-76` | §5.9 | Löschfehler inline, Erfolg per `notify` – derselbe Vorgang zwei Mechanismen |
| 4 | `C/DiagnosticsLockControl.tsx:59` | §5.9 | Fehler einer Schalter-Aktion als Inline-Alert |
| 5 | `C/EditLibrarySourceDialog.tsx:122`, `C/EditLibraryScheduleDialog.tsx` | §5.9 / a11y 2.8 | Speichern ohne jede Bestätigung |
| 6 | `C/LibraryScheduleForm.tsx:38,55,66,83` | §5.2 | Schwebende MUI-Labels; „Zeitplan“ doppelt (h3 + Label) |
| 7 | `C/GoogleDriveSourceForm.tsx:355` | §5.2 | Floating-Label „Ordner-ID“ |
| 8 | `C/GoogleDriveSourceForm.tsx:399`, `C/SharePointSourceForm.tsx:571`, `C/SourceConsentPanel.tsx:67`, `C/SharePointSourceForm.tsx:427` | §5.1 | Zweite contained-Schaltfläche neben „Weiter“ |
| 9 | `C/LibraryConnectionDialog.tsx:283-284` | §5.1/§2.3 | Gefüllte Warnfläche „Trotzdem zuordnen“ als Standardaktion |
| 10 | `C/S3SourceForm.tsx:567`, `C/GoogleDriveSourceForm.tsx:374`, `C/SharePointSourceForm.tsx:536,670` | §5.1 | Nur-Icon-Papierkorb ohne `Tooltip` (kein Import in den Dateien) |
| 11 | `C/LibrarySourceSection.tsx:487,517,525,533` | §5.5 | Lauf-/Ereignis-Chips in Signalfarben; „Läuft“ als warning-Chip statt Text mit Fortschritt |
| 12 | `C/ConfluenceSourceForm.tsx:507` | §5.5 | Warning-Chip für nicht lesbare Spaces |
| 13 | `C/S3SourceForm.tsx:706` | §2.3 | Satz in `warning.main` statt Signal nur am Zeichen |
| 14 | `C/LibraryScopeHero.tsx:64-70` | §2.3/§2.4 | flächige Warnfläche per `alpha(warning, .08)` (Alpha-Ton) |
| 15 | Dateien mit `gap: '14px'`: Confluence 286,357 · Dropbox 154 · Nextcloud 177 · S3 343,421,629 · SMB 176 · Url 78 | §4.1 | 14 px nicht im Raster |
| 16 | `mb/mt 1.75|0.75`, `mt:-1`: Confluence 282,349,441,522 · CreateSourceSetupStep 117 · Dropbox 150,169 · Nextcloud 169 · Path 29 · S3 307,417,525,605 · SMB 168 · Url 64,69 · S3Event 113,119 · ConfluenceWebhook 97,103 | §4.1 | 14 px / 6 px / negative Ränder |
| 17 | `fontFamily: 'monospace'` (15 Dateien, z. B. `C/PathSourceForm.tsx:46`) | §3.1 | generische Mono statt JetBrains Mono |
| 18 | `C/S3SourceForm.tsx:621` | §6 / Ehrlichkeit | Akkordeon nennt „TLS“, enthält kein TLS-Feld |
| 19 | `C/S3SourceForm.tsx:592` | §5.1 | Knopf „Bereich“ ohne Verb („Bereich hinzufügen“) |
| 20 | `C/UrlSourceForm.tsx:98` | §6 | „Anmeldedaten“ vs sonst „Zugangsdaten“ |
| 21 | Nutzertexte mit „ - “: `C/UrlSourceForm.tsx:58`, `C/NextcloudSourceForm.tsx:95`, `C/SmbSourceForm.tsx:93`, `C/S3SourceForm.tsx:243,252,403,664`, `C/sources/GenericSourceViews.tsx:108`, `…/SmbSourceViews.tsx:23,37`, `…/NextcloudSourceViews.tsx:25`, `…/DropboxSourceViews.tsx:25` | Typografie | Bindestrich statt Halbgeviertstrich (andere Texte nutzen „—“/„–“) |
| 22 | Quellarten-Ladefehler (`useSourceTypeChoice` → `P/LibraryCreatePage.tsx:514`) | §5.7 | Rohtext „HTTP 502: Request failed …“, kein nächster Schritt |
| 23 | `P/LibraryDetailPage.tsx:499-503` | §5.7 | Ladefehler ohne Wiederholen; Reiter verschwinden |
| 24 | `C/ConnectionProfileSelect.tsx:47-60` | §6 | Profilvorgaben ohne Deklaration als Rohschlüssel („edition: INTERN“) |
| 25 | `C/DropboxSourceForm.tsx:166-173` | a11y 2.7 | Hilfetext per Negativrand an Schalter geklebt; `body2` statt Hilfetextstil |
| 26 | Wizard-Validierung (`P/LibraryCreatePage.tsx:390-392`) | a11y 2.7 / §5.2 | Name-Fehler nicht am Feld, kein `aria-describedby`; nur erste Quellfehlermeldung |
| 27 | `C/sources/ScopeConsequence.tsx:24` vs Alert warning in Confluence/S3 | §5.9/§2.3 | dieselbe Aussage einmal Info-Icon, einmal Warn-Alert |

---

## 5 Top 10 UX-Probleme (nach Wirkung)

1. Auswahl-UIs skalieren nicht: unbegrenzte Checkbox-/Chip-/Button-Listen ohne Suche, Filter, „alle wählen“ oder Virtualisierung (SharePoint, GDrive, NC/SMB/Dropbox, Gruppen) – s. 7.
2. Elf Quellformulare, elf Layouts: Kopf, Spalten, Hilfetextstil, Knopfvariante und Testergebnis unterscheiden sich je Konnektor.
3. Rückmeldung uneinheitlich: Speichern stumm, Löschen halb Popup halb Inline, Listen-Fehler als eingeschobene Alerts (§5.9).
4. Validierung zeigt nur einen Fehler auf einmal und teils oben statt am Feld – lange Formulare erfordern Fehlerschleifen.
5. Zeitplan-Block mit Floating-Labels bricht das Formularmuster in jedem Konnektor-Schritt.
6. Freigabefolge-Hinweis fehlt bei Nextcloud/SMB/Dropbox im Formular, bei anderen mal Warn-Alert, mal Info-Zeile.
7. Mehrere Primärschaltflächen (Test/Verbinden + Weiter) – unklar, was der nächste Schritt ist.
8. Fehler-/Ladezustände roh oder stumm (HTTP-Text, verschwindende Reiter, keine Skeletons, kein „Erneut versuchen“).
9. Ordnerwahl per Freitext-Textarea (NC/SMB/Dropbox) neben echtem Drill-Down (SharePoint) – gleiche Aufgabe, zwei Bedienkonzepte; nur Stammebene browsebar.
10. Begriffe schwanken (Anmeldedaten/Zugangsdaten/Schlüssel; Quelle bearbeiten/Quellkonfiguration/Anbindung; Bereich/Geltungsbereich/Ordner/Space).

---

## 6 Lücken der Mock-Abdeckung

| Lücke | Folge |
|---|---|
| Fixtures nur für Upload, Dateisystem, S3, Nextcloud(privat) | Detailseiten anderer Arten nur über Assistent/POST erzeugbar (Skript macht das) |
| Zugänge nur Nextcloud; Nextcloud-Profil trägt undeklarierte Vorgabe `edition` | SharePoint/Dropbox ohne Seed nicht anlegbar; Rohtext in Kachel |
| OAuth-Rundweg (Dropbox) bricht: `leaveFor` lädt Seite neu → MSW-Zustand + `pendingStates` weg | Formular nach Zustimmung im Assistenten nicht zeigbar |
| POST `/libraries` verwirft `sourceSettings` für FILESYSTEM/HTTP/RSS/DROPBOX, `sourceCredentialsSet` für HTTP/RSS | Detail zeigt „Ausschlussmuster: keine“, „0 Ordner“ |
| Indizierungsstatus global (ein Zähler), Läufe-Historie fix für alle Bibliotheken, Dokumentzahl 0 vs „37 verarbeitet“ | widersprüchliche Kopfzeile |
| Keine `sourceBlock`-, `connectionProfileRemoved`-, abgelaufene-Zustimmung-, `lastScheduledRunsFailed`-, Nachfolge-, Kontingent-Fixtures | Sperr-/Warnzustände des Reiters Quelle nicht sichtbar |
| Browse-Mocks liefern feste 2–5 Einträge, nie `complete:false`, nie langsam/fehlerhaft | Skalierung/Fehler nur per Laufzeit-Override (`scale__*`) |
| Source-Test immer Erfolg | Teilfehler je Bereich (S3 ✗, SharePoint nicht erreichbar) nicht sichtbar |
| `/api/v1/notifications` unbehandelt (502) | Konsolenrauschen |
| MSW-Worker antwortet nach ~5 min in derselben Seite nicht mehr | Skript lädt je Quellart neu |
| Katalog leer/Fehler, Dokumentliste mit tausenden Einträgen, Upload-Fortschritt | nicht abgedeckt |

---

## 7 Skalierung

Methode: `capture.js` Bereich `scale` ersetzt zur Laufzeit Handler über `worker.use()` (Modul `/src/app/mocks/browser.ts` und `msw` aus Vites Modulgraph), ohne Repo-Änderung. Screens `scale__<typ>__<fall>__light.png`. Hinweis: Das Skript vergrößert den Viewport auf Inhaltshöhe; geöffnete Popper (Gruppen-Autocomplete) wirken dadurch länger als real (Listbox real ~40 % Viewport, scrollend).

### 7.1 Auswahl-UIs im Überblick

| UI | Komponente | Suche/Filter | Paging/Virtualisierung | Alle wählen | Grenze (FE/BE) | Backend-Auflistung |
|---|---|---|---|---|---|---|
| SharePoint Site | 2 Felder (Adresse / Suche) + Liste Text-Buttons | serverseitig (Graph `search`) | keine; Graph liefert 1 Seite | – | – | Suche: nur erste Seite, `complete:true` |
| SharePoint Dokumentbibliothek | Checkbox-Liste je Site | nein | nein | nein | 50 | bis 10 Graph-Seiten, danach still abgeschnitten, trotzdem `complete:true` |
| SharePoint Ordner | Drill-Down-Picker (Checkbox + „Öffnen“, Ebenenpfad) | nein | nein | nein | 50 je Bibliothek | wie oben je Ebene |
| Confluence Spaces | Autocomplete multiple + Chips | ja (clientseitig) | nein (MUI rendert alle Optionen) | nein | 500 (Hinweis ab 450) | alle Spaces, ein Aufruf |
| Google Drive Bereiche | Checkbox-Liste + Freitext Ordner-ID | nein | nein | nein | 50 | Ablagen + geteilte Ordner je ≤10×100, still abgeschnitten |
| S3 Buckets/Präfixe | Zeilen: freeSolo-Autocomplete + Präfix-Textfeld | Bucket: ja | nein | – | 50 Zeilen | nur Bucketnamen, keine Präfix-Auflistung |
| Nextcloud/SMB Ordner | Textarea + Chip-Wolke „Gefundene Ordner“ | nein | nein | nein | 50 | nur Stammebene (PROPFIND Tiefe 1 / Share-Root) |
| Dropbox Ordner | Textarea + Pfadfeld + Chip-Wolke | nein | nein | nein | 50 | 1 Ebene unter Pfad, ≤10×2000, `complete:true` |
| Eigentümer-Gruppe | Autocomplete (alle eigenen Gruppen clientseitig) | ja (clientseitig) | nein | – | – | `/me/groups` komplett |
| Freigaben (Person/Gruppe) | `SubjectPicker` Autocomplete | serverseitig | Server begrenzt | – | – | Suche |
| Dokumentliste | Tabelle + Ordnerzeilen | ja (serverseitig) | Pagination 20/50/100 | „Alle auf dieser Seite“ | – | paginiert |
| Läufe | Accordion-Liste | nein | letzte 10, Ereignisse gekappt („… und n weitere“) | – | – | – |

### 7.2 Je Konnektor

**SharePoint** (`scale__sharepoint__*`)

| Edge Case | realistische Größenordnung | Ist | Bricht? |
|---|---|---|---|
| Site-Suche viele Treffer | 20–200 (Sites.Read.All, „Amt“) | jede Site ein Text-Button, zentrierter Umbruch bei langen Namen, Liste wächst ungebremst (`sites-40-long`) | degraded |
| Lange Namen | 80–120 Zeichen | zentriert umbrochene Buttons; Bibliotheksnamen 2-zeilig ok | degraded |
| Viele Dokumentbibliotheken je Site | 5–40 | Checkbox-Liste ohne Suche/Alle (`libraries-25-long`) | degraded |
| Ordner je Ebene | 50–1000 | 200 Checkbox-Zeilen + je „Öffnen“; Seite wird mehrere tausend px lang (`folders-200`) | ja |
| Tiefe | 6–10 Ebenen | Pfad „Stamm / … / …“ und Auswahlzeile wachsen; keine Breadcrumb-Navigation, nur „Eine Ebene höher“ (`folders-depth-8-long`) | degraded |
| > 10 Graph-Seiten | große Bibliotheken | Backend schneidet still ab, UI zeigt „vollständig“ | ja (falsch) |
| 0 Treffer | häufig bei Sites.Selected | Info-Alert mit Hinweis auf Adresse (`sites-0`) | n |
| Langsam | Graph 2–10 s | nur Knopf-Spinner, Liste leer (`sites-slow`) | n |
| Fehler / 403 | Rechte fehlen | Error-Alert mit Backendtext (`sites-failed`) | n |
| Site nicht freigegeben (Sites.Selected) | häufig | Warn-Alert aus `browseFailure` | n |
| Gewählter Ordner gelöscht/umbenannt | Betrieb | Name bleibt aus Settings, erst Lauf meldet | degraded |
| Klein (1–3) braucht | — | Adresse eingeben → Bibliothek anhaken genügt; Suche/Filter erst ab ~10 Einträgen |

**Confluence** (`scale__confluence__*`)

| Edge Case | Größe | Ist | Bricht? |
|---|---|---|---|
| Lesbare Spaces | 20–2000 (Cloud), Limit 500 Auswahl | Autocomplete rendert alle Optionen ohne Virtualisierung | degraded ab ~1000 |
| Viele gewählte | 40–450 | Chips füllen das Feld (40 Chips ≈ 750 px, `spaces-450-40-chosen-long`), kein „n gewählt“-Kollaps | ja |
| Lange Namen | 80+ | Chip mit Ellipse, Name nur per Tooltip-loser Abkürzung | degraded |
| Persönliche Spaces | Hunderte (`~user`) | nicht gefiltert/gruppiert | degraded |
| 0 Spaces | Token ohne Rechte | „Kein Space passt zur Suche“ im leeren Popup; Status „0 von 0“ (`spaces-0`) | degraded |
| Liste fehlgeschlagen | Rate-Limit 429 | Error-Alert + „Erneut laden“ (`spaces-failed`) | n |
| Token abgelaufen (Edit) | PAT-Laufzeit | Test schlägt fehl, Auswahl bleibt, Hinweis „derzeit nicht lesbar“ | n |
| Klein braucht | — | 1–5 Spaces als Checkbox-Kacheln wären direkter als Autocomplete |

**Google Drive** (`scale__gdrive__*`)

| Edge Case | Größe | Ist | Bricht? |
|---|---|---|---|
| Geteilte Ablagen + Ordner | 50–1000 (≤10×100 je Art) | flache Checkbox-Liste, Art nur als Klammerzusatz (`areas-300-long`) | ja |
| Ordnertiefe | beliebig | kein Browsen unterhalb; nur Ordner-ID von Hand | degraded |
| Namensgleichheit | häufig („Projekte“) | nicht unterscheidbar (keine ID/Pfad) | degraded |
| Imitiertes Konto ändern | Edit | Info-Alert „Schlüssel neu hochladen“ | n |
| Langsam | — | nur „Lädt …“ im Knopf (`areas-slow`) | n |
| Fehler | — | Error-Alert (`areas-failed`) | n |
| Klein braucht | — | 1–3 Bereiche: Liste reicht; Suche/Gruppierung nach Art ab ~10 |

**S3** (`scale__s3__*`)

| Edge Case | Größe | Ist | Bricht? |
|---|---|---|---|
| Buckets | 100–1000 | freeSolo-Autocomplete je Zeile, filtert, nicht virtualisiert (`buckets-600-open`) | n/degraded |
| Geltungsbereiche | bis 50 | je Zeile 2 Felder + Label „Bucket n“/„Präfix n (optional)“; 25 Zeilen ≈ 900 px (`scopes-25-tested`) | degraded |
| Präfixe | tief, tausende | nicht browsebar, Freitext | degraded |
| Testergebnis je Bereich | bis 50 | Mono-Liste, eine Zeile je Bereich | n |
| ListBuckets verboten | häufig (Least Privilege) | Info-Alert, Eingabe von Hand | n |
| Klein braucht | — | 1 Zeile ohne Nummer „Bucket 1“; Nummerierung/Liste erst ab 2 |

**Nextcloud / SMB / Dropbox** (`scale__nextcloud__*`, `scale__smb__*`)

| Edge Case | Größe | Ist | Bricht? |
|---|---|---|---|
| Ordner im Stamm | 20–500 (Gruppenordner, Freigaben) | Chip-Wolke ohne Suche; 200 Chips ≈ 3000 px; gewählte Chips nicht markiert (`folders-200-long`) | ja |
| Tiefe | 5–10 | nur Stammebene (Dropbox: Pfadfeld) – Unterordner nur per Freitext | degraded |
| Lange Namen / Sonderzeichen | 80+, Umlaute, Leerzeichen | Textarea bricht um, Zeilenzuordnung unklar | degraded |
| 0 Ordner | leerer Stamm | Warn-Alert (`folders-0`) | n |
| Fehler | 401/Timeout | Error-Alert (`folders-failed`) | n |
| Zugriff verweigert (SMB) | Stamm nicht listbar | Hinweis „von Hand eintragen“ | n |
| Dropbox Team ohne Team | — | Listing `complete:false` mit Satz | n |
| Klein braucht | — | „/“ (alles) als Standard reicht; Auswahl erst bei Bedarf aufklappen |

**Dateisystem / Webverzeichnis / RSS**: keine Auswahl-UI; Skalierung betrifft nur lange Muster (Textarea wächst) und Testergebnis (Satz).

**Gruppen / Freigaben / Dokumente**: Eigentümer-Autocomplete lädt alle Gruppen clientseitig (300 Gruppen: lange Liste, filterbar, `scale__groups__owner-300-long-open`); `SubjectPicker` serverseitig; Dokumentliste paginiert – skaliert.

### 7.3 Stress-Fixtures für spätere Varianten

| Handler (Datei) | Benötigtes Stress-Set |
|---|---|
| `POST /source-types/:type/browse` (`libraryHandlers.ts`, `sharePointSourceMock.ts`) | je Typ: N Einträge, lange Namen, `complete:false` + Meldung, Verzögerung, 4xx/5xx, Tiefe (SharePoint `folder`, Dropbox `path`) |
| `POST /libraries/source-test` | Teilerfolg je Bereich (S3 `scopes[]`, GDrive `scopes[]`, SharePoint `libraries[]`), `credentialsVerified:false`, Zeitüberschreitung |
| `GET /connection-profiles` (`connectionProfileHandlers.ts`) | Profile je Typ, viele Profile, gesperrte, private |
| `GET /libraries/:id` (`libraryHandlers.ts`) | Fixtures je Quellart inkl. `sourceBlock`, `connectionProfileRemoved`, abgelaufene `sourceConnection`, 50 Scopes, 500 Spaces |
| `GET /libraries/:id/indexing/runs`, `/status` (`indexingHandlers.ts`) | je Bibliothek, viele Ereignisse, gekappte Ereignisse, laufend/fehlgeschlagen |
| `GET /me/groups`, `/groups/selectable` (`groupHandlers.ts`) | 300+ Gruppen, lange Namen |
| `GET /libraries/:id/documents` + Ordner (`libraryDocumentHandlers.ts`, `libraryFolderHandlers.ts`) | 10 000 Dokumente, 8 Ordnerebenen |
| `GET /source-types` | leer, Fehler, gesperrte Arten |
