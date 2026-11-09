# Rechte-Matrix

Wer welche Tätigkeit darf, auf einen Blick. „—“ heißt: keine Voraussetzung in dieser Spalte.

## Benutzer: ab Werk

| Tätigkeit | Systemrolle | Rolle am Objekt | Anlegerecht oder Schalter | Ab Werk |
|---|---|---|---|---|
| [Fragen stellen](../benutzer/fragen-stellen.md), [Prompts verwenden](../benutzer/prompts-verwenden.md), [Chats verwalten](../benutzer/chats.md) | — | Mitglied im Space, Leserecht an der Bibliothek | — | jedes Konto im eigenen Space |
| [Dateien anhängen und Spracheingabe](../benutzer/anhaengen-und-diktieren.md) | — | Mitglied im Space | Spracheingabe eingerichtet | Anhänge an, Diktat aus |
| [Antworten weitergeben](../benutzer/antworten-weitergeben.md), [Belege prüfen](../benutzer/belege-pruefen.md) | — | wer den Chat führt | — | ja |
| [Katalog nutzen](../benutzer/katalog.md) | — | Leserecht; zuordnen als *Kurator* | — | ja |
| [Wissensbibliothek anlegen](../benutzer/bibliothek-anlegen.md) | — | — | „Bibliotheken für Uploads anlegen“, „Konnektorbibliotheken anlegen“ | Upload und fünf Quellarten für alle |
| [Prompt-Bibliothek anlegen](../benutzer/prompt-bibliothek-anlegen.md) | — | Prompts schreiben ab *Bearbeiter* | „Prompt-Bibliotheken anlegen“ | jedes Konto |
| [Agent anlegen](../benutzer/agent-anlegen.md) | — | einrichten ab *Verwalter* | „Agenten anlegen“, Agenten eingeschaltet | niemand, Agenten aus |
| [Space anlegen](../benutzer/space-anlegen.md) | — | — | „Spaces anlegen“ | jedes Konto |
| [Konto verbinden](../benutzer/konto-verbinden.md) | — | — | Zugang für Personen freigegeben | kein Zugang |
| [Private Bibliothek anlegen](../benutzer/private-bibliothek.md) | — | verbundenes Konto | Anlegerecht für den Zugang | kein Zugang |
| [KI-Werkzeug einrichten](../benutzer/ki-werkzeug-einrichten.md) | — | Leserecht, Bibliothek freigegeben | externe KI-Werkzeuge an | aus |
| [Eigene Daten herunterladen](../benutzer/meine-daten.md) | — | — | Selbstauskunft an | an |

## Benutzer: mit Rolle am Objekt

