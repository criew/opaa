# Kanäle & Oberflächen

> **Status: Entwurf.** Themenbereich I der Produktvision. Phasenlage: Web-Oberfläche und REST-API
> gehören in **Phase 1**, die Anbindung an self-hosted Team-Chats in **Phase 3**, Erweiterungen für
> Office und Browser in **Phase 4**. Das Zielbild der Chat-Kanäle ist entschieden
> ([#352](https://github.com/criew/opaa/issues/352)): Es bleiben ausschließlich selbst betriebene
> Team-Chats.

## Motivation

Ein Assistent, den man erst aufsuchen muss, wird in der Verwaltung wenig genutzt. Sachbearbeitung
arbeitet in einem Vorgang, nicht in einem Werkzeugkasten — je weiter der Weg zur Antwort, desto eher
bleibt die Frage ungestellt oder wandert in ein Werkzeug außerhalb des Hauses. Der zweite Grund ist
Schatten-KI: Wo kein zugelassener Kanal erreichbar ist, entsteht ein nicht zugelassener.

Zugleich ist jeder zusätzliche Kanal dauerhafter Aufwand — eigene Anmeldung, eigene Darstellung von
Quellen, eigene Fehlerbilder, eigene Pflege bei jeder Änderung der Plattform. OPAA baut deshalb nicht
möglichst viele Kanäle, sondern ein tragfähiges Fundament und darauf eine begründete Auswahl.

Dieses Dokument beschreibt, welche Oberflächen es gibt, welche Eigenschaften sie teilen und in welcher
Reihenfolge sie entstehen.

---

## Überblick

1. **Die Web-Oberfläche ist die vollständige Oberfläche.** Alles, was OPAA kann, ist dort erreichbar —
   Fragen, Quellen, Assets, Verwaltung. Jeder weitere Kanal zeigt einen Ausschnitt davon.
2. **Die REST-API ist das Fundament aller weiteren Kanäle.** Auch die Web-Oberfläche benutzt sie. Ein
   Kanal, der etwas könnte, was die API nicht anbietet, ist ein Konstruktionsfehler.
3. **Kein Kanal hat eigene Rechte.** Jede Anfrage läuft unter der Identität einer angemeldeten Person
   und mit deren Leserechten; ein Kanal-Konto mit erweiterter Sicht gibt es nicht.
4. **Jede Antwort führt ihre Belege mit** — in jedem Kanal. Wo ein Kanal Quellenangaben nicht
   darstellen kann, ist er kein geeigneter Kanal.
5. **Selbst betriebene Team-Chats sind Ausbau, nicht Fundament.** Sie holen OPAA an den Ort, an dem
   Teams ohnehin sprechen, setzen aber die tragenden Fähigkeiten voraus. Fremd betriebene
   Verbraucherdienste sind kein Kanal — wer einen weiteren braucht, baut ihn gegen die REST-API.
6. **Erweiterungen für Office und Browser bleiben eine spätere Option** mit hohem Aufwand je
   Erweiterung und entsprechend hoher Begründungslast.

---

## Web-Oberfläche

Die Web-Oberfläche ist der Kanal, an dem sich das Produkt entscheidet. Sie ist die einzige Stelle, an
der alle Fähigkeiten vollständig sichtbar sind, und der Maßstab für alles Weitere.

### Was sie leistet

| Bereich | Zweck | Heute gebaut |
|---|---|---|
| **Fragen und Antworten** | Frage stellen, Antwort mit Fundstellen erhalten, Relevanz und Trefferzahl je Quelle sehen, erkennen, welche Quelle tatsächlich zitiert wurde | ja |
| **Gesprächsverlauf** | Rückfragen im laufenden Gespräch, die den bisherigen Verlauf berücksichtigen | ja — Gespräche liegen persistent in genau einem Arbeitsraum und überleben ein Neuladen der Seite (#525/#527) |
| **Suchfilter** | den Suchbereich einer Anfrage ausschließlich über die Chip-Leiste am Eingabefeld steuern (Spezial-Chip @Alles-Wissen, konkrete @-Bibliotheksreferenzen, oder eine geleerte Leiste), nicht mehr über eine Space-Auswahl oder einen separaten Schalter | ja — die Space-Auswahl ist entfernt; Chip-Leiste, @-Autocomplete und sticky Chips sind gebaut und werden am persistierten Gespräch gespeichert (siehe unten). Die Space↔Bibliothek-Assoziation (#203) ist weiterhin Zielbild |
| **Arbeitsräume** | Chats und Artefakte eines Themas, Entwurf und Ablage getrennt (siehe [spaces-and-assets.md](./spaces-and-assets.md)) | teilweise — Übersicht, Mitglieder, Rollen, Eigentumsübergabe und die Gesprächsliste je Arbeitsraum (anlegen, umbenennen, löschen) sind vorhanden |
| **Wissen** | Dokumente einer Wissensbibliothek einsehen, hochladen, Indizierungsstand erkennen | ja — Bibliotheksdetailseite mit Bestandsdarstellung, Upload/Löschen für Upload-Bibliotheken und Indizierungsstand für Konnektor-Bibliotheken |
| **Assets** | Agenten, Prompt-Bibliotheken und Wissensbibliotheken anlegen, beschreiben, freigeben, finden | nein — Zielbild |
| **Rückmeldung** | Antworten und Treffer bewerten; die Rückmeldung fließt in die Suchqualität ein (siehe [search-quality-evaluation.md](./search-quality-evaluation.md)) | teilweise — Bedienelement vorhanden, ohne Wirkung (siehe unten) |
| **Systemverwaltung** | Gruppen und Verzeichnisabgleich, Rollen, Auslösen und Stand der Indizierung | teilweise — Gruppen, Verzeichnisabgleich, Rollen und Indizierung sind vorhanden; Modellvorgaben und Protokolleinsicht sind Zielbild (siehe [access-control.md](./access-control.md) und [llm-integration.md](./llm-integration.md)) |
| **Persönliche Einstellungen** | Darstellung, später eigene Zugänge zur Schnittstelle | teilweise — nur die Darstellung; eine Verwaltung eigener API-Zugänge gibt es nicht |

### Dokumentenübersicht, Gesprächsverwaltung und Suchfilter

Drei Bereiche, die der frühere Stand dieses Dokuments beschrieben hat und die hier mit dem
tatsächlichen Stand abgeglichen sind. Die Dokumentenübersicht ist inzwischen gebaut — die
Bibliotheksdetailseite (`LibraryDetailPage.tsx`) zeigt den Bestand einer Wissensbibliothek mit
Indizierungsstand je Dokument. Seit #738 bietet jede Dokumentzeile die Aktion „Original öffnen": das
Frontend lädt die Datei über `GET /api/v1/documents/{documentId}/content` (#736) als Blob und öffnet
sie per Objekt-URL in einem neuen Tab (PDF/Bilder als Browser-Vorschau). Seit #780 gilt das auch für
Markdown und Klartext: Statt eines stillen Downloads (der Browser zeigt einen `text/markdown`- oder
`text/plain`-Blob sonst nur als Rohtext an oder lädt ihn herunter) rendert `DocumentTextPreviewDialog`
den Inhalt clientseitig in einem eigenen Dialog — Markdown über dieselbe, bereits gehärtete
`MarkdownRenderer`-Komponente wie die Chat-Antworten (kein `rehype-raw`, react-markdowns
Standard-`urlTransform` entfernt `javascript:`-URLs, siehe #743 Sperre für SVG). Ein Original über 2
MiB fällt zurück auf den Download, mit demselben sichtbaren Hinweis wie unten beschrieben. Jedes
andere Format (insbesondere DOCX — eine serverseitige Konvertierung ist bewusst außerhalb dieses
Zuschnitts) bleibt beim Download unter dem ursprünglichen Dateinamen, jetzt aber mit einer sichtbaren
Snackbar „‹Dateiname› wird heruntergeladen" statt eines Klicks ohne erkennbare Wirkung. Die gemeinsame
Logik dafür liegt in `frontend/src/utils/documentContent.ts` (Entscheidung Vorschau/Download/
Text-Vorschau) und `frontend/src/hooks/useDocumentPreview.ts` (Dialog-/Snackbar-/Fehlerzustand); sie
wird vom Zitat-Deeplink (#739) und den Fundstellen unter einer Chat-Antwort mitverwendet. Seit #747
gilt das für **jeden** Quellentyp: der Endpunkt streamt für HTTP_DIRECTORY/RSS_FEED das Original
serverseitig von der beim Indizieren gespeicherten Quell-URL durch, statt den Client dorthin
weiterzuleiten — auf der Demo-Instanz sind die Quellhosts (`http://demo-corpus/...`) nur im
Docker-Netz erreichbar, ein direkter Browserlink lief zuvor ins Leere. `sourceEntryUrl`/`sourceUrl`
(HTTP_DIRECTORY/RSS_FEED) bleiben als sekundäre Information neben der Aktion sichtbar — auf der
Dokumentenübersicht als eigene Zeile („Herkunft:"/„Quelle:"), in den Fundstellen und im Belegfenster
als kleiner „Quelle"-Link mit der rohen URL als `title`-Tooltip.

`LibraryDocumentResponse.sourceUrl` ist bewusst für jede VIEWER-Berechtigung sichtbar — anders als
`LibraryResponse.sourceUrl`, das #507 unterhalb von MANAGER maskiert. Maskiert bleibt dort die
**Quellkonfiguration** der Bibliothek (Crawl-Ziel, Proxy, Zugangsdaten); das neue Feld nennt dagegen
nur die eigene Herkunfts-URL **eines einzelnen Dokuments** — der Dokument-Deeplink ist der Kern von
#738/#740, analog zu `sourceEntryUrl`, das seit #493 ebenfalls ungefiltert an Leseberechtigte geht.
Maintainer-Entscheidung auf PR #743 (Epic #740).

Seit #739 gilt derselbe Deeplink auch für die Fundstellen unter einer Antwort und für das
Belegfenster („Belege dieser Antwort"): `SourceReference` (OpenAPI) trägt jetzt `documentId` und
`sourceType`, dazu ein eigenes `sourceUrl` mit derselben Bedeutung wie auf
`LibraryDocumentResponse`. Ein Klick auf „Im Dokument öffnen" lädt seit #747 für **jeden**
Quellentyp das Original über `GET /api/v1/documents/{documentId}/content` (dasselbe Hilfsmodul
`documentContent.ts`) — vor #747 öffnete diese Aktion für HTTP_DIRECTORY/RSS_FEED stattdessen
`sourceEntryUrl`/`sourceUrl` direkt in einem neuen Tab, was bei einer nur intern erreichbaren Quelle
im Browser ins Leere lief. Seit #780 verhält sich „Im Dokument öffnen" an den Fundstellen und im
Belegfenster wie auf der Dokumentenübersicht: Markdown/Klartext rendern in `DocumentTextPreviewDialog`
statt still herunterzuladen, jedes andere Format zeigt beim Download eine Snackbar. Beide
Oberflächen teilen sich dafür in `MessageBubble.tsx` eine einzige `useDocumentPreview()`-Instanz —
die Fundstellen (`SourceFootnotes.tsx`) und das Belegfenster (`SourceEvidenceDrawer.tsx`) bieten
dieselbe Aktion für dieselbe Antwort und erhalten `openDocument`/Fehler-/Vorschau-/Download-Zustand
als Props, statt je einen eigenen Aufruf zu verwalten; Dialog und Snackbar hängen dadurch auch nicht
am Lebenszyklus des Belegfensters, das seine Kindelemente beim Schließen abbaut. Backendseitig
schlüsselt die Zusammenführung mehrfach zitierter
Fundstellen (`QueryService#mergeSourceReferences`) auf `documentId` statt auf den Dateinamen: zwei
unterschiedliche Dokumente mit identischem Dateinamen (etwa zwei RSS-Anlagen) erscheinen dadurch als
zwei getrennte Fundstellen mit je eigenem Deeplink, statt zu einer zusammenzufallen.

**Gesprächsverwaltung.** Ein Gespräch ist ein persistentes Objekt, das **von Anfang an in genau einem
Arbeitsraum** liegt — dort erstellt (`POST /api/v1/spaces/{spaceId}/chats`), dort unter
`/spaces/:spaceId/chats/:chatId` gelistet und geöffnet, nicht verschiebbar (siehe
[Chats](./spaces-and-assets.md#chats)). Die Arbeitsraum-Seite und die Seitenleiste zeigen die eigenen
Gespräche eines Arbeitsraums mit Titel und letztem Nutzungszeitpunkt, sortiert nach letzter Nutzung;
von dort lassen sie sich umbenennen und löschen (mit Bestätigung). Die frühere globale Route `/chat`
führt auf den Standard-Arbeitsraum und dessen zuletzt genutztes Gespräch, oder auf ein neues Gespräch,
falls dort noch keines existiert — nie auf eine Sackgasse. Ein Neuladen der Seite stellt Verlauf und
Liste wieder her. Es entsteht als Entwurf, sichtbar nur für den Autor, und wird für die Mitglieder des
Arbeitsraums erst sichtbar, sobald der Autor es dort teilt (`SHARED`/`WITHDRAWN`, Zielbild — siehe
[Chats](./spaces-and-assets.md#chats)). Ebenfalls Zielbild: das Löschen des eigenen Verlaufs im
Sinne einer Aufbewahrungsfrist und ein Export des Gesprächs samt Fundstellen, weil ein
Gesprächsergebnis in der Verwaltung regelmäßig in einen Vorgang übernommen wird.

Die Aufbewahrungsdauer abgelegter Gespräche ist eine Betriebs- und Mitbestimmungsfrage, keine
Voreinstellung des Produkts.

**Suchfilter.** Die frühere Eingrenzung auf ausgewählte Arbeitsräume ist entfernt — sie hatte ohnehin
keine Wirkung im Backend, weil der zugrundeliegende Parameter dort ignoriert wurde. Ein Gespräch liegt
bereits in genau einem Arbeitsraum, eine zusätzliche Space-Auswahl je Anfrage wäre redundant gewesen
und hätte eine Wirkung suggeriert, die es nicht gab. An ihre Stelle tritt eine gesprächsbezogene, keine
anfragebezogene Steuerung (siehe [Suchbereich je Chatart](./spaces-and-assets.md#suchbereich-je-chatart)):
die **Chip-Leiste** am Eingabefeld, die einzige Suchbereichssteuerung — kein separater Schalter daneben.
„Durchsucht wird, was in der Leiste steht" — die Leiste kennt drei Zustände:

- **@Alles-Wissen** (Standard, vorbelegter Spezial-Chip) — durchsucht heute alle Wissensbibliotheken,
  die der Nutzer lesen darf (Übergangsregel bis #203; im Zielbild die dem Arbeitsraum assoziierten),
- **konkrete Bibliotheks-Chips** — durchsucht ausschließlich die referenzierten. Tippen von `@` im
  Eingabefeld schlägt alle Bibliotheken vor, die der Nutzer lesen darf, unabhängig vom Arbeitsraum,
  dazu als erster Eintrag (bei leerer Eingabe) @Alles-Wissen selbst, per Tastatur oder Maus auswählbar. Der erste
  konkrete Chip ersetzt @Alles-Wissen; @Alles-Wissen erneut hinzuzufügen ersetzt umgekehrt die
  konkreten Chips,
- **leere Leiste** — kein Retrieval, das Modell antwortet ohne Wissensbasis, sichtbar gekennzeichnet in
  Eingabefeld und Antwort, mit einem Ein-Klick-Weg zurück zu @Alles-Wissen.

Jeder Chip ist entfernbar, auch @Alles-Wissen. Der Zustand bleibt als entfernbare Chips **sticky am
Gespräch** erhalten, nicht nur für eine einzelne Anfrage — er wird mit dem Gespräch persistiert
(`PATCH /api/v1/chats/{chatId}`, #527) und überlebt damit ein Neuladen der Seite.

Im Zielbild kommen dazu die Eingrenzung auf den Dokumenttyp und auf den Stand der Indizierung. Ein
Filter, der die Rechteprüfung ersetzen würde, ist ausgeschlossen: Filter verengen die Sicht, sie
erweitern sie nie.

### Belegbarkeit ist Oberfläche, nicht Beiwerk

Das Leitprinzip der Belegbarkeit steht und fällt mit der Darstellung. Eine Fundstelle, die man nicht
öffnen kann, ist kein Beleg. Die Oberfläche zeigt deshalb zu jeder Antwort, worauf sie beruht, macht
den Sprung in das Quelldokument möglich und benennt sichtbar, wenn eine Aussage nicht belegt werden
konnte. Im Zitierzwang verweigert das System die Antwort, statt sie plausibel zu formulieren — auch
das ist ein Zustand, der dargestellt werden muss und nicht als Fehler aussehen darf.

### Barrierefreiheit

Die Web-Oberfläche ist der Ort, an dem sich die Verpflichtung zur Barrierefreiheit einlöst. Die
Anforderungen und ihre Rechtsgrundlage stehen in [public-sector.md](./public-sector.md); ihre
Umsetzung ist eine Eigenschaft dieses Kanals, keine nachgelagerte Prüfung.

---

## Rückmeldung zur Antwortqualität

Die Rückmeldung schließt die Rückkopplungsschleife aus Themenbereich A: Ohne sie ist die einzige
verfügbare Aussage über die Antwortqualität die Vermutung derer, die das System gebaut haben.

**Stand:** Das Bedienelement — Zustimmung oder Ablehnung zu einer Antwort — ist in der Oberfläche
vorhanden und beschriftet, hat aber **keine Wirkung**: Es gibt keinen Endpunkt, der eine Bewertung
entgegennimmt, und keine Speicherung. Alles Weitere in diesem Abschnitt ist Zielbild.

### Was bewertet wird

- **Die Antwort als Ganzes** — zutreffend oder nicht. Das ist die niedrigschwelligste Form und
  deshalb die einzige, die verlässlich genutzt wird.
- **Die einzelne Fundstelle** — trug sie zur Antwort bei oder war sie ein Fehltreffer? Diese Angabe
  ist die fachlich wertvollere, weil sie auf den Abruf zeigt und nicht auf die Formulierung.
- **Ein freier Hinweis** in Textform, ausdrücklich freiwillig.

### Was mit der Bewertung geschieht

1. **Sie wird zur Frage gespeichert, nicht zur Person.** Festgehalten werden die Frage, die
   gelieferten Fundstellen und die Bewertung — nicht, wer bewertet hat.
2. **Sie fließt in die Suchqualitäts-Evaluierung ein.** Negativ bewertete Fragen sind die besten
   Kandidaten für den Golden-Datensatz, gegen den Änderungen am Abruf geprüft werden (siehe
   [search-quality-evaluation.md](./search-quality-evaluation.md)).
3. **Sie zeigt der Systemverwaltung Muster, keine Fälle.** Häufungen — eine Wissensbibliothek mit
   auffällig vielen Fehltreffern, ein Bestand, der veraltet ist — sind der eigentliche Ertrag.
4. **Sie verändert keine Rechte und kein Ranking im laufenden Betrieb.** Eine Rückmeldung, die
   unmittelbar auf die Trefferreihenfolge durchschlägt, wäre manipulierbar und nicht mehr
   nachvollziehbar. Der Weg führt über eine geprüfte Änderung, nicht über die Bewertung selbst.

### Die Grenze zur Mitbestimmung

Rückmeldungen dürfen **keinen personenbezogenen Auswertungspfad** eröffnen. Es gibt weder eine
Auswertung nach bewertender Person noch eine Rangfolge von Beschäftigten nach Zustimmung oder Menge
der Rückmeldungen; Auswertungen sind aggregiert und nicht auf Einzelne rückführbar. Eine Bewertung
ist eine Aussage über das System, nicht über die Person, die es bedient — wäre sie es, entstünde ein
zur Leistungs- und Verhaltenskontrolle geeignetes Instrument und die Rückmeldung würde schlicht
unterbleiben.

---

## REST-API

Die REST-API ist zugleich Zugang für eigene Anwendungen **und** die Grundlage aller weiteren Kanäle.
Beide Rollen fallen zusammen, und das ist beabsichtigt: Ein Kanal, der eine Sonderschnittstelle
bräuchte, würde eine zweite Rechteprüfung und eine zweite Fehlerbehandlung erzeugen.

### Eigenschaften

- **Spezifikation zuerst.** Die Schnittstelle ist in OpenAPI beschrieben; die verwendeten Datentypen
  werden daraus erzeugt (siehe [ADR-0006](../decisions/0006-openapi-dto-generation.md)). Änderungen
  beginnen an der Spezifikation, nicht am Code.
- **Anmeldung wie überall.** Zugang über den Verzeichnisdienst des Hauses; maschinelle Zugänge sind
  eigene, nachvollziehbare Identitäten mit eigenen Rechten und nicht das Konto einer Person
  (Einzelheiten und Stand unter [Authentifizierung und Zugang](#authentifizierung-und-zugang)).
- **Rechte der aufrufenden Person.** Ein Aufruf sieht genau die Wissensbibliotheken, die der
  aufrufenden Identität freigegeben sind. Die Prüfung sitzt in der Suche, nicht davor.
- **Belege im Antwortformat.** Fundstellen, Konfidenz und der durchsuchte Bereich sind Teil der
  Antwort, nicht eine gesonderte Abfrage.
- **Protokollierung.** Jeder Aufruf ist zurechenbar und erscheint im revisionssicheren Protokoll.
- **Grenzen.** Anfragekontingente schützen den Betrieb und begrenzen den Modellverbrauch.

### Was die Schnittstelle anbietet

Der folgende Katalog beschreibt den **Zweck** der Endpunkte — wozu man sie aufruft. Die formale
Beschreibung, also Pfade, Felder und Fehlerbilder, steht in der OpenAPI-Spezifikation des Backends
und wird hier nicht wiederholt; sie beantwortet das *Wie*, nicht das *Wozu*. Gruppiert ist nach
Zweck, nicht nach Pfad.

**Abfragen**

| Zweck | Endpunkt | Heute gebaut |
|---|---|---|
| Frage stellen und belegte Antwort erhalten — mit Fundstellen, Relevanz je Quelle, Kennzeichnung der tatsächlich zitierten Quellen und einer Gesprächskennung für Rückfragen; der Suchbereich wird über die Chip-Leiste des Gesprächs gesteuert, nicht per Space-Auswahl je Anfrage | `POST /api/v1/query` | ja — Frage, Antwort, Fundstellen und die Chip-Leiste (@Alles-Wissen, @-Referenzen, leere Leiste) sind gebaut; die Space↔Bibliothek-Assoziation (#203) bleibt Zielbild |
| Antwort auf eine Antwort geben (Bewertung, Fehltreffer melden) | — | nein — Zielbild, siehe [Rückmeldung](#rückmeldung-zur-antwortqualität) |

**Wissensbestände verwalten**

| Zweck | Endpunkt | Heute gebaut |
|---|---|---|
| Wissensbibliotheken anlegen, umbenennen, beschreiben, auflisten, löschen | `/api/v1/libraries` und `/api/v1/libraries/{id}` | ja |
| Bestand einer Wissensbibliothek einsehen — welche Dokumente sind drin, in welchem Indizierungsstand | `GET /api/v1/libraries/{id}/documents` | ja |
| Lesezugriff auf eine Wissensbibliothek erteilen, einsehen und entziehen — Rechte hängen an der Bibliothek, nicht am einzelnen Dokument | `/api/v1/libraries/{id}/grants` | ja |
| Indizierung einer Bibliothek auslösen — aus ihrer eigenen, gespeicherten Quellkonfiguration; wer an der Bibliothek mindestens `EDITOR` ist, darf anstoßen | `POST /api/v1/libraries/{id}/indexing` | ja |
| Stand des letzten Indizierungslaufs einer Bibliothek abfragen — verarbeitet, übersprungen, fehlgeschlagen, mit Fehlertext; bei einem RSS-Lauf zusätzlich die Gesamtzahl indizierter Dokumente einschließlich Anhängen (`documentsIndexedTotal`), getrennt von der Zahl verarbeiteter Feed-Einträge (`documentCount`) (#518) | `GET /api/v1/libraries/{id}/indexing/status` | ja |
| Dokument hochladen und wieder entfernen | `POST`/`DELETE /api/v1/libraries/{libraryId}/documents` | ja (#420, #422) |

**Arbeitsräume und Gruppen**

| Zweck | Endpunkt | Heute gebaut |
|---|---|---|
| Arbeitsräume anlegen, ändern, auflisten, löschen | `/api/v1/spaces` und `/api/v1/spaces/{id}` | ja |
| Mitglieder eines Arbeitsraums führen, Rolle ändern, Eigentum übergeben — damit ein Arbeitsraum beim Ausscheiden einer Person nicht verwaist | `/api/v1/spaces/{id}/members`, `/api/v1/spaces/{id}/transfer-ownership` | ja |
| Gruppen als Rechtesubjekt führen und ihre Mitglieder verwalten | `/api/v1/admin/groups` | ja |

**Systemverwaltung**

| Zweck | Endpunkt | Heute gebaut |
|---|---|---|
| Benutzende auflisten und die Systemrolle einer Person setzen | `/api/v1/admin/users` | ja |
| Abgleich mit dem Verzeichnisdienst des Hauses — zuerst als Probelauf ohne Wirkung, dann scharf, dazu der Stand des letzten Laufs | `/api/v1/admin/directory-sync` | ja |
| Anmeldeverfahren der Installation erfragen, bevor eine Anmeldung beginnt | `GET /api/v1/auth/config` | ja |
| Branding des Hauses erfragen — Produktname, Claim, Logo, Akzentfarbe und Farbschema-Vorgabe; ohne Anmeldung lesbar, weil die Anmeldeseite es braucht; ohne Konfiguration gilt der OPAA-Standard, jedes Feld für sich | `GET /api/v1/branding`, `GET /api/v1/branding/logo` | ja (#582) |
| Branding des Hauses setzen — nur Systemverwaltung; das Logo wird auf Format, Größe und tatsächlichen Inhalt geprüft, SVG bewusst abgelehnt | `PUT /api/v1/system/branding`, `PUT`/`DELETE /api/v1/system/branding/logo` | ja (#582) |
| Eigene Identität, Rollen und Zugehörigkeiten erfragen | `GET /api/v1/auth/me` | ja |
| Betriebsbereitschaft prüfen — für Lastverteiler und Betriebsüberwachung | `GET /api/health` | ja |

**Was der frühere Stand dieses Dokuments nannte und heute nicht existiert:** ein Endpunkt zum
Hochladen von Dokumenten, ein eigener Such-Endpunkt neben der Abfrage, das Abrufen eines einzelnen
Dokuments, das Auflisten der eigenen Uploads, ein Endpunkt für Rückmeldungen sowie
Sammelverarbeitung mehrerer Fragen in einem Aufruf. Ersatzlos entfallen sind die Endpunkte zum
**Teilen und Entteilen einzelner Dokumente über Workspace-Grenzen**: Zugriff wird an der
Wissensbibliothek erteilt, nicht am einzelnen Dokument — das Modell dahinter ist abgelöst (siehe
[spaces-and-assets.md](./spaces-and-assets.md)).

### Authentifizierung und Zugang

**Gebaut:**

- **Anmeldung über den Verzeichnisdienst des Hauses.** Die Schnittstelle nimmt ein Zugangsmerkmal
  entgegen, das der Identitätsanbieter ausgestellt hat, und prüft es gegen dessen Signaturschlüssel.
  Eine eigene Benutzer- und Passwortverwaltung gibt es nicht.
- **Ein Entwicklungsmodus ohne echte Prüfung**, der ausschließlich Entwicklungs- und Testumgebungen
  vorbehalten ist. Er muss ausdrücklich gewählt werden, und das System bricht den Start ab, wenn gar
  kein Verfahren gesetzt ist — eine Installation ist nie versehentlich offen (siehe
  [ADR-0005](../decisions/0005-authentication-strategy.md)).
- **Rechte der aufrufenden Person.** Der Aufruf sieht, was dieser Identität freigegeben ist; einzelne
  Endpunkte sind zusätzlich der Systemverwaltung vorbehalten.
- **Anfragekontingente.** Sie greifen je aufrufender Netzadresse und zusätzlich für die Installation
  insgesamt, in einem gleitenden Zeitfenster. Die Abfrage und das Auslösen der Indizierung haben getrennte, für
  sich gesetzte Kontingente — der Indizierungspfad ist deutlich enger begrenzt, weil ein einzelner
  Aufruf dort viel Arbeit auslöst; seit [ADR-0018](../decisions/0018-quellkonfiguration-in-der-bibliothek.md)
  gilt das Kontingent je Bibliothek, und es läuft höchstens ein Lauf gleichzeitig je Bibliothek. Alle
  Werte sind über Umgebungsvariablen einstellbar; die ausgelieferten Voreinstellungen und ihre Bedeutung
  stehen in [deployment.md](../deployment.md). Ein überschrittenes Kontingent führt zu einer klaren
  Ablehnung, nicht zu einer langsamen Antwort.

**Zielbild:**

- **Maschinelle Zugänge als eigene Identität.** Ein Fachverfahren, das OPAA abfragt, bekommt einen
  eigenen, benannten Zugang mit eigenen Rechten, eigener Gültigkeitsdauer und eigener
  Widerrufbarkeit — nicht das Konto einer Person und nicht ein Dauerschlüssel ohne Ablauf. Ein
  Zugang, der beim Ausscheiden einer Person weiterläuft, ist ein Prüfungsbefund.
- **Selbstverwaltung eigener Zugänge** in den persönlichen Einstellungen: anlegen, benennen,
  Gültigkeit sehen, widerrufen. Das Merkmal ist genau einmal sichtbar, danach nur noch sein Name.
- **Kontingente je Zugang statt nur je Netzadresse**, damit ein einzelnes Fachverfahren die
  Installation nicht für die Beschäftigten ausbremst. Hinter einem gemeinsamen Ausgangspunkt im
  Behördennetz teilen sich heute alle dieselbe Adresse — das Kontingent trifft dann die Falschen.

---

## Anbindung an Team-Chats

Der Ausbau bringt OPAA in den Chat, in dem ein Team ohnehin arbeitet: Frage im Kanal stellen,
Rückfragen im selben Strang, Antwort mit Fundstellen für alle sichtbar. Der Gewinn ist nicht
Bequemlichkeit, sondern Sichtbarkeit — eine beantwortete Frage steht dort, wo die nächste Person sie
findet.

### Was ein Chat-Kanal leisten muss

Nicht jede Plattform ist als Kanal geeignet. Verbindliche Bedingungen:

1. **Zuordenbare Identität.** Die Person hinter einer Nachricht muss eindeutig auf ein Konto im
   Verzeichnisdienst abbildbar sein. Ohne diese Zuordnung gibt es keine rechtebewusste Suche, sondern
   nur eine Vermutung.
2. **Darstellbare Belege.** Fundstellen mit Sprungziel müssen im Nachrichtenformat unterzubringen sein.
3. **Betrieb im Verantwortungsbereich des Hauses.** Der Weg einer Frage darf die Grenze nicht
   überschreiten, die für die zugrunde liegenden Daten gilt.
4. **Protokollierbarkeit.** Anfrage und Antwort müssen zurechenbar im Protokoll landen.

### Die Kanäle im Zielbild

Im Zielbild bleiben **ausschließlich selbst betriebene Team-Chats**, weil nur sie alle vier Bedingungen
erfüllen können. Dazu gehört ausdrücklich der **Chat-Baustein des souveränen Arbeitsplatzes**, der auf
dem offenen **Matrix**-Protokoll aufsetzt: Wo ein Haus diesen Arbeitsplatz einführt, ist der Chat
bereits vorhanden, im eigenen Betrieb und an den Verzeichnisdienst angebunden. Ein Kanal dorthin ist
damit eine Anbindung an vorhandene Infrastruktur und nicht die Einführung eines weiteren Systems.
Daneben stehen mit **Mattermost** und **Rocket.Chat** zwei verbreitete, selbst betriebene
Team-Chat-Plattformen, die dieselben Bedingungen erfüllen und in vielen Häusern bereits laufen.

| Kanal | Heute gebaut | Einordnung |
|---|---|---|
| Web-Oberfläche | ja | Fundament, Phase 1 |
| REST-API | ja | Fundament, Phase 1, Grundlage aller weiteren Kanäle |
| Chat-Baustein des souveränen Arbeitsplatzes (Matrix) | nein | Ausbau, Phase 3 |
| Mattermost | nein | Ausbau, Phase 3 |
| Rocket.Chat | nein | Ausbau, Phase 3 |

**Gebaut ist heute kein einziger Chat-Kanal.** Die Web-Oberfläche und die REST-API sind die einzigen
realen Zugänge. Frühere Aufzählungen in diesem Repository beschrieben eine Absicht, keinen Zustand.

### Warum fremd betriebene Verbraucherdienste entfallen

Kanäle über fremd betriebene Verbraucherdienste — genannt waren Slack, Telegram, Signal und WhatsApp —
entfallen ersatzlos. Dafür gibt es zwei Sachgründe.

**Ein Chat-Kanal ist kein Ausgabeweg, sondern ein Zugang.** Das ist der tragende Grund, und er gilt
unabhängig davon, wie schutzbedürftig die Daten im Einzelfall sind. Ein Kanal muss die Identität der
fragenden Person verlässlich auf ein OPAA-Konto abbilden (Bedingung 1); gelingt das nicht, greift die
rechtebewusste Suche ins Leere und der Kanal wird zum Umgehungsweg um das gesamte Rechtemodell. Bei
einem selbst betriebenen Team-Chat hängt die Identität an derselben zentralen Anmeldung wie OPAA — die
Zuordnung ist eine Prüfung. Bei einem Verbraucherdienst hängt sie an einer Telefonnummer oder einem
privaten Konto. Das ist kein Dienstkonto, und eine nachträgliche Verknüpfung ist eine
Vertrauensannahme, keine Prüfung.

**Jede Nachricht an einen fremd betriebenen Dienst ist eine Übermittlung** (Bedingung 3). Das
kollidiert mit der Zusage, dass Daten das Haus nicht verlassen, und für einen erheblichen Teil der
Verwaltungsdaten mit dem Recht. Bei einem Frage-Antwort-System wiegt das doppelt: Nicht nur die Antwort
verlässt das Haus, sondern auch die **Frage** — und die verrät oft mehr über einen Vorgang als die
Antwort.

### Die REST-API bleibt der offene Weg

Die Streichung ist keine Abschottung. Die REST-API ist das Fundament aller Kanäle, und wer einen
weiteren braucht — eine andere Chat-Plattform, ein Fachverfahren, ein hauseigenes Werkzeug —, baut ihn
dagegen. Das ist ausdrücklich vorgesehen. Zugesagt, gepflegt und dokumentiert sind die oben genannten
Kanäle; darüber hinaus steht die Schnittstelle offen, und die vier Bedingungen sind der Maßstab, an dem
ein selbst gebauter Kanal zu messen ist.

---

## Erweiterungen für Office und Browser

Erweiterungen, die OPAA in Textverarbeitung, Tabellenkalkulation, Postfach oder Browser holen, sind
eine **spätere Option** (Phase 4) und ausdrücklich keine Zusage.

Der Bedarf ist plausibel: Ein Vermerk entsteht im Textprogramm, nicht in einem Chatfenster. Dagegen
steht der Aufwand — **je Erweiterung** eine eigene Erweiterungsschnittstelle, ein eigener
Freigabeprozess der jeweiligen Plattform, eine eigene Verteilung auf die Arbeitsplätze und eine eigene
Pflege bei jedem Plattformwechsel. Vier Erweiterungen sind vier Produkte, nicht ein Produkt mit vier
Ausgaben.

Daraus folgt die Reihenfolge: Erst wenn ein konkretes Einführungsvorhaben den Bedarf trägt und die
Zielumgebung feststeht, wird über die erste Erweiterung entschieden. Bis dahin bleibt der Zugang über
die Web-Oberfläche und die REST-API.

---

## Kanalübergreifende Eigenschaften

Was in einem Kanal gilt, gilt in allen. Andernfalls wäre der schwächste Kanal die tatsächliche
Sicherheitsgrenze des Systems.

| Eigenschaft | Regel |
|---|---|
| **Identität** | Anmeldung über den Verzeichnisdienst des Hauses; ein Kanal führt keine eigene Nutzerverwaltung |
| **Rechte** | Gefiltert wird über die lesbaren Wissensbibliotheken, bereits in der Suche; ein Agent liest immer mit den Rechten der aufrufenden Person |
| **Belege** | Fundstellen, Konfidenz und durchsuchter Bereich gehören zur Antwort |
| **Vorgaben** | Modell- und Werkzeugvorgaben der Systemverwaltung wirken in jedem Kanal; kein Kanal kann sie erweitern |
| **Protokoll** | Jede Anfrage und jede schreibende Aktion ist zurechenbar protokolliert |
| **Sichtbarkeit** | Was als Entwurf entsteht, bleibt beim Autor, bis er es ablegt — auch bei Nutzung über einen Chat-Kanal |

---

## Integrationspunkte

- **[spaces-and-assets.md](./spaces-and-assets.md)** — Arbeitsräume, Assets und die Trennung von
  Entwurf und Ablage, die jeder Kanal abbilden muss
- **[access-control.md](./access-control.md)** — Identität, Rollen und rechtebewusste Suche, an die
  jeder Kanal gebunden ist
- **[data-indexing-rag.md](./data-indexing-rag.md)** — Herkunft der Antworten und ihrer Fundstellen
- **[llm-integration.md](./llm-integration.md)** — Modellvorgaben, die in jedem Kanal gelten
- **[public-sector.md](./public-sector.md)** — Barrierefreiheit, Leichte Sprache und Amtssprache als
  Anforderungen an die Oberfläche
- **[deployment-infrastructure.md](./deployment-infrastructure.md)** — welche Kanäle in einer
  Installation ohne Netzanbindung überhaupt erreichbar sind

---

## Offene Fragen / Zukünftige Erweiterungen

- Wie weit reicht ein maschineller Zugang: nur Abfragen, oder auch das Verwalten von Beständen? Ein
  Zugang, der indizieren darf, ist betrieblich etwas anderes als einer, der nur fragt.
- Wie lange werden Rückmeldungen aufbewahrt, und wer darf die aggregierte Auswertung sehen — die
  Systemverwaltung des Hauses, die Verantwortlichen einer Wissensbibliothek oder beide?
- Wie werden Rückfragen in einem Chat-Strang einem Arbeitsraum zugeordnet — über eine feste Zuordnung
  des Kanals oder über eine Angabe je Strang?
- Wie stellt ein Kanal mit knappem Nachrichtenformat mehrere Fundstellen dar, ohne dass die Antwort
  unlesbar wird?
- Ein Assistent für Bürgerinnen und Bürger und ein öffentlich eingebettetes Widget wären ein Kanal mit
  anderem Nutzerkreis und anderen Haftungsfragen. Sie sind Ausblick der Phase 4, nicht Fundament;
  siehe [public-sector.md](./public-sector.md).
- Native Anwendungen für Mobilgeräte sind bewusst nicht vorgesehen (siehe [VISION.md](../VISION.md)).

---

## Erfolgs-Metriken

- Anteil der Antworten, die von der fragenden Person über die Fundstelle bis in das Quelldokument
  verfolgt werden — der Beleg wird benutzt, nicht nur angezeigt.
- Anteil der Beschäftigten mit Zugang, die OPAA regelmäßig nutzen, je Organisationseinheit und
  ausschließlich aggregiert.
- Anteil der über einen Kanal gestellten Fragen, die ohne Wechsel in die Web-Oberfläche beantwortet
  werden.
- Rückläufige Zahl der Fragen, die außerhalb zugelassener Werkzeuge gestellt werden — messbar nur
  indirekt, aber der eigentliche Zweck der Kanalvielfalt.
