# Schlanke Konfiguration und Einrichtungs-Assistent

> **Status: in Umsetzung.** Zielbild zu Issue #3160. Gebaut sind Teilschritt 1 (Installationsmodus,
> Token, Installations-API, Antwortdatei mit erstem Konto, Anmeldeverfahren und Chat-Modell; die drei
> Seeder und die Startwerte sind entfallen), Teilschritt 3 im Backend (Embedding-Modell als Schritt
> und in der Antwortdatei, `vector_store` entsteht beim Abschluss) und Teilschritt 6 (Helm, Compose,
> e2e und Erprobungsaufbau installieren über die Antwortdatei). Eine ältere Installation übernimmt
> ihr Embedding-Modell noch einmalig aus `OPAA_OPENAI_EMBEDDING_*`, bis ein Release mit dieser
> Übernahme erschienen ist.
> Entscheidungen: [ADR-0060](../decisions/0060-schlanke-konfiguration-und-einrichtungsassistent.md).

## Motivation

Wer OPAA heute zum ersten Mal installiert, füllt eine `.env` mit Dutzenden Variablen. Einige davon
wirken nur beim ersten Start (Chat-Modell, Identitätsanbieter, erstes Konto), andere sind Vorgaben,
die eine Einstellung in der Datenbank später überschreibt. Welcher Wert gerade gilt, ist weder für
den Betrieb noch für die Systemverwaltung erkennbar, und eine falsch gesetzte Startvariable lässt
sich nach dem ersten Start nicht mehr korrigieren, ohne die Datenbank zu kennen.

Ziel ist eine Konfiguration, die nur noch enthält, was der Betrieb braucht, und eine
Erstinstallation, die im Browser durch die nötigen Schritte führt — oder ohne Browser mit einer
Antwortdatei abläuft.

---

## Überblick

1. **Drei Quellen, je Wert genau eine:** Konfiguration für Betrieb und Grenzen, Datenbank für
   fachliche Einstellungen, Werkseinstellung im Code, solange die Datenbank nichts enthält.
2. **Grenzen des Betriebs** (Allowlists, Funktionsschalter, Obergrenzen) bleiben in der Konfiguration
   und begrenzen, was die Verwaltung einstellen darf.
3. **Installationsmodus:** Eine Instanz mit nicht abgeschlossener Installation bietet nur den
   Assistenten an.
4. **Installations-Token** sichert den Assistenten, solange es noch kein Konto gibt.
5. **Einrichtungs-Assistent** mit Pflicht- und optionalen Schritten, fortsetzbar.
6. **Antwortdatei** für die Installation ohne Browser, über die API oder beim Start.
7. **Nach dem Abschluss** ist die Installations-API weg (`404`), auf jeder Instanz.
8. **Änderungsnachweis, Export und Import** aller fachlichen Einstellungen.

---

## Begriffe

| Begriff | Bedeutung |
|---|---|
| **Konfiguration** | Umgebungsvariablen und `application.yml`; gelesen bei jedem Start |
| **Fachliche Einstellung** | Ein Wert, den die Systemverwaltung entscheidet; lebt in der Datenbank |
| **Werkseinstellung** | Die im Code festgelegte Vorgabe einer fachlichen Einstellung, solange die Datenbank keinen Wert enthält |
| **Grenze des Betriebs** | Ein Konfigurationswert, der festlegt, was die Verwaltung höchstens einstellen darf |
| **Installationsmodus** | Zustand einer Installation, deren Einrichtung nicht abgeschlossen ist |
| **Installations-Token** | Einmaliges Geheimnis, das den Assistenten bis zum Abschluss absichert |
| **Antwortdatei** | JSON-Datei mit allen Angaben des Assistenten, für die Installation ohne Browser |

---

## Der Installationsmodus

Beim ersten Start mit leerer Datenbank legt die Migration das Schema an (ohne `vector_store`) und
die Anwendung steht im Installationsmodus. Er gilt für alle Instanzen gemeinsam, weil der Zustand in
der Datenbank steht.

| Endpunkt | Im Installationsmodus | Nach dem Abschluss |
|---|---|---|
| Installations-API | erreichbar, nur mit Token | `404` |
| öffentliche Konfiguration der Oberfläche | meldet „Installation ausstehend“ | wie heute |
| Health, Readiness, Liveness | `UP`; eigene Komponente „Installation“ | wie heute |
| Metriken | `opaa_installation_pending` = 1 | 0 |
| jede andere API | `503` mit Hinweis auf die Installation | wie heute |

Die Oberfläche zeigt im Installationsmodus nur den Assistenten. Ein Rückweg in den
Installationsmodus besteht nur über einen Eingriff des Betriebs in die Datenbank.

### Installations-Token

```
OPAA_INSTALLATION_TOKEN=<vom Betrieb gesetzt, etwa aus einem Secret>    # optional
OPAA_INSTALLATION_ALLOWED_CIDRS=10.0.0.0/8                              # optional
OPAA_INSTALLATION_ANSWER_FILE=/run/opaa/installation.json               # optional
```

- Ist `OPAA_INSTALLATION_TOKEN` gesetzt, gilt es. Sonst erzeugt der erste Start ein Token
  (32 Zeichen) und schreibt es einmalig als markierten Block ins Anwendungslog, nach dem Commit und
  unter dem Advisory-Lock der Startaktionen (ADR-0053), also genau einmal je Installation.
- Gespeichert wird nur ein Hash. Ein verlorenes Token stellt der Betrieb wieder her, indem er
  `OPAA_INSTALLATION_TOKEN` setzt und neu startet; ein gesetztes Token ersetzt ein erzeugtes.
- Fehlversuche sind je Netzadresse begrenzt und protokolliert.
- `OPAA_INSTALLATION_ALLOWED_CIDRS` beschränkt die Installations-API auf Netzbereiche (wie
  `OPAA_LOCAL_ADMIN_ALLOWED_CIDRS`).

---

## Schritte des Assistenten

```
┌──────────────────────────────────────────────────────────────────────┐
│  OPAA einrichten                                    Schritt 3 von 10  │
│  ──────────────────────────────────────────────────────────────────  │
│  ✓ Installations-Token        Pflicht                                │
│  ✓ Erstes Konto               Pflicht                                │
│  ● Anmeldeverfahren           Pflicht                                │
│  ○ Chat-Modell                optional · später unter Modelle        │
│  ○ Embedding-Modell           optional · später unter Modelle        │
│  ○ Rerank-Modell              optional · später unter Modelle        │
│  ○ Transkriptionsmodell       optional · später unter Modelle        │
│  ○ Ausgangsproxy              optional · später unter Anbindungen     │
│  ○ E-Mail-Versand             optional · später unter E-Mail          │
│  ○ Branding                   optional · später unter Branding        │
│  ○ Abschluss                                                        │
│                                         [Zurück]  [Prüfen und weiter] │
└──────────────────────────────────────────────────────────────────────┘
```

| Schritt | Pflicht | Angaben | Prüfung | Später änderbar unter |
|---|---|---|---|---|
| Installations-Token | ja | Token | Hash-Vergleich | — |
| Erstes Konto | ja | E-Mail-Adresse, Anzeigename, Passwort nach der Passwortregel | Adresse zustellbar formatiert, nicht `admin@opaa.local` | Benutzer (Notanker-Konto, ADR-0033) |
| Anmeldeverfahren | ja | nur lokale Konten, oder zusätzlich ein OIDC-Anbieter: Issuer, Client-ID, JWK-Set-Adresse | Discovery und JWK-Set erreichbar, Adressprüfung | Identitätsanbieter |
| Chat-Modell | optional | Adresse, Modellname, Schlüssel, Temperatur, Antwortlänge | Verbindungstest wie in der Modellverwaltung | Modelle |
| Embedding-Modell | optional | Adresse, Modellname, Schlüssel, Eingabegrenze | Probeeinbettung; legt die Vektordimension fest | Modelle → Embedding: Modellwechsel mit Neueinbettung, Endpunktwechsel ohne ([Wechsel des Embedding-Modells](./embedding-model-change.md)) |
| Rerank-Modell | optional | Adresse ohne `/rerank`, Modellname, Schlüssel (Regeln der Modellverwaltung) | Adressprüfung; dieselbe Rerank-Probe wie „Verbindung testen“ der Modellverwaltung; der Abschluss schaltet das Reranking ein | Modelle → Reranking |
| Transkriptionsmodell (nur mit `OPAA_FEATURES_SPEECH_INPUT_ENABLED`) | optional | Adresse, Modellname, Schlüssel, Sprache (Regeln der Modellverwaltung) | Probe der Modellverwaltung (`GET {Basis-Adresse}/models`, keine Aufnahme), Adressprüfung der Modelle; ohne Bestätigung der Mitbestimmung (ADR-0060, Entscheidung 16) | Modelle → Spracheingabe |
| Ausgangsproxy | optional; Pflicht, wenn ohne ihn kein Modell erreichbar ist | Proxy-Adresse, Ausnahmeliste | Verbindungstest der Modelle über den Proxy | Anbindungen → Einstellungen |
| E-Mail-Versand | optional | Server, Port, Verschlüsselung, Zugangsdaten, Absender (Regeln der Mail-Verwaltung) | Adressprüfung der Installation; Verbindung und Anmeldung ohne Versand, auf Wunsch Testnachricht an eine angegebene Adresse | E-Mail |
| Branding | optional | Produktname, Claim, Hinweis zur KI-Nutzung, Akzentfarbe, Farbschema-Vorgabe (Regeln der Branding-Verwaltung); keine Bilder (ADR-0060, Entscheidung 15) | Regeln der Branding-Verwaltung erneut, ohne Verbindung | Branding |
| Abschluss | ja | Zusammenfassung | alle Pflichtschritte und gespeicherten optionalen Schritte geprüft | — |

- Jeder Schritt wird beim „Prüfen und weiter“ gespeichert. Ein neu geöffneter Assistent setzt beim
  ersten offenen Schritt fort und zeigt die gespeicherten Werte (Geheimnisse nur als „gesetzt“).
- Ein Pflichtschritt ist erst grün, wenn seine Prüfung gelungen ist. Ändert sich ein Wert, verfällt
  die Prüfung.
- Ohne Chat- oder Embedding-Modell ist die Installation regulär nutzbar; beide legt die
  Systemverwaltung später in der Modellverwaltung fest (ADR-0060, Entscheidungen 6 und 10; ADR-0061,
  Entscheidung 8).
- Der Abschluss legt `vector_store` mit der Dimension aus der Probeeinbettung an, sofern das
  Embedding-Modell nicht übersprungen ist, setzt den Zustand
  auf „installiert“ und verwirft Token und Zwischenstand. Danach meldet sich die Person mit dem
  ersten Konto an.

---