| Tätigkeit | Systemrolle | Rolle am Objekt | Anlegerecht oder Schalter | Ab Werk |
|---|---|---|---|---|
| [Bibliothek teilen und für KI-Werkzeuge freigeben](../benutzer/arbeitsmittel-teilen.md) | — | *Verwalter*; übergeben *Eigentümer* | — | wer angelegt hat |
| [Bibliothek aktuell halten](../benutzer/bibliothek-aktuell-halten.md) | — | indizieren, hochladen ab *Bearbeiter*; Zeitplan, Verbindung ab *Verwalter* | — | wer angelegt hat |
| [Metadaten pflegen](../benutzer/metadaten-pflegen.md) | — | Werte ab *Bearbeiter*; Felder ab *Verwalter* | — | wer angelegt hat |
| [Für KI-Werkzeuge freigeben](../benutzer/arbeitsmittel-teilen.md#für-externe-ki-werkzeuge-freigeben) | oder Systemverwaltung | *Verwalter* | externe KI-Werkzeuge an | aus |
| [Inhalte eines Space zuordnen](../benutzer/space-inhalte.md) | — | *Kurator*, *Administrator* oder Eigentümer, plus Leserecht | — | wer angelegt hat |
| [Mitglieder eines Space verwalten](../benutzer/space-mitglieder.md) | — | *Administrator* oder Eigentümer | — | wer angelegt hat |
| [Space einstellen](../benutzer/space-einstellungen.md) | archivieren, löschen auch Systemverwaltung | *Administrator*; archivieren, löschen Eigentümer | — | wer angelegt hat |
| [Interne Gruppe pflegen](../benutzer/gruppe-pflegen.md) | oder Systemverwaltung | *Verantwortliche* | „Interne Gruppen anlegen“ | nur Systemverwaltung |
| [Zugriff nachvollziehen](../benutzer/zugriff-nachvollziehen.md) | — | eigener Weg: Zugriff; andere: *Administrator* oder Eigentümer im Space; Gruppenmitglieder ab *Verwalter* | — | ja |

## Administration

| Tätigkeit | Systemrolle | Ort | Ab Werk |
|---|---|---|---|
| [Konten](../administration/konten.md), [Anmeldung](../administration/anmeldung-einstellen.md), [Konto übergeben](../administration/konto-uebergeben.md) | Systemverwaltung | „Administration → Benutzer“ | Selbstregistrierung aus |
| [Systemrollen vergeben](../administration/systemrollen.md) | Systemverwaltung; Revision bestätigt die Revision | „Administration → Benutzer“, „Revision → Rollenanträge“ | Rolle *Nutzer* |
| [Gruppen verwalten](../administration/gruppen.md) | Systemverwaltung | „Administration → Gruppen“ | — |
| Identitätsanbieter und Verzeichnisabgleich ([Anmeldung einstellen](../administration/anmeldung-einstellen.md)) | Systemverwaltung | „Administration → Identitätsanbieter“, „Administration → Verzeichnisabgleich“ | keiner |
| [Anlegerechte vergeben](../administration/anlegerechte.md) | Systemverwaltung | „Administration → Anlegerechte“ | siehe Seite |
| [Ausscheiden und Nachfolge](../administration/ausscheiden.md) | Systemverwaltung; eigenes Eigentum jede Person | „Administration → Ohne Zuständigkeit“ | — |
| [Zugänge einrichten](../administration/zugaenge.md) | Systemverwaltung | „Administration → Anbindungen“ | kein Zugang |
| [Externe KI-Werkzeuge zulassen](../administration/externe-ki-werkzeuge.md) | Systemverwaltung | „Administration → Anbindungen“, Reiter „Externe KI-Werkzeuge“ | aus |
| [Agenten einschalten](../administration/agenten.md) | Systemverwaltung | „Administration → Agenten“ | aus, „Rechnen“ aus |
| [Modelle verwalten](../administration/modelle.md) | Systemverwaltung | „Administration → Modelle“ | Reranking, Spracheingabe aus |
| [Suche und Indexierung](../administration/suche-und-indexierung.md) | Systemverwaltung; „Sicht als“ nur mit Befugnis | „Administration → Suche & Indexierung“ | keine Befugnis |
| [Datenschutz einstellen](../administration/datenschutz.md) | Systemverwaltung | „Administration → Datenschutz“ | siehe Seite |
| [Branding](../administration/branding.md), [E-Mail](../administration/e-mail.md), [Einstellungen übertragen](../administration/einstellungen-uebertragen.md) | Systemverwaltung | „Administration“ | Versand aus |

## Revision

| Tätigkeit | Systemrolle | Ort | Ab Werk |
|---|---|---|---|
| [Stichtagsauskunft](../administration/stichtagsauskunft.md) | Revision | „Revision → Stichtagsauskunft“ | niemand hat *Revision* |
| [Verbindungsprotokoll](../administration/verbindungsprotokoll.md) | Revision | „Revision → Verbindungsprotokoll“ | niemand hat *Revision* |
| [Nachweisprotokoll](../administration/nachweisprotokoll-lesen.md) | Revision; Klärung mit zweiter Person | Programmierschnittstelle | niemand hat *Revision* |

**Zwei Regeln gelten überall:** Die Systemverwaltung verwaltet jedes Arbeitsmittel, liest seinen
Inhalt aber nur mit eigenem Leserecht. Die Revision liest Protokolle und sonst nichts.
