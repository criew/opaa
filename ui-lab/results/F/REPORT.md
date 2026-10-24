# Variante F – D-Zeilen mit E-Dialog

Grundlage D (Zeilen „Verbindung · Umfang · Zeitplan“, höchstens eine offen, gleiche Zeilen in Assistent, Prüfschritt, Reiter „Quelle“). Prototyp, wird nicht gemerged.

## Was kam woher
| Teil | Herkunft | Umsetzung |
|---|---|---|
| Zeilen, Prüfschritt, Bearbeiten an Ort und Stelle | D | `setup/SourceSetupRows.tsx`, `SummaryRow.tsx` (Zusammenfassung max. 2 Zeilen) |
| Radiopaar „Alle / Nur ausgewählte …“ | B | `setup/ScopePicker.tsx`; nur Nextcloud, SMB, Dropbox (dort liest „/“ wirklich alles). Confluence, SharePoint, S3, Drive kennen kein „alles“ – ein „Alle Spaces“ wäre eine veraltende Momentaufnahme |
| ≤ 7 flache Einträge als Checkboxen in der Zeile | neu | `ScopePicker`; bei Ordnern/S3 dazu „Unterordner/Präfixe auswählen …“ in den Dialog |
| Auswahldialog (Breadcrumb, Suche ab 7, Teilauswahl, „Alle n auswählen“, „n ausgewählt · Übernehmen“, „Ganz ausgewählt über …“) | E | `sourceKit/SelectionDialog.tsx`, Adapter `selectionSources.ts`; neu: `initialLevel`, `startManual`, Tooltips nicht interaktiv, „Site von Hand“ nur auf oberster Ebene |
| Combobox mit Pfad-Chips | D | **gelöscht** (`PickCombo.tsx`, `*Nodes.ts`) |
| Namen statt Anzahlen | neu | Zeile, Prüfschritt, Reiter „Quelle“ und Kopfzeile der Detailseite: max. 5 Namen + „und n weitere“ |
| SharePoint-Site je Eintrag | E-Idee, neu gespeichert | `site` an `SharePointLibraryChoice`, in den Settings `siteId`/`siteName`; Dialog öffnet in der gemeinsamen Site |
| Lauf-Status Punkt + Wort | A | `LibrarySourceSection`: alle `color="warning"`-/Umriss-Chips → Text |
| Popup-Timer pausiert bei Hover/Fokus | Prüfliste #41 | `NotificationHost` + Guideline §5.9 |
| Protokollcodes raus | neu | `sourceKit/plainMessage.ts` (HTTP-, STATUS_-, AADSTS-, CamelCase-Codes; Axios-Text) in Dialog, Zeile, Test, Läufen |

## Begriffssatz
- **Eigene Konzepte:** Verbindung · Umfang · Zeitplan · Zugang · „gesetzt · Ersetzen“ · Übernehmen (statt „Fertig“) · Verbindung testen · Trotzdem fortfahren · Ändern · Speichern.
- **Herstellerbegriffe** (Feldlabel = Portal): Confluence „E-Mail-Adresse“, „API-Token“ / „Persönliches Zugriffstoken“ · Nextcloud „Benutzername“, „App-Passwort“ · SMB „Benutzername“, „Passwort“ · S3 „Endpoint“, „Access Key“, „Secret Key“, „Session-Token“ · SharePoint „Site“, „Dokumentbibliothek“ · Drive „Geteilte Ablage“, „Schlüsseldatei“ · Dropbox „Teambereich“.
- Keine Doppel-Labels in Klammern, kein „Kennung“/„Kennwort“, kein „Geltungsbereich“/„Bereich“ im neuen Fluss.
- Fehler: „Fehler: n Angaben prüfen“, Einträge „Abschnitt: Meldung“ („Verbindung: Endpoint fehlt“, „Umfang: Mindestens einen Bucket wählen“).
- Leerzustände: „Kein Zugriff auf …“ · „Keine … vorhanden.“ / „In „X“ liegen keine …“ · „Keine Treffer für „…“.“ + „Suche zurücksetzen“.