## Installations-API

Ein eigenes Thema `installation` in der OpenAPI (`opaa-api/src/main/openapi/installation.yaml`).
Skizze; Feldnamen legt die Spec-Änderung fest:

| Operation | Zweck |
|---|---|
| `GET /api/v1/installation` | Zustand: welche Schritte gespeichert und geprüft sind, welche Pflicht |
| `PUT /api/v1/installation/steps/{step}` | Angaben eines Schritts speichern (`FIRST_ADMIN`, `SIGN_IN`, `CHAT_MODEL`) |
| `POST /api/v1/installation/steps/{step}/check` | Prüfung eines Schritts ausführen |
| `POST /api/v1/installation/steps/{step}/discover` | Modelle eines Endpunkts zur Auswahl abrufen (`CHAT_MODEL`, `EMBEDDING_MODEL`, `RERANK_MODEL`), ohne zu speichern |
| `POST /api/v1/installation/answer-file` | Antworten annehmen: alle Schritte speichern, prüfen und abschließen, alles oder nichts |
| `POST /api/v1/installation/complete` | Abschließen |

- Jede Operation verlangt das Token im Header `Authorization: Bearer <Token>`.
- Je Netzadresse und Instanz gelten 300 Anfragen und 10 abgelehnte Tokens in 15 Minuten; danach
  `429`, auch mit dem richtigen Token.
- Die Adresse des Chat-Modells durchläuft beim Speichern und vor dem Verbindungstest die
  Adressprüfung der Modelle (`OPAA_MODEL_TARGET_VALIDATION_*`, getrennt von der Zielprüfung der
  Quellen); ein gespeicherter Schlüssel bleibt nur
  bei unveränderter Adresse (Schema, Host, Port, Pfad).
- Der Modellabruf der Schritte Chat-, Embedding-, Rerank- und Transkriptionsmodell ist derselbe wie in
  der Modellverwaltung
  (Adressprüfung der Modelle, keine Weiterleitung, Grenzen für Zeit und Antwortgröße, bei Ollama
  mit Details). Ohne Schlüssel in der Anfrage nutzt er den im Schritt gespeicherten, nur für
  dieselbe Adresse; ein leerer Schlüssel sendet keinen. Je Instanz gelten 30 Abrufe in 10 Minuten,
  danach `429`. Ohne Liste bleibt die freie Eingabe der Modell-Kennung.
- Die Prüfungen der vier Modellschritte teilen sich je Instanz 30 in 10 Minuten, danach `429` mit
  dem Code `MODEL_CHECK_LIMIT`; die Antwortdatei über die API zählt jedes Modell vorab als eine
  Prüfung, auch wenn sie danach abgelehnt wird. Der Schritt E-Mail-Versand hat je Instanz 30
  Prüfungen (`MAIL_CHECK_LIMIT`) und 10 Testnachrichten (`MAIL_TEST_LIMIT`) in 10 Minuten. Die
  Antwortdatei beim Start zählt bei keinem Budget.
- Fehler kommen deutsch je Feld wie in der übrigen Verwaltung.
- Nach dem Abschluss antwortet jede Operation `404`.

---

## Antwortdatei

```json
{
  "firstAdmin": {
    "email": "it-betrieb@stadt.example",
    "displayName": "Systemverwaltung",
    "passwordFile": "/run/secrets/opaa-admin-password"
  },
  "signIn": {
    "mode": "LOCAL_AND_OIDC",
    "oidc": {
      "issuerUri": "https://login.stadt.example/realms/opaa",
      "clientId": "opaa-frontend",
      "jwkSetUri": "https://login.stadt.example/realms/opaa/protocol/openid-connect/certs"
    }
  },
  "chatModel": {
    "baseUrl": "http://ollama:11434/v1",
    "model": "qwen2.5:7b",
    "apiKeyFile": null,
    "maxTokens": 8000
  },
  "embeddingModel": {
    "baseUrl": "http://ollama:11434/v1",
    "model": "bge-m3"
  },
  "rerankModel": null,
  "transcriptionModel": null,
  "outboundProxy": null,
  "mail": null,
  "branding": null
}
```

- In der Datei stehen Geheimnisse nie im Klartext, sondern als `…File`-Verweis auf eine Datei, die
  aus einem Secret eingehängt ist; `password` oder `apiKey` in der Datei lehnt der Start ab. Über
  `POST /api/v1/installation/answer-file` kommen dieselben Angaben mit `password` und `apiKey` im
  Rumpf, wie in den einzelnen Schritten: Ein Dateiverweis über die API ließe den Inhaber des Tokens
  Dateien des Backends lesen (ADR-0060, Entscheidung 7).
- `chatModel`, `embeddingModel`, `rerankModel`, `transcriptionModel`, `mail` und `branding` sind
  optional; ein fehlender Teil überspringt seinen Schritt, ohne `rerankModel` bleibt das Reranking
  aus, ohne `transcriptionModel` die Spracheingabe, ohne `mail` der Versand, ohne `branding` gilt der
  OPAA-Standard. Bei abgeschalteter Spracheingabe wird `transcriptionModel` übergangen, nicht
  abgelehnt. Angaben späterer Schritte (`outboundProxy`) wertet diese Version noch nicht aus.
- Beim Start mit `OPAA_INSTALLATION_ANSWER_FILE` und nicht abgeschlossener Installation liest die
  Anwendung die Datei unter dem Advisory-Lock der Startaktionen, prüft wie der Assistent und schließt
  ab. Scheitert eine Prüfung, bleibt der Installationsmodus, und das Log nennt Schritt und Grund.
- Nach dem Abschluss wird die Datei ignoriert; das Log sagt das einmal je Start.
- Helm erzeugt die Datei aus den `bootstrap`-Werten, Compose bringt eine Beispieldatei mit. Das
  Profil `dev` richtet sich selbst ein, mit einem Chat-Modell aus festen Vorgaben der Entwicklung
  (Adresse `spring.ai.openai.base-url`, ohne Zielprüfung); die Testkontexte schließen über eine
  Test-Hilfe ab.

---

## Fachliche Einstellungen in der Datenbank

- Jede fachliche Einstellung hat eine Werkseinstellung im Code. Die Datenbank enthält einen Wert erst,
  wenn die Verwaltung ihn setzt; „Vorgabe wiederherstellen“ löscht ihn wieder, wie heute beim
  Speicherkontingent privater Bibliotheken.
- Grenzen des Betriebs prüfen jeden gespeicherten Wert (ADR-0060, Entscheidung 2). Sinkt eine
  Grenze nach dem Speichern unter den Wert, gilt die Grenze, und die Verwaltung sieht beides.
- Jede Änderung ist ein Revisionsereignis mit vorherigem und neuem Wert; Geheimnisse nur als
  „geändert“.
- **Export und Import:** Unter Allgemein exportiert die Systemverwaltung alle fachlichen
  Einstellungen ohne Geheimnisse als JSON. Der Import zeigt vorher die Unterschiede zur laufenden
  Installation, prüft jeden Wert und ist ein Revisionsereignis.

### Export und Import (gebaut mit #3193 und #3201)

- **Ein Register:** Jede Einstellung gehört zu genau einem Abschnitt (`SettingsSection`), einer
  Bean im Paket des Moduls, dem die Einstellung gehört. Ein Abschnitt liefert seine Werte ohne
  Geheimnisse und wendet sie über denselben Schreibweg an wie die Oberfläche, mit dessen Prüfung,
  Grenzen des Betriebs und Revisionsereignis. `SettingsSectionRegistryTest` lässt den Build
  scheitern, wenn eine Tabelle `*_settings` keinem Abschnitt gehört und nicht begründet
  ausgenommen ist.
- **Format:** `{"format": "opaa-settings", "formatVersion": 1, "exportedAt": …, "sections": {…}}`,
  je Abschnitt ein Objekt mit allen Schlüsseln und ihrem wirksamen Wert; was `null` bedeutet, legt
  der Schlüssel fest (Branding: OPAA-Standard, Kontingent und Vollabgleich-Rhythmus: Werkseinstellung, Vorlage:
  ausgelieferter Text, Mailserver-Feld: nicht gesetzt). Die Version steigt,
  wenn ein Schlüssel seine Bedeutung ändert oder entfällt, nicht für neue Abschnitte und Schlüssel.
  Abschnitte heute: `auditRetention`, `branding` (ohne Bilder), `connectionLifecycle` (Tage oder
  Anzahl, `null` für die Werkseinstellung), `connectionLogRetention`, `connectorLocks` (je
  Quellart `<TYPE>.locked`, bei Quellarten mit optionalem Zugang zusätzlich
  `<TYPE>.profileRequired` mit `null`, `RUNS` oder `LOCKED`),
  `diagnosticContextRetention`, `externalAccess`, `linkTargets`, `localAccounts` (ohne den
  Schalter der lokalen Konten), `mail` (ohne Passwort), `mailTemplates` (je Vorlage der Sprache
  `de` ein Schlüssel), `fullSyncRhythm` (je Quellart mit Rhythmus ein Schlüssel, Tage oder `null`),
  `outboundProxy`, `permissionHistoryRetention`, `privateStorageQuota`, `selfDisclosure`.
- **Sperren und „Nur über Zugänge“** (`connectorLocks`): abgeglichen über den Schlüssel der
  Quellart, angewandt über `ConnectorLockService.lockType` und `ProfileRequirementService.require`
  mit deren Ereignissen (`CONNECTOR_LOCKED`/`CONNECTOR_UNLOCKED`, `CONNECTOR_PROFILE_REQUIRED`/
  `CONNECTOR_PROFILE_OPTIONAL`); jede Angabe ist sicherheitsrelevant markiert. Findet sich für „Nur
  über Zugänge“ kein nicht gesperrter Zugang, der Bibliotheken zulässt, lehnt der Import das
  Dokument unter `connectorLocks.<TYPE>.profileRequired` mit dem Code
  `PROFILE_REQUIREMENT_NEEDS_PROFILE` ab.
- **Nicht übertragen** (ADR-0060, Entscheidung 12):
  - Chat-Modelle: Die Adresse empfängt jeden Chat, es gibt keinen eindeutigen Namen, und der
    Schlüssel folgt keiner neuen Adresse.
  - Embedding-Modell: Es gehört zum Index der Installation.
  - Rerank- und Transkriptionsmodell: Die Adresse empfängt Anfragen bzw. Spracheingaben, der
    Schlüssel folgt keiner neuen Adresse.
  - Identitätsanbieter und Schalter der lokalen Konten: das Anmeldeverfahren. Die Zuordnung von
    Claims zu Systemrollen erweiterte per Import die Systemverwaltung, ein ausgeschalteter Anbieter
    oder Standardwechsel sperrte sie aus. Ein Geheimnis haben Identitätsanbieter nicht.
  - Verzeichniszugänge: das Geheimnis des Dienstkontos, gebunden an einen Identitätsanbieter.
  - Zugänge: App-Registrierung und Geheimnis der Installation; eine geänderte Adresse, ein anderer
    Mandant oder andere Scopes verwerfen alle Geheimnisse und beenden die verbundenen Konten.
  - Konnektor-Freigabe (Anlegerechte je Quellart und Zugang, `capability_grants`): Sie verweist
    auf Gruppen und Zugänge dieser Installation.
  - Bilder des Brandings: dürfen größer sein als die Grenze der Datei.
