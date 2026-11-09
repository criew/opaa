# ADR-0070: Live-Transkript über WebSocket mit Adaptergrenze

## Status

Akzeptiert · zuletzt geändert 2026-11-09 · ergänzt ADR-0053 und ADR-0060

## Kontext

Die Spracheingabe (Transkriptionsmodell als Modellrolle, ADR-0060 Entscheidung 16) schickt die
ganze Aufnahme nach dem Ende an `POST /api/v1/transcriptions`; der Text erscheint erst danach. Ein
Live-Transkript zeigt den Text schon beim Sprechen. Die Dienste dafür sprechen jeweils eigene
WebSocket-Protokolle: die Realtime-API von OpenAI (24 kHz, Ergebnisse je `item_id`), vLLM mit
Voxtral Realtime (16 kHz, `transcription.delta`/`done`) und die Realtime-Transkription von Mistral
(16 kHz, Modell in der Adresse). Selbst betrieben braucht Voxtral Realtime eine GPU mit mindestens
16 GB Speicher und trägt darauf etwa eine gleichzeitige Sitzung.

OPAA hatte bisher keinen WebSocket. Der Browser kann an einen WebSocket keine Header hängen, die
Anmeldung läuft aber über `Authorization: Bearer` (ADR-0005). Ein Live-Dienst ist teuer und knapp;
mehrere Backend-Instanzen (ADR-0053) teilen ihn. Fällt er aus, darf die Spracheingabe nicht
ausfallen.

## Entscheidung

### 1. Ein eigener WebSocket mit OPAA-Protokoll

`WS /api/v1/transcriptions/stream` (`TranscriptionStreamHandler`), nur mit eingeschalteter
Spracheingabe. Der Browser spricht immer dasselbe Protokoll, gleich welcher Dienst dahinter steht:
der Server sendet `ready` mit der Abtastrate, der Browser binäre PCM16-Frames (mono, little-endian)
und `commit`, der Server `delta` (der ganze bisher erkannte Text), `done` und `error`. Jedes `error`
beendet die Sitzung. Begründung: Der Browser kennt kein Anbieterprotokoll und keinen Schlüssel; ein
weiterer Dienst ändert nichts am Browser.

### 2. Anmeldung beim Upgrade wie bei REST

Das Token geht als Subprotokoll `opaa-bearer.<token>` neben `opaa-transcription.v1`, im Dev-Profil
der Dev-Nutzer als `opaa-dev-user.<subject>`. Ein Filter vor der Security-Chain
(`TranscriptionStreamCredentialsFilter`) macht daraus die üblichen Header und streicht sie aus
`Sec-WebSocket-Protocol`; die Antwort nennt nur `opaa-transcription.v1`. Danach prüft dieselbe
Security-Chain wie bei jedem REST-Aufruf, das Upgrade nimmt nur angemeldete Personen ohne
Zugangstoken der externen Anbindung an und nur von der eigenen Origin oder einer aus
`OPAA_CORS_ALLOWED_ORIGINS`. Begründung: kein zweiter Anmeldeweg und kein Token in der URL, die in
Zugriffsprotokollen landet.

### 3. Adaptergrenze je Dienst

`RealtimeTranscriptionAdapter` übersetzt zwischen PCM16-Frames, dem Ende der Aufnahme und den
Ereignissen eines Dienstes: `OpenAiRealtimeTranscriptionAdapter`, `VllmRealtimeTranscriptionAdapter`,
`MistralRealtimeTranscriptionAdapter`. Das Protokoll ist ein fester Wert
(`RealtimeTranscriptionProtocol`: `OPENAI`, `VLLM`, `MISTRAL`), in Verwaltung, Assistent und
Antwortdatei eine Auswahl und nie Freitext. `RealtimeTranscriptionClient` öffnet die Verbindung mit
dem JDK-Client über den ausgehenden Proxy, folgt keiner Umleitung und sendet den Schlüssel nur im
Handshake. Begründung: ein weiterer Dienst ist ein weiterer Adapter, ohne Änderung am Endpunkt.

### 4. Konfiguration als Teil der Rolle Transkriptionsmodell