## Hilfetexte (gezählt im Assistenten + Reiter „Quelle“, je Konnektor inkl. gemeinsamer Texte)
| Konnektor | D | F | bleibt (Grund) |
|---|---|---|---|
| Dateisystem | 4 | 2 | „Absoluter Pfad, von der Systemverwaltung freigegeben.“, „Ein Muster je Zeile, relativ zum Verzeichnis.“ (Fehler) |
| Confluence | 7 | 3 | API-Token-Fundort (Fundort), „Leer = Vorgabe der Instanz“ (Fehler), Läufe-Zeile (Fehler) |
| SharePoint | 7 | 1 | Läufe-Zeile |
| Nextcloud | 5 | 1 | „In Nextcloud unter Einstellungen › Sicherheit › App-Passwörter.“ |
| SMB | 4 | 0 | – |
| S3 | 7 | 3 | „Ohne Bucket und Pfad.“, Session-Token „Nur für befristete Schlüssel.“, Pfad-Stil MinIO/AWS |
| Google Drive | 6 | 3 | Schlüsseldatei-Fundort, imitiertes Konto, Läufe-Zeile |
| Dropbox | 8 | 2 | „Weiter zu „…“; Ihre Eingaben bleiben erhalten.“, Läufe-Zeile |
| **Summe** | **48** | **15** | |

Gelöscht u. a.: Bedienhinweise („Pfeil am Eintrag …“, „Nach dem Namen suchen …“), Selbstverständliches („Nur Spaces, die das Dienstkonto lesen darf …“, „Die Edition wird aus der Adresse erkannt“, „Zugänge legt die Systemverwaltung an“), Folgen („Der erste Lauf liest …“ ×8, „Sie fahren ohne erfolgreichen Test fort …“), Läufe-Absätze auf eine Zeile. Edition „Cloud“ und „Verbunden als …“ sind Status, kein Hilfetext.

## Messung
Volle Matrix: 8 Typen × small/scale/empty/slow/failed/partial × 1280/320, **hell und dunkel je direkt im Schema geladen** (zwei Läufe, kein Umschalten), dazu Zustände `wizard-select` und `edit-select`. 716 Aufnahmen + `__full` (1432 JPEG), `metrics.jsonl` 720 Zeilen.
- axe hell (WCAG 2.0–2.2 A/AA, best-practice, `target-size`): **0** · axe dunkel `color-contrast`: **0** · `overflowX` **0** · deaktivierte Absenden-Knöpfe **0**.
- `not-found` 4: Dropbox-Validierung hell+dunkel (leeres Formular gültig, gewollt). Drei Ausreißer des ersten Hell-Laufs (Mock antwortete einmal mit 502) per Wiederholung (`run: light-redo`) ersetzt.
- `focusObscured` 4 Aufnahmen (320 px, Tooltip/Popup), `clippedText` nur visuell versteckte Zusätze an „Ändern“ (Fehlalarm).
- Runden: quick 4 Typen → Namensliste statt Fließtext, Space-Schlüssel raus, SharePoint-Ordner mit Bibliothek; quick 8 Typen → Grid-Überlauf, Fixtures passend zur Auflistung; failed/empty/partial → doppelte Leerzeilen, „Von Hand eintragen …“ direkt offen; Videos → interaktive Tooltips verdeckten Knöpfe, Site-Eingabe nur oben, „Verzeichnispfad“-Meldung.

## Videos (1280×800, Mauszeiger, ~600 ms je Aktion, H.264 crf 30, je < 1 MB)
Skript `ui-lab/harness/record-flows.mjs`. Erste 2 s (Laden) abgeschnitten; vier „viele“-Videos auf 59 s beschleunigt (Faktor 0,94–0,97).