- **Zugänge im Protokoll:** `CONNECTION_PROFILE_CREATED`, `…_CHANGED` und `…_DELETED` nennen
  `clientId`, `tenant` und `scopes` mit Wert, `""` für nicht gesetzt, und von den Konnektor-Vorgaben
  nur die gesetzten Schlüssel (`connectorSettingsKeys`), neben den Schaltern `clientIdSet`,
  `tenantSet`, `scopesSet` und `connectorSettingsSet`. `…_CHANGED` nennt zusätzlich die Schlüssel,
  deren Wert sich änderte (`connectorSettingsChanged`), ohne Werte: Eine Vorgabe kann eine Person
  nennen (Delegationskonto `subject` von Google Drive). Bei einem Dienstkonto-Schlüssel ist die
  Client-ID die Adresse des Dienstkontos, eines Funktionskontos. Die Selbstauskunft gibt diese
  Werte nicht weiter, wie die Server-Adresse. Text-Vorgaben sind wie die übrigen Textfelder eines
  Zugangs auf 255 Zeichen begrenzt.
- **Import:** eine Transaktion; ein fehlender Abschnitt bleibt, ein fehlender Schlüssel behält
  seinen Wert, ein unbekannter Abschnitt oder Schlüssel, eine andere Version oder ein Wert außerhalb
  der Grenzen lehnt das ganze Dokument ab. Die Vorschau läuft denselben Weg und rollt zurück. Ein
  Import prüft die Grenzen des Formulars im Schreibweg (Pflicht, Länge, Anzahl), meldet sie als
  Feldfehler `<abschnitt>.<schlüssel>` und liest die Datei streng (keine doppelten oder unbekannten
  Angaben). Die Vorschau markiert sicherheitsrelevante Änderungen und nennt jede Angabe deutsch.
  Ändern sich Server, Port, Zugangsname oder Verschlüsselung des Mailservers, folgt das
  gespeicherte Passwort nicht, die Änderung wird abgelehnt (wie `requireSecretFollows` der Zugänge).
  Ein Schreibweg, dessen Eingabe gleich dem Gespeicherten ist, schreibt nichts. Ein
  Import, der etwas ändert, schreibt `SETTINGS_IMPORTED`; er und jede Einzeländerung tragen dieselbe
  Vorgangskennung. Ein unveränderter Import schreibt nichts. Grenzen: 256 KiB je Datei, 30 Prüfungen
  und Importe je Stunde für die Installation.
- **Geheimnisse im Protokoll:** Ein Ereignis nennt ein Geheimnis nur als `<name>Change` mit `SET`,
  `CHANGED` oder `REMOVED` (`AuditSecretChange`), nie den Wert; Freitexte (Mail-Vorlagen,
  Einleitungstext externer KI-Werkzeuge) nur als `<name>Sha256` und `<name>Length`
  (`AuditTextDigest`).

Neue Verwaltungsorte für Werte, die heute nur in der Konfiguration stehen:

| Einstellung | Verwaltungsort |
|---|---|
| Rerank- und Transkriptionsmodell | Modelle (neue Reiter) |
| Vollabgleich-Rhythmus je Konnektor (Vorgabe der Installation) | Anbindungen → Einstellungen |
| Schwellen und Fristen der Verbindungen | Anbindungen → Einstellungen |
| Aufbewahrung der Chats und der anlassbezogenen Klärungen | Datenschutz, neben der Selbstauskunft |
| Vorlauf der Wiedervorlage externer KI-Werkzeuge | Externe KI-Werkzeuge → Einstellungen |
| Alterung „Ohne Zuständigkeit“ | Ohne Zuständigkeit |
| Plausibilitätsschwelle des Verzeichnisabgleichs | Verzeichnisabgleich, je Anbieter |

---

## Integrationspunkte

- **Benutzerverwaltung** ([ADR-0033](../decisions/0033-lokale-benutzerverwaltung.md)): Das erste Konto
  ist das Notanker-Konto; `OPAA_LOCAL_ADMIN_RESET` bleibt.
- **Identitätsanbieter** ([ADR-0025](../decisions/0025-mehrere-oidc-anbieter.md)): Der erste Anbieter
  kommt aus dem Assistenten.
- **Modelle** ([llm-integration.md](./llm-integration.md)): Alle Modellrollen in der Datenbank;
  `vector_store` entsteht beim Abschluss. Der spätere Wechsel des Embedding-Modells:
  [embedding-model-change.md](./embedding-model-change.md).
- **Mehrinstanzbetrieb** ([ADR-0053](../decisions/0053-mehrinstanzbetrieb-aktiv-aktiv.md)): Zustand in
  der Datenbank, Abschluss und Antwortdatei unter Advisory-Lock.
- **Helm** ([ADR-0042](../decisions/0042-kubernetes-lieferung-mit-helm.md)): `bootstrap` wird zur
  Antwortdatei.
- **Externe KI-Werkzeuge, Anbindungen:** neue Einstellungen in den bestehenden Reitern.

---

## Wirkung auf Behördenunterlagen

- [x] Neue oder geänderte personenbezogene Daten oder Protokollereignisse — Revisionsereignisse für
  Installation und jede Einstellungsänderung
- [ ] Neue Sichtbarkeit: Daten werden für weitere Rollen oder Personen sichtbar
- [x] Neue Auswertung oder neuer Export, auch von bereits gespeicherten Daten — Export der
  Einstellungen (ohne Personenbezug)
- [ ] Geänderte Aufbewahrungs- oder Löschfrist
- [ ] Neuer externer Datenfluss (neuer Dienst, neues Ziel ausgehender Verbindungen)
- [x] Geänderte Voreinstellung, geändertes Standardmodell oder geänderter System-Prompt — Modelle und
  Vorgaben kommen aus dem Assistenten bzw. der Werkseinstellung statt aus der Umgebung
- [ ] Geänderte KI-Einstufung (Zweck oder Einsatzbereich im Sinne der KI-Verordnung)
- [ ] Keine Wirkung auf Daten, Sichtbarkeit, Auswertung, Datenflüsse oder Voreinstellungen

**Voraussichtliche Stufe:** Anzeige

**Betroffene Vorlagen in `docs/behoerden/`:** Betriebs- und Installationsunterlagen (Verzeichnis beim
Umsetzen bestimmen)

---

## Umsetzung in Teilschritten

Je Teilschritt ein Sub-Issue an #3160:

1. Installationsmodus, Token, Installations-API und Antwortdatei mit den Schritten erstes Konto,
   Anmeldeverfahren und Chat-Modell; die drei Seeder entfallen.
2. Browser-Assistent auf dieser API.
3. Embedding-Modell im Assistenten; `vector_store` entsteht beim Abschluss.
4. Fachliche Konfigurationswerte themenweise in die Datenbank (Gruppe E), zuerst Speicherkontingent
   und Vollabgleich-Rhythmus; Rerank- und Transkriptionsmodell in der Modellverwaltung.
5. Revisionsereignisse aller Einstellungen, Export und Import.
6. Helm-Chart, Compose, e2e, Erprobungsaufbau und Handbuch.

---

## Offene Fragen / Zukünftige Erweiterungen

- Experten-Feinabstimmung (Gruppe F) später mit Messung und Leitplanken in der Verwaltung?

---

## Bestandsaufnahme der Konfiguration

Stand `main` 4bf007eb2 (25.10.2026): alle `OPAA_*`-Variablen aus `application.yml` (alle Profile) und
die übrigen Variablen aus `docs/handbuch/betrieb/konfiguration.md` (Compose, Frontend-Image,
Altnamen). Jede Variable steht in genau einer Gruppe. Gruppen A bis D und F bleiben in der
Konfiguration; Gruppe E entfällt oder wandert in die Datenbank.

| Gruppe | Bedeutung | Anzahl |
|---|---|---|
| A | Start und Infrastruktur: nötig, bevor es eine Datenbank gibt, oder vom Betrieb bereitgestellte Dienste | 68 |
| B | Schlüssel und Geheimnisse des Betriebs, die Werte der Datenbank schützen oder Dienste anbinden | 8 |
| C | Grenzen des Betriebs (ADR-0060, Entscheidung 2) | 29 |
| D | Lastgrenzen, Überlastschutz und Technik: Budgets, Größen, Timeouts, Nebenläufigkeit, Endpunkte | 182 |
| E | Fachliche Einstellungen: entfallen oder wandern in die Datenbank | 48 |
| F | Experten-Feinabstimmung von Suche und Index (ADR-0060, Entscheidung 3) | 38 |

Neu hinzu kommen `OPAA_INSTALLATION_ANSWER_FILE` (Gruppe A), `OPAA_INSTALLATION_TOKEN` (Gruppe B),
`OPAA_INSTALLATION_ALLOWED_CIDRS` sowie `OPAA_MODEL_TARGET_VALIDATION_ENABLED` und
`OPAA_MODEL_TARGET_VALIDATION_ALLOWLIST` (Gruppe C, die Adressprüfung der Modelle getrennt von der
der Quellen).

### Gruppe A: Start und Infrastruktur (bleibt in der Konfiguration)

