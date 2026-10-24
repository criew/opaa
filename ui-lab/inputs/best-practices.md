# Best Practices für das Redesign „Wissensbibliotheken“

Stand 24.10.2026. Nur, was **nicht** schon in `docs/design/guidelines.md`, `docs/design/accessibility.md`
oder der Prüfliste „kein KI-Look“ (Punkte 1–24) steht. Eine Regel je Zeile, mit Quelle.
Hinweis zur Recherche: nngroup.com, design-system.service.gov.uk und carbondesignsystem.com waren aus der
Sandbox nicht abrufbar; die Links sind die kanonischen Seiten, Inhalte über Suche bzw. Fachkenntnis
abgeglichen. Vor einem Zitat in einem ADR die Seite gegenlesen.

## 1 · Formulare und Assistenten

### Aufbau und Reihenfolge
- Eine Frage/ein Thema je Schritt; Schrittinhalt passt ohne Scrollen auf 1280×800, sonst teilen. — [GOV.UK: Question pages / One thing per page](https://design-system.service.gov.uk/patterns/question-pages/)
- Assistent nur bei linearer, abhängiger Abfolge; unabhängige Einstellungen (Bearbeiten-Dialog) als ein Formular mit Abschnitten. — [NN/g: Wizards](https://www.nngroup.com/articles/wizards/)
- Felder in der Reihenfolge, in der die Person die Information im Quellsystem findet (Adresse → Anmeldung → Umfang → Zeitplan), nicht nach Backend-Feldern. — [NN/g: Form design](https://www.nngroup.com/articles/web-form-design/)
- Einspaltig; keine nebeneinanderstehenden Felder außer bei logisch Zusammengehörigem (Host + Port). — [Baymard: Form fields single column](https://baymard.com/blog/avoid-multi-column-forms)
- Feldbreite spiegelt erwartete Eingabelänge (Port schmal, URL breit). — [GOV.UK: Text input – field widths](https://design-system.service.gov.uk/components/text-input/)
- Fortgeschrittenes progressiv offenlegen („Erweiterte Einstellungen“ zugeklappt), sinnvolle Vorgaben gesetzt. — [NN/g: Progressive Disclosure](https://www.nngroup.com/articles/progressive-disclosure/)
- Vorgaben nur, wenn sie für die Mehrheit richtig sind; nie eine folgenreiche Option (Freigabe an alle, Löschen im Ziel) vorbelegen. — [NN/g: Defaults](https://www.nngroup.com/articles/the-power-of-defaults/)
- Abhängige Felder erscheinen unter der auslösenden Option (Conditional reveal), nicht auf einer anderen Seite; höchstens eine Ebene tief. — [GOV.UK: Radios – conditionally revealing](https://design-system.service.gov.uk/components/radios/)
- Was das System schon weiß, nicht erneut abfragen (Name aus Site-/Space-Titel vorbelegen, Zugangsdaten nicht doppelt). — [WCAG 2.2 SC 3.3.7 Redundant Entry](https://www.w3.org/WAI/WCAG22/Understanding/redundant-entry.html)

### Validierung und Absenden
- Inline-Prüfung beim Verlassen des Feldes (blur), nie beim Fokus oder während des ersten Tippens; ist ein Fehler sichtbar, beim Tippen erneut prüfen und Fehler sofort entfernen. — [Baymard: Inline validation](https://baymard.com/blog/inline-form-validation)
- Beim Absenden zusätzlich Fehlerzusammenfassung oben mit Sprunglinks zu jedem Feld; Text identisch zur Feldmeldung. — [GOV.UK: Error summary](https://design-system.service.gov.uk/components/error-summary/)
- Seitentitel bei Fehler mit Präfix „Fehler: “. — [GOV.UK: Validation](https://design-system.service.gov.uk/patterns/validation/)
- Absenden-/Weiter-Knopf bleibt aktiv; Fehlende Angaben melden statt Knopf deaktivieren. — [GOV.UK Frontend v4.6.0: disabled controls](https://github.com/alphagov/govuk-frontend/releases/tag/v4.6.0), [Adam Silver: Disabled buttons](https://adamsilver.io/blog/the-problem-with-disabled-buttons-and-what-to-do-instead/)
- Wenn doch deaktiviert (z. B. während des Sendens): `aria-disabled` statt `disabled`, fokussierbar, Grund sichtbar. — [Smashing: Disabled buttons](https://www.smashingmagazine.com/2021/08/frustrating-design-patterns-disabled-buttons/)
- Doppeltes Absenden verhindern: Knopf zeigt „Wird gespeichert …“, zweiter Klick wirkungslos. — [GOV.UK: Button – preventDoubleClick](https://design-system.service.gov.uk/components/button/)
- Format großzügig annehmen (Leerzeichen, abschließender Schrägstrich, `http://` fehlt) und normalisieren statt ablehnen. — [GOV.UK: Validation – be tolerant](https://design-system.service.gov.uk/patterns/validation/), Postel’sches Prinzip

### Navigation im Assistenten
- „Zurück“ behält alle Eingaben; Browser-Zurück verhält sich wie „Zurück“, nicht wie Abbruch. — [GOV.UK: Back link](https://design-system.service.gov.uk/components/back-link/), [NN/g: Wizards](https://www.nngroup.com/articles/wizards/)
- Schrittanzeige nur bei ≤ 5–7 Schritten; Schritte vorwärts nicht anklickbar, erledigte zurück anklickbar. — [Material 3 / MUI Stepper](https://mui.com/material-ui/react-stepper/), [NN/g: Wizards](https://www.nngroup.com/articles/wizards/)
- Letzter Schritt vor dem Anlegen = Prüfseite („Prüfen“): Zusammenfassung je Abschnitt mit „Ändern“-Link, der direkt zum Feld führt und danach zurück zur Prüfseite. — [GOV.UK: Check answers](https://design-system.service.gov.uk/patterns/check-answers/)
- „Ändern“-Links tragen versteckten Kontext („Ändern <span class=visually-hidden>Endpoint</span>“). — [GOV.UK: Summary list](https://design-system.service.gov.uk/components/summary-list/)
- Nach dem Anlegen: Bestätigungsseite/-zustand mit „Was passiert jetzt“ (Indizierung startet, Dauer, wo der Status steht). — [GOV.UK: Confirmation pages](https://design-system.service.gov.uk/patterns/confirmation-pages/)

### Ungespeicherte Änderungen
- Warnen nur, wenn tatsächlich geändert wurde (Dirty-Vergleich gegen Ausgangswert, nicht „Feld berührt“). — [NN/g: Confirmation dialogs](https://www.nngroup.com/articles/confirmation-dialog/)
- Warnung bei In-App-Navigation, Dialog-Schließen (Escape, Klick daneben, X) und `beforeunload`; Escape in einem geänderten Dialog verwirft nicht still. — [Fluent 2: Dialog](https://fluent2.microsoft.design/components/web/react/core/dialog/usage), [MDN beforeunload](https://developer.mozilla.org/docs/Web/API/Window/beforeunload_event)
- Lange Assistenten: Entwurf speichern oder Eingaben über Sitzungsablauf hinweg erhalten. — [WCAG 2.2 SC 2.2.5 Re-authenticating (AAA)](https://www.w3.org/WAI/WCAG22/Understanding/re-authenticating.html), [GOV.UK: Save and return](https://design-system.service.gov.uk/patterns/save-and-return/)

### Verbindungstest (Konnektor-Einrichtung)
- Branchenmuster: Verbindung wird beim „Weiter/Speichern“ automatisch geprüft, zusätzlich expliziter Knopf „Verbindung testen“; Edit heißt „Testen und speichern“. — [Airbyte: Add a source](https://docs.airbyte.com/platform/using-airbyte/getting-started/add-a-source), [Fivetran: Connection tests](https://fivetran.com/docs/using-fivetran/fivetran-dashboard/connectors), [n8n: Credentials](https://docs.n8n.io/credentials/)
- Test erst möglich, wenn die nötigen Felder valide sind; Testergebnis inline unter dem Knopf, nicht als Popup. — Airbyte/Fivetran (s. o.)
- Ergebnis gestuft benennen: erreichbar → angemeldet → Rechte ausreichend → n Objekte sichtbar; bei Fehlschlag die Stufe nennen, die scheiterte, und das betroffene Feld markieren. — [Fivetran setup tests](https://fivetran.com/docs/getting-started/core-concepts)
- Testergebnis veraltet, sobald ein Verbindungsfeld geändert wird (Ergebnis ausblenden/„erneut testen“). — eigene Ableitung aus Airbyte-Verhalten
- Anlegen trotz fehlgeschlagenem Test nur mit ausdrücklicher Wahl („Trotzdem speichern“), Fivetran/Airbyte blockieren standardmäßig. — Airbyte (s. o.)
- OAuth-Quellen (SharePoint, Google Drive, Dropbox): „Anmelden bei …“-Knopf statt Token-Feld, danach angemeldetes Konto anzeigen und „Anderes Konto“. — [Power Automate: Connections](https://learn.microsoft.com/power-automate/add-manage-connections), [Zapier: Connect accounts](https://help.zapier.com/hc/en-us/articles/8496343026701)
- Gespeicherte Geheimnisse nie zurückspiegeln; Feld zeigt „gesetzt“ und „Ersetzen“. — [OWASP ASVS V8/V14](https://owasp.org/www-project-application-security-verification-standard/) (Guideline 5.2 regelt nur Kennwortfeld)
- Kennwortfelder erlauben Einfügen und „Anzeigen“; kein Abtippen-Zwang. — [WCAG 2.2 SC 3.3.8 Accessible Authentication](https://www.w3.org/WAI/WCAG22/Understanding/accessible-authentication-minimum.html), [GOV.UK: Passwords](https://design-system.service.gov.uk/patterns/passwords/)

### Pflicht/optional
- „(optional)“ statt Sternchen ist GOV.UK-konform; NN/g empfiel Sternchen – siehe Widersprüche. — [GOV.UK: Question pages](https://design-system.service.gov.uk/patterns/question-pages/), [NN/g: Required fields](https://www.nngroup.com/articles/required-fields/)
- Optionale Felder minimieren: jedes optionale Feld begründen oder streichen. — [GOV.UK: Question protocol](https://www.gov.uk/service-manual/design/designing-good-questions)

## 2 · Skalierbare Auswahl

| Menge (realistisch) | Muster | Quelle |
| --- | --- | --- |
| 2–~7, gleichartig, mit Icon | Kacheln (Guideline 5.11) / Radios | [NN/g: Listbox vs. dropdown](https://www.nngroup.com/articles/listbox-dropdown/) |
| ~5–~15 Mehrfach | Checkbox-Liste, alles sichtbar | [NN/g: Checkboxes vs. radios](https://www.nngroup.com/articles/checkboxes-vs-radio-buttons/) |
| ~15–hunderte, flach | Liste mit Filterfeld + Checkboxen + „n ausgewählt“, oder Combobox-Mehrfachauswahl mit Chips | [Carbon: Multiselect / Filterable](https://carbondesignsystem.com/components/dropdown/usage/), [GOV.UK Accessible autocomplete](https://alphagov.github.io/accessible-autocomplete/) |
| > 20 mit Bedarf, Gewähltes getrennt zu sehen | Transferliste (Dual list) | [PatternFly: Dual list selector](https://www.patternfly.org/components/dual-list-selector/design-guidelines) („< 20 → Select“) |
| hierarchisch (Site → Bibliothek → Ordner) | Baum mit Suche, Lazy Loading je Ebene, Tri-State | [Carbon: Tree view](https://carbondesignsystem.com/components/tree-view/usage/), [WAI-ARIA APG: Tree view](https://www.w3.org/WAI/ARIA/apg/patterns/treeview/) |
| tief (> 3 Ebenen), Browsen statt Suchen | Miller-Spalten nur Desktop; schmal → Drilldown-Liste mit Breadcrumb | [NN/g: Mega/Column navigation](https://www.nngroup.com/articles/miller-columns/) (Stichwort; ggf. gegenlesen) |

- Kombobox-Mehrfachauswahl nur, wenn die Person Namen kennt (Suche); wer browsen muss, braucht sichtbare Liste/Baum. — [NN/g: Listbox vs. dropdown](https://www.nngroup.com/articles/listbox-dropdown/)
- Ausgewähltes bleibt beim Filtern erhalten, Filter entfernt nie Auswahl; Zähler „n ausgewählt“ zählt auch Ausgeblendetes. — [Carbon: Data table – batch actions](https://carbondesignsystem.com/components/data-table/usage/#batch-actions)
- „Alle auswählen“ wählt die sichtbaren/gefilterten Einträge und sagt das („Alle 37 Treffer auswählen“); bei Paginierung zweiter Schritt „Alle 412 auswählen“. — [Carbon: Data table selection](https://carbondesignsystem.com/components/data-table/usage/), Gmail-Muster
- Tri-State: Elternknoten „teilweise“ = `aria-checked="mixed"`; Klick auf „teilweise“ wählt alle Kinder. — [WAI-ARIA APG: Checkbox (mixed)](https://www.w3.org/WAI/ARIA/apg/patterns/checkbox/examples/checkbox-mixed/)
- Semantik von Elternauswahl festlegen und anzeigen: „ganze Site inkl. künftiger Bibliotheken“ vs. „nur die jetzt vorhandenen“. — [Microsoft Purview/SharePoint scopes](https://learn.microsoft.com/purview/), eigene Ableitung — fachlich zu klären
- Baum: Tastatur nach APG (Pfeil rechts öffnet, links schließt/zum Eltern, Leertaste wählt, Typeahead). — [WAI-ARIA APG: Tree view](https://www.w3.org/WAI/ARIA/apg/patterns/treeview/)
- Lazy-Load-Ebenen: Lade- und Fehlerzustand im Knoten selbst, „Erneut versuchen“ dort. — [Carbon: Tree view](https://carbondesignsystem.com/components/tree-view/usage/)
- Suche im Baum zeigt Treffer mit Pfad („Site › Bibliothek › Ordner“), nicht nur den Blattnamen. — [NN/g: Tree testing / Search results context](https://www.nngroup.com/articles/search-results-page/)
- Zusammenfassung der Auswahl außerhalb der Liste sichtbar (Chips mit Entfernen oder Zeile „3 Sites, 12 Bibliotheken“), bei > ~10 Chips eingeklappt „+n“. — [Carbon: Multiselect](https://carbondesignsystem.com/components/dropdown/usage/)
- Kein Drag-and-drop als einziger Weg (Transferliste: Knöpfe „Hinzufügen/Entfernen“). — [WCAG 2.2 SC 2.5.7 Dragging Movements](https://www.w3.org/WAI/WCAG22/Understanding/dragging-movements.html)

## 3 · Rückmeldungen: Entscheidungsregeln

| Ereignis | Muster | Quelle |
| --- | --- | --- |
| Feld ungültig | Inline am Feld (+ Zusammenfassung beim Absenden) | GOV.UK Error summary |
| Abschnitt/Bereich betroffen (Ladefehler, nur Leserechte) | Inline/Section message über dem Bereich, bleibt bis behoben | [Atlassian: Section message](https://atlassian.design/components/section-message/), [Carbon: Inline](https://carbondesignsystem.com/patterns/notification-pattern/) |
| Quittung einer Aktion, unkritisch | Toast/Flag, kurz | [Atlassian: Flag](https://atlassian.design/components/flag/), [Material 3: Snackbar](https://m3.material.io/components/snackbar/guidelines) |
| System-/produktweit, nicht aufgabenbezogen (Wartung, Lizenz, Konnektor überall gestört) | Banner oben, verdrängt Inhalt | [Atlassian: Banner](https://atlassian.design/components/banner/) |
| Entscheidung nötig, blockierend | Dialog | [NN/g: Modal & nonmodal](https://www.nngroup.com/articles/modal-nonmodal-dialog/) |

- Ein Toast enthält nie die einzige Kopie einer Fehlerinformation; Fehler, die Handeln verlangen, stehen inline am Ort und bleiben. — [Carbon: Notification pattern](https://carbondesignsystem.com/patterns/notification-pattern/), [NN/g: Indicators, validations, notifications](https://www.nngroup.com/articles/indicators-validations-notifications/)
- Ein Toast-Text ≤ ~2 Zeilen, keine Links außer einer Aktion. — [Material 3: Snackbar](https://m3.material.io/components/snackbar/guidelines)
- Toast mit Aktion: bei Fokus/Hover pausiert der Timer; Screenreader-Nutzer erreichen die Aktion. — [WCAG 2.2.1 Timing Adjustable](https://www.w3.org/WAI/WCAG22/Understanding/timing-adjustable.html), [Material 3 Snackbar accessibility](https://m3.material.io/components/snackbar/accessibility)
- Nicht mehr als ein Toast gleichzeitig; Folgemeldungen ersetzen oder stapeln max. 3. — [Atlassian: Flag](https://atlassian.design/components/flag/)
- Meldung unmittelbar nach eigener Aktion auf derselben Seite (Speichern im Formular): Inline-Bestätigung am Knopf („Gespeichert“) reicht, kein Toast. — [GOV.UK: Notification banner – success](https://design-system.service.gov.uk/components/notification-banner/)
- Erfolgsmeldung nach Seitenwechsel (Anlegen → Detailseite): GOV.UK nutzt Success-Banner auf der Zielseite statt Toast. — [GOV.UK: Notification banner](https://design-system.service.gov.uk/components/notification-banner/)

## 4 · Leer-, Lade-, Fehlerzustände und Hintergrundjobs

- Zeitschwellen: < 0,1 s keine Anzeige; ~1 s Fokus bleibt, Spinner/Skeleton erst nach ~300–1000 ms einblenden (kein Flackern); > 10 s Prozent- oder Mengenfortschritt plus Möglichkeit, weiterzuarbeiten. — [NN/g: Response time limits](https://www.nngroup.com/articles/response-times-3-important-limits/), [NN/g: Progress indicators](https://www.nngroup.com/articles/progress-indicators/)
- Skeleton nur für Seiten-/Bereichsaufbau mit bekannter Struktur; für Aktionen (Testen, Speichern) Spinner im Knopf. — [NN/g: Skeleton screens](https://www.nngroup.com/articles/skeleton-screens/)
- Drei Leerzustände unterscheiden: erstmals leer (Einstieg + Handlung), leer durch Filter („Keine Treffer für …“ + „Filter zurücksetzen“), leer durch Fehler/fehlende Rechte. — [Carbon: Empty states](https://carbondesignsystem.com/patterns/empty-states-pattern/), [NN/g: Empty states](https://www.nngroup.com/articles/empty-state-interface-design/)
- Fehlerzustand nennt Ursache in Nutzerbegriffen und Handlung („Zugangsdaten abgelaufen – Neu anmelden“), Fehlercode nur ausklappbar für Betrieb. — [NN/g: Error message guidelines](https://www.nngroup.com/articles/error-message-guidelines/)
- Langlaufende Indizierung: Status (Wartet · Läuft · Abgeschlossen · Teilweise fehlgeschlagen · Fehlgeschlagen) + Mengen („1 240 von 3 100 Dokumenten“) + Zeitpunkt des letzten Laufs + Abbrechen. — [Carbon: Status indicators](https://carbondesignsystem.com/patterns/status-indicator-pattern/), [Fivetran: Sync status](https://fivetran.com/docs/using-fivetran/fivetran-dashboard)
- „Teilweise fehlgeschlagen“ als eigener Zustand mit Liste der betroffenen Dokumente/Ordner und Grund, nicht als Gesamtfehler. — Fivetran/Airbyte Sync-Logs
- Keine Zeitprognose, wenn sie unzuverlässig ist; Mengenangabe statt Restzeit. — [NN/g: Progress indicators](https://www.nngroup.com/articles/progress-indicators/)
- Statusänderung eines Hintergrundjobs: Live-Region nur bei Zustandswechsel, nicht bei jedem Prozentpunkt. — [WCAG 4.1.3 Status Messages](https://www.w3.org/WAI/WCAG22/Understanding/status-messages.html)
- Fortschritt per Polling/Push ohne Layoutsprung; Zeilen springen beim Aktualisieren nicht in der Sortierung. — [WCAG 3.2.5 / NN/g: Change blindness](https://www.nngroup.com/articles/change-blindness-definition/)

## 5 · Weitere anerkannte Prüfungen

- **Heuristische Evaluation** nach Nielsens 10 Heuristiken, 3–5 Prüfende unabhängig, Befunde mit Schweregrad 0–4. — [NN/g: 10 Heuristics](https://www.nngroup.com/articles/ten-usability-heuristics/), [NN/g: Severity ratings](https://www.nngroup.com/articles/how-to-rate-the-severity-of-usability-problems/)
- **WCAG 2.2 statt 2.1:** EN 301 549 v4 (2025/26) bzw. BITV-Fortschreibung zieht 2.2 nach; neue AA-Kriterien: 2.4.11 Focus not obscured, 2.5.7 Dragging, 2.5.8 Target size (24 px), 3.2.6 Consistent help, 3.3.7 Redundant entry, 3.3.8 Accessible authentication. — [W3C: What’s new in WCAG 2.2](https://www.w3.org/WAI/standards-guidelines/wcag/new-in-22/)
- **Zielgröße ≥ 24×24 CSS-px** oder 24-px-Abstandskreis (AA); Icon-Knöpfe in Tabellen/Bäumen prüfen. — [WCAG 2.5.8](https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum.html)
- **Fokus nicht verdeckt:** Sticky-Kopf/-Fuß, Toast und Assistenten-Knopfleiste dürfen das fokussierte Element nicht ganz verdecken. — [WCAG 2.4.11](https://www.w3.org/WAI/WCAG22/Understanding/focus-not-obscured-minimum.html)
- **Fokus beim Schrittwechsel** im Assistenten auf die Schrittüberschrift (h1/h2, `tabindex=-1`), Seitentitel enthält Schritt („Schritt 2 von 4: Verbindung – Neue Wissensbibliothek“). — [GOV.UK: Page template / titles](https://design-system.service.gov.uk/styles/page-template/), [WAI-ARIA APG: Dialog](https://www.w3.org/WAI/ARIA/apg/patterns/dialog-modal/)
- **Dialog-Erstfokus:** erstes Feld bei Formular-Dialogen, sonst Titel/sicherste Aktion. — [WAI-ARIA APG: Dialog](https://www.w3.org/WAI/ARIA/apg/patterns/dialog-modal/)
- **Reiter:** Pfeiltasten wechseln, URL enthält Reiter (Deep-Link, Zurück-Taste). — [WAI-ARIA APG: Tabs](https://www.w3.org/WAI/ARIA/apg/patterns/tabs/), [NN/g: Tabs](https://www.nngroup.com/articles/tabs-used-right/)
- **Textausdehnung Deutsch:** Layout verträgt +30–40 % Textlänge gegenüber Englisch und Wörter > 25 Zeichen („Dokumentbibliotheksberechtigungen“); `hyphens: auto` mit `lang="de"`, `overflow-wrap: anywhere` nur in Namen/URLs. — [W3C i18n: Text size](https://www.w3.org/International/articles/article-text-size), [MDN hyphens](https://developer.mozilla.org/docs/Web/CSS/hyphens)
- **Konsistente Hilfe:** Hilfe-/Handbuch-Link an derselben relativen Stelle auf allen Assistenten-Schritten. — [WCAG 3.2.6](https://www.w3.org/WAI/WCAG22/Understanding/consistent-help.html)
- **axe-Abdeckung erweitern** auf Assistenten-Schritte, geöffnete Dialoge, Fehlerzustände (nach Absenden) – axe prüft nur, was gerendert ist; automatisch findet axe ~57 % der Probleme. — [Deque: Automated testing coverage](https://www.deque.com/automated-accessibility-testing-coverage/)
- **Visuelle Regressionstests:** Playwright `toHaveScreenshot` je Zustand × Schema × Breite (320/768/1280), Animationen aus, feste Fixtures, Toleranz klein und begründet. — [Playwright: Visual comparisons](https://playwright.dev/docs/test-snapshots)
- **Kognitive Last messen:** Aufgabenzeit, Erfolgsrate, SEQ (Single Ease Question) je Aufgabe; Interaktionskosten (Klicks/Felder) je Konnektor-Anlage vergleichen. — [NN/g: SEQ](https://www.nngroup.com/articles/measuring-perceived-usability/), [NN/g: Interaction cost](https://www.nngroup.com/articles/interaction-cost-definition/)
- **Fünf-Sekunden-/Erstklick-Test** für Kachelwahl und Detailseite. — [NN/g: First click testing](https://www.nngroup.com/articles/first-click-testing/) (Stichwort)
- **Verständliche Sprache:** Leichte/einfache Sprache-Prüfung, Satzlänge ≤ 20 Wörter, Fachbegriffe der Quellsysteme (Site, Space, Bucket) einmal erklärt. — [GOV.UK: Writing for GOV.UK](https://www.gov.uk/guidance/content-design/writing-for-gov-uk), [BITV 2.0 Anlage 2 / KERN UX](https://www.kern-ux.de/)

## 6 · Systematisch konsistenter werden

- **Interface Inventory:** Screenshot jedes Vorkommens je Komponentenart (Knöpfe, Felder, Meldungen, Leerzustände, Statusanzeigen) quer über alle 10 Konnektor-Formulare, in ein Raster sortiert; Abweichungen zählen. — [Brad Frost: Interface Inventory](https://bradfrost.com/blog/post/interface-inventory/)
- **Atomic Design:** Formular eines Konnektors = Komposition aus gemeinsamen Feldgruppen (Verbindung, Anmeldung, Umfang, Zeitplan); konnektorspezifisch nur der Umfang. — [Brad Frost: Atomic Design](https://atomicdesign.bradfrost.com/)
- **Schema-getriebene Formulare:** Airbyte/n8n rendern Konnektor-Formulare aus einer Spezifikation (JSON Schema) – erzwingt Konsistenz von Reihenfolge, Hilfetext, Geheimfeldern. — [Airbyte: Connector spec](https://docs.airbyte.com/platform/understanding-airbyte/airbyte-protocol#spec)
- **Design Tokens** dreistufig (Basis → semantisch → Komponente), W3C-Format; Lint gegen Rohwerte. — [W3C Design Tokens Format](https://www.designtokens.org/tr/drafts/format/) (Guideline 2.3 deckt Farben ab; Abstände/Radien analog prüfen)
- **Komponenten-Konsolidierung:** je Bedarf genau eine Komponente; Varianten-Zählung pro Release (Ziel sinkend), Nachbauten per ESLint-Regel `no-restricted-imports` sperren. — [Nathan Curtis: Design systems metrics](https://medium.com/eightshapes-llc/measuring-design-system-success-7e1ff7d4d24b)
- **Content-Inventar:** alle Texte der Konnektor-Formulare in einer Tabelle (Label, Hilfe, Platzhalter, Fehler) nebeneinander – Begriffs- und Tonabweichungen sichtbar machen. — [NN/g: Content inventory](https://www.nngroup.com/articles/content-audits/)

## Widersprüche zu unseren Richtlinien

1. **Pflichtfeld-Kennzeichnung:** NN/g empfiehlt Sternchen an Pflichtfeldern (mit Legende), auch wenn optionale markiert sind; Guideline 5.2 verbietet Sternchen. GOV.UK und KERN stützen unsere Regel. → beibehalten, Abweichung bewusst.
2. **Kacheln vs. Checkbox-Liste (5.11):** 5.11 schließt Checkbox-Listen aus; NN/g/PatternFly/Carbon empfehlen ab ~15–20 Einträgen Liste/Filter/Baum/Transferliste. 5.11 gilt nur für „überschaubare Menge“, nennt aber keine Grenze. → Schwelle festlegen (Vorschlag: Kacheln ≤ 12, darüber gefilterte Liste, hierarchisch Baum).
3. **Toast-Fehler (5.9):** 5.9 nennt „fehlgeschlagener Download“ als Toast. Carbon/NN/g: handlungsrelevante Fehler nicht nur im flüchtigen Toast. → Toast für Fehler nur, wenn nichts zu tun bleibt oder der Fehler zusätzlich am Ort steht.
4. **6-s-Timer (5.9):** WCAG 2.2.1 verlangt, Zeitlimits abschaltbar/verlängerbar zu machen; ein Toast mit Aktion („Rückgängig“) unterliegt dem. 5.9 mildert durch Alternativweg ab; Timer-Pause bei Fokus/Hover fehlt in der Regel.
5. **Zielniveau WCAG 2.1 (accessibility.md 1):** EN 301 549 v4 referenziert WCAG 2.2; sechs neue AA-Kriterien sind ungeprüft. Kein Widerspruch im Wortlaut, aber eine Lücke mit Ablaufdatum.
6. **Stepper ohne Unterzeilen (Prüfliste 9) vs. Seitentitel:** kein Widerspruch, aber GOV.UK verzichtet ganz auf Stepper und setzt „Schritt n von m“ als Text – bei ≤ 4 Schritten als Alternative erwägen.

## Ergänzungen für die Prüfliste

25. **Absenden bleibt aktiv.** Kein `disabled` an Weiter/Speichern/Anlegen wegen fehlender Eingaben (DOM: `button[disabled]` im Formular = Befund).
26. **Fehlerzusammenfassung.** Absenden mit 2 leeren Pflichtfeldern → oben eine Liste mit 2 Sprunglinks, Fokus darauf, `document.title` beginnt mit „Fehler: “.
27. **Keine verfrühten Fehler.** Feld fokussieren und wieder verlassen ohne Eingabe vor dem ersten Absenden zeigt keinen Fehler; Tippen in ein fehlerhaftes Feld entfernt den Fehler, sobald gültig.
28. **Prüfseite vor dem Anlegen.** Letzter Schritt zeigt alle Angaben je Abschnitt mit „Ändern“; „Ändern“ springt zum Feld und kehrt zur Prüfseite zurück (Screenshot + E2E).
29. **Zurück verliert nichts.** Schritt 3 → Zurück → Weiter: alle Eingaben noch da; Browser-Zurück verhält sich gleich.
30. **Verbindungstest gestuft und inline.** Ergebnis steht unter dem Knopf, nennt die gescheiterte Stufe (Adresse · Anmeldung · Rechte) und markiert das Feld; Ändern eines Verbindungsfelds blendet das alte Ergebnis aus.
31. **Ungespeicherte Änderungen.** Geänderter Dialog: Escape/Klick daneben/X fragen nach („Änderungen verwerfen?“); ungeänderter Dialog schließt ohne Frage.
32. **Geheimnisse.** Bearbeiten-Ansicht zeigt gespeichertes Kennwort/Token als „gesetzt“ + „Ersetzen“, nie Punkte mit echter Länge; Einfügen ins Kennwortfeld funktioniert.
33. **Auswahlmuster nach Menge.** Kacheln ≤ 12 Einträge; darüber Liste mit Filterfeld; hierarchisch Baum mit Suche — Fixture 200 Ordner darf keine Kachelwand ergeben.
34. **Tri-State im Baum.** Teilweise gewählter Elternknoten zeigt Strich-Symbol und `aria-checked="mixed"`; Klick wählt alle Kinder.
35. **„Alle auswählen“ benennt den Umfang** („Alle 37 Treffer“) und wirkt nur auf Gefiltertes; Filterwechsel lässt bestehende Auswahl und Zähler unverändert.
36. **Drei Leerzustände.** Erstmals leer, 0 Filtertreffer (mit „Filter zurücksetzen“) und kein Zugriff sind textlich verschieden (Screenshot-Vergleich).
37. **Kein Lade-Flackern.** Antwort < 300 ms zeigt weder Spinner noch Skeleton (Netzwerk-Mock mit 100 ms); Aktion > 1 s zeigt Spinner im auslösenden Knopf.
38. **Indizierungsstatus vollständig.** Zustand als Wort + Mengen („n von m Dokumenten“) + letzter Lauf + bei Teilfehlern Liste der Betroffenen; keine Restzeit.
39. **Fehler nie nur im Toast.** Jeder Fehler, der Handeln verlangt, steht zusätzlich am Ort; Toast-Timer pausiert bei Fokus/Hover.
40. **Zielgröße.** Jedes Bedienelement ≥ 24×24 px oder mit 24-px-Freiraum (axe-Regel `target-size` aktiviert).
41. **Fokus nicht verdeckt.** Tab durch Assistent und Detailseite bei 1280×720 und 200 % Zoom: fokussiertes Element nie vollständig unter Sticky-Leiste oder Toast.
42. **Schrittwechsel-Fokus.** Nach „Weiter“ liegt der Fokus auf der Schrittüberschrift; `document.title` nennt Schritt und Assistent.
43. **Deutsche Textlänge.** Screenshots mit Pseudo-Lokalisierung +40 % und einem 35-Zeichen-Kompositum bei 320 px: kein abgeschnittener Knopftext, kein horizontales Scrollen.
44. **Visuelle Regression.** Jeder neue Zustand (leer, laden, Fehler, 200 Einträge) × hell/dunkel × 320/1280 px hat einen Playwright-Screenshot.

## Quellen-Kurzliste
NN/g · GOV.UK Design System · Baymard · WCAG 2.2 / WAI-ARIA APG · Carbon · Atlassian · Material 3 · Fluent 2 · PatternFly · Airbyte · Fivetran · n8n · Power Automate · Zapier · Brad Frost · Playwright · Deque · KERN UX ([kern-ux.de](https://www.kern-ux.de/), EUPL-1.2, Basis KoliBri/ITZBund — fachlich nah an GOV.UK).