| Datei | Länge | Inhalt |
|---|---|---|
| `videos/dateisystem__ok.mp4` | 40 s | Verzeichnis, Ausschlussmuster, Prüfen, Anlegen; Reiter „Quelle“: Zeitplan „Ändern“ → wöchentlich → Speichern |
| `videos/dateisystem__viele.mp4` | 45 s | Langer Pfad, vier Ausschlussmuster, Prüfen; Bearbeiten der Fixture (40 Muster) |
| `videos/dateisystem__fehler.mp4` | 27 s | „Weiter“ leer → „Fehler: 1 Angabe prüfen“, Sprung ins Feld, Test schlägt fehl (Zugriff verweigert), „Trotzdem fortfahren“ |
| `videos/confluence__ok.mp4` | 47 s | Adresse (Edition erkannt), E-Mail-Adresse, API-Token, 2 Spaces als Checkboxen in der Zeile, Prüfen, Anlegen, Zeitplan ändern |
| `videos/confluence__viele.mp4` | 52 s | 450 Spaces im Dialog: Suche „Bauamt“, „Alle 45 Treffer auswählen“, Übernehmen, Prüfen; Bearbeiten: 120 Spaces vorausgewählt |
| `videos/confluence__fehler.mp4` | 36 s | Fehlerzusammenfassung (4 Angaben), Felder füllen, Test: Anmeldung abgelehnt, Spaces „ließen sich nicht laden“ + „Erneut laden“ |
| `videos/sharepoint__ok.mp4` | 43 s | Zugang wählen, Dialog: Sites › Rathaus › „Dokumente“ ankreuzen, Übernehmen, Prüfen, Anlegen, Zeitplan ändern |
| `videos/sharepoint__viele.mp4` | 59 s | 17 Sites: Suche „Rathaus“, Bibliothek 002 ganz, „Dokumente“ › Suche „Ordner 00“ › „Alle 9 Treffer“, Breadcrumb zurück („teilweise“); Bearbeiten: 100 Einträge über 17 Sites vorausgewählt |
| `videos/sharepoint__fehler.mp4` | 44 s | Leerer Umfang → Fehlerzusammenfassung, Test: Entra ID lehnt ab, Dialog „Kein Zugriff auf Sites“, Site-Adresse von Hand |
| `videos/nextcloud__ok.mp4` | 49 s | Eigene Adresse, Benutzername, App-Passwort, Radiopaar „Nur ausgewählte Ordner“, 2 Checkboxen, Prüfen, Anlegen, Zeitplan ändern |
| `videos/nextcloud__viele.mp4` | 59 s | 200 Ordner/Ebene: Ordner 001 öffnen, Suche „Vorgang 00“, „Alle 9 Treffer“, zurück; Bearbeiten: 50 tiefe Pfade, Eltern „teilweise“ |
| `videos/nextcloud__fehler.mp4` | 47 s | Fehlerzusammenfassung, Test: Anmeldung abgelehnt, „Ordner ließen sich nicht laden“, Erneut laden, Pfad von Hand |
| `videos/smb__ok.mp4` | 47 s | Adresse, Benutzername, Passwort, Radiopaar, 2 Checkboxen, Prüfen, Anlegen, Zeitplan ändern |
| `videos/smb__viele.mp4` | 59 s | wie Nextcloud: Ebene öffnen, Suche, Alle Treffer, Breadcrumb; Bearbeiten vorausgewählt |
| `videos/smb__fehler.mp4` | 45 s | Fehlerzusammenfassung, Test: Anmeldung abgelehnt (ohne STATUS-Code), Freigabe nicht erreichbar, Pfad von Hand |
| `videos/s3__ok.mp4` | 43 s | Endpoint, Access Key, Secret Key, Bucket als Checkbox, Prüfen, Anlegen, Zeitplan ändern |
| `videos/s3__viele.mp4` | 58 s | 600 Buckets: bucket-0001 öffnen, Suche „ordner-00“, Alle Treffer, zurück zu Buckets; Bearbeiten: 50 Einträge vorausgewählt |
| `videos/s3__fehler.mp4` | 42 s | Fehlerzusammenfassung, Test: Access/Secret Key falsch, „Kein Zugriff auf die Buckets“, Bucket/Präfix von Hand |
| `videos/googledrive__ok.mp4` | 37 s | Schlüsseldatei, Geteilte Ablage als Checkbox, Prüfen, Anlegen, Zeitplan ändern |
| `videos/googledrive__viele.mp4` | 46 s | 300 Ablagen und Ordner: Suche „Ordner 01“, Alle Treffer, Übernehmen; Bearbeiten vorausgewählt |
| `videos/googledrive__fehler.mp4` | 36 s | Fehlerzusammenfassung, Test: Schlüssel widerrufen, „Kein Zugriff“, Ordner-ID von Hand |
| `videos/dropbox__ok.mp4` | 46 s | Zugang, Dienstkonto bestätigen, Zustimmung (Mock), Zeile „Verbunden als …“, Radiopaar, Checkboxen, Prüfen, Anlegen, Zeitplan ändern |
| `videos/dropbox__viele.mp4` | 59 s | nach der Zustimmung: Ordner-Ebene, Suche, Alle Treffer; Bearbeiten: 50 Pfade vorausgewählt |
| `videos/dropbox__fehler.mp4` | 43 s | Zustimmung, leerer Umfang → Zusammenfassung, Test: Zustimmung abgelaufen, Ordner nicht ladbar, Pfad von Hand |