| Variable | Property bzw. Ort |
|---|---|
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | Spring Boot Logging |
| `OPAA_AUTH_DEV_DEFAULT_USER` | `opaa.auth.dev.default-user` |
| `OPAA_AUTH_DEV_ISSUER` | `opaa.auth.dev.issuer` |
| `OPAA_AUTH_LOCAL_COOKIE_SECURE` | `opaa.auth.local.cookie-secure` |
| `OPAA_BACKEND_PORT` | Compose |
| `OPAA_BACKEND_READ_TIMEOUT` | Frontend-nginx |
| `OPAA_BACKEND_RESOLVER` | Frontend-nginx |
| `OPAA_BACKEND_UPSTREAM` | Frontend-nginx |
| `OPAA_BUILD_COMMIT` | Image |
| `OPAA_COMPOSE_FRONTEND_ADDRESS` | Compose |
| `OPAA_COMPOSE_SUBNET` | Compose |
| `OPAA_CORS_ALLOWED_ORIGINS` | `opaa.cors.allowed-origins` |
| `OPAA_CSP_CONNECT_SRC_EXTRA` | Frontend-nginx |
| `OPAA_DB_POOL_SIZE` | `spring.datasource.hikari.maximum-pool-size` |
| `OPAA_DB_PORT` | Compose |
| `OPAA_DB_SCHEMA` | `opaa.database.schema` |
| `OPAA_DB_URL` | `spring.datasource.url` |
| `OPAA_DB_USERNAME` | `spring.datasource.username` |
| `OPAA_DEMO_CHAT_IMPORT_ENABLED` | `opaa.demo.chat-import.enabled` |
| `OPAA_DEMO_CORPUS_PORT` | Compose (Demo) |
| `OPAA_DEMO_MODE` | Compose (Demo) |
| `OPAA_DEMO_OBJECTSTORE_CONSOLE_PORT` | Compose (Demo) |
| `OPAA_DEMO_OBJECTSTORE_PORT` | Compose (Demo) |
| `OPAA_DEMO_PRESSE_PORT` | Compose (Demo) |
| `OPAA_ENV_FILE` | Compose |
| `OPAA_FRONTEND_PORT` | Compose |
| `OPAA_HTTP_FORCE_HTTP1` | `opaa.http.force-http1` |
| `OPAA_INDEXING_DOCUMENT_PATH_HOST` | Compose |
| `OPAA_INSTANCE_DEAD_AFTER` | `opaa.instance.dead-after` |
| `OPAA_INSTANCE_HEARTBEAT_INTERVAL` | `opaa.instance.heartbeat-interval` |
| `OPAA_KEYCLOAK_PORT` | Compose |
| `OPAA_MAILPIT_SMTP_PORT` | Compose |
| `OPAA_MAILPIT_WEB_PORT` | Compose |
| `OPAA_MALWARE_SCAN_ENABLED` | `opaa.malware-scan.enabled` |
| `OPAA_MALWARE_SCAN_HOST` | `opaa.malware-scan.host` |
| `OPAA_MALWARE_SCAN_PORT` | `opaa.malware-scan.port` |
| `OPAA_MALWARE_SCAN_TIMEOUT` | `opaa.malware-scan.timeout` |
| `OPAA_MODE` | Compose |
| `OPAA_PGVECTOR_DISTANCE_TYPE` | `spring.ai.vectorstore.pgvector.distance-type` |
| `OPAA_PUBLIC_BASE_URL` | `opaa.public-base-url` |
| `OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS` | `opaa.rate-limit.trusted-proxy-cidrs` |
| `OPAA_SERVER_ADDRESS` | `server.address` |
| `OPAA_SHUTDOWN_TIMEOUT` | `spring.lifecycle.timeout-per-shutdown-phase` |
| `OPAA_SIEM_EXPORT_BATCH_SIZE` | `opaa.siem-export.batch-size` |
| `OPAA_SIEM_EXPORT_ENABLED` | `opaa.siem-export.enabled` |
| `OPAA_SIEM_EXPORT_HOST` | `opaa.siem-export.host` |
| `OPAA_SIEM_EXPORT_INCLUDE_LABELS` | `opaa.siem-export.include-labels` |
| `OPAA_SIEM_EXPORT_PORT` | `opaa.siem-export.port` |
| `OPAA_SIEM_EXPORT_SETTLE_DELAY` | `opaa.siem-export.settle-delay` |
| `OPAA_SIEM_EXPORT_TIMEOUT` | `opaa.siem-export.timeout` |
| `OPAA_SIEM_EXPORT_TLS` | `opaa.siem-export.tls` |
| `OPAA_SPIKE_TOOL_LOOP_ENABLED` | `opaa.spike.tool-loop.enabled` |
| `OPAA_STOP_GRACE_PERIOD` | Compose |
| `OPAA_UPLOAD_S3_BUCKET` | `opaa.upload.s3.bucket` |
| `OPAA_UPLOAD_S3_ENDPOINT` | `opaa.upload.s3.endpoint` |
| `OPAA_UPLOAD_S3_KEY_PREFIX` | `opaa.upload.s3.key-prefix` |
| `OPAA_UPLOAD_S3_PATH_STYLE` | `opaa.upload.s3.path-style` |
| `OPAA_UPLOAD_S3_REGION` | `opaa.upload.s3.region` |
| `OPAA_UPLOAD_S3_TEMP_DIRECTORY` | `opaa.upload.s3.temp-directory` |
| `OPAA_UPLOAD_STORAGE_PATH` | `opaa.upload.storage-path` |
| `OPAA_UPLOAD_STORAGE_PATH_HOST` | Compose |
| `OPAA_UPLOAD_STORE` | `opaa.upload.store` |
| `OPAA_UPLOAD_STORE_BUCKET` | Compose |
| `OPAA_UPLOAD_STORE_CONSOLE_PORT` | Compose |
| `OPAA_UPLOAD_STORE_PORT` | Compose |
| `SPRING_FLYWAY_ENABLED` | Spring Boot |
| `SPRING_PROFILES_ACTIVE` | Spring Boot |
| `TZ` | Container |

### Gruppe B: Schlüssel und Geheimnisse des Betriebs (bleibt)

| Variable | Property bzw. Ort |
|---|---|
| `OPAA_AUTH_JWT_SECRET` | `opaa.auth.local.jwt-secret` |
| `OPAA_CREDENTIALS_ENCRYPTION_KEY` | `opaa.security.credentials.encryption-key` |
| `OPAA_DB_PASSWORD` | `spring.datasource.password` |
| `OPAA_SETTINGS_ENCRYPTION_KEY` | `opaa.security.settings.encryption-key` |
| `OPAA_UPLOAD_S3_ACCESS_KEY` | `opaa.upload.s3.access-key` |
| `OPAA_UPLOAD_S3_SECRET_KEY` | `opaa.upload.s3.secret-key` |
| `OPAA_UPLOAD_STORE_ROOT_PASSWORD` | Compose |
| `OPAA_UPLOAD_STORE_ROOT_USER` | Compose |

### Gruppe C: Grenzen des Betriebs (bleibt, ADR-0060, Entscheidung 2)

| Variable | Property bzw. Ort |
|---|---|
| `OPAA_AUTH_LOCAL_ACCESS_TOKEN_TTL` | `opaa.auth.local.access-token-ttl` |
| `OPAA_AUTH_LOCAL_ADMIN_REFRESH_TOKEN_TTL` | `opaa.auth.local.admin-refresh-token-ttl` |
| `OPAA_AUTH_LOCAL_ADMIN_SESSION_MAX_LIFETIME` | `opaa.auth.local.admin-session-max-lifetime` |
| `OPAA_AUTH_LOCAL_LOCKOUT_DURATION` | `opaa.auth.local.lockout.duration` |
| `OPAA_AUTH_LOCAL_LOCKOUT_MAX_ATTEMPTS` | `opaa.auth.local.lockout.max-attempts` |
| `OPAA_AUTH_LOCAL_REFRESH_GRACE_PERIOD` | `opaa.auth.local.refresh-grace-period` |
| `OPAA_AUTH_LOCAL_REFRESH_TOKEN_TTL` | `opaa.auth.local.refresh-token-ttl` |
| `OPAA_AUTH_LOCAL_SESSION_MAX_LIFETIME` | `opaa.auth.local.session-max-lifetime` |
| `OPAA_AUTH_RECENT_LOGIN_MAX_AGE` | `opaa.auth.recent-login.max-age` |
| `OPAA_EXTERNAL_ACCESS_MAX_RELEASE_DAYS` | `opaa.external-access.max-release-days` |
| `OPAA_FEATURES_EXTERNAL_ACCESS_ENABLED` | `opaa.features.external-access.enabled` |
| `OPAA_FEATURES_METADATA_ENABLED` | `opaa.features.metadata.enabled` |
| `OPAA_FEATURES_PROMPT_LIBRARIES_ENABLED` | `opaa.features.prompt-libraries.enabled` |
| `OPAA_FEATURES_SPEECH_INPUT_ENABLED` | `opaa.features.speech-input.enabled` |
| `OPAA_INDEXING_FILESYSTEM_ALLOWLIST` | `opaa.indexing.filesystem.allowlist` |
| `OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST` | `opaa.indexing.target-validation.allowlist` |
| `OPAA_INDEXING_TARGET_VALIDATION_ENABLED` | `opaa.indexing.target-validation.enabled` |
| `OPAA_LIBRARY_QUOTA_BYTES` | `opaa.library.quota-bytes` |
| `OPAA_LOCAL_ADMIN_ALLOWED_CIDRS` | `opaa.auth.local.admin-allowed-cidrs` |
| `OPAA_LOCAL_ADMIN_RESET` | `opaa.auth.local.admin-reset` |
| `OPAA_MALWARE_SCAN_ON_UNAVAILABLE` | `opaa.malware-scan.on-unavailable` |
| `OPAA_MINIMUM_GROUP_SIZE` | `opaa.permission.minimum-group-size` |
| `OPAA_MODEL_TARGET_VALIDATION_ALLOWLIST` | `opaa.model.target-validation.allowlist` |
| `OPAA_MODEL_TARGET_VALIDATION_ENABLED` | `opaa.model.target-validation.enabled` |
| `OPAA_OIDC_TARGET_VALIDATION_ALLOWLIST` | `opaa.auth.oidc.target-validation.allowlist` |
| `OPAA_OIDC_TARGET_VALIDATION_ENABLED` | `opaa.auth.oidc.target-validation.enabled` |
| `OPAA_UPLOAD_MAX_FILE_SIZE` | `spring.servlet.multipart.max-file-size` |
| `OPAA_UPLOAD_S3_TARGET_VALIDATION_ALLOWLIST` | `opaa.upload.s3.target-validation.allowlist` |
| `OPAA_UPLOAD_S3_TARGET_VALIDATION_ENABLED` | `opaa.upload.s3.target-validation.enabled` |

### Gruppe D: Lastgrenzen, Überlastschutz und Technik (bleibt)