Protokoll, WebSocket-Adresse, Modell, eigener Schlüssel und Höchstzahl gleichzeitiger Sitzungen
liegen in `transcription_model` (Schlüssel verschlüsselt, `LlmEncryptedColumns`), geschrieben über
`ModelRoleAdministration` mit Revisionsprotokoll, Adressprüfung der Modelle (die `ws`/`wss`-Adresse
als `http`/`https`) und Verbindungstest (Sitzung ohne Audio). Ohne eigenen Schlüssel geht der
Schlüssel des Transkriptionsmodells mit, aber nur an dessen Origin (Schema, Host, Port). OpenAI und
Mistral ohne einen dieser Schlüssel lehnen Verwaltung, Assistent und Antwortdatei ab. Im Assistenten
liegt der eigene Schlüssel bis zum Abschluss verschlüsselt im Zwischenstand des Schritts, in der
Antwortdatei als Pfad einer Datei (`apiKeyFile`). Verwaltung und Assistent zeigen Live als
eigenen Abschnitt mit Schalter neben dem Diktat, nicht als Entweder-oder; der Diktat-Endpunkt bleibt
Pflicht. Ist er noch leer, belegt die Wahl des Live-Dienstes ihn vor (OpenAI mit dessen
Transkriptions-API, vLLM mit dem Server der Live-Adresse), Mistral nicht, solange dessen
Batch-Endpunkt mit `TranscriptionClient` nicht geprüft ist. Live gibt
es nur zusätzlich zur Spracheingabe; der Zustand liegt als `TRANSCRIPTION_REALTIME` in
`model_role_observations`. Begründung: Ein Live-Dienst ist ein weiteres Ziel der Aufnahmen und
gehört unter dieselbe Kontrolle wie das Transkriptionsmodell.

### 5. Grenzen einer Sitzung

Höchstens zwei Minuten je Sitzung, 30 Sekunden ohne Audio, 15 Sekunden nach `commit` ohne
endgültigen Text, Frames bis 64 KB. Eine Sitzung je Person und eine installationsweite Höchstzahl
(`realtime_max_sessions`, Vorgabe 1). `realtimeAvailable` meldet Live nur, wenn das
Transkriptionsmodell bereit ist, der Live-Dienst bereit ist und ein Platz frei ist.

### 6. Höchstzahl über die Datenbank, eine Sitzung je Person je Instanz

Die Plätze liegen in `transcription_realtime_slots` (nur Kennung und Zeitpunkt, kein
Personenbezug), vergeben unter Tabellensperre mit der Uhr der Datenbank; eine Zeile, die nach
150 Sekunden noch steht (die Instanz ist ausgefallen), zählt nicht mehr. Die Grenze von einer
Sitzung je Person hält jede Instanz im Speicher. Begründung: Die Höchstzahl schützt einen teuren
Dienst und muss über alle Instanzen gelten (ADR-0053, Entscheidung 12); eine zweite Sitzung derselben
Person auf einer anderen Instanz bleibt innerhalb der Höchstzahl und kostet nicht mehr als eine
Sitzung einer anderen Person.

### 7. Die Aufnahme bleibt der Rückfall

Neben jeder Live-Sitzung nimmt der Browser wie bisher mit `MediaRecorder` auf. Bricht Live ab, ist
kein Platz frei oder kommt nach dem Ende kein endgültiger Text, geht die Aufnahme an
`POST /api/v1/transcriptions`; die Person sieht dafür keine Fehlermeldung. Der vorläufige Text
erscheint gedimmt unter der Eingabe, in einer Farbe mit mindestens 4,5:1 Kontrast, und wird erst mit
dem endgültigen Text in den Entwurf übernommen. Begründung: Live ist eine Komfortstufe; die
Spracheingabe hängt nicht von ihr ab.

### 8. Kein Speichern, kein Protokollieren

Audio und erkannter Text laufen nur durch und werden weder gespeichert noch protokolliert, auch
nicht in Fehlermeldungen des Dienstes; ein Fehler nennt nur Code oder Typ. Der Browser hält beides
nur im Speicher.

## Konsequenzen

- Einfacher: ein weiterer Dienst ist ein Adapter und ein Enum-Wert; der Browser bleibt gleich.
- Schwieriger: nginx im Frontend-Container, ein Ingress oder ein Gateway müssen das Upgrade
  weiterreichen; ohne Upgrade bleibt es still beim Diktat mit ganzer Aufnahme.
- Eine Sitzung lebt auf einer Instanz; stoppt sie, fällt der Browser auf die Aufnahme zurück.
- Restrisiko: Mit N Instanzen kann eine Person bis zu N Sitzungen halten (Entscheidung 6).
- Restrisiko: Ein externer Dienst erhält die Aufnahme laufend während des Sprechens; das braucht
  eine eigene datenschutzrechtliche Prüfung, Verwaltung und Assistent sagen es bei der Auswahl.

## Verworfene Alternativen

- Token als Query-Parameter — landet in Zugriffsprotokollen von Proxys.
- Kurzlebiges Ticket per REST vor dem Upgrade — ein zweiter Anmeldeweg mit eigenem Zustand.
- Server-Sent Events mit Audio per POST — kein Rückkanal für laufendes Audio ohne viele Anfragen.
- Browser direkt zum Anbieter — der Schlüssel läge im Browser und die Adressprüfung fiele weg.
- Freitext für das Protokoll — eine Adresse allein sagt nicht, welcher Dialekt gesprochen wird.

## Offen

- Weitere Dialekte (etwa Deepgram oder ein eigener Whisper-Streaming-Dienst) bei Bedarf.
