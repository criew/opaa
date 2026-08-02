# OPAA Konzepte & Glossar

Dieses Dokument erläutert die wichtigsten Konzepte und die in der gesamten OPAA-Dokumentation verwendete Terminologie.

---

## Kernkonzepte

### Wissensbasis / Organisationswissen

Die gesammelten Informationen, Dokumente und Daten, die über die Systeme einer Organisation gespeichert sind.

- **Beispiel:** Unternehmens-Wiki-Seiten, E-Mail-Archive, Richtliniendokumente, Team-Entscheidungsaufzeichnungen
- **Herausforderung:** Über mehrere Systeme verteilt (Confluence, Gmail, SharePoint, Dateiserver)
- **OPAAs Rolle:** Einheitlicher Zugang durch intelligente Suche

### RAG (Retrieval-Augmented Generation)

Eine Technik, die Informationsabruf mit Sprachgenerierung kombiniert. Anstatt dass das LLM Antworten nur aus seinen Trainingsdaten generiert, ruft RAG zunächst relevante Dokumente ab und verwendet diese dann, um genaue, fundierte Antworten zu generieren.

**Wie es funktioniert:**
1. Benutzer stellt eine Frage
2. System ruft relevante Dokumente aus der Wissensbasis ab
3. LLM liest diese Dokumente
4. LLM generiert eine Antwort basierend auf den abgerufenen Dokumenten
5. Antwort enthält Quellen (Attribution)

**Warum es wichtig ist:**
- Antworten basieren auf echten Organisationsdokumenten
- Reduziert Halluzinationen (LLM erfindet keine Fakten)
- Jede Antwort ist durch Prüfung der Quelle verifizierbar
- Hält Informationen aktuell (neue Dokumente werden automatisch verwendet)

---

### Embedding (Vektor-Embedding)

Eine numerische Darstellung von Text, die seine Bedeutung erfasst. Ein Embedding ist eine Liste von Zahlen (ein "Vektor"), der den semantischen Inhalt eines Dokuments oder einer Frage kodiert.

**Einfache Erklärung:**
- Ein Dokument über "Remote-Arbeit-Richtlinie" könnte als `[0.21, -0.18, 0.45, ..., 0.32]` (100s-1000s von Zahlen) dargestellt werden
- Eine Frage über "von zu Hause arbeiten" erzeugt einen ähnlichen Vektor `[0.20, -0.17, 0.46, ..., 0.31]`
- Ähnliche Vektoren = ähnliche Bedeutung
- Das System verwendet diese Ähnlichkeit, um relevante Dokumente zu finden

**Warum es wichtig ist:**
- Ermöglicht **semantische Suche** (Suche nach Bedeutung, nicht nur Schlüsselwörtern)
- "Kann ich remote arbeiten?" findet Dokumente über "Fernarbeit" auch wenn diese genauen Wörter nicht in der Frage stehen
- Mächtiger als Schlüsselwort-Matching

---

### Vektor-Datenbank

Eine spezialisierte Datenbank, die für die Speicherung und Suche von Embeddings (Vektoren) optimiert ist.

**Häufige Beispiele:**
- **Elasticsearch** — Allzweck-Suchmaschine mit Vektor-Unterstützung
- **PostgreSQL + pgvector** — Traditionelle SQL-Datenbank mit Vektor-Erweiterung
- **Milvus** — Open-Source, konzipiert für groß angelegte Vektorsuche
- **Cloud-Optionen** — Pinecone, Weaviate, Qdrant

**Warum separat von regulären Datenbanken:**
- Traditionelle SQL-Datenbanken (MySQL, PostgreSQL) sind für exakte Treffer optimiert
- Vektor-Datenbanken sind für **Ähnlichkeitssuche** optimiert ("finde die 10 ähnlichsten Vektoren")
- Viel schneller und effizienter für semantische Suche

---

### Chunk / Chunking