| Variable | Property bzw. Ort |
|---|---|
| `OPAA_CHAT_NOTE_MAX_ITEMS` | `opaa.chat.note.max-items` |
| `OPAA_DIRECTORY_SYNC_KEYCLOAK_CONNECT_TIMEOUT` | `opaa.directory-sync.keycloak.connect-timeout` |
| `OPAA_DIRECTORY_SYNC_KEYCLOAK_MAX_ACCOUNTS` | `opaa.directory-sync.keycloak.max-accounts` |
| `OPAA_DIRECTORY_SYNC_KEYCLOAK_MAX_GROUPS` | `opaa.directory-sync.keycloak.max-groups` |
| `OPAA_DIRECTORY_SYNC_KEYCLOAK_MAX_MEMBERS_PER_GROUP` | `opaa.directory-sync.keycloak.max-members-per-group` |
| `OPAA_DIRECTORY_SYNC_KEYCLOAK_PAGE_SIZE` | `opaa.directory-sync.keycloak.page-size` |
| `OPAA_DIRECTORY_SYNC_KEYCLOAK_REQUEST_TIMEOUT` | `opaa.directory-sync.keycloak.request-timeout` |
| `OPAA_DIRECTORY_SYNC_SCHEDULE_ENABLED` | `opaa.directory-sync.schedule-enabled` |
| `OPAA_DOCUMENTS_ATTACHMENT_EXTRACTION_ACQUIRE_TIMEOUT` | `opaa.documents.attachment-extraction.acquire-timeout` |
| `OPAA_DOCUMENTS_ATTACHMENT_EXTRACTION_MAX_CONCURRENT` | `opaa.documents.attachment-extraction.max-concurrent` |
| `OPAA_DOCUMENTS_REMOTE_CONTENT_MAX_BYTES` | `opaa.documents.remote-content.max-bytes` |
| `OPAA_DOCUMENTS_REMOTE_CONTENT_TIMEOUT_SECONDS` | `opaa.documents.remote-content.timeout-seconds` |
| `OPAA_DOCUMENTS_REMOTE_CONTENT_TRANSFER_TIMEOUT_SECONDS` | `opaa.documents.remote-content.transfer-timeout-seconds` |
| `OPAA_INDEXING_ATTACHMENTS_MAX_DEPTH` | `opaa.indexing.attachments.max-depth` |
| `OPAA_INDEXING_ATTACHMENTS_MAX_PER_PARENT` | `opaa.indexing.attachments.max-per-parent` |
| `OPAA_INDEXING_ATTACHMENTS_MAX_SIZE_BYTES` | `opaa.indexing.attachments.max-size-bytes` |
| `OPAA_INDEXING_BATCH_SIZE` | `opaa.indexing.batch-size` |
| `OPAA_INDEXING_CONFLUENCE_DETECTION_TIMEOUT` | `opaa.indexing.confluence.detection-timeout` |
| `OPAA_INDEXING_CONFLUENCE_INCREMENTAL_OVERLAP` | `opaa.indexing.confluence.incremental-overlap` |
| `OPAA_INDEXING_CONFLUENCE_MAX_ATTACHMENT_SIZE_BYTES` | `opaa.indexing.confluence.max-attachment-size-bytes` |
| `OPAA_INDEXING_CONFLUENCE_MAX_LISTING_PAGES` | `opaa.indexing.confluence.max-listing-pages` |
| `OPAA_INDEXING_CONFLUENCE_MAX_RATE_LIMIT_RETRIES` | `opaa.indexing.confluence.max-rate-limit-retries` |
| `OPAA_INDEXING_CONFLUENCE_MAX_RESPONSE_BYTES` | `opaa.indexing.confluence.max-response-bytes` |
| `OPAA_INDEXING_CONFLUENCE_MAX_RETRY_AFTER` | `opaa.indexing.confluence.max-retry-after` |
| `OPAA_INDEXING_CONFLUENCE_PAGE_SIZE` | `opaa.indexing.confluence.page-size` |
| `OPAA_INDEXING_CONFLUENCE_REQUEST_BUDGET_PER_RUN` | `opaa.indexing.confluence.request-budget-per-run` |
| `OPAA_INDEXING_CONFLUENCE_REQUEST_TIMEOUT` | `opaa.indexing.confluence.request-timeout` |
| `OPAA_INDEXING_CONFLUENCE_WEBHOOK_DEBOUNCE` | `opaa.indexing.confluence.webhook.debounce` |
| `OPAA_INDEXING_CONFLUENCE_WEBHOOK_MAX_DEFERRALS` | `opaa.indexing.confluence.webhook.max-deferrals` |
| `OPAA_INDEXING_CONFLUENCE_WEBHOOK_MAX_PENDING_PAGES` | `opaa.indexing.confluence.webhook.max-pending-pages` |
| `OPAA_INDEXING_CRAWL_MAX_DEPTH` | `opaa.indexing.crawl.max-depth` |
| `OPAA_INDEXING_CRAWL_MAX_ENTRIES` | `opaa.indexing.crawl.max-entries` |
| `OPAA_INDEXING_CRAWL_MAX_FILE_SIZE_BYTES` | `opaa.indexing.crawl.max-file-size-bytes` |
| `OPAA_INDEXING_DROPBOX_API_BASE` | `opaa.indexing.dropbox.api-base` |
| `OPAA_INDEXING_DROPBOX_AUTHORIZATION_ENDPOINT` | `opaa.indexing.dropbox.authorization-endpoint` |
| `OPAA_INDEXING_DROPBOX_CONTENT_BASE` | `opaa.indexing.dropbox.content-base` |
| `OPAA_INDEXING_DROPBOX_DOWNLOAD_CONCURRENCY` | `opaa.indexing.dropbox.download-concurrency` |
| `OPAA_INDEXING_DROPBOX_DOWNLOAD_TIMEOUT` | `opaa.indexing.dropbox.download-timeout` |
| `OPAA_INDEXING_DROPBOX_MAX_FILES_PER_RUN` | `opaa.indexing.dropbox.max-files-per-run` |
| `OPAA_INDEXING_DROPBOX_MAX_FILE_SIZE_BYTES` | `opaa.indexing.dropbox.max-file-size-bytes` |
| `OPAA_INDEXING_DROPBOX_MAX_RETRIES` | `opaa.indexing.dropbox.max-retries` |
| `OPAA_INDEXING_DROPBOX_MAX_RETRY_WAIT` | `opaa.indexing.dropbox.max-retry-wait` |
| `OPAA_INDEXING_DROPBOX_PAGE_SIZE` | `opaa.indexing.dropbox.page-size` |
| `OPAA_INDEXING_DROPBOX_REQUEST_BUDGET_PER_RUN` | `opaa.indexing.dropbox.request-budget-per-run` |
| `OPAA_INDEXING_DROPBOX_REQUEST_TIMEOUT` | `opaa.indexing.dropbox.request-timeout` |
| `OPAA_INDEXING_DROPBOX_REVOCATION_ENDPOINT` | `opaa.indexing.dropbox.revocation-endpoint` |
| `OPAA_INDEXING_DROPBOX_TOKEN_ENDPOINT` | `opaa.indexing.dropbox.token-endpoint` |
| `OPAA_INDEXING_EMBEDDING_CONCURRENCY` | `opaa.indexing.embedding-concurrency` |
| `OPAA_INDEXING_EMBEDDING_RATE_ESTIMATE` | `opaa.indexing.embedding-rate-estimate` |
| `OPAA_INDEXING_EVENTS_DEBOUNCE` | `opaa.indexing.events.debounce` |
| `OPAA_INDEXING_EVENTS_MAX_DEFERRALS` | `opaa.indexing.events.max-deferrals` |
| `OPAA_INDEXING_EVENTS_MAX_PENDING_KEYS` | `opaa.indexing.events.max-pending-keys` |
| `OPAA_INDEXING_GOOGLE_DRIVE_DOWNLOAD_CONCURRENCY` | `opaa.indexing.google-drive.download-concurrency` |
| `OPAA_INDEXING_GOOGLE_DRIVE_DOWNLOAD_TIMEOUT` | `opaa.indexing.google-drive.download-timeout` |
| `OPAA_INDEXING_GOOGLE_DRIVE_MAX_FILES_PER_RUN` | `opaa.indexing.google-drive.max-files-per-run` |
| `OPAA_INDEXING_GOOGLE_DRIVE_MAX_FILE_SIZE_BYTES` | `opaa.indexing.google-drive.max-file-size-bytes` |
| `OPAA_INDEXING_GOOGLE_DRIVE_MAX_RETRIES` | `opaa.indexing.google-drive.max-retries` |
| `OPAA_INDEXING_GOOGLE_DRIVE_PAGE_SIZE` | `opaa.indexing.google-drive.page-size` |
| `OPAA_INDEXING_GOOGLE_DRIVE_REQUEST_BUDGET_PER_RUN` | `opaa.indexing.google-drive.request-budget-per-run` |
| `OPAA_INDEXING_GOOGLE_DRIVE_REQUEST_TIMEOUT` | `opaa.indexing.google-drive.request-timeout` |
| `OPAA_INDEXING_GOOGLE_DRIVE_RETRY_BACKOFF` | `opaa.indexing.google-drive.retry-backoff` |
| `OPAA_INDEXING_HTTP_MAX_RATE_LIMIT_RETRIES` | `opaa.indexing.http.max-rate-limit-retries` |
| `OPAA_INDEXING_HTTP_MAX_RATE_LIMIT_WAIT_PER_RUN` | `opaa.indexing.http.max-rate-limit-wait-per-run` |
| `OPAA_INDEXING_HTTP_MAX_RETRY_AFTER` | `opaa.indexing.http.max-retry-after` |
| `OPAA_INDEXING_HTTP_REQUEST_BUDGET_PER_RUN` | `opaa.indexing.http.request-budget-per-run` |
| `OPAA_INDEXING_HTTP_USER_AGENT` | `opaa.indexing.http.user-agent` |
| `OPAA_INDEXING_MAIL_MAX_ATTACHMENTS_PER_MESSAGE` | `opaa.indexing.mail.max-attachments-per-message` |
| `OPAA_INDEXING_MAIL_MAX_ATTACHMENT_BYTES` | `opaa.indexing.mail.max-attachment-bytes` |
| `OPAA_INDEXING_MAIL_MAX_MESSAGE_BYTES` | `opaa.indexing.mail.max-message-bytes` |
| `OPAA_INDEXING_NEXTCLOUD_DOWNLOAD_CONCURRENCY` | `opaa.indexing.nextcloud.download-concurrency` |
| `OPAA_INDEXING_NEXTCLOUD_MAX_ENTRIES_PER_RUN` | `opaa.indexing.nextcloud.max-entries-per-run` |
| `OPAA_INDEXING_NEXTCLOUD_MAX_FILE_SIZE_BYTES` | `opaa.indexing.nextcloud.max-file-size-bytes` |
| `OPAA_INDEXING_NEXTCLOUD_MAX_RESPONSE_BYTES` | `opaa.indexing.nextcloud.max-response-bytes` |
| `OPAA_INDEXING_NEXTCLOUD_REQUEST_BUDGET_PER_RUN` | `opaa.indexing.nextcloud.request-budget-per-run` |
| `OPAA_INDEXING_NEXTCLOUD_REQUEST_TIMEOUT` | `opaa.indexing.nextcloud.request-timeout` |
| `OPAA_INDEXING_ODF_MAX_CONTENT_XML_BYTES` | `opaa.indexing.odf.max-content-xml-bytes` |
| `OPAA_INDEXING_ODF_MAX_ODP_SLIDES` | `opaa.indexing.odf.max-odp-slides` |
| `OPAA_INDEXING_ODF_MAX_ODT_PARAGRAPHS` | `opaa.indexing.odf.max-odt-paragraphs` |
| `OPAA_INDEXING_ODF_MAX_SPACE_REPEAT` | `opaa.indexing.odf.max-space-repeat` |
| `OPAA_INDEXING_ODF_MAX_TEXT_CHARACTERS` | `opaa.indexing.odf.max-text-characters` |
| `OPAA_INDEXING_PARSER_LIMITS_MAX_OOXML_PART_BYTES` | `opaa.indexing.parser-limits.max-ooxml-part-bytes` |
| `OPAA_INDEXING_PARSER_LIMITS_MAX_OOXML_TOTAL_BYTES` | `opaa.indexing.parser-limits.max-ooxml-total-bytes` |
| `OPAA_INDEXING_PARSER_LIMITS_MAX_PDF_CONTENT_BYTES` | `opaa.indexing.parser-limits.max-pdf-content-bytes` |
| `OPAA_INDEXING_PARSER_LIMITS_MAX_PDF_OPERATORS` | `opaa.indexing.parser-limits.max-pdf-operators` |
| `OPAA_INDEXING_PARSER_LIMITS_MAX_PDF_PARSE_SECONDS` | `opaa.indexing.parser-limits.max-pdf-parse-seconds` |
| `OPAA_INDEXING_PARSER_LIMITS_MAX_TEXT_CHARACTERS` | `opaa.indexing.parser-limits.max-text-characters` |
| `OPAA_INDEXING_RSS_MAX_ATTACHMENTS_PER_ENTRY` | `opaa.indexing.rss.max-attachments-per-entry` |
| `OPAA_INDEXING_RSS_MAX_ATTACHMENT_SIZE_BYTES` | `opaa.indexing.rss.max-attachment-size-bytes` |
| `OPAA_INDEXING_RSS_MAX_ENTRIES` | `opaa.indexing.rss.max-entries` |
| `OPAA_INDEXING_RSS_MAX_FEED_SIZE_BYTES` | `opaa.indexing.rss.max-feed-size-bytes` |
| `OPAA_INDEXING_RSS_MAX_PAGE_SIZE_BYTES` | `opaa.indexing.rss.max-page-size-bytes` |
| `OPAA_INDEXING_RSS_REQUEST_DELAY_MS` | `opaa.indexing.rss.request-delay-ms` |
| `OPAA_INDEXING_RSS_USER_AGENT` | `opaa.indexing.rss.user-agent` |
| `OPAA_INDEXING_S3_DOWNLOAD_CONCURRENCY` | `opaa.indexing.s3.download-concurrency` |
| `OPAA_INDEXING_S3_LIST_PAGE_SIZE` | `opaa.indexing.s3.list-page-size` |
| `OPAA_INDEXING_S3_MAX_OBJECTS_PER_RUN` | `opaa.indexing.s3.max-objects-per-run` |
| `OPAA_INDEXING_S3_MAX_OBJECT_SIZE_BYTES` | `opaa.indexing.s3.max-object-size-bytes` |
| `OPAA_INDEXING_S3_MAX_RETRIES` | `opaa.indexing.s3.max-retries` |
| `OPAA_INDEXING_S3_REQUEST_BUDGET_PER_RUN` | `opaa.indexing.s3.request-budget-per-run` |
| `OPAA_INDEXING_S3_REQUEST_TIMEOUT` | `opaa.indexing.s3.request-timeout` |
| `OPAA_INDEXING_S3_RETRY_BACKOFF` | `opaa.indexing.s3.retry-backoff` |
| `OPAA_INDEXING_S3_TEMP_DIRECTORY` | `opaa.indexing.s3.temp-directory` |
| `OPAA_INDEXING_SHAREPOINT_DOWNLOAD_CONCURRENCY` | `opaa.indexing.sharepoint.download-concurrency` |
| `OPAA_INDEXING_SHAREPOINT_DOWNLOAD_TIMEOUT` | `opaa.indexing.sharepoint.download-timeout` |
| `OPAA_INDEXING_SHAREPOINT_MAX_FILES_PER_RUN` | `opaa.indexing.sharepoint.max-files-per-run` |
| `OPAA_INDEXING_SHAREPOINT_MAX_FILE_SIZE_BYTES` | `opaa.indexing.sharepoint.max-file-size-bytes` |
| `OPAA_INDEXING_SHAREPOINT_MAX_RETRIES` | `opaa.indexing.sharepoint.max-retries` |
| `OPAA_INDEXING_SHAREPOINT_MAX_RETRY_WAIT` | `opaa.indexing.sharepoint.max-retry-wait` |
| `OPAA_INDEXING_SHAREPOINT_MAX_THROTTLE_WAIT_PER_RUN` | `opaa.indexing.sharepoint.max-throttle-wait-per-run` |
| `OPAA_INDEXING_SHAREPOINT_PAGE_SIZE` | `opaa.indexing.sharepoint.page-size` |
| `OPAA_INDEXING_SHAREPOINT_REQUEST_BUDGET_PER_RUN` | `opaa.indexing.sharepoint.request-budget-per-run` |
| `OPAA_INDEXING_SHAREPOINT_REQUEST_TIMEOUT` | `opaa.indexing.sharepoint.request-timeout` |
| `OPAA_INDEXING_SMB_DOWNLOAD_CONCURRENCY` | `opaa.indexing.smb.download-concurrency` |
| `OPAA_INDEXING_SMB_LIST_PAGE_SIZE` | `opaa.indexing.smb.list-page-size` |
| `OPAA_INDEXING_SMB_MAX_ENTRIES_PER_RUN` | `opaa.indexing.smb.max-entries-per-run` |
| `OPAA_INDEXING_SMB_MAX_FILE_SIZE_BYTES` | `opaa.indexing.smb.max-file-size-bytes` |
| `OPAA_INDEXING_SMB_REQUEST_BUDGET_PER_RUN` | `opaa.indexing.smb.request-budget-per-run` |
| `OPAA_INDEXING_SMB_REQUEST_TIMEOUT` | `opaa.indexing.smb.request-timeout` |
| `OPAA_INDEXING_STALE_JOB_TIMEOUT` | `opaa.indexing.stale-job-timeout` |
| `OPAA_INDEXING_TABULAR_MAX_ODS_CELL_REPEAT` | `opaa.indexing.tabular.max-ods-cell-repeat` |
| `OPAA_INDEXING_TABULAR_MAX_ODS_CONTENT_XML_BYTES` | `opaa.indexing.tabular.max-ods-content-xml-bytes` |
| `OPAA_INDEXING_TABULAR_MAX_ODS_ROWS` | `opaa.indexing.tabular.max-ods-rows` |
| `OPAA_INDEXING_TABULAR_MAX_ROW_COLUMNS` | `opaa.indexing.tabular.max-row-columns` |
| `OPAA_INDEXING_THREAD_POOL_CORE_SIZE` | `opaa.indexing.thread-pool.core-size` |
| `OPAA_INDEXING_THREAD_POOL_MAX_SIZE` | `opaa.indexing.thread-pool.max-size` |
| `OPAA_INDEXING_THREAD_POOL_QUEUE_CAPACITY` | `opaa.indexing.thread-pool.queue-capacity` |
| `OPAA_MAIL_CONNECT_TIMEOUT` | `opaa.mail.connect-timeout` |
| `OPAA_MAIL_READ_TIMEOUT` | `opaa.mail.read-timeout` |
| `OPAA_MAIL_WRITE_TIMEOUT` | `opaa.mail.write-timeout` |
| `OPAA_OPENAI_EMBEDDING_TIMEOUT` | `spring.ai.openai.embedding.timeout` |
| `OPAA_RATE_LIMIT_CHAT_SEARCH_GLOBAL_MAX_REQUESTS` | `opaa.rate-limit.chat-search.global-max-requests` |
| `OPAA_RATE_LIMIT_CHAT_SEARCH_MAX_REQUESTS` | `opaa.rate-limit.chat-search.max-requests` |
| `OPAA_RATE_LIMIT_CHAT_SEARCH_MAX_REQUESTS_PER_ACCOUNT` | `opaa.rate-limit.chat-search.max-requests-per-account` |
| `OPAA_RATE_LIMIT_CHAT_SEARCH_WINDOW_SECONDS` | `opaa.rate-limit.chat-search.window-seconds` |
| `OPAA_RATE_LIMIT_DOCUMENT_CONTENT_GLOBAL_MAX_REQUESTS` | `opaa.rate-limit.document-content.global-max-requests` |
| `OPAA_RATE_LIMIT_DOCUMENT_CONTENT_MAX_REQUESTS` | `opaa.rate-limit.document-content.max-requests` |
| `OPAA_RATE_LIMIT_DOCUMENT_CONTENT_WINDOW_SECONDS` | `opaa.rate-limit.document-content.window-seconds` |
| `OPAA_RATE_LIMIT_ENABLED` | `opaa.rate-limit.enabled` |
| `OPAA_RATE_LIMIT_INDEXING_GLOBAL_MAX_REQUESTS` | `opaa.rate-limit.indexing.global-max-requests` |
| `OPAA_RATE_LIMIT_INDEXING_MAX_REQUESTS` | `opaa.rate-limit.indexing.max-requests` |
| `OPAA_RATE_LIMIT_INDEXING_WINDOW_SECONDS` | `opaa.rate-limit.indexing.window-seconds` |
| `OPAA_RATE_LIMIT_LOCAL_AUTH_CHANGE_PASSWORD_MAX_REQUESTS` | `opaa.rate-limit.local-auth.change-password.max-requests` |
| `OPAA_RATE_LIMIT_LOCAL_AUTH_CHANGE_PASSWORD_WINDOW_SECONDS` | `opaa.rate-limit.local-auth.change-password.window-seconds` |
| `OPAA_RATE_LIMIT_LOCAL_AUTH_FORGOT_PASSWORD_GLOBAL_MAX_REQUESTS` | `opaa.rate-limit.local-auth.forgot-password.global-max-requests` |
| `OPAA_RATE_LIMIT_LOCAL_AUTH_FORGOT_PASSWORD_MAX_REQUESTS` | `opaa.rate-limit.local-auth.forgot-password.max-requests` |
| `OPAA_RATE_LIMIT_LOCAL_AUTH_FORGOT_PASSWORD_MAX_REQUESTS_PER_ADDRESS` | `opaa.rate-limit.local-auth.forgot-password.max-requests-per-address` |
| `OPAA_RATE_LIMIT_LOCAL_AUTH_FORGOT_PASSWORD_WINDOW_SECONDS` | `opaa.rate-limit.local-auth.forgot-password.window-seconds` |
| `OPAA_RATE_LIMIT_LOCAL_AUTH_HANDOVER_MAX_REQUESTS` | `opaa.rate-limit.local-auth.handover.max-requests` |
| `OPAA_RATE_LIMIT_LOCAL_AUTH_HANDOVER_WINDOW_SECONDS` | `opaa.rate-limit.local-auth.handover.window-seconds` |
| `OPAA_RATE_LIMIT_LOCAL_AUTH_LOGIN_GLOBAL_MAX_REQUESTS` | `opaa.rate-limit.local-auth.login.global-max-requests` |
| `OPAA_RATE_LIMIT_LOCAL_AUTH_LOGIN_MAX_REQUESTS` | `opaa.rate-limit.local-auth.login.max-requests` |
| `OPAA_RATE_LIMIT_LOCAL_AUTH_LOGIN_WINDOW_SECONDS` | `opaa.rate-limit.local-auth.login.window-seconds` |
| `OPAA_RATE_LIMIT_LOCAL_AUTH_REFRESH_MAX_REQUESTS` | `opaa.rate-limit.local-auth.refresh.max-requests` |
| `OPAA_RATE_LIMIT_LOCAL_AUTH_REFRESH_WINDOW_SECONDS` | `opaa.rate-limit.local-auth.refresh.window-seconds` |
| `OPAA_RATE_LIMIT_LOCAL_AUTH_REGISTER_GLOBAL_MAX_REQUESTS` | `opaa.rate-limit.local-auth.register.global-max-requests` |
| `OPAA_RATE_LIMIT_LOCAL_AUTH_REGISTER_MAX_REQUESTS` | `opaa.rate-limit.local-auth.register.max-requests` |
| `OPAA_RATE_LIMIT_LOCAL_AUTH_REGISTER_MAX_REQUESTS_PER_ADDRESS` | `opaa.rate-limit.local-auth.register.max-requests-per-address` |
| `OPAA_RATE_LIMIT_LOCAL_AUTH_REGISTER_WINDOW_SECONDS` | `opaa.rate-limit.local-auth.register.window-seconds` |
| `OPAA_RATE_LIMIT_LOCAL_AUTH_SET_PASSWORD_MAX_REQUESTS` | `opaa.rate-limit.local-auth.set-password.max-requests` |
| `OPAA_RATE_LIMIT_LOCAL_AUTH_SET_PASSWORD_WINDOW_SECONDS` | `opaa.rate-limit.local-auth.set-password.window-seconds` |
| `OPAA_RATE_LIMIT_LOCAL_AUTH_VERIFY_EMAIL_MAX_REQUESTS` | `opaa.rate-limit.local-auth.verify-email.max-requests` |
| `OPAA_RATE_LIMIT_LOCAL_AUTH_VERIFY_EMAIL_WINDOW_SECONDS` | `opaa.rate-limit.local-auth.verify-email.window-seconds` |
| `OPAA_RATE_LIMIT_QUERY_GLOBAL_MAX_REQUESTS` | `opaa.rate-limit.query.global-max-requests` |
| `OPAA_RATE_LIMIT_QUERY_MAX_REQUESTS` | `opaa.rate-limit.query.max-requests` |
| `OPAA_RATE_LIMIT_QUERY_MAX_REQUESTS_PER_ACCOUNT` | `opaa.rate-limit.query.max-requests-per-account` |
| `OPAA_RATE_LIMIT_QUERY_WINDOW_SECONDS` | `opaa.rate-limit.query.window-seconds` |
| `OPAA_RATE_LIMIT_SEARCH_GLOBAL_MAX_REQUESTS` | `opaa.rate-limit.search.global-max-requests` |
| `OPAA_RATE_LIMIT_SEARCH_MAX_REQUESTS` | `opaa.rate-limit.search.max-requests` |
| `OPAA_RATE_LIMIT_SEARCH_MAX_REQUESTS_PER_ACCOUNT` | `opaa.rate-limit.search.max-requests-per-account` |
| `OPAA_RATE_LIMIT_SEARCH_WINDOW_SECONDS` | `opaa.rate-limit.search.window-seconds` |
| `OPAA_RATE_LIMIT_SOURCE_TEST_GLOBAL_MAX_REQUESTS` | `opaa.rate-limit.source-test.global-max-requests` |
| `OPAA_RATE_LIMIT_SOURCE_TEST_MAX_REQUESTS` | `opaa.rate-limit.source-test.max-requests` |
| `OPAA_RATE_LIMIT_SOURCE_TEST_WINDOW_SECONDS` | `opaa.rate-limit.source-test.window-seconds` |
| `OPAA_RATE_LIMIT_WEBHOOK_GLOBAL_MAX_REQUESTS` | `opaa.rate-limit.webhook.global-max-requests` |
| `OPAA_RATE_LIMIT_WEBHOOK_MAX_REQUESTS` | `opaa.rate-limit.webhook.max-requests` |
| `OPAA_RATE_LIMIT_WEBHOOK_WINDOW_SECONDS` | `opaa.rate-limit.webhook.window-seconds` |
| `OPAA_RERANK_PROBE_INTERVAL` | `opaa.rerank.probe-interval` |
| `OPAA_RERANK_TIMEOUT` | `opaa.rerank.timeout` |
| `OPAA_SELF_DISCLOSURE_MAX_CONCURRENT_EXPORTS` | `opaa.self-disclosure.max-concurrent-exports` |
| `OPAA_SELF_DISCLOSURE_MAX_DURATION` | `opaa.self-disclosure.max-duration` |
| `OPAA_SUCCESSION_DETECTION_CRON` | `opaa.succession.detection-cron` |
| `OPAA_TRANSCRIPTION_MAX_AUDIO_BYTES` | `opaa.transcription.max-audio-bytes` |
| `OPAA_TRANSCRIPTION_MAX_CONCURRENT` | `opaa.transcription.max-concurrent` |
| `OPAA_TRANSCRIPTION_TIMEOUT` | `opaa.transcription.timeout` |
| `OPAA_UPLOAD_ORPHAN_GRACE_MINUTES` | `opaa.upload.orphan-grace-minutes` |
| `OPAA_UPLOAD_THREAD_POOL_CORE_SIZE` | `opaa.upload.thread-pool.core-size` |
| `OPAA_UPLOAD_THREAD_POOL_MAX_SIZE` | `opaa.upload.thread-pool.max-size` |
| `OPAA_UPLOAD_THREAD_POOL_QUEUE_CAPACITY` | `opaa.upload.thread-pool.queue-capacity` |