## Prüfliste
| # | | Beleg |
|---|---|---|
| 1 | ✓ | Status nur Punkt + Wort; keine Chips im Fluss |
| 2 | ✓ | Reiter unverändert |
| 3 | ✓ | Zeilen ohne Icons; Warnhinweis Kopfzeile ohne Icon |
| 4 | teilweise | Zustimmung, Fehlerliste, Kopfwarnung ohne Tönung; Dokumente-Reiter-Info (Bestand) getönt |
| 5 | ✓ | Akzent nur an Knöpfen/Links/Checkboxen |
| 6 | ✓ | Dialogzeile Name + „teilweise“/Sperrgrund + Chevron |
| 7 | ✓ | `wizard-validation__*` |
| 8 | ✓ | „Änderungen an „Umfang“ verwerfen?“, „Geänderte Auswahl verwerfen?“ |
| 9 | ✓ | Quellart · Quelle · Name · Freigaben · Prüfen |
| 10 | n/a | |
| 11 | ✓ | |
| 12 | ✓ | 1–3 Wörter, Herstellerbegriffe |
| 13 | ✓ | 48 → 15, alle einzeilig bei 1280 |
| 14 | ✓ | nur Beispiele |
| 15 | ✓ | Codes entfernt (`plainMessage`) |
| 16 | ✓ | |
| 17 | ✓ | „Fertig“ → „Übernehmen“ |
| 18 | ✓ | |
| 19 | ✓ | Begriffssatz oben |
| 20 | ✓ | Suche ab 7, „12 ausgewählt“, 100 Zeilen + „Weitere anzeigen“ |
| 21 | ✓ | Breadcrumb Sites / Rathaus / Dokumente, „Auswahl ansehen“ mit Pfad |
| 22 | ✓ | Ellipse + Tooltip, Zusammenfassung 2 Zeilen |
| 23 | ✓ | Lade-/Fehler-/Leerzustand je Ebene, „Erneut laden/versuchen“ |
| 24 | ✓ | „Alle n (Treffer) auswählen“ |
| 25 | ✓ | `overflowX` 0 bei 320 |
| 26 | teilweise | aus D; Tastaturstrecke nicht manuell |
| 27 | teilweise | 4 Aufnahmen |
| 28 | ✓ | |
| 29 | teilweise | Zusammenfassung „Fehler: …“ mit Sprunglinks; Prüfung beim Absenden, nicht beim Verlassen |
| 30 | teilweise | Browser-Zurück nicht behandelt |
| 31 | ✓ | |
| 32 | ✓ | Prüfschritt mit Namen |
| 33 | ✓ | aus D |
| 34 | ✓ | „Zugangsdaten gesetzt · Ersetzen“ |
| 35 | ✓ | Liste/Dialog, keine Kacheln |
| 36 | ✓ | `aria-checked="mixed"` im Dialog |
| 37 | ✓ | Suche löscht keine Auswahl |
| 38 | ✓ | drei Leerzustände in Zeile und Dialog |
| 39 | ✓ | 300 ms |
| 40 | teilweise | nur Chips entfernt |
| 41 | ✓ | Timer pausiert; Dropbox-Erfolg kein Popup mehr; Fehler am Ort |
| 42 | ✓ | |
| 43 | teilweise | Ellipse, keine Pseudo-Lokalisierung |
| 44 | ✓ | axe 0 hell, Kontrast 0 dunkel |