Große Dokumente in kleinere, handhabbare Teile aufteilen.

**Warum nötig:**
- Ein 50-seitiges Richtliniendokument würde ein riesiges Embedding erzeugen
- Stattdessen in 50 kleinere Chunks aufteilen (Absätze oder Abschnitte)
- Jeder Chunk erhält sein eigenes Embedding
- Granularere Suchergebnisse

**Beispiel:**
```
Dokument: "Unternehmens-Richtlinienhandbuch" (10.000 Wörter)
  ↓
Chunks:
  Chunk 1: "Einstellungsprozess" (200 Wörter)
  Chunk 2: "Remote-Arbeit" (300 Wörter)
  Chunk 3: "Ausgaben-Richtlinie" (250 Wörter)
  ...
```

Wenn der Benutzer nach "Remote-Arbeit" sucht, gibt das System speziell Chunk 2 zurück, nicht das gesamte 10.000-Wort-Handbuch.

---

### LLM (Large Language Model)

Ein KI-Modell, das auf großen Mengen von Textdaten trainiert wurde und menschenähnlichen Text verstehen und generieren kann.

**Beispiele:**
- GPT-4, GPT-3.5-turbo (OpenAI)
- Claude (Anthropic)
- Llama, Mistral (Open-Source)
- Ollama (lokal, kleinere Modelle)

**Im OPAA-Kontext:**
- LLM liest die abgerufenen Dokumente
- LLM generiert die Antwort
- Verschiedene LLMs können ausgetauscht werden (OpenAI → lokal → Anthropic)
- OPAA ist **modell-agnostisch** — die LLM-Wahl ist konfigurierbar

---

### Space

Ein thematischer Arbeitsraum — für ein Projekt, ein Team, einen Fachbereich oder die eigene Arbeit. Der Space ist **kein Sicherheitssilo für Dokumente**, sondern der Ort, an dem gearbeitet wird und an dem die Ergebnisse dieser Arbeit liegen.

**Zweck:**
- Chats und Artefakte thematisch bündeln
- Kuratieren, welche Assets hier angeboten werden
- Standard-Suchbereich, Modell-Obergrenze und Zurechnung für Nutzung und Kosten

**Flaches Modell:**
Spaces sind **flach** — keine Hierarchie, keine Verschachtelung. Es gibt drei Arten:

1. **Persönlicher Space** — automatisch je Nutzer, nicht teilbar, nicht löschbar
2. **Projekt-Space** — von jedem Nutzer anlegbar, nicht im Verzeichnis gelistet, nur selbst eingeladene Mitglieder
3. **Team-Space** — vom System-Admin angelegt, für Teams, Fachbereiche und hausweite Räume

**Wichtig:** Space-Mitgliedschaft gewährt **keinen** Zugriff auf die im Space assoziierten Assets — aber vollen Zugriff auf die **abgelegten** space-eigenen Inhalte. Chats und Artefakte entstehen als Entwurf beim Ersteller und werden erst durch Ablegen sichtbar. Siehe [Spaces, Assets & Zugangskontrolle](./features/spaces-and-assets.md).

**Analogie:**
- Wie ein Confluence-Space: Er besitzt seine Seiten, verlinkt aber auf Fremdes, ohne daran Rechte zu vergeben.

---

### Asset

Ein eigenständiges, teilbares Objekt mit genau einem Eigentümer und einer eigenen Rechteliste — eine **Wissensbibliothek**, ein **Agent** oder eine **Prompt-Bibliothek**.

**Zweck:**
- KI-Können und Wissen verteilbar machen, ohne es zu kopieren
- Ein Bestand liegt einmal und wird an vielen Stellen genutzt

**Assoziation:** Ein Asset wird in beliebig viele Spaces assoziiert. Die Assoziation ist reine Kuratierung und **gewährt keinerlei Zugriff**; sie stellt das Asset nur denen im Space bereit, die ohnehin ein Recht darauf haben.