### Gruppe E: Fachliche Einstellungen (wandert in die Datenbank)

| Variable | Heute | Künftig |
|---|---|---|
| `OPAA_AI_CHAT_PROVIDER` | `LlmModelSeeder (Altname)` | Altname entfällt |
| `OPAA_AUDIT_INCIDENT_SCOPE_RETENTION_YEARS` | `opaa.audit.incident-scope-retention-years` | Vorgabe im Code; `audit_incident_scope_retention_settings`, Grenzen 1 bis 10 Jahre im Code |
| `OPAA_CHAT_AUTO_CLEANUP_ARCHIVE_AFTER_DAYS` | `opaa.chat.auto-cleanup.archive-after-days` | Vorgabe im Code; `chat_auto_cleanup_settings` |
| `OPAA_CHAT_AUTO_CLEANUP_DELETE_AFTER_DAYS` | `opaa.chat.auto-cleanup.delete-after-days` | Vorgabe im Code; `chat_auto_cleanup_settings` |
| `OPAA_CHAT_LOCKED_ACCOUNT_CLEANUP_DELETE_AFTER_DAYS` | `opaa.chat.locked-account-cleanup.delete-after-days` | Vorgabe im Code; `locked_account_chat_cleanup_settings` |
| `OPAA_CHAT_LOCKED_ACCOUNT_CLEANUP_ENABLED` | `opaa.chat.locked-account-cleanup.enabled` | Vorgabe im Code; `locked_account_chat_cleanup_settings` |
| `OPAA_CONNECTION_EXPIRED_WARNING_THRESHOLD` | `opaa.connection.expired-connections.warning-threshold` | Anbindungen → Einstellungen; Werkseinstellung 10, Grenzen 1 bis 1000 im Code (`connection_lifecycle_settings`) |
| `OPAA_CONNECTION_INACTIVITY_THRESHOLD_DAYS` | `opaa.connection.inactivity-threshold-days` | Anbindungen → Einstellungen; Werkseinstellung 90, Grenzen 30 bis 365 Tage im Code |
| `OPAA_CONNECTION_PRIVATE_LIBRARY_DELETION_DAYS` | `opaa.connection.private-library-deletion-days` | Anbindungen → Einstellungen; Werkseinstellung 30, Grenzen 1 bis 90 Tage im Code |
| `OPAA_DIRECTORY_SYNC_CHANGE_THRESHOLD_FRACTION` | `opaa.directory-sync.change-threshold-fraction` | Vorgabe im Code; Verzeichnisabgleich, installationsweit in Prozent (`directory_sync_settings`), nicht je Anbieter: die Anbieter gehen nicht in die Übertragung der Einstellungen |
| `OPAA_EXTERNAL_ACCESS_REMINDER_LEAD_DAYS` | `opaa.external-access.reminder-lead-days` | Vorgabe im Code; Einstellungen der externen KI-Werkzeuge (`external_access_settings`), höchstens einen Tag unter der längsten Befristung |
| `OPAA_INDEXING_CONFLUENCE_FULL_SYNC_INTERVAL` | `opaa.indexing.confluence.full-sync-interval` | Vorgabe der Installation in der Datenbank; je Bibliothek wie bisher |
| `OPAA_INDEXING_DROPBOX_FULL_SYNC_INTERVAL` | `opaa.indexing.dropbox.full-sync-interval` | wie Confluence |
| `OPAA_INDEXING_GOOGLE_DRIVE_FULL_SYNC_INTERVAL` | `opaa.indexing.google-drive.full-sync-interval` | wie Confluence |
| `OPAA_INDEXING_NEXTCLOUD_FULL_DESCENT_INTERVAL` | `opaa.indexing.nextcloud.full-descent-interval` | Vollabgleich-Rhythmus der Quellart Nextcloud (`full_sync_rhythm_settings`), in ganzen Tagen; ohne Rhythmus je Bibliothek |
| `OPAA_INDEXING_SHAREPOINT_FULL_SYNC_INTERVAL` | `opaa.indexing.sharepoint.full-sync-interval` | wie Confluence; OneDrive, das die Variable mitnutzte, bekommt einen eigenen Wert der Quellart |
| `OPAA_INITIAL_ADMIN_EMAIL` | `opaa.auth.initial-admin-email` | Startwert entfällt; erstes Konto im Assistenten |
| `OPAA_INITIAL_ADMIN_PASSWORD` | `opaa.auth.local.initial-admin-password` | Startwert entfällt; erstes Konto im Assistenten |
| `OPAA_LIBRARY_PRIVATE_STORAGE_QUOTA_BYTES` | `opaa.library.private-storage-quota-bytes` | Vorgabe im Code; `private_storage_quota_settings` |
| `OPAA_OIDC_AUTHORITY` | `opaa.auth.oidc.authority` | Startwert entfällt; Anmeldeverfahren im Assistenten |
| `OPAA_OIDC_BOOTSTRAP` | `opaa.auth.oidc.bootstrap` | entfällt |
| `OPAA_OIDC_CLIENT_ID` | `opaa.auth.oidc.client-id` | Startwert entfällt; Anmeldeverfahren im Assistenten |
| `OPAA_OIDC_ISSUER_URI` | `opaa.auth.oidc.issuer-uri` | Startwert entfällt; Anmeldeverfahren im Assistenten (`oidc_providers`) |
| `OPAA_OIDC_JWK_SET_URI` | `opaa.auth.oidc.jwk-set-uri` | Startwert entfällt; Anmeldeverfahren im Assistenten |
| `OPAA_OLLAMA_BASE_URL` | `LlmModelSeeder (Altname)` | Altname entfällt |
| `OPAA_OLLAMA_CHAT_MODEL` | `LlmModelSeeder (Altname)` | Altname entfällt |
| `OPAA_OPENAI_API_KEY` | `spring.ai.openai.api-key` | gemeinsamer Rückfall entfällt; Schlüssel je Modell in der Datenbank |
| `OPAA_OPENAI_BASE_URL` | `spring.ai.openai.base-url` | gemeinsamer Rückfall entfällt; Adresse je Modell in der Datenbank |
| `OPAA_OPENAI_CHAT_API_KEY` | `spring.ai.openai.chat.api-key` | Startwert entfällt; Chat-Modell im Assistenten |
| `OPAA_OPENAI_CHAT_BASE_URL` | `spring.ai.openai.chat.base-url` | Startwert entfällt; Chat-Modell im Assistenten |
| `OPAA_OPENAI_CHAT_MAX_TOKENS` | `spring.ai.openai.chat.max-tokens` | Startwert entfällt; Chat-Modell im Assistenten |
| `OPAA_OPENAI_CHAT_MODEL` | `spring.ai.openai.chat.model` | Startwert entfällt; Chat-Modell im Assistenten (`llm_models`) |
| `OPAA_OPENAI_CHAT_TEMPERATURE` | `spring.ai.openai.chat.temperature` | Startwert entfällt; Chat-Modell im Assistenten |
| `OPAA_OPENAI_EMBEDDING_API_KEY` | `spring.ai.openai.embedding.api-key` | Embedding-Modell im Assistenten |
| `OPAA_OPENAI_EMBEDDING_BASE_URL` | `spring.ai.openai.embedding.base-url` | Embedding-Modell im Assistenten |
| `OPAA_OPENAI_EMBEDDING_MAX_INPUT_TOKENS` | `opaa.embedding.max-input-tokens` | Eigenschaft des Embedding-Modells |
| `OPAA_OPENAI_EMBEDDING_MODEL` | `spring.ai.openai.embedding.model` | Embedding-Modell im Assistenten (Beschluss 3) |
| `OPAA_PGVECTOR_DIMENSIONS` | `spring.ai.vectorstore.pgvector.dimensions` | entfällt; Dimension aus der Probeeinbettung |
| `OPAA_RERANK_API_KEY` | `opaa.rerank.api-key` | Rerank-Modell in der Modellverwaltung |
| `OPAA_RERANK_BASE_URL` | `opaa.rerank.base-url` | Rerank-Modell in der Modellverwaltung |
| `OPAA_RERANK_ENABLED` | `opaa.rerank.enabled` | Rerank-Modell in der Modellverwaltung |
| `OPAA_RERANK_MODEL` | `opaa.rerank.model` | Rerank-Modell in der Modellverwaltung |
| `OPAA_SUCCESSION_AGING_THRESHOLD_MONTHS` | `opaa.succession.aging-threshold-months` | Vorgabe im Code; Ohne Zuständigkeit (`succession_settings`) |
| `OPAA_TRANSCRIPTION_API_KEY` | `opaa.transcription.api-key` | Transkriptionsmodell in der Modellverwaltung |
| `OPAA_TRANSCRIPTION_BASE_URL` | `opaa.transcription.base-url` | Transkriptionsmodell in der Modellverwaltung |
| `OPAA_TRANSCRIPTION_ENABLED` | `opaa.transcription.enabled` | Transkriptionsmodell in der Modellverwaltung (der Funktionsschalter `OPAA_FEATURES_SPEECH_INPUT_ENABLED` bleibt) |
| `OPAA_TRANSCRIPTION_LANGUAGE` | `opaa.transcription.language` | Transkriptionsmodell in der Modellverwaltung |
| `OPAA_TRANSCRIPTION_MODEL` | `opaa.transcription.model` | Transkriptionsmodell in der Modellverwaltung |

