# Modelle

OPAA bringt kein eigenes Sprachmodell mit, sondern ruft Modelle über Endpunkte auf, die das Haus
festlegt — im eigenen Rechenzentrum oder bei einem Anbieter. Dieses Kapitel beschreibt, welche
Modelle es braucht, wie sie angebunden werden und woran es liegt, wenn sie nicht antworten. Wie die
Modelle in die Suche eingreifen, steht im Kapitel [Suche](suche.md).

> **Beispiel.** Ein Haus betreibt einen Modellserver im Rechenzentrum. Die Einbettung läuft dort mit
> `bge-m3` und wird einmal in der Umgebung festgelegt. Für den Chat legt die Systemverwaltung in der
> Oberfläche zwei Modelle an, ein schnelles für den Alltag und ein größeres zum Vergleich, und
> schaltet bei Bedarf um — ohne Neustart und ohne den Index anzufassen.

## Drei Modellrollen

| Rolle | Wofür | Festgelegt über | Wechsel |
|---|---|---|---|
| **Chat** | Antwort, Zerlegung in Teilfragen, Chat-Titel, Gesprächsnotiz, Metadaten-Extraktion | Modellverwaltung in der Oberfläche; die Umgebung nur beim allerersten Start | jederzeit, ohne Neustart; berührt den Index nicht |
| **Embedding** | Vektoren der Chunks und der Suchanfragen | Umgebung (`OPAA_OPENAI_EMBEDDING_*`, `OPAA_PGVECTOR_DIMENSIONS`) | verlangt eine vollständige Neuindizierung |
| **Reranking** | ordnet die Kandidaten der Suche neu, optional | Umgebung (`OPAA_RERANK_*`) | jederzeit mit Neustart; aus ist die Voreinstellung |

## LLM-Anbieter

Es gibt einen einzigen Anbindungsweg für Chat und Einbettung: die **openai-kompatible
Schnittstelle**. Das ist eine Protokollangabe, keine Anbieterangabe — Ollama bedient sie unter seinem
eigenen `/v1`-Pfad, ebenso vLLM, LiteLLM, Azure und die üblichen Zwischenschichten.

**Voreingestellt sind lokal betriebene Modelle.** Eine Installation, an der niemand etwas
konfiguriert, ruft kein Modell außerhalb des Hauses auf: `OPAA_OPENAI_BASE_URL` zeigt auf
`http://localhost:11434/v1` (im Profil `docker` auf `http://ollama:11434/v1`), Chat- und
Embedding-Modell sind `phi3:mini` und `nomic-embed-text`, und Ollama braucht keinen Zugangsschlüssel.
Betrieb ohne Netzanbindung ist der vorgesehene Fall; es gibt kein automatisches Ausweichen auf ein
Modell außerhalb des Hauses. Für einen anderen Anbieter wird die **Zieladresse** überschrieben:

```env
OPAA_OPENAI_BASE_URL=https://modellserver.example.internal/v1
OPAA_OPENAI_API_KEY=sk-your-key-here
```

`OPAA_OPENAI_CHAT_BASE_URL` und `OPAA_OPENAI_EMBEDDING_BASE_URL` überschreiben die Adresse je Rolle;
ohne sie gilt `OPAA_OPENAI_BASE_URL` für beide. Ein **ausdrücklich leer gesetzter** Wert
(`OPAA_OPENAI_BASE_URL=` in einer `.env`) überschreibt die Voreinstellung mit einer leeren
Zeichenkette und lässt das Backend mit einer Meldung abbrechen, die die Variable nennt.