**Merkregel:** Was im Space entsteht, gehört dem Space. Was assoziiert wird, behält seinen Eigentümer.

---

### Wissensbibliothek

Der Dokumentencontainer. Jedes Dokument gehört zu genau einer Wissensbibliothek, und jeder Chunk trägt die Bibliotheks-Kennung als Filterachse der rechtebewussten Suche.

**Details zur persönlichen Ablage:**
- Jeder Nutzer hat einen persönlichen Space und darin eine persönliche Wissensbibliothek "Meine Dokumente"
- Automatisch erstellt, einer pro Nutzer, nicht löschbar (bei Offboarding deaktiviert)
- Teilen läuft über die Bibliothek, nicht über den Raum: Ein direkter Grant an eine andere Person genügt, der persönliche Space bleibt privat

---

### Rolle (in der Zugangskontrolle)

Eine Reihe von Berechtigungen, die Benutzern zugewiesen werden. Bestimmt, welche Aktionen sie ausführen können.

**Systemweite Rolle:**
- **System-Admin** — Organisationsweite Administration. Kann Team-Spaces anlegen, Konnektoren konfigurieren, Quellzuordnungen definieren, Gruppen und Verzeichnis-Synchronisation verwalten. Auf der Benutzer-Entität gespeichert. Er verwaltet das System, ist aber **nicht automatisch leseberechtigt** für alle Inhalte.

**Space-Rollen (pro Space-Mitgliedschaft) — regeln Mitarbeit und Kuratierung, nicht den Dokumentenzugriff:**
- **Member** — Space betreten, Chats führen, alle Chats und Artefakte des Space lesen
- **Curator** — zusätzlich Assets assoziieren und lösen
- **Admin** — zusätzlich Mitglieder, Einstellungen und Modell-Obergrenze verwalten, Inhalte moderieren

Dazu trägt jeder Space einen **Verantwortlichen** als Attribut; nur er oder ein System-Admin darf den Space löschen.

**Asset-Rollen (pro Asset) — regeln den tatsächlichen Zugriff:**
- **User** — benutzen, ohne die Konfiguration zu sehen
- **Viewer** — zusätzlich die Konfiguration einsehen
- **Editor** — zusätzlich ändern
- **Manager** — zusätzlich teilen und Rechte vergeben
- **Owner** — zusätzlich löschen und Eigentum übertragen

Rechte werden an **Nutzer oder Gruppen** vergeben. Gruppen bilden die Aufbauorganisation ab und tragen die Verteilungsstufe "Fachbereich".

---

## Datenpipeline-Konzepte

### Datenquelle

Jedes System, in dem Organisationswissen gespeichert ist.

**Beispiele:**
- Confluence (Wiki-Plattform)
- Jira, GitHub Issues, GitLab (Issue-Tracker)
- Gmail (E-Mail)
- S3, Google Drive, Dropbox (Cloud-Dateispeicher)
- SharePoint (Dokumentenverwaltung)
- GitHub, GitLab (Code-Dokumentation)

---

### Dokumentenverarbeitungs-Pipeline

Die automatisierten Schritte, die OPAA unternimmt, um Dokumente durchsuchbar zu machen.

**Schritte:**
1. **Entdeckung** — Dokumente in Datenquellen finden
2. **Extraktion** — Textinhalt extrahieren (verarbeitet PDF, Word, usw.)
3. **Chunking** — In kleinere Teile aufteilen
4. **Embedding** — In numerische Vektoren umwandeln
5. **Speicherung** — Embeddings in Vektor-Datenbank speichern
6. **Indizierung** — Für Suche verfügbar machen

---

### Semantische Suche

Suche basierend auf **Bedeutung** statt exaktem Schlüsselwort-Matching.

