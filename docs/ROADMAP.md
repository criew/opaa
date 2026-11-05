# Roadmap

Was im Zielbild der [Vision](./VISION.md) noch nicht gebaut ist, nach Phasen geordnet. Was gebaut ist,
steht im [Handbuch](./handbuch/README.md) und im [Gesamtstand](./fortschritt/gesamtstand.md).

Stand: 05.11.2026. Ein Punkt bekommt erst ein Epic mit Spezifikation, wenn er
angegangen wird. ⚑ markiert eine offene Entscheidung, die vor dem Bau fällt.

## Phase 1 — Souveräner Wissensassistent (Rest)

### A · Wissensschicht & Retrieval

- **Reranking produktiv machen (Latenz-/Hardwareprofil)** — Laufzeit auf Referenzhardware messen und je
  Hardwareklasse eine Aktivierungsempfehlung geben; heute ab Werk aus (rund 3 min je Frage auf CPU).
  _(Epic #3492 (Sizing); #1051 ungebaut geschlossen)_

### G · Sicherheit, Nachweis & Prüfbarkeit

- **Löschrecht nach DSGVO als Ablauf** — Löschung eines Kontos und seiner Daten über alle abgeleiteten
  Bestände; heute nur Sperrpfad und echte Löschung nie benutzter Konten.

### J · Betrieb & Deployment

- **Erprobung auf einem produktiven Cluster und erstes finales Release `v0.1.0`** — bisher k3d und kind.

- **Hardware- und Größenprofile (Sizing)** — Auslegung je Installationsgröße; trägt auch die
  Reranking-Empfehlung (A). _(Epic #3492)_

### Querschnitt

- **Quick-Tour für neue Nutzer** — nur als Epic. _(Epic #2312)_

## Phase 2 — Agenten, Werkzeuge und teilbare Assets

### A · Wissensschicht & Retrieval

- **Deep Research** — eigener Betriebsmodus: Zerlegung in Teilfragen, gegliederter Bericht mit
  Fundstellen und benannten Widersprüchen, Ergebnis als Artefakt, Verbrauch vorab angezeigt.
- **Texterkennung (OCR) und Bildverständnis** — Scans, Fotos, Diagramme, Handschrift erschließen;
  OCR-Text als unsicherer gekennzeichnet; Verfügbarkeit je Modell sichtbar. Voraussetzung ist der
  Docling-PoC samt Abnahmeschwellen. _(#1062, Epic #2640 (geparkt))_
- **Filterwerte aus der Frage ableiten** — Metadatenfilter automatisch setzen, sichtbar und mit einem
  Klick entfernbar. _(#1363)_
- **Geführter Assistent für Bibliotheksschemata** — Zweck erfragen, Stichprobe von 5–20 Dokumenten,
  Feldvorschlag zur Bestätigung (Arbeitspaket 9). _(#1362; Epic #1704 (Metadaten-Nacharbeiten,
  geparkt))_
- **Modellgestützte Metadatenextraktion abnehmen** — gebaut, aber aus und nicht abgenommen
  (Vokabularlücke, Schwelle 0,90).
- **Weitere Formate** — AsciiDoc, reStructuredText, RTF, Excel `.xls`, XML, JSON und strukturierte
  Datensätze, Postfachexporte (MBOX, PST); dazu Inhaltserkennung statt Endungsliste, einheitlich über
  alle Indizierungswege. _(⚑ (Inhaltserkennung))_
- **Erklärbares Chunking in der Dokumentansicht** — Chunkgrenzen dem Original überlagert, Chunk mit
  Regel, Überlappung und Nachbarn anwählbar; heute gibt es die Chunk-Ansicht in der Diagnose. Wer sie
  sehen darf, ist offen. _(⚑ (Kreis der Berechtigten))_
- **Bekannte Retrieval-Schwächen** — einchunkige Dokumente ohne Kontextpräfix ranken schlechter;
  Geschwister-Chunks über Teilfragen nicht rangfair; Komposita-Zerlegung (ispell oder Decompounder) erst
  nach Messung. _(⚑ (Komposita))_
- **Suchparameter je Bibliothek oder installationsweit; Feinabstimmung in der Verwaltung** — Chunkgröße,
  Überlappung, `top-k`, Schwellen heute als Expertenvariablen in der Konfiguration. ⚑
- **Gesprächsgedächtnis: Qualitätsgewinn belegen** — gebaut, Nutzen mit dem Eval-Modell nicht belegt.

### B · Wissensquellen & Konnektoren

- **Schreibende Integrationen mit Freigabe** — Vorgang eröffnen, Vermerk ablegen, Antwortentwurf
  hinterlegen; Freischaltung je Integration und Aktionsart, eigene Schreib-Zugangsdaten, menschliche
  Bestätigung im Einzelfall.
- **Löschverlangen nach Datenschutzrecht** — Löschung greift auf Chunks, Einbettungen, Extrakte und
  Vorschauen durch (siehe Phase 1, Löschrecht nach DSGVO).
- **Live-Abfrage verbundener Konten und Token-Austausch** — Quellen im Chat ohne Indexierung abfragen;
  Token Exchange (RFC 8693) als fünfte Anmeldeart nur für den Live-Zugriff, Machbarkeit belegt (Spike
  #2174). _(#1747 · ⚑)_
- **Schutz gegen Prompt-Injection für alle Fremdtext-Quellen** — offen, ob die Pflicht des Postfach-Schutzes
  auch für Web und RSS gilt. ⚑
- **Strukturfragen zu Bibliotheken** — mehrere Quellen desselben Typs je Bibliothek, Zwischenspeicherung
  von Quelldokumenten für Belegsprünge, Entfernen hinterlegter Zugangsdaten, Massenübernahme aus lokalem
  Laufwerk, Abschätzung großer Erstläufe. ⚑

### C · Spaces, Assets & Verteilung

- **Verschachtelte Gruppen aus dem Verzeichnis** — Auflösungsregel offen. ⚑
- **Voreinstellungen der Aufbewahrungsfristen** — je Space-Art und für private Inhalte. ⚑

### D · Agenten, Prompts & Werkzeuge

- **Fundament für Werkzeugaufrufe im Chat** — Modellfähigkeit „Werkzeugaufrufe“ messen, Werkzeugschleife
  mit der Suche als erstem Werkzeug, sichtbare Zwischenschritte, Werkzeug-Audit; heute Spike hinter
  Schalter. _(Epic #1747 (geparkt); #1752)_
- **Skills als Objektart** — teilbare Fähigkeit mit lesbarem Paketformat, später mit Werkzeugen (Schritt
  3). _(Epic #1727 (geparkt))_
- **Agent als teilbares Paket** — Verhalten, Wissensbindung, Werkzeugrechte und Modellwahl in einem
  Asset; liest immer mit den Rechten der aufrufenden Person.
- **Geführtes Agenten-Onboarding** — Beschreibung wie eine Stelle mit festen Abschnitten statt
  Freitext-Prompt. Wie streng die Struktur erzwungen wird, ist offen. _(⚑ (Strenge))_
- **Agenten-Prüfstand vor der Freigabe** — automatisierter Prüflauf in sechs Kategorien, Bericht als
  Freigabeunterlage; Mindest-Prüffallbasis offen. _(⚑ (Prüffallbasis))_
- **Prüfagenten für kritische Vorgänge** — unabhängige Gegenprüfung vor der Ausgabe, abgestuft nach
  Kritikalität; welches Modell sie nutzen, ist offen. _(⚑ (Modellwahl))_
- **Textwerkzeuge** — Zusammenfassung, Übersetzung, Leichte Sprache/Amtssprache, Export nach Text,
  Tabelle, Textdokument, Foliensatz (siehe K).
- **Isolierte Ausführungsumgebung je Chat** — Dateiverarbeitung, Tabellenauswertung, OCR, Transkription
  mit Sprechererkennung, Diagramme und Foliensätze; Umgang mit Abbrüchen offen.
- **Ausgangstor (Egress-Kontrollpunkt)** — Genehmigungsfähigkeit klären (TLS-Aufbruch, Protokollumfang,
  Verhältnis zu Proxy und Zielprüfung) vor dem Bau fremder Werkzeuge. ⚑
- **MCP-Client mit Zulassungsliste** — fremde MCP-Server als Werkzeuge, Liste der Systemverwaltung mit
  verantwortlicher Gruppe und Erreichbarkeitszustand; Profiltyp und Token-Speicher sind als Grundlage
  gebaut. _(Folge-Epic hinter #1747 (offen))_
- **Fremde Werkzeuge mit Bestätigung** — Klassifikation lesend/schreibend, wartende Bestätigung als
  Vorgang.
- **Auslösung ohne Person** — Zeitpläne und Ereignis-Auslöser für Agenten; mit wessen Rechten, ist
  offen. ⚑
- **Offene Grundsatzfragen der Werkzeugschleife** — Höhe der Grenzen und des Platzlimits, mehrschrittige
  Suche statt fester Zerlegung, Begründungspflicht je Aufruf, Unterrichtung der Beschäftigten, Agent
  ruft Agent, Belegvalidierung für Agenten. ⚑
- **Oberflächenfragen zu Skills und Agenten** — Wahl im Chat (`@` oder eigenes Zeichen), Wirkdauer,
  Ungelesen-Markierung langer Läufe, Agentenstart ohne Gespräch, Teilen von Läufen. _(#1297 (Postfach) ·
  ⚑)_

### E · Modelle & zentrale Steuerung

- **Vorgaben als Obergrenze** — Modell-Policies aus System ∩ Space ∩ Bibliothek ∩ Agent; Beschränkung
  hängt an den Daten; erst relevant, wenn mehr als ein Chat-Modell zur Wahl steht.
- **Vollständiger Modelleintrag** — Freigabestatus, Datenklassen/Schutzstufen, Zuständigkeit, Nachfolge,
  Fähigkeiten (z. B. Werkzeugaufrufe, Bildverständnis); Schema der Schutzstufen offen. _(⚑
  (Schutzstufen))_
- **Modellauswahl im Chat** — erst nach den Obergrenzen.
- **Schutz vor Weitergabe personenbezogener Daten** — bei externen Modellen: Verweigern als
  Voreinstellung, optional Ersetzen, Anzeige „verlässt das Haus“, Protokoll jedes Aufrufs nach außen,
  technische Durchsetzung.
- **Voreinstellungen je Aufgabe** — Zusammenfassung, Klassifizierung, Bildverständnis als eigene
  Modellrollen mit Parametern.
- **Geprüfter Modellwechsel** — Vergleichsläufe gegen Referenzfälle, Vorschau der Auswirkung, Freigabe
  für einen begrenzten Kreis. ⚑

### F · Identität, Rechte & Mandanten

- **Sitzungsverwaltung** — Übersicht eigener Sitzungen mit Beenden, Höchstdauer und Leerlauffrist
  organisationsweit, Abbruch laufender Antworten bei Sitzungsende.
- **Erzwungene Neuanmeldung durch die Systemverwaltung** — für Person, Gruppe oder alle; automatisch bei
  Verzeichnissperre und zugangsrelevanter Rechteänderung (für lokale Konten gebaut).
- **Vorgabewert der Freigabe-Obergrenze für Konnektorbibliotheken** — installationsweiter Standard bei
  Neuanlage. ⚑

### G · Sicherheit, Nachweis & Prüfbarkeit

- **C5-Nachweispaket** — Verantwortungsmatrix Produkt/Betreiber, Härtungsleitfaden mit
  Referenzkonfiguration, reproduzierbare Builds; bisher nicht vorhanden.
- **Unabhängige Sicherheitsprüfung** — ab wann und in welchem Umfang. ⚑
- **Durchsetzung getrennter Auswertungswege und Umfangsbegrenzung der anlassbezogenen Klärung** —
  Rollen, Endpunkte oder Bestände; technische Erzwingung der vorab festgelegten Grenze; Netzadresse im
  Protokollsatz für C5. ⚑

### H · Monitoring, Kosten & Governance

- **Grenzen je Person und Gruppe** — Anfragen, Modellverbrauch, gleichzeitige Läufe, Uploads; der Person
  selbst angezeigt, für andere unsichtbar, auch für Tokens und Dienstkonten. Heute gibt es
  Kontingente je Netzadresse, Token, Dienstkonto und Speicher.
- **Kostentransparenz** — Verbrauch je Sitzung, Asset, Bibliothek, Modell, Organisationseinheit;
  Kostenvergleich zwischen Modellen; Vorschau vor teuren Läufen.
- **Auswertungscockpit** — Betriebs- und Nutzungssicht, Mindestgruppengröße mit Unterdrückung,
  Nutzungsstatistik an der Erhebung abschaltbar.
- **Fortschritt der KI-Einführung** — Nutzung je Einheit, erfolgreiche Assets, schwache Verbreitung,
  Suchanfragen ohne Treffer.
- **Export für Berichte** — maschinenlesbar, wiederkehrende Berichte, Kostenexport für die
  Haushaltsstelle, nie feiner als das Cockpit.
- **Mindestgruppengröße und Rekonstruktionsschutz** — Ausgangswert und Verfahren gegen Differenzbildung.
  ⚑
- **Erster Kennzahlenschnitt und Kostenzurechnung lokaler Modelle** — welche Kennzahlen zuerst; welche
  Bezugsgröße für Rechenzeit; ob Rückmeldungen in die Auswertung eingehen. ⚑

### I · Kanäle & Oberflächen

- **Antwort-Bewertung** — Antwort, einzelne Fundstelle und freier Hinweis bewerten, zur Frage statt zur
  Person gespeichert, Eingang in Golden-Datensatz; Bedienelement wurde ausgebaut, Konzept fehlt.
  Aufbewahrung und Sichtkreis offen. _(⚑ (Konzept))_
- **Live-Transkript der Spracheingabe** — per WebSocket (Stufe 2). _(#2615)_

### J · Betrieb & Deployment

- **Getrennte Dienste für große Installationen** — Betriebsarchitektur mit getrennten Speicher-Backends.
  _(Epic #1439)_
- **Hochverfügbarkeit über die Anwendung hinaus** — Datenbank mit Replikation, Blau-Grün-Umschaltung,
  getrennte Netzbereiche für Oberfläche, Anwendung, Daten und Modelle.
- **Offene Betriebsfragen** — Voreinstellungen für RTO/RPO, Bündelung von Abbildern, Modellgewichten und
  Stückliste für abgeschottete Netze, Zuordnung der Netzsicherheit zwischen zwei Spezifikationen. ⚑
- **Ablage auf openCode** _(#3041)_

### K · Verwaltungs-Spezifika

- **Textwerkzeuge Leichte Sprache und Amtssprache** — beide Richtungen, Rechtsfolge tragende Begriffe
  gekennzeichnet; weiter ohne Vorgang.
- **Barrierefreiheits-Audit einer veröffentlichten Version** — Nachweis über automatisierte Prüfung
  hinaus; Nachweisweg (automatisiert, extern, beides) offen. _(#3006 · ⚑ (Nachweisweg))_
- **Behördenunterlagen: offene Regeln** — Bagatell-Liste der Stufenregeln, Einordnung geänderter
  System-Prompts, landesrechtliche Unterschiede. ⚑

### Querschnitt

- **API nach Nutzergruppen trennen** — Anwender-, Verwaltungs- und versionierte Integrations-API; nur
  als Epic, nicht in den Spezifikationen. _(Epic #2058)_
- **Messgrundlage für die Retrieval-Arbeit** — Verwaltungskorpus mit echter §-Gliederung, Tabellen,
  Folien für typspezifische Chunkgrößen; Nachschärfung der Verwaltungs-Evaldomäne bei Verdacht. ⚑

## Phase 3 — Kollaboration und organisationsweiter Rollout

### B · Wissensquellen & Konnektoren

- **Rechtespiegelung aus dem Quellsystem (Option 2)** — Quellrechte auslesen und auf Verzeichnisgruppen
  abbilden, nur einschränkend; welche Quellsysteme das belastbar liefern, ist offen. _(⚑ (welche
  Quellen))_
- **Lebenszyklus der Dokumente: Ausschluss** — einzelne Dokumente (auch aus Konnektorbibliotheken)
  dauerhaft aus dem Bestand nehmen, laufübergreifend, rücknehmbar; Zustand „archiviert“.

### C · Spaces, Assets & Verteilung

- **Versionierung von Assets** — Grundlage für Freigabeweg, Abkömmlinge und Drift-Anzeige.
- **Freigabe- und Prüfworkflow** — vorschlagen, prüfen, freigeben, veröffentlichen; Freigabestand im
  Katalog; Governance-Arbeitsliste. Offen sind die Prüfstelle, wenn eine Behörde keine
  benennt, und die Fristen. _(⚑ (Prüfstelle, Fristen))_
- **Parameter, Abkömmlinge und Drift-Schutz** — typisierte Parameter statt Fork; gekennzeichnete
  Abkömmlinge mit Versionsstand, Benachrichtigung und (zurückgestellt) Prüfaufforderung mit Frist.
- **Rückruf durch Deaktivieren** — Asset deaktivieren statt löschen, mit Begründungspflicht und
  Warnhinweis in bestehenden Verläufen.
- **Export und Import von Assets** — Portabilität; behördenübergreifender Austausch geprüfter Pakete
  erst später, Signatur des Freigabestempels offen. _(⚑ (Signatur))_
- **Vorlagenkatalog nach Fachbereich und organisationsweiter Katalog** — Katalogeintrag mit
  Anwendungsfall, Fachbereich, Freigabestand, Nutzungsangaben.
- **Nutzungstransparenz je Asset** — aggregiert, Voreinstellung aus, Schwelle nach Zahl der Nutzenden
  (siehe H).
- **Mitgelieferte Assets (Startkatalog)** — `origin = BUILT_IN` ist vorgesehen, aber nichts wird
  angelegt; Aktualisierungsweg auch offline (Signatur, Prüfung). _(⚑ (Offline-Aktualisierung))_
- **Chats in den Space teilen** — Zustände `SHARED`/`SUPERSEDED`/`WITHDRAWN`, Teilen-Dialog, Rückzug,
  Benachrichtigung; Gesprächsnotiz geht mit.
- **Artefakte** — space-eigene Arbeitsergebnisse mit Ursprungs-Chat, ersetzbar, in Bibliothek
  übernehmbar; ihr Ort in der Oberfläche ist offen. _(⚑ (Ort in der Oberfläche))_

### I · Kanäle & Oberflächen

- **Benachrichtigungen und Postfach** — In-App-Postfach, Ungelesen-Markierung, weitere Kanäle (E-Mail,
  Zusammenfassung). _(Epic #1297)_
- **Eigene Ansichten für Tabellen, Mail, DOCX und Folien** — bewusster Schnitt, Textfassung deckt sie
  ab.

### J · Betrieb & Deployment

- **Mandantenfähiger Betrieb durch ein Rechenzentrum** — getrennte Verwaltungsebenen Betreiber/Haus,
  Zurechenbarkeit und Ressourcengrenzen je Organisation, Objektspeicher je Mandant, Modellzuteilung je
  Haus (siehe F). _(Epic #1442 · ⚑ (Speicher, Modelle je Mandant))_

### K · Verwaltungs-Spezifika

- **Feinschliff der Amtssprache**
- **Zuordenbarkeit der Fassung bei Auskünften** — welche Agentenfassung, Modellvorgabe und welcher
  Wissensstand einer Antwort zugrunde lagen (hängt an der Versionierung).

## Phase 4 — Ökosystem und Ausblick

### A · Wissensschicht & Retrieval

- **Mehrsprachige Verarbeitung** — erkannte Sprache je Dokument, sprachübergreifendes Finden, Beleg im
  Originalwortlaut. Ob sie im Zielbild bleibt, ist offen. _(⚑ (Verbleib im Zielbild))_
- **Wissensgraph** — Entitäten und Beziehungen als Ergänzung des Vektor-Retrievals, mit Rechteprüfung
  auch im Graphen.
- **Leistungsziele belegen** — P95 der Retrieval-Zeit unter 1 s, Trennung der Lasten (Erstlauf bremst
  Suche nicht), Indizierungsdurchsatz; bisher Zielwerte ohne Messung auf Referenzhardware. _(Epic
  #3492)_

### B · Wissensquellen & Konnektoren

- **Postfach-Konnektoren (Exchange/Outlook, IMAP für openDesk)** — persönliches Postfach und
  Funktionspostfächer, erst nach erneuter Prüfung des Postfach-Konzepts und unter sieben Bedingungen (u.
  a. keine Auswertung nach Bearbeitenden, nur hausinternes Modell, Belege als „Korrespondenz“). _(Epic
  #2172 (geparkt))_
- **S3: Objektmetadaten, Tags und vorsignierte Beleg-Links**
- **Ladbare Erweiterungsschnittstelle für Konnektoren** — steckbar im Build (ADR-0038) ja, zur Laufzeit
  ladbar nein; unentschieden zurückgestellt. _(#106 · ⚑)_
- **XML-Fachformate (LegalDocML.de, XJustiz, XÖV)** — erst mit konkretem Quellanschluss (z. B. NeuRIS).

### F · Identität, Rechte & Mandanten

- **Mehrere Organisationen in einer Installation** — Mandantenfähigkeit; vier bekannte Lücken des
  strukturellen Prüflaufs werden mit der zweiten Organisation scharf. _(Epic #1442 (geparkt))_
- **Verzeichnisabgleich jenseits von Keycloak** — z. B. LDAP; SCIM ist ausdrücklich nicht der Weg.
- **Mehrfachzugehörigkeit zu Organisationseinheiten** — Abbildung im Rechte- und Auswertungsmodell.
  ⚑

### G · Sicherheit, Nachweis & Prüfbarkeit

- **Bestandsexport für Betreiberwechsel** — Wissensbestände, Assets und Konfiguration in offenen
  Formaten; Umfang (inkl. Protokolldaten?) offen. Konfigurationsexport ist gebaut. ⚑

### I · Kanäle & Oberflächen

- **Anbindung an self-hosted Team-Chats** — Matrix (souveräner Arbeitsplatz), Mattermost, Rocket.Chat;
  Zuordnung von Strängen zu Spaces und Darstellung mehrerer Fundstellen offen. _(⚑ (Strangzuordnung))_
- **Erweiterungen für Office und Browser** — nur bei konkretem Einführungsvorhaben.
- **Chatliste: Ordner oder Schlagworte** — nur bei gemeldetem Bedarf („Phase 5“ der Chatliste).
- **Chatsuche erweitern** — spaceübergreifend (eigenes Epic), nach Titeln zitierter Dokumente, Filter
  „mit mir geteilt“, automatische Bereinigung geteilter Chats. _(⚑ (zitierte Dokumente nach
  Rechteentzug))_

### J · Betrieb & Deployment

- **Andockung an den souveränen Arbeitsplatz (openDesk)** — KI- und Wissensschicht über Dateiablage,
  Wiki, Postfach, Matrix-Chat und zentrale Anmeldung.

### K · Verwaltungs-Spezifika

- **Anbindung an elektronische Akte und DMS** — nur projektgetrieben, je System eine eigene Entwicklung;
  welches Zielsystem zuerst, ist offen. ⚑
- **Assistent für Bürgerinnen und Bürger** — Ausblick, damit frühe Entscheidungen den Weg nicht
  verbauen.
- **Behördenunterlagen: zurückgestellt** — Unterlagen für Unternehmen (Betriebsrat, ISO 27001, NIS2,
  CRA-Herstellerrolle), Formatkonvertierung nach PDF/ODT/DOCX.

## Gestrichen

Am 02.11.2026 aus dem Zielbild genommen, sofern kein anderes Datum dabeisteht. Kommt ein Punkt wieder, braucht er einen neuen Anlass.

- Rerank-Mindestschwelle für Belege
- Fassungsgebundene Belege / Fassungsfolge
- Ablauf und Archivierung von Dokumenten
- Nicht genutzte Retrieval-Verfahren
- Zwischenspeicherung wiederkehrender Fragen
- Neuindizierung nach Pipeline-Umstellung auslösen
- Vorrangstufen und Schonzeitraum
- Einschluss- und Ausschlussmuster
- OneDrive für private Microsoft-Konten
- Filterung von Inhalten
- Kürzung an der Kontextgrenze darstellen
- Weitere Modellfragen
- Netzbereiche für die interaktive Anmeldung
- Gruppen-Claim-Overage nachladen
- Befristete Rechte und Rezertifizierung
- Erweiterte Rechtemodelle
- Externe Beteiligte
- Selbstauskunft Stufe 2 und Ausbauten
- Selbstauskunft für Protokolldaten über die eigene Person hinaus (05.11.2026)
- Monatssiegel an zweitem Ort und Prüfansicht
- Testzugang für die Personalvertretung
- Suchfilter nach Stand der Indizierung
- Prüfung gegen das Regelwerk der Leichten Sprache
- Feedbackwünsche zu Prozessen und Kommunikation