## Schwächen
- `siteId`/`siteName` je SharePoint-Bibliothek gibt es nur im Mock; das Backend müsste sie speichern. Gespeicherte Ordner kennen nur Site und Bibliothek, Zwischenordner zeigen kein „teilweise“.
- Inline-Checkboxen brauchen die Auflistung; ohne sie bleibt nur der Dialog bzw. „Von Hand eintragen …“ (Confluence ohne Handeingabe).
- „Trotzdem fortfahren“ verfällt, sobald sich der Umfang ändert (Test aus D prüft den Umfang mit) – im Video `*__fehler` sichtbar.
- Ordnerpfade mit Komma bleiben gesperrt (Backend-Format).
- `LibraryScopeHero` nutzt die Zeilen-Zusammenfassung; Warntexte zu nicht gelesenen Bereichen sagen noch „Bereich/Geltungsbereich“.
- Alte Formulare (`*SourceForm.tsx`) liegen weiter als toter Code (Tests).

## Gebrochene Unit-Tests (90 Fälle in 8 Dateien; `pnpm exec vitest run`, 3870 grün)
- `pages/LibraryCreatePage.test.tsx` 42
- `components/LibrarySourceSection.test.tsx` 18
- `pages/LibraryDetailPage.test.tsx` 18
- `components/LibrarySourceSection.sourceConnection.test.tsx` 5
- `pages/LibraryCreatePage.sourceConsent.test.tsx` 4
- `components/ConnectionProfileSelect.test.tsx` 1
- `pages/ConnectionCallbackPage.test.tsx` 1
- `utils/sharePointSource.test.ts` 1

Neu gegenüber D: Popup nach Dropbox-Zustimmung entfällt (`ConnectionCallbackPage`), Hinweis zur Zugangsvergabe entfernt (`ConnectionProfileSelect`), Site wird mitgelesen (`sharePointSource`). Grün: `pnpm run lint`, `pnpm exec tsc -b`, `pnpm run build`, Prettier.

<details><summary>Einzeln</summary>