**Beispiel:**
```
Frage: "Kann ich von zu Hause arbeiten?"

Schlüsselwort-Suche würde finden:
  - "Von zu Hause arbeiten" ✓
  - "Remote-Arbeit" ✗ (kein exakter Treffer)
  - "Telearbeit" ✗ (kein exakter Treffer)

Semantische Suche findet:
  - "Von zu Hause arbeiten" ✓
  - "Remote-Arbeit" ✓
  - "Telearbeit" ✓
  - "Außerhalb des Büros arbeiten" ✓
```

---

### Berechtigungsdurchsetzung zur Abfragezeit

Berechtigungen werden **als Teil der Vektorsuche selbst** durchgesetzt — die für den Benutzer lesbaren Wissensbibliotheken werden als Metadaten-Filter direkt in die Abfrage übergeben. Nicht autorisierte Chunks werden niemals geladen oder gerankt.

**Wie es funktioniert:**
1. System bestimmt die lesbaren Wissensbibliotheken des Benutzers und schneidet sie mit dem Suchbereich des Kontexts (Space bzw. gebundener Agent)
2. Benutzer sucht: "Gehaltsrichtlinien"
3. Vektorsuche gibt nur Chunks zurück, deren `library_id` im ermittelten Suchbereich liegt
4. Benutzer sieht nur Dokumente, auf die er autorisiert ist zuzugreifen

**Warum das wichtig ist:**
- Benutzer wissen nicht, dass Dokumente existieren, die sie nicht sehen können
- Ergebnisse wirken vollständig, auch wenn gefiltert
- Keine Nachfilterung nötig — die Suche selbst ist berechtigungsbewusst
- Berechtigungen ändern sich sofort (keine Neu-Indizierung nötig)

---

## Architektur-Konzepte

### Orchestrierungsschicht

Der zentrale Koordinator, der Benutzeranfragen verarbeitet.

**Verantwortlichkeiten:**
- Empfängt Anfrage (Frage) von jedem Frontend
- Prüft Berechtigungen
- Ruft RAG-Engine auf, um Dokumente abzurufen
- Ruft LLM auf, um Antwort zu generieren
- Formatiert und gibt Ergebnis zurück

**Analogie:** Wie ein Restaurant-Host — nimmt Ihre Bestellung entgegen, koordiniert mit der Küche, liefert Ihr Essen

---

### Frontend

Die Benutzeroberfläche, über die Menschen mit OPAA interagieren.

**Typen:**
- **Web-UI** — Browserbasierte Chat-Schnittstelle
- **Chat-Integrationen** — Bots in Mattermost, Slack, usw.
- **REST-API** — Für programmatischen Zugang
- **Benutzerdefiniert** — Jede auf der REST-API aufgebaute Schnittstelle

---

### Datenquellen-Konnektor

Software, die weiß, wie man sich mit einer bestimmten Datenquelle verbindet und Dokumente extrahiert.

**Beispiele:**
- Confluence-Konnektor — Weiß, wie man sich mit der Confluence-API authentifiziert, Seiten extrahiert
- E-Mail-Konnektor — Weiß, wie man sich mit IMAP-Servern verbindet, E-Mails parst
- S3-Konnektor — Weiß, wie man sich mit AWS authentifiziert, Dateien listet und herunterlädt
- Google-Drive-Konnektor — Weiß, wie man Google-APIs verwendet, Dokumente herunterlädt
- Jira-Konnektor — Weiß, wie man Issues, Kommentare und Anhänge liest

---

### Benutzer-Dokument-Upload

Das Hochladen eines Dokuments durch einen Benutzer in OPAA über eine Frontend-Schnittstelle (Web-UI, Chat, REST-API), im Gegensatz dazu, dass OPAA Dokumente über Konnektoren von konfigurierten Datenquellen abruft.