> **Es gibt keine technische Sperre**, die einen Aufruf außerhalb festgelegter Netzbereiche
> verhindert. Wer die Voreinstellung ändert, kann jedes erreichbare Ziel eintragen. Wer zusichern
> muss, dass keine Daten das Haus verlassen, weist die Konfiguration nach und sichert den Netzweg
> außerhalb von OPAA ab, etwa über Firewall-Regeln oder den [Ausgangsproxy](betrieb.md#ausgangsproxy).
> Jede Änderung des Chat-Modells in der Oberfläche ist protokolliert und einer Person zurechenbar.

Wie Ollama im Compose-Stapel oder im Cluster läuft, steht unter
[Docker Compose](docker-compose.md#lokal-betriebenes-ollama-im-compose-stack) und
[Kubernetes](kubernetes.md#modelle-im-cluster).

## Chat-Modelle in der Oberfläche anlegen

Das Chat-Modell ist ein **verwaltetes** Modell: Unter **Administration → Modelle → Chat-Modelle** legt
ein Systemverwalter Modelle an, bearbeitet, testet und aktiviert sie. Genau eines ist aktiv; eine
Aktivierung oder Änderung wirkt ohne Neustart, das aktive Modell lässt sich nicht löschen.

**Der erste Start übernimmt die Umgebung.** Beim allerersten Start legt OPAA aus
`OPAA_OPENAI_CHAT_*`, `OPAA_OPENAI_API_KEY` und `OPAA_OPENAI_BASE_URL` einmalig das erste Chat-Modell
an und aktiviert es. Danach haben diese Variablen für den Chat keine Wirkung mehr. Ist dabei ein
Zugangsschlüssel gesetzt, muss auch `OPAA_SETTINGS_ENCRYPTION_KEY` gesetzt sein; sonst bleibt das
Modell unangelegt, eine Fehlermeldung steht im Log, und der nächste Start mit Schlüssel holt die
Übernahme nach ([Verschlüsselung](#verschlüsselung-der-zugangsschlüssel-verwalteter-chat-modelle)).

Das Formular führt in drei Abschnitten:

1. **Endpunkt.** Das Feld „Basis-Adresse" schlägt Vorlagen vor; jede andere openai-kompatible Adresse
   wird einfach eingetragen.

   | Vorlage | Trägt ein | Hinweis |
   |---|---|---|
   | Ollama | `http://ollama:11434/v1` | Der Host `ollama` ist danach markiert und wird mit dem eigenen überschrieben. |
   | OpenAI | `https://api.openai.com/v1` | nur mit Zugangsschlüssel |
   | Anthropic (Claude) | `https://api.anthropic.com/v1` | nur mit Zugangsschlüssel; das Feld Temperatur leer lassen |

   Typische Ollama-Adressen, je nachdem, wo Ollama vom Backend aus erreichbar ist:

   | Ollama läuft | Basis-Adresse |
   |---|---|
   | im Compose-Stapel | `http://ollama:11434/v1` |
   | auf dem Docker-Host, OPAA im Container | `http://host.docker.internal:11434/v1` |
   | im Kubernetes-Cluster | `http://ollama.<namespace>.svc.cluster.local:11434/v1` |
   | auf demselben Rechner, Backend ohne Container (`./gradlew bootRun`) | `http://localhost:11434/v1` |

   Mit eingeschalteten Ausgangs-Policies braucht das Backend im Cluster eine Regel zu Port 11434
   ([Kubernetes](kubernetes.md#networkpolicies)). Der Zugangsschlüssel ist optional; er wird
   verschlüsselt gespeichert und nie wieder angezeigt.
2. **Modell.** „Modelle abrufen" (oder Enter im Adressfeld) fragt den Endpunkt nach seinen Modellen
   (`GET {Basis-Adresse}/models`); die Vorlagen für OpenAI und Anthropic rufen sofort ab, wenn ein
   Schlüssel eingetragen ist, die Ollama-Vorlage nie, weil der Host erst zu bestätigen ist. Die
   Kennung ist danach eine durchsuchbare Auswahl. Hinter einem Ollama zeigt jede Zeile zusätzlich
   Parameterzahl, Größe, Kontextlänge und Fähigkeiten (Werkzeuge, Bilder, Denkmodus); andere Server
   liefern meist nur die Kennungen. Reine Einbettungsmodelle stehen in der Gruppe „Nicht für den Chat
   geeignet" und sind nicht wählbar. Eine Kennung lässt sich immer von Hand eintragen, auch wenn ein
   Anbieter die Liste mit dem Schlüssel ablehnt.
3. **Anzeige und Antwort.** Die Wahl eines Modells belegt den Anzeigenamen mit der Kennung (ohne
   `:latest` und Anbieterpfad). Ist das Kontextfenster bekannt, begrenzt sie die maximale
   Antwortlänge auf die Hälfte davon, falls der Vorschlagswert nicht hineinpasst; die andere Hälfte
   brauchen Frage, Verlauf und gefundene Textstellen. Von Hand Eingetragenes überschreibt die
   Vorbelegung nicht. Die Temperatur bleibt ohne Eintrag leer.

Nach der Wahl eines Modells läuft der **Verbindungstest** automatisch; „Verbindung testen" wiederholt
ihn. Er schickt die eingetragene Temperatur mit, wie später der Chat. Was seine Meldungen bedeuten,
steht unter [Fehlerbilder](#fehlerbilder).

**Der gespeicherte Schlüssel geht nur an denselben Ursprung.** Wer beim Bearbeiten keinen neuen
Schlüssel einträgt, nutzt den gespeicherten — aber nur für eine Adresse mit demselben Schema, Host
und Port. Bei einer anderen Adresse lehnt OPAA ab, statt den Schlüssel dorthin zu senden. Eine
Adresse mit Anmeldedaten (`https://benutzer:passwort@host`) wird abgelehnt. Abruf und Ollama-Details
haben eine kurze Zeit- und Größengrenze, damit ein langsamer Endpunkt das Formular nicht aufhält.

### Temperatur und maximale Antwortlänge

**Die Temperatur ist optional, „keine Angabe" ist die Voreinstellung.** Ohne Eintrag schickt OPAA
den Parameter nicht mit, und das Modell nimmt seine eigene Voreinstellung. Manche Modelle akzeptieren
keine andere: Bei neueren Anthropic-Modellen (etwa Claude Haiku 5.5 über die openai-kompatible
Schnittstelle) lehnt der Anbieter jeden anderen Wert mit HTTP 400 ab, und jede Chat-Anfrage scheitert.
Weitere Sampling-Parameter (`top_p`, `top_k`) sendet OPAA nie.

**Die maximale Antwortlänge** (Token) begrenzt die Ausgabe. Bei Modellen mit internem Reasoning zählt
das Reasoning mit, ohne sichtbar zu werden; ein knapper Wert schneidet die Antwort mitten im Satz ab
oder lässt sie leer. Das Formular schlägt für neue Einträge einen darauf ausgelegten Wert vor;
`OPAA_OPENAI_CHAT_MAX_TOKENS` bestimmt nur das erste Modell. Bricht ein Modell an der Grenze ab oder
liefert es keinen Text, schreibt das Backend eine Warnung, und unter der Antwort erscheint ein
Hinweis, dass sie unvollständig ist oder fehlt — nur für die gerade erzeugte Antwort, nicht im
gespeicherten Verlauf.

### Denktext im Antworttext

Manche Anbieter liefern das Nachdenken eines Modells am Anfang des Antworttexts als Block
`<think>…</think>` oder `<thought>…</thought>`, etwa Gemma 4 über die openai-kompatible Schnittstelle
von Gemini. OPAA entfernt einen solchen Block bei jedem Aufruf des Chat-Modells, bevor der Text
weiterverarbeitet wird — nur ganz am Anfang und nur mit öffnendem Tag. Denktext ohne öffnendes Tag
(nur `…</think>` vor der Antwort, bei manchen selbst betriebenen Modellen) bleibt stehen. Ist ein
führender Block nicht geschlossen, gilt die Antwort als leer, und unter ihr erscheint der Hinweis auf
die fehlende Antwort. Ein Schalter je Modell ist nicht nötig; der Verbindungstest weist darauf hin,
wenn die Testantwort mit einem Denkblock beginnt.

## Embedding

Das Embedding-Modell legt die Umgebung fest, fortlaufend und nicht über die Oberfläche:
`OPAA_OPENAI_EMBEDDING_MODEL`, die Adresse aus `OPAA_OPENAI_EMBEDDING_BASE_URL` oder
`OPAA_OPENAI_BASE_URL`, der Schlüssel entsprechend, dazu `OPAA_PGVECTOR_DIMENSIONS` — die Zahl der
Dimensionen, mit der das Modell einbettet. Die Oberfläche zeigt die Einbettung nur an.

Eine Auswahlliste gibt es bewusst nicht: Ein Wechsel macht alle vorhandenen Vektoren unvergleichbar
und erzwingt eine vollständige Neuindizierung, und ein versehentlich verstellter Wert machte den
Bestand unbrauchbar. Wer wechselt, ändert Modell und Dimension gemeinsam, setzt die Datenbank zurück
und indiziert neu ([Was ein Update mit dem Index macht](betrieb.md#was-ein-update-mit-dem-index-macht)).
Liegen bereits Vektoren einer anderen Dimension vor, bricht der Start mit einer Meldung ab, statt erst
beim ersten Lauf zu scheitern.

Das voreingestellte `nomic-embed-text` bettet mit 768 Dimensionen ein, die Anwendungsvorgabe von
`OPAA_PGVECTOR_DIMENSIONS` ist 1536 — wer die Voreinstellung nutzt, setzt `768`.

## Reranking einschalten

Reranking ist voreingestellt **aus** (`OPAA_RERANK_ENABLED=false`). Die Rolle braucht einen eigenen
Endpunkt, und ob sie auf einem Bestand etwas bringt, wird gemessen und nicht vermutet: Der
Qualitätsgewinn ist auf den Korpora des Projekts gezeigt, ein Latenz- und Hardwareprofil fehlt noch.
Was die Stufe in der Suche tut, steht unter [Suche](suche.md#stufe-8-reranking).

Zum Einschalten gehören drei Angaben zusammen: `OPAA_RERANK_ENABLED=true`, `OPAA_RERANK_BASE_URL` und
`OPAA_RERANK_MODEL`. Fehlt eine Endpunktangabe oder antwortet der Endpunkt nicht, steht das im Log und
fortlaufend auf der Seite „Suche & Indexierung"; die Suche läuft dann ohne Reranking weiter — ein
Ausfall kostet die Sortierung, nie die Antwort.

> **`OPAA_QUERY_FETCH_K` mit anheben.** Der Reranker bewertet nur, was die Suchstufen zuvor geliefert
> haben. Bei `OPAA_QUERY_FETCH_K=25` liefern Vektor- und Volltextpfad je Teilfrage zusammen höchstens
> 50 verschiedene Abschnitte; ein Kandidatenfenster von 50 (`OPAA_QUERY_RERANK_CANDIDATE_COUNT`) ist
> damit gerade gedeckt, ein größeres läuft ins Leere. Wer dem Reranking mehr Material geben will, hebt
> `OPAA_QUERY_FETCH_K` mit an.

> **Zeitgrenzen mit anheben.** `OPAA_RERANK_TIMEOUT` bindet nur den Rerank-Aufruf, nicht die Anfrage
> als Ganzes: Eine Suche mit CPU-Reranker kann minutenlang warten, und die übrigen Modellaufrufe kommen
> hinzu. Wer `OPAA_RERANK_TIMEOUT` anhebt, hebt `OPAA_BACKEND_READ_TIMEOUT` des Frontends mit an und
> die Zeitgrenze jedes Proxys davor ([Netzwerkzugang](betrieb.md#netzwerkzugang)). Sonst endet die
> Anfrage beim Client mit `504` oder einem Verbindungsabbruch, ohne Fehler im Backend-Log.

## vLLM / OpenAI-kompatible Server

Server, die HTTP/2 nicht vertragen, etwa vLLM und andere auf Uvicorn-Basis, brauchen den
HTTP/1.1-Modus; der HTTP-Client des Backends bevorzugt sonst HTTP/2, und die Verbindung scheitert:

```env
OPAA_HTTP_FORCE_HTTP1=true
OPAA_OPENAI_BASE_URL=http://your-vllm-server:8000/v1
```

## Verschlüsselung der Zugangsschlüssel verwalteter Chat-Modelle

Ein hinterlegter Zugangsschlüssel eines Chat-Modells liegt **verschlüsselt** in der Datenbank
(AES-256-GCM, zufälliger Initialisierungsvektor je Wert) und erscheint in keiner API-Antwort und keinem
Log. Der Schlüssel dafür ist `OPAA_SETTINGS_ENCRYPTION_KEY`, getrennt von dem der Quellen
([Schlüssel und Geheimnisse](betrieb.md#schlüssel-und-geheimnisse)); er schützt auch das
SMTP-Passwort. Erzeugt wird er mit `openssl rand -base64 32` und außerhalb des Repositorys abgelegt.

- **Ohne Schlüssel startet das Backend normal.** Eine Installation, die nur lokale Modelle ohne
  Zugangsschlüssel führt, braucht ihn nie. Erst das Anlegen oder Ändern eines Modells **mit**
  Zugangsschlüssel scheitert mit einer Meldung, die die Variable nennt; die Übernahme beim ersten
  Start bricht den Start nicht ab, sondern schreibt einen Fehler ins Log und wird beim nächsten Start
  nachgeholt.
- **Bei Schlüsselverlust** sind die gespeicherten Zugangsschlüssel nicht wiederherstellbar; der übrige
  Modelleintrag bleibt. Abhilfe: den Zugangsschlüssel des Modells in der Oberfläche neu eintragen.
- Für Entwicklung und Tests (nur Profil `dev`) ist ein fester, **nicht für den Betrieb gedachter**
  Schlüssel voreingestellt.

## Fehlerbilder

Der Zustand der Modelle steht an drei Stellen: in der Statusanzeige der Seite „Suche & Indexierung"
(Modellrollen mit Endpunkt, Kennung und Erreichbarkeit), in den Gesundheitsgruppen
`GET /actuator/health/chat-model` und `…/embedding-model` ([Bereitschaft](betrieb.md#bereitschaft-und-lebendigkeit))
und im Protokoll des Backends.

**Meldungen des Verbindungstests:**

| Meldung | Bedeutung |
|---|---|
| Host nicht gefunden, Verbindung abgelehnt, Zeitlimit, Zertifikat | Der Endpunkt ist von OPAA aus nicht erreichbar. Es zählt das Netz des Backends, nicht das des Browsers |
| Zugangsschlüssel verlangt oder abgelehnt (HTTP 401), Zugriff verweigert (HTTP 403) | Schlüssel fehlt oder passt nicht |
| keine Modellliste | Der Server antwortet, bietet `/models` aber nicht an; die Kennung von Hand eintragen |
| Der Endpunkt meldet keine Modelle | Die Liste ist leer; bei Ollama ist noch kein Modell geladen (`ollama pull …`) |
| Umleitung, anderer HTTP-Status, Liste zu groß | Der Server antwortet unerwartet. Umleitungen folgt OPAA nicht; die Zieladresse direkt eintragen |
| Der Anbieter lehnt die Temperatur ab | Das Feld Temperatur leer lassen |
| Die Testantwort beginnt mit einem Denkblock | Ein Server ohne Reasoning-Parser; siehe [Denktext im Antworttext](#denktext-im-antworttext) |

**Im Betrieb:**

| Bild | Ursache und Abhilfe |
|---|---|
| Der Start bricht ab und nennt `OPAA_OPENAI_BASE_URL` | Die Variable ist leer gesetzt; die Zeile entfernen oder eine Adresse eintragen |
| Jede Chat-Anfrage scheitert mit HTTP 400 | Meist eine Temperatur, die das Modell nicht annimmt |
| Antworten brechen mitten im Satz ab oder fehlen, mit Hinweis unter der Antwort | Die maximale Antwortlänge ist zu knapp, oft bei Modellen mit Reasoning |
| Die erste Einbettung scheitert mit einem Dimensionsfehler | `OPAA_PGVECTOR_DIMENSIONS` passt nicht zum Embedding-Modell ([Embedding](#embedding)) |
| Der Start bricht mit einer Meldung zur Vektordimension ab | Im Index liegen Vektoren einer anderen Dimension; Datenbank zurücksetzen und neu indizieren |
| Dokumente enden mit `FAILED`, Fragen scheitern, alles andere läuft | Modellserver nicht erreichbar oder Modell nicht geladen; unter Compose mit Profil `ollama` zuerst `docker compose logs ollama-pull` |
| Nach einem Update scheitert die Einbettung | Eine entfallene Variable steht noch in der Umgebung ([Konfiguration](konfiguration.md#entfallene-und-veraltete-variablen)) |
| Verbindungsfehler gegen vLLM | HTTP/1.1-Modus fehlt ([vLLM](#vllm--openai-kompatible-server)) |
| Reranking „nicht verfügbar" | Endpunkt nicht erreichbar oder Zeitbudget überschritten; die Suche lief ohne Reranking |
| Lange Fragen enden beim Client mit `504` | Zeitgrenzen von Frontend und Proxy kürzer als die Modellaufrufe ([Reranking einschalten](#reranking-einschalten)) |
| Ein Modell mit Zugangsschlüssel lässt sich nicht speichern | `OPAA_SETTINGS_ENCRYPTION_KEY` fehlt oder ist ungültig |
| Verbindungen scheitern mit dem Status eines Proxys | [Ausgangsproxy](betrieb.md#ausgangsproxy); ein Modellserver im eigenen Netz gehört auf seine Ausnahmeliste |

## Weiterführende Kapitel

- [Suche](suche.md) — wie die Modellrollen in die Suchstrecke eingreifen
- [Konfiguration](konfiguration.md) — alle `OPAA_OPENAI_*`- und `OPAA_RERANK_*`-Variablen
- [Authentifizierung](authentifizierung.md)