Für die entfallenen Lösch- und Aufbewahrungsvariablen gilt die Übergangsregel aus ADR-0060,
Entscheidung 13: Sie bremsen nur, nie als Wert. Hält eine noch gesetzte Variable auf einer lebenden
Instanz länger (oder schaltet sie ab, oder ist sie nicht lesbar) und hat die Verwaltung noch keinen
Wert gespeichert, pausiert der Löschschritt auf allen Instanzen, gemeldet über
`backend_instances.retired_settings`; Hinweis unter Administration → Datenschutz, Fehler im Log je
Lauf.

### Gruppe F: Experten-Feinabstimmung von Suche und Index (bleibt vorerst, siehe ADR-0060, Entscheidung 3)

| Variable | Property bzw. Ort |
|---|---|
| `OPAA_INDEXING_CHUNK_OVERLAP` | `opaa.indexing.chunk-overlap` |
| `OPAA_INDEXING_CHUNK_SIZE` | `opaa.indexing.chunk-size` |
| `OPAA_INDEXING_CHUNK_TARGET_CHARACTERS` | `opaa.indexing.chunk-target-characters` |
| `OPAA_INDEXING_RSS_ATTACHMENT_PROFILE` | `opaa.indexing.rss.attachment-profile` |
| `OPAA_INDEXING_RSS_MAIN_CONTENT_SELECTOR` | `opaa.indexing.rss.main-content-selector` |
| `OPAA_MCP_DEFAULT_MAX_HITS` | `opaa.mcp.default-max-hits` |
| `OPAA_MCP_EXCERPT_CHARACTERS` | `opaa.mcp.excerpt-characters` |
| `OPAA_QUERY_CONVERSATION_WINDOW_MESSAGES` | `opaa.query.conversation-window-messages` |
| `OPAA_QUERY_DECOMPOSITION_ENABLED` | `opaa.query.query-decomposition-enabled` |
| `OPAA_QUERY_FETCH_K` | `opaa.query.fetch-k` |
| `OPAA_QUERY_FULL_TEXT_SEARCH_ENABLED` | `opaa.query.full-text-search-enabled` |
| `OPAA_QUERY_FUSION_GUARANTEED_PER_LIST` | `opaa.query.fusion.guaranteed-per-list` |
| `OPAA_QUERY_FUSION_METHOD` | `opaa.query.fusion.method` |
| `OPAA_QUERY_FUSION_SUB_QUERY_QUOTA` | `opaa.query.fusion.sub-query-quota` |
| `OPAA_QUERY_FUSION_VECTOR_WEIGHT` | `opaa.query.fusion.vector-weight` |
| `OPAA_QUERY_MAX_CHUNKS_PER_DOCUMENT` | `opaa.query.max-chunks-per-document` |
| `OPAA_QUERY_MAX_SUB_QUERIES` | `opaa.query.max-sub-queries` |
| `OPAA_QUERY_METADATA_FILTER_DOCUMENT_DATE_THRESHOLD` | `opaa.query.metadata-filter.document-date-offer-threshold` |
| `OPAA_QUERY_METADATA_FILTER_DOCUMENT_TYPE_THRESHOLD` | `opaa.query.metadata-filter.document-type-offer-threshold` |
| `OPAA_QUERY_METADATA_FILTER_LIBRARY_FIELD_THRESHOLD` | `opaa.query.metadata-filter.library-field-offer-threshold` |
| `OPAA_QUERY_METADATA_FILTER_OPTIONS_CACHE_TTL` | `opaa.query.metadata-filter.options-cache-ttl` |
| `OPAA_QUERY_MMR_LAMBDA` | `opaa.query.mmr-lambda` |
| `OPAA_QUERY_NEIGHBOR_CHUNKS_MAX_ADDED_CHARACTERS` | `opaa.query.neighbor-chunks.max-added-characters` |
| `OPAA_QUERY_NEIGHBOR_CHUNKS_RADIUS` | `opaa.query.neighbor-chunks.radius` |
| `OPAA_QUERY_RELEVANCE_CUTOFF_KNEE_DROP` | `opaa.query.relevance-cutoff.knee-drop` |
| `OPAA_QUERY_RELEVANCE_CUTOFF_MIN_CHUNKS` | `opaa.query.relevance-cutoff.min-chunks` |
| `OPAA_QUERY_RELEVANCE_CUTOFF_MODE` | `opaa.query.relevance-cutoff.mode` |
| `OPAA_QUERY_RELEVANCE_CUTOFF_RELATIVE_THRESHOLD` | `opaa.query.relevance-cutoff.relative-threshold` |
| `OPAA_QUERY_RERANK_CANDIDATE_COUNT` | `opaa.query.rerank-candidate-count` |
| `OPAA_QUERY_SEARCH_WINDOW_TURNS` | `opaa.query.search-window-turns` |
| `OPAA_QUERY_SIMILARITY_THRESHOLD` | `opaa.query.similarity-threshold` |
| `OPAA_QUERY_TOP_K` | `opaa.query.top-k` |
| `OPAA_QUERY_VECTOR_INDEX_MAX_SCAN_TUPLES` | `opaa.query.vector-index.max-scan-tuples` |
| `OPAA_SEARCH_CONTEXT_PASSAGES` | `opaa.search.context-passages` |
| `OPAA_SEARCH_DEFAULT_MAX_HITS` | `opaa.search.default-max-hits` |
| `OPAA_SEARCH_EXCERPT_MAX_CHARACTERS` | `opaa.search.excerpt-max-characters` |
| `OPAA_SEARCH_FETCH_MAX_CHARACTERS` | `opaa.search.fetch-max-characters` |
| `OPAA_SEARCH_MAX_HITS` | `opaa.search.max-hits` |