**Wesentliche Unterschiede zur Konnektor-basierten Aufnahme:**
- Benutzer schiebt aktiv Dokumente (vs. OPAA zieht aus Quellen)
- Auf Anfrage durch Benutzeraktion ausgelöst (vs. geplant oder ereignisbasiert)
- Dokumente landen standardmäßig in der persönlichen Wissensbibliothek des Benutzers
- Originaldateien werden auf OPAAs konfigurierbarem Speicher-Backend gespeichert

Siehe [Daten-Indizierung & RAG — Benutzer-Dokument-Upload](./features/data-indexing-rag.md#user-document-upload) für Details.

---

### Speicher-Backend

Das pluggbare Dateispeichersystem, in dem hochgeladene Dokumente gespeichert werden. Dies ist getrennt von der Vektor-Datenbank — das Speicher-Backend hält Originaldateien (PDF, DOCX, usw.) zum Herunterladen und Neu-Verarbeiten, während die Vektor-Datenbank Embeddings für die Suche hält.

**Unterstützte Backends (zum Deployment-Zeitpunkt gewählt):**
- **S3-kompatibler Objektspeicher** — AWS S3, MinIO (Cloud/Hybrid)
- **Netzlaufwerk (SMB/NFS)** — Gemeinsames Dateisystem-Mount (On-Premises)
- **Lokales Dateisystem** — Direkte Disk-Speicherung (Entwicklung/kleine Deployments)

---

### Bestände mehrfach verwenden

Ein Bestand, der an mehreren Stellen gebraucht wird, wird nicht kopiert: Dieselbe **Wissensbibliothek** wird in mehreren Spaces assoziiert oder an weitere Nutzer und Gruppen freigegeben. Eine Fassung, eine Pflegestelle, keine Vervielfachung von Chunks.

**Status:** Durch das Asset-Modell gelöst. Das frühere Konzept eines workspace-übergreifenden Dokument-Teilens samt seiner offenen Sicherheitsfragen ist gegenstandslos — siehe [Dokument-Teilen](./features/document-sharing.md) (überholt).

---

## Infrastruktur-Konzepte

### On-Premises-Deployment

OPAA läuft auf Ihren eigenen Servern, in Ihrem eigenen Rechenzentrum oder Büro.

**Vorteile:**
- Vollständige Datensouveränität (Daten verlassen nie Ihre Infrastruktur)
- Keine externen API-Abhängigkeiten
- Funktioniert in Air-Gap-Umgebungen
- Erfüllt strenge Datenschutzanforderungen

**Kompromiss:**
- Sie verwalten Infrastruktur, Backups, Sicherheits-Patches

---

### Cloud-Deployment

OPAA läuft auf Cloud-Infrastruktur (AWS, Azure, GCP), die Sie besitzen oder kontrollieren.

**Vorteile:**
- Einfaches Skalieren
- Verwaltete Backups und Disaster Recovery
- Keine physischen Server zu warten
- Cloud-verwaltete Vektor-Datenbanken nutzen

**Kompromiss:**
- Daten in Drittanbieter-Infrastruktur
- Cloud-Kosten können mit der Skalierung wachsen

---

### Container / Docker

Eine Methode, OPAA und alle seine Abhängigkeiten in eine einzelne Einheit zu verpacken, die überall gleich läuft.

**Warum es wichtig ist:**
- "Funktioniert auf meinem Rechner"-Problem gelöst
- Einfach auf verschiedene Server zu deployen
- Einfach zu aktualisieren (einfach neues Container-Image ziehen)

---

### Kubernetes (K8s)

Ein Orchestrierungssystem zur Verwaltung von Containern in großem Maßstab.

**Was es tut:**
- Führt mehrere Kopien von OPAA für Redundanz aus
- Startet fehlgeschlagene Instanzen automatisch neu
- Verteilt Traffic auf Instanzen
- Einfaches Skalieren (3 Instanzen → 10 Instanzen)

**Für:** Organisationen mit 1000+ Mitarbeitern oder hohem Abfragevolumen

---

### Konfigurationsmanagement

Wege, OPAA ohne Code-Änderungen anzupassen.

**Methoden:**
- **Umgebungsvariablen** — `LLM_PROVIDER=openai`
- **Konfigurationsdateien** — YAML-Dateien mit Einstellungen
- **Admin-UI** — Web-Schnittstelle zum Ändern von Einstellungen

**Warum es wichtig ist:**
- Von OpenAI zu lokalem LLM mit einer Konfigurationsänderung wechseln
- Vektor-Datenbank ohne Code-Änderungen wechseln
- Organisationen passen an, ohne Code anzufassen

---

## Qualitäts- & Leistungs-Konzepte

### Konfidenz-Score

Ein numerischer Score (0-1), der angibt, wie zuversichtlich das System ist, dass abgerufene Dokumente für die Frage relevant sind.

**Skala:**
- **0,9-1,0** — Sehr zuversichtlich, definitiv relevant
- **0,7-0,9** — Zuversichtlich, wahrscheinlich relevant
- **0,5-0,7** — Unsicher, könnte relevant sein
- **< 0,5** — Nicht zuversichtlich, wahrscheinlich nicht relevant

**Benutzernutzen:** Auf einen Blick sehen, ob der Antwort vertraut werden soll

---

### Latenz

Wie lange eine Abfrage von Frage bis Antwort dauert.

**Ziele:**
- Vektorsuche: < 500 ms
- LLM-Generierung: 1-3 Sekunden
- Gesamt: < 4 Sekunden

**Einflussfaktoren:**
- Größe der Wissensbasis
- LLM-Modell (GPT-4 langsamer als 3.5-turbo)
- Infrastruktur (lokale Modelle schneller als Cloud-APIs)

---

### Halluzination

Wenn ein LLM falsche Informationen generiert oder Fakten erfindet.

**OPAAs Schutz:**
- RAG zwingt LLM, Quellen zu zitieren
- LLM kann nur Dinge behaupten, die in abgerufenen Dokumenten erscheinen
- Wenn Antwort nicht in Dokumenten ist, sagt das System "Ich weiß es nicht"

---

## Daten- & Sicherheits-Konzepte

### Berechtigungs-Vererbung

Dokumente erben Berechtigungen von ihrem Quellsystem.

**Beispiel:**
- Confluence-Seite hat Berechtigungen: "Nur Engineering-Team"
- Wenn in OPAA indiziert, behält sie dieselben Berechtigungen
- Nur Engineers können sie in OPAA-Suchen sehen

**Identity-Provider-Integration:**
OPAA muss wissen, wer Benutzer sind und welchen Gruppen sie angehören. Dies wird typischerweise durch Verbindung zu einem organisatorischen Identity-Provider wie Keycloak, Active Directory oder Okta gehandhabt. Der genaue Integrationsansatz (direktes LDAP, OIDC, SAML) ist eine offene Frage, die während der Implementierung entschieden wird.

---

### Audit-Logging

Aufzeichnen, wer was wann getan hat und welches Ergebnis entstand.

**Beispiele geloggter Aktionen:**
- Benutzer hat gesucht: [Zeitstempel], [Benutzer], [Abfrage], [Ergebnisanzahl]
- Benutzer hat auf Dokument zugegriffen: [Zeitstempel], [Benutzer], [Dokument], [Ergebnis]
- Admin hat Berechtigung geändert: [Zeitstempel], [Admin], [was geändert], [Grund]

**Anwendungsfälle:**
- Compliance (beweisen, wer auf sensible Daten zugegriffen hat)
- Debugging (verstehen, was schiefgelaufen ist)
- Nutzungs-Analytics (wonach suchen Menschen?)

---

### Verschlüsselung

Daten in eine kodierte Form umwandeln, sodass nur autorisierte Benutzer sie lesen können.

**Typen:**
- **Im Transit** — Daten verschlüsselt während der Übertragung über Netzwerke (TLS/HTTPS)
- **Im Ruhezustand** — Daten verschlüsselt während der Speicherung auf Disk
- **Ende-zu-Ende** — Daten auf dem Gerät des Benutzers verschlüsselt, niemals vom Server lesbar

---

## Leistungsoptimierungs-Konzepte

### Caching

Zuvor berechnete Ergebnisse speichern, damit sie nicht neu berechnet werden müssen.

**Beispiele in OPAA:**
- Häufige Fragen cachen (nicht neu einbetten oder neu generieren)
- Benutzerberechtigungen cachen (nicht bei jeder Anfrage erneut prüfen)
- Dokument-Embeddings cachen (unveränderte Dokumente nicht neu einbetten)

**Kompromiss:** Verbraucht mehr Speicher, aber spart Rechenleistung und Geld

---

### Batch-Verarbeitung

Mehrere Elemente zusammen statt einzeln verarbeiten.

**Beispiel:**
- 1.000 Dokumente einzeln indizieren: langsam
- 1.000 Dokumente in Batches von 100 indizieren: schneller (effizienter)

**Wenn in OPAA verwendet:**
- Während der Indizierung (Batch-Embedding-Generierung)
- Während der Berichtserstellung (Batch-Abfragen)

---

### Multi-Modell-Strategie

Verschiedene KI-Modelle für verschiedene Aufgaben verwenden, um Kosten, Geschwindigkeit und Qualität zu optimieren.

**Beispiel:**
- **Embedding-Modell** (lokal, günstig): Konvertiert Dokumente und Fragen in Vektoren für die Suche
- **Reasoning-Modell** (Cloud, leistungsstark): Generiert die endgültige Antwort aus abgerufenen Dokumenten
- **Zusammenfassungsmodell** (mittlere Stufe): Erstellt Dokumentzusammenfassungen für Vorschauen

Das bedeutet, eine Organisation kann ein lokales Embedding-Modell on-premises (kostenlos, schnell) betreiben, während sie ein Cloud-basiertes Reasoning-Modell (höhere Qualität) nur für die Antwortgenerierung verwendet — das Beste aus beiden Welten kombinierend.

---

### Kostenoptimierung

Strategien zur Reduzierung von LLM-API-Kosten.

**Techniken:**
- Multi-Modell-Strategie verwenden (günstige Modelle für einfache Aufgaben, leistungsstarke Modelle nur wenn nötig)
- Antworten auf häufige Fragen cachen
- Lokale Modelle verwenden (kostenlos nach Infrastrukturkosten)
- Anfragen außerhalb der Stoßzeiten bündeln

---

## Verwandte Konzepte (Nicht direkt in OPAA)

### Wissensgraph

Eine strukturierte Darstellung von Informationen und wie Konzepte miteinander in Beziehung stehen.

**Beispiel:**
```
Person: Hans Müller
  ├── Arbeitet bei: Beispiel GmbH
  ├── Abteilung: Engineering
  └── Vorgesetzter: Maria Schmidt

Dokument: Remote-Arbeit-Richtlinie
  └── Gilt für: Engineering-Abteilung
```

**Status in OPAA:** Außerhalb des MVP-Rahmens, mögliche zukünftige Erweiterung

---

### Indizierungs-Auslöser: Geplant vs. Ereignisbasiert

Es gibt zwei Ansätze, um den Index aktuell zu halten:

**Geplante Indizierung (Polling):**
- OPAA prüft Datenquellen nach einem regulären Zeitplan (z. B. jede Stunde, täglich um 2 Uhr)
- Einfach zu implementieren, funktioniert mit jeder Datenquelle
- Kompromiss: Änderungen sind erst nach dem nächsten geplanten Lauf sichtbar

**Ereignisbasierte Indizierung (Push):**
- Datenquellen benachrichtigen OPAA, wenn sich Dokumente ändern (über Webhooks, Ereignisse oder APIs)
- Änderungen werden viel schneller indiziert (Minuten statt Stunden)
- Erfordert, dass die Datenquelle Ereignisbenachrichtigungen unterstützt
- Beispiel: Confluence sendet einen Webhook, wenn eine Seite aktualisiert wird → OPAA indiziert diese Seite sofort neu

OPAA unterstützt beide Modelle. Die Wahl hängt von den Fähigkeiten der Datenquelle und den Aktualitätsanforderungen der Organisation ab.

### Echtzeit-Synchronisation

OPAA sofort aktualisieren, wenn Quelldokumente sich ändern (innerhalb von Sekunden).

**Beispiel:** Benutzer bearbeitet Confluence-Seite → OPAA aktualisiert automatisch innerhalb von Sekunden

**Status in OPAA:** Kein primäres Ziel — ereignisbasierte Indizierung bietet nahezu-Echtzeit-Aktualisierungen (Minuten), was für die meisten Anwendungsfälle ausreicht. Echte Echtzeit-Synchronisation (Sekunden) kann später für bestimmte Datenquellen hinzugefügt werden.

---

## Schnellreferenz-Tabelle

| Begriff | Definition | Beispiel |
|---------|-----------|---------|
| **RAG** | Retrieval + KI-Generierung | Frage stellen → Dokumente abrufen → LLM antwortet |
| **Embedding** | Vektor, der Textbedeutung repräsentiert | [0,21, -0,18, 0,45, ...] |
| **Chunk** | Teil eines Dokuments | Seite 3 eines 50-seitigen Handbuchs |
| **Semantisch** | Basierend auf Bedeutung, nicht Schlüsselwörtern | "Remote-Arbeit" ≈ "von zu Hause arbeiten" |
| **LLM** | KI-Sprachmodell | GPT-4, Claude, Llama |
| **Space** | Thematischer Arbeitsraum (flach, keine Hierarchie); trägt Chats und Artefakte | "Projekt Phoenix" |
| **Asset** | Eigenständiges, teilbares Objekt mit eigenem Eigentümer und eigener Rechteliste | Wissensbibliothek, Agent, Prompt-Bibliothek |
| **Wissensbibliothek** | Dokumentencontainer und Rechteanker der Suche | "Rechtsquellen Soziales" |
| **Benutzer-Upload** | Benutzer schiebt Dokument in OPAA | Drag-and-Drop in Web-UI |
| **Speicher-Backend** | Pluggbarer Dateispeicher für Uploads | S3, Netzlaufwerk, lokal |
| **Assoziation** | Ein Asset in einem Space bereitstellen — reine Kuratierung, gewährt keine Rechte | "Rechtsquellen" in fünf Team-Spaces |
| **System-Admin** | Systemweite Rolle für organisationsweite Administration, je Organisation | Konnektor-Konfiguration, Anlegen von Team-Spaces |
| **Space-Rolle** | Mitarbeit und Kuratierung im Arbeitsraum | Member, Curator, Admin |
| **Asset-Rolle** | Tatsächlicher Zugriff auf ein Asset | User, Viewer, Editor, Manager, Owner |
| **Konnektor** | Datenquellen-Verbindung; jede Quelle indiziert in genau eine Wissensbibliothek | Confluence-Server mit Space→Bibliothek-Zuordnungen |
| **Vektor-DB** | Für Ähnlichkeitssuche optimierte Datenbank | Elasticsearch, Milvus, pgvector |
| **Latenz** | Zeit bis zur Antwort | < 4 Sekunden Ziel |
| **Halluzination** | LLM erfindet Fakten | LLM: "Unsere Richtlinie ist X" (nicht wahr) |

---

## Mehr erfahren

- [VISION.md](./VISION.md) für vollständiges Systemdesign lesen
- Spezifische Feature-Spezifikationen in `features/` für detaillierte Informationen lesen
- Siehe [INDEX.md](./INDEX.md) für Lesepfade nach Rolle