- components/ConnectionProfileSelect.test.tsx > ConnectionProfileSelect > offers the own address and a released profile, with its address, sign-in and defaults
- components/LibrarySourceSection.sourceConnection.test.tsx > LibrarySourceSection - Quelle verbinden (#2169) > disconnects only after the question, then reloads the library
- components/LibrarySourceSection.sourceConnection.test.tsx > LibrarySourceSection - Quelle verbinden (#2169) > names the account, who answers for it and since when it is connected
- components/LibrarySourceSection.sourceConnection.test.tsx > LibrarySourceSection - Quelle verbinden (#2169) > names why an ended connection ended, and offers no disconnect then
- components/LibrarySourceSection.sourceConnection.test.tsx > LibrarySourceSection - Quelle verbinden (#2169) > offers no reconnect once the profile is gone, and points to „Zugang zuordnen“
- components/LibrarySourceSection.sourceConnection.test.tsx > LibrarySourceSection - Quelle verbinden (#2169) > reconnects only with the service account confirmed, and remembers where to return
- components/LibrarySourceSection.test.tsx > LibrarySourceSection - Aktion am Sperrhinweis > opens the source configuration where the managers enter the secret anew
- components/LibrarySourceSection.test.tsx > LibrarySourceSection - Zugang (#2160) > names the connection profile of the library
- components/LibrarySourceSection.test.tsx > LibrarySourceSection - Zugang zuordnen, wechseln, lösen (#2162) > keeps the status of a library whose profile is locked, with its reason and the way out
- components/LibrarySourceSection.test.tsx > LibrarySourceSection - Zugang zuordnen, wechseln, lösen (#2162) > names a deleted profile as removed, not as an own address
- components/LibrarySourceSection.test.tsx > LibrarySourceSection - Zugang zuordnen, wechseln, lösen (#2162) > offers a manager only the profiles released to her, asking with the library
- components/LibrarySourceSection.test.tsx > LibrarySourceSection - Zugang zuordnen, wechseln, lösen (#2162) > offers no release while the type is usable only through profiles
- components/LibrarySourceSection.test.tsx > LibrarySourceSection - Zugang zuordnen, wechseln, lösen (#2162) > offers the way back after „Zugang entfernt“
- components/LibrarySourceSection.test.tsx > LibrarySourceSection - Zugang zuordnen, wechseln, lösen (#2162) > offers „Zugang zuordnen“ for a library with its own address of a type with profiles
- components/LibrarySourceSection.test.tsx > LibrarySourceSection - Zugang zuordnen, wechseln, lösen (#2162) > offers „Zugang zuordnen“ on the lock of the own address and connects through the chosen profile
- components/LibrarySourceSection.test.tsx > LibrarySourceSection - Zugang zuordnen, wechseln, lösen (#2162) > private library (#2164) > leads to "Verbundene Konten" when no other profile carries an account of the owner
- components/LibrarySourceSection.test.tsx > LibrarySourceSection - Zugang zuordnen, wechseln, lösen (#2162) > private library (#2164) > never offers a profile for persons only to a shared library
- components/LibrarySourceSection.test.tsx > LibrarySourceSection - Zugang zuordnen, wechseln, lösen (#2162) > private library (#2164) > offers no release from the profile
- components/LibrarySourceSection.test.tsx > LibrarySourceSection - Zugang zuordnen, wechseln, lösen (#2162) > private library (#2164) > offers only the profiles the owner has a connected account on, one for persons only included
- components/LibrarySourceSection.test.tsx > LibrarySourceSection - Zugang zuordnen, wechseln, lösen (#2162) > releases a library from its profile after a confirmation
- components/LibrarySourceSection.test.tsx > LibrarySourceSection - Zugang zuordnen, wechseln, lösen (#2162) > says when a switch to another server discarded the secret and offers to edit the source
- components/LibrarySourceSection.test.tsx > LibrarySourceSection - Zugang zuordnen, wechseln, lösen (#2162) > shows the result of „Verbindung prüfen“ without saving, and an error of the test itself
- components/LibrarySourceSection.test.tsx > LibrarySourceSection - Zugang zuordnen, wechseln, lösen (#2162) > switches a connected library to another profile, never offering the current one
- components/LibrarySourceSection.test.tsx > LibrarySourceSection - Zugang zuordnen, wechseln, lösen (#2162) > tests the new profile before switching and saves after a failed test only on request
- pages/ConnectionCallbackPage.test.tsx > ConnectionCallbackPage > Quelle verbinden (#2169) > hands the pending connection to the waiting wizard and returns there
- pages/LibraryCreatePage.sourceConsent.test.tsx > LibraryCreatePage - Quelle verbinden (#2169) > asks to connect again when the pending connection can no longer be used
- pages/LibraryCreatePage.sourceConsent.test.tsx > LibraryCreatePage - Quelle verbinden (#2169) > comes back on the source step with the connection, its account and its focus
- pages/LibraryCreatePage.sourceConsent.test.tsx > LibraryCreatePage - Quelle verbinden (#2169) > creates the library with the pending connection, the creator answering for it
- pages/LibraryCreatePage.sourceConsent.test.tsx > LibraryCreatePage - Quelle verbinden (#2169) > lets the owning group answer for the connection
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Anlegerechte (#1813, ADR-0036 Entscheidung 5) > moves the selection off a tile that turns out to be hidden
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Anlegerechte (#1813, ADR-0036 Entscheidung 5) > shows an empty step naming the system administration when nothing may be created
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Confluence origin (#1135, ADR-0023) > detects the edition before asking for credentials and shows Cloud fields for Cloud
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Confluence origin (#1135, ADR-0023) > drops a late test answer once the address changed in the meantime
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Confluence origin (#1135, ADR-0023) > joins e-mail and token for Cloud and drops verification when the address changes
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Confluence origin (#1135, ADR-0023) > keeps the space picker usable after "Zurück" re-enters the source step
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Confluence origin (#1135, ADR-0023) > offers a retry when the space listing fails, keeping the selection visible
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Confluence origin (#1135, ADR-0023) > sends an entered full-sync rhythm as the number, not as the update path’s zero
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Confluence origin (#1135, ADR-0023) > shows a blocked or unreachable address as an error with the backend wording, and never a credentials field
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Confluence origin (#1135, ADR-0023) > shows the PAT field for Data Center and refuses to continue without a tested selection
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Confluence origin (#1135, ADR-0023) > skips the first run when the immediate-indexing switch is turned off
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Confluence origin (#1135, ADR-0023) > verifies credentials, loads the spaces, and sends edition, credentials and selection
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Private Bibliothek (#2164) > creates a private library over the own account, without a secret and without „Freigaben“
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Private Bibliothek (#2164) > keeps the shareable way on a profile admitting both as an explicit choice of its own
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Private Bibliothek (#2164) > never chooses the private way by itself, even where it is the only one
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > S3 origin (#1377, ADR-0027) > creates a MinIO library with the template values and sends its settings
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > S3 origin (#1377, ADR-0027) > creates an AWS library from the template with a derived virtual-host endpoint
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > S3 origin (#1377, ADR-0027) > offers the S3 tile, states the sharing consequence before the scopes, and blocks Weiter until the stages are complete
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > S3 origin (#1377, ADR-0027) > refuses overlapping scopes before anything is sent
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > SharePoint (ADR-0047) > creates a library through the profile with the chosen document library, without a secret
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Zeitplan und Sofortstart im Assistenten (#1942) > sends no schedule at all for an upload library
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Zeitplan und Sofortstart im Assistenten (#1942) > sets the schedule together with the library, for a connector type that never had one here
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Zugang (#2162) > creates a library through the chosen profile, its address prefilled
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Zugang (#2162) > does not carry address or fixed values of the previous way over to the next
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Zugang (#2162) > drops the edition detected for an own address once a profile clears that address
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Zugang (#2162) > offers no certificate switch under the chosen profile and sends none
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Zugang (#2162) > offers the own address alone while no profile exists yet
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > Zugang (#2162) > sends no profile for the own address
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > asks before noting a group of an external provider and notes nothing on cancel
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > blocks Weiter without a name and says so
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > creates a group-owned library, offering only the groups returned for the user
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > creates the library and navigates to its detail page
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > errors at their field in „Quelle einrichten“ (#2193) > announces an issue of a field the form does not show in the step’s live region
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > errors at their field in „Quelle einrichten“ (#2193) > ties a missing S3 access key to its field
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > errors at their field in „Quelle einrichten“ (#2193) > ties a missing address to the URL field
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > errors at their field in „Quelle einrichten“ (#2193) > ties a wrong path to its field, focuses it and lets the step stand
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > goes to the new library when a noted grant is refused, and names the grant
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > keeps entered values when navigating back
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > names a failed source-type list in German and does not go on with the upload library
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > names the steps and drops "Quelle einrichten" for an upload library
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > notes a person with the shared subject picker and grants on the asset after creation
- pages/LibraryCreatePage.test.tsx > LibraryCreatePage (#596, #1942) > switches the connection form with the chosen kind and validates its fields
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > Reiter Quelle (#1940) > explains what a Geltungsbereich is and shows a VIEWER the Umfang only
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > Reiter Quelle (#1940) > names the sections behind the MANAGER bar in order
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > Reiter Quelle (#1940) > tests the stored configuration from the Anbindung section, without opening the editor
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > edits the schedule through the dialog, resending the unrelated Stammdaten fields untouched
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > edits the source configuration through the dialog, resending the unrelated Stammdaten fields untouched
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > offers "Bearbeiten" for a MANAGER on a connector library but hides it for a VIEWER
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > offers a forced full reconciliation only for a Confluence library (#1139)
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > resends the whole Google Drive settings with a changed rhythm (ADR-0040)
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > returns the rhythm to the instance default when the field is cleared (#1200)
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > shows a Confluence library read-only with edition and spaces, and still offers "Bearbeiten" (#1135)
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > shows a warning when the last two scheduled runs failed
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > shows an S3 library with its scopes in the page head, the configuration card and "Bearbeiten" (#1377)
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > shows no incomplete-listing warning while the assessment is clean (#1191)
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > shows the configuration and an indexing trigger for a connector library
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > shows the current schedule and the next planned run for a MANAGER
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > shows the full-sync rhythm of a Confluence library and lets a MANAGER lengthen it (#1200)
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > shows the webhook row to a manager of a Confluence library only (#1140)
- pages/LibraryDetailPage.test.tsx > LibraryDetailPage > states the Confluence sharing consequence to a VIEWER as well, and never for other types (#1138)
- utils/sharePointSource.test.ts > sharePointSource (ADR-0047) > reads stored folders as plain ids and with their shown names

</details>

## git diff --stat origin/feature/3084_ui-lab-var-d (ohne results/)
```
 docs/design/guidelines.md                          |  10 +-
 .../library/components/ConnectionProfileSelect.tsx |   6 +-
 .../library/components/LibraryScheduleForm.tsx     |   2 +-
 .../library/components/LibraryScopeHero.tsx        |  38 +-
 .../library/components/LibrarySourceSection.tsx    |  79 ++-
 .../library/components/SourceConsentPanel.tsx      |  18 +-
 .../components/setup/ConnectionCheckView.tsx       |   8 +-
 .../library/components/setup/ErrorSummary.tsx      |   4 +-
 .../library/components/setup/PickCombo.tsx         | 608 --------------------
 .../library/components/setup/ScopePicker.tsx       | 307 ++++++++++
 .../library/components/setup/SourceSetupRows.tsx   |  10 +-
 .../library/components/setup/SummaryRow.tsx        |   5 +
 .../setup/connectors/ConfluenceSetupFields.tsx     |  63 +-
 .../setup/connectors/DropboxSetupFields.tsx        |   6 +-
 .../components/setup/connectors/FolderScope.tsx    |  72 +--
 .../setup/connectors/GenericSetupFields.tsx        |   8 +-
 .../setup/connectors/GoogleDriveSetupFields.tsx    |  43 +-
 .../setup/connectors/NextcloudSetupFields.tsx      |   6 +-
 .../components/setup/connectors/S3SetupFields.tsx  |  61 +-
 .../setup/connectors/SharePointSetupFields.tsx     | 135 +----
 .../components/setup/connectors/SmbSetupFields.tsx |   6 +-
 .../library/components/setup/connectors/common.ts  |  11 +-
 .../components/setup/connectors/confluenceSetup.ts |  11 +-
 .../components/setup/connectors/driveNodes.ts      |  23 -
 .../components/setup/connectors/dropboxSetup.ts    |   2 +-
 .../components/setup/connectors/folderNodes.ts     |  49 +-
 .../components/setup/connectors/genericSetups.ts   |  13 +-
 .../setup/connectors/googleDriveSetup.ts           |  14 +-
 .../components/setup/connectors/nextcloudSetup.ts  |   2 +
 .../library/components/setup/connectors/s3Nodes.ts |  28 -
 .../library/components/setup/connectors/s3Setup.ts |  19 +-
 .../components/setup/connectors/sharePointNodes.ts |  60 --
 .../components/setup/connectors/sharePointSetup.ts |  26 +-
 .../components/setup/connectors/smbSetup.ts        |   1 +
 .../components/sourceKit/SelectionDialog.tsx       | 640 +++++++++++++++++++++
 .../library/components/sourceKit/plainMessage.ts   |  20 +
 .../library/components/sourceKit/selection.ts      | 100 ++++
 .../components/sourceKit/selectionSources.ts       | 346 +++++++++++
 .../components/sources/confluenceSource.tsx        |   3 +-
 .../library/components/sources/dropboxSource.tsx   |   3 +-
 .../components/sources/googleDriveSource.tsx       |   2 +-
 .../components/sources/sharePointSource.tsx        |   2 +-
 .../library/mocks/libraryScenarioFixtures.ts       |  43 +-
 .../features/library/mocks/sourceScenarioMock.ts   |   2 +-
 .../library/pages/ConnectionCallbackPage.tsx       |   9 +-
 .../src/features/library/utils/sharePointSource.ts |  10 +-
 .../src/shared/ui/feedback/NotificationHost.tsx    |  28 +-
 ui-lab/harness/capture.mjs                         | 123 ++--
 ui-lab/harness/record-flows.mjs                    | 466 +++++++++++++++
 49 files changed, 2290 insertions(+), 1261 deletions(-)
```
