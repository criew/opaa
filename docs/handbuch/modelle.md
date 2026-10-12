# Modelle

## LLM-Anbieter

Es gibt einen einzigen Anbindungsweg für Chat
und Einbettung: die **openai-kompatible Schnittstelle**. Das ist eine Protokollangabe, keine
Anbieterangabe — Ollama bedient dieselbe Schnittstelle unter ihrem eigenen `/v1`-Pfad, ebenso vLLM,
LiteLLM, Azure und die üblichen Zwischenschichten. Einen zweiten, nativen Anbindungsweg speziell für
Ollama gibt es nicht.

> **Für den Chat-Aufruf gilt eine wichtige
> Einschränkung: `OPAA_OPENAI_CHAT_*`/`OPAA_OPENAI_API_KEY`/`OPAA_OPENAI_BASE_URL` steuern das
> Chat-Modell nur beim allerersten Start einer Installation.** Dabei übernimmt
> `io.opaa.llm.LlmModelSeeder` die damalige Umgebungskonfiguration einmalig als ersten Eintrag der
> Tabelle `llm_models` und aktiviert ihn. Danach ist **ausschließlich diese Tabelle** maßgeblich für
> das Chat-Modell — zur Laufzeit gelesen von `io.opaa.llm.ActiveChatModelResolver`, der Basis-Adresse,
> Modell-Kennung, Temperatur, maximale Antwortlänge und (falls hinterlegt) den entschlüsselten
> Zugangsschlüssel des jeweils **aktiven** Eintrags zu einem `ChatClient` zusammenbaut, zwischenspeichert
> und bei jeder Aktivierung oder Änderung des jeweils aktiven Eintrags über die
> Administrationsoberfläche neu auflöst — ohne Neustart. Löschen ist dabei kein eigener Auslöser:
> Das aktive Modell kann nicht gelöscht werden (409), und das Löschen eines inaktiven Eintrags
> berührt die Auflösung nicht. Ein Ändern von `OPAA_OPENAI_CHAT_*` **nach** diesem ersten Start hat
> keine Wirkung mehr;
> die Modellauswahl läuft über die Administrationsoberfläche (`SYSTEM_ADMIN`, Modellverwaltung), nicht
> mehr über Umgebungsvariablen. Die Umgebungsvariablen bleiben unten dokumentiert, weil sie beim
> Erststart nach wie vor gelesen werden und weil ihre Werte den anfänglichen aktiven Eintrag bilden.
>
> **Die Einbettung ist von dieser Umstellung nicht betroffen**: `OPAA_OPENAI_EMBEDDING_*`/
> `OPAA_OPENAI_BASE_URL` steuern das Embedding-Modell weiterhin unverändert und fortlaufend über die
> native Spring-AI-Autoconfiguration — es gibt (bewusst) keine verwaltete Einbettungsmodell-Tabelle:
> Ein Wechsel des Einbettungsmodells macht alle vorhandenen Vektoren unvergleichbar und erzwingt eine
> vollständige Neuindizierung; eine Auswahlliste, die eine Wissensbasis unbrauchbar macht, sobald
> jemand sie versehentlich verstellt, wäre eine Falle. Die Oberfläche zeigt die Einbettungskonfiguration
> deshalb nur an.

**Voreingestellt sind lokal betriebene Modelle** über genau diesen Weg, für Chat und für Einbettung.
Eine Installation, an der niemand etwas konfiguriert, ruft kein Modell außerhalb des Hauses auf —
`OPAA_OPENAI_BASE_URL` zeigt ohne weitere Angabe auf `http://localhost:11434/v1` (im Spring-Profil
`docker`: `http://ollama:11434/v1`), `OPAA_OPENAI_CHAT_MODEL`/`OPAA_OPENAI_EMBEDDING_MODEL` auf
`phi3:mini`/`nomic-embed-text`, und Ollama braucht keinen Zugangsschlüssel (der voreingestellte
`OPAA_OPENAI_API_KEY`-Platzhalter wird nie geprüft). Diese Voreinstellung ist so gewollt und bleibt:
Betrieb ohne Netzanbindung ist der vorgesehene Fall, nicht der Notbetrieb; es gibt kein automatisches
Ausweichen auf ein Modell außerhalb des Hauses.

```env
# Kein Eintrag nötig - dies ist bereits der Anwendungs-Default.
```

Um stattdessen einen anderen openai-kompatiblen Anbieter zu verwenden, ist die **Zieladresse**
anzugeben — sie überschreibt die lokale Voreinstellung:

```env
OPAA_OPENAI_BASE_URL=https://modellserver.example.internal/v1
OPAA_OPENAI_API_KEY=sk-your-key-here
```

`OPAA_OPENAI_CHAT_BASE_URL` und `OPAA_OPENAI_EMBEDDING_BASE_URL` überschreiben die Adresse je
Funktion; ohne sie gilt `OPAA_OPENAI_BASE_URL` für beide.

> **Es gibt keine technische Sperre**, die einen Aufruf außerhalb festgelegter Netzbereiche
> verhindert. Wer die Voreinstellung ändert, kann jedes erreichbare Ziel eintragen, und OPAA hält
> ihn nicht auf. Wer zusichern muss, dass keine Daten das Haus verlassen, weist die Konfiguration nach
> und sichert den Netzweg außerhalb von OPAA ab, etwa über die Firewall-Regeln der Umgebung, in der
> das Backend läuft. Jede Änderung des Chat-Modells über die Administrationsoberfläche ist
> protokolliert und einer Person zurechenbar.

### Temperatur und maximale Antwortlänge

**Die Temperatur ist optional, und „keine Angabe" ist die Voreinstellung.** Ein Eintrag ohne
Temperatur schickt den Parameter gar nicht mit, das Modell verwendet dann seine eigene
Voreinstellung. Manche Modelle akzeptieren keine Temperatur außer ihrer eigenen: Bei neueren
Anthropic-Modellen (etwa Claude Haiku 5.5 über die openai-kompatible Schnittstelle) lehnt der
Anbieter jeden anderen Wert mit HTTP 400 ab, und dann scheitert jede Chat-Anfrage. Für solche
Modelle das Feld Temperatur leer lassen. Weitere Sampling-Parameter (`top_p`, `top_k`) sendet OPAA
nie.

Der **Verbindungstest** der Modellverwaltung schickt die eingetragene Temperatur mit, genau wie
später der Chat. Lehnt der Anbieter die Anfrage ab, zeigt der Test dessen Begründung an und weist
bei einer abgelehnten Temperatur darauf hin, das Feld leer zu lassen. Dieselbe Prüfung läuft für
das aktive Modell in der Statusanzeige der Suche.

**Die maximale Antwortlänge** (Token) begrenzt die Ausgabe des Modells. Bei Modellen mit internem
Reasoning zählt dieses Reasoning mit, ohne in der Antwort sichtbar zu werden; ein knapper Wert
schneidet die sichtbare Antwort deshalb mitten im Satz ab oder lässt sie ganz leer. Das Formular der
Modellverwaltung schlägt für neue Einträge einen fest eingestellten Wert vor, der darauf ausgelegt
ist; `OPAA_OPENAI_CHAT_MAX_TOKENS` bestimmt nur den anfänglichen Eintrag beim ersten Start. Hält ein
Modell die Grenze trotzdem nicht ein, erkennt OPAA das: Bricht das Modell an der Grenze ab oder
liefert es keinen Text, protokolliert das Backend eine Warnung, und unter der Antwort erscheint ein
Hinweis, dass sie unvollständig ist beziehungsweise fehlt. Abhilfe ist eine höhere maximale
Antwortlänge in der Modellverwaltung. Der Hinweis gilt nur für die gerade erzeugte Antwort; im
gespeicherten Verlauf erscheint er nach dem Neuladen nicht mehr.

### Denktext im Antworttext

Manche Anbieter liefern das Nachdenken eines Modells nicht getrennt, sondern am Anfang des
Antworttexts als Block `<think>…</think>` oder `<thought>…</thought>` — etwa Gemma 4 über die
openai-kompatible Schnittstelle von Gemini. OPAA entfernt solche Blöcke bei jedem Aufruf des aktiven
Chat-Modells (Antwort, Zerlegung in Teilfragen, Chat-Titel, Gesprächsnotiz, Metadaten-Extraktion),
bevor der Text weiterverarbeitet wird. Erkannt wird nur eine Antwort, die mit dem öffnenden Tag
beginnt; entfernt wird nur, was ganz am Anfang steht, dieselben Zeichenfolgen weiter hinten bleiben
Teil der Antwort. Denktext ohne öffnendes Tag (nur `…</think>` vor der Antwort) bleibt stehen; das
kommt bei manchen selbst betriebenen Modellen vor, deren Chat-Template das öffnende Tag schon in den
Prompt setzt. Ist ein führender Block nicht geschlossen, gilt die Antwort als leer, gleich aus
welchem Grund das Modell abgebrochen hat, und unter ihr erscheint der Hinweis auf die fehlende
Antwort. Ein Schalter je Modell ist dafür nicht nötig. Der Verbindungstest weist darauf hin, wenn
die Testantwort mit einem Denkblock beginnt, etwa bei einem Modell, das über einen Server ohne
Reasoning-Parser mit `<think>` anfängt. Gemma über die Gemini-Schnittstelle liefert auf die kurze
Testanfrage gar keinen Text; dort erscheint der Hinweis nicht.

## Chat-Modelle in der Oberfläche anlegen

Unter **Administration → Modelle → Chat-Modelle** legt ein Systemverwalter Chat-Modelle an,
bearbeitet, testet und aktiviert sie. Das Formular führt in drei Abschnitten:

1. **Endpunkt.** Das Feld „Basis-Adresse“ schlägt beim Öffnen Vorlagen vor; jede andere
   openai-kompatible Adresse (vLLM, LiteLLM, Azure …) wird einfach eingetragen.

   | Vorlage | Trägt ein | Hinweis |
   |---|---|---|
   | Ollama | `http://ollama:11434/v1` | Der Host `ollama` ist danach markiert und wird mit dem eigenen überschrieben (siehe unten). |
   | OpenAI | `https://api.openai.com/v1` | nur mit Zugangsschlüssel |
   | Anthropic (Claude) | `https://api.anthropic.com/v1` | nur mit Zugangsschlüssel; das Feld Temperatur leer lassen |

   Typische Ollama-Adressen, je nachdem, wo Ollama vom Backend aus erreichbar ist:

   | Ollama läuft | Basis-Adresse |
   |---|---|
   | im Compose-Stack (nächster Abschnitt) | `http://ollama:11434/v1` |
   | auf dem Docker-Host, OPAA im Container | `http://host.docker.internal:11434/v1` |
   | im Kubernetes-Cluster | `http://ollama.<namespace>.svc.cluster.local:11434/v1` |
   | auf demselben Rechner, Backend ohne Container (`./gradlew bootRun`) | `http://localhost:11434/v1` |

   Mit eingeschalteten Ausgangs-Policies braucht das Backend im Cluster eine Ausgangsregel zu Port
   11434 von Ollama ([Kubernetes, NetworkPolicies](kubernetes.md#networkpolicies)). Der
   Zugangsschlüssel ist optional; er wird verschlüsselt gespeichert und nie wieder angezeigt.
   Lehnt ein Anbieter die Modellliste mit seinem Schlüssel ab (etwa weil er sie nur in eigener
   Form anbietet), nennt das Formular den Grund, und die Kennung wird von Hand eingetragen.
2. **Modell.** „Modelle abrufen" (oder Enter im Adressfeld) fragt den Endpunkt nach seinen Modellen
   (`GET {Basis-Adresse}/models`). Die Vorlagen für OpenAI und Anthropic rufen sofort ab, wenn schon
   ein Schlüssel eingetragen ist; die Ollama-Vorlage nie, weil der Host erst zu bestätigen ist. Die
   Modell-Kennung ist danach eine durchsuchbare Auswahl. Erkennt
   OPAA hinter einer Adresse auf `/v1` ein Ollama, zeigt jede Zeile zusätzlich Parameterzahl, Größe,
   Kontextlänge und Fähigkeiten (Werkzeuge, Bilder, Denkmodus), gelesen über Ollamas eigene
   Schnittstelle (`/api/tags`, `/api/show`) auf derselben Adresse. Andere Server liefern meist nur die
   Kennungen; eine Kontextlänge übernimmt OPAA, wenn der Server sie in der Liste mitschickt.
   Reine Einbettungsmodelle stehen in einer eigenen Gruppe „Nicht für den Chat geeignet" und sind nicht
   wählbar. Eine Kennung lässt sich immer auch von Hand eintragen.
3. **Anzeige und Antwort.** Die Wahl eines Modells belegt den Anzeigenamen mit der Kennung (ohne
   `:latest` und ohne Anbieterpfad). Ist das Kontextfenster des Modells bekannt, begrenzt sie die
   maximale Antwortlänge auf die Hälfte davon, falls der Vorschlagswert für neue Einträge (siehe
   [„Temperatur und maximale Antwortlänge"](#temperatur-und-maximale-antwortlänge)) nicht
   hineinpasst; die andere Hälfte brauchen Frage, Gesprächsverlauf und gefundene Textstellen. Was
   von Hand eingetragen wurde, überschreibt die Vorbelegung nicht; beim Bearbeiten bleibt die
   gespeicherte Antwortlänge. Die Temperatur bleibt ohne
   Eintrag leer.

Nach der Wahl eines Modells läuft der Verbindungstest automatisch; „Verbindung testen" wiederholt
ihn jederzeit. Kann der Endpunkt keine Liste liefern, nennt das Formular den Grund und bleibt von
Hand bedienbar:

| Meldung | Bedeutung |
|---|---|
| Host nicht gefunden, Verbindung abgelehnt, Zeitlimit, Zertifikat | Der Endpunkt ist von OPAA aus nicht erreichbar. Aus dem Backend-Container heraus zählt dessen Netz, nicht das des Browsers. |
| Zugangsschlüssel verlangt oder abgelehnt (HTTP 401), Zugriff verweigert (HTTP 403) | Schlüssel fehlt oder passt nicht. |
| keine Modellliste | Der Server antwortet, bietet `/models` aber nicht an. Die Kennung von Hand eintragen. |
| Der Endpunkt meldet keine Modelle | Die Liste ist leer, bei Ollama ist noch kein Modell geladen (`ollama pull …`). |
| Umleitung, anderer HTTP-Status, Liste zu groß | Der Server antwortet unerwartet. Umleitungen folgt OPAA nicht; die Zieladresse direkt eintragen. |

Wie beim Verbindungstest gilt beim Bearbeiten: Ohne neu eingetragenen Schlüssel nutzt OPAA den
gespeicherten, aber nur für eine Adresse mit demselben Ursprung (Schema, Host, Port) wie die
gespeicherte. Bei einer anderen Adresse lehnt OPAA die Anfrage ab, statt den Schlüssel dorthin zu
senden; dann den Schlüssel neu eintragen. Eine Adresse mit Anmeldedaten
(`https://benutzer:passwort@host`) wird abgelehnt. Abfrage und Ollama-Details haben eine kurze
Zeitgrenze und eine Größengrenze, damit ein langsamer Endpunkt das Formular nicht aufhält.

## Erforderliche Variablen

Im Standardfall — lokal betriebene Modelle über die openai-kompatible Schicht für Chat und
Einbettung (der einzige Anbindungsweg) — ist **keine** Modellvariable erforderlich. Der
Stack startet ohne zusätzliche Angabe; die Basis-Adresse zeigt bereits auf einen lokal betriebenen
Ollama-Server (`http://localhost:11434/v1` bzw., im Profil `docker`, `http://ollama:11434/v1`).

Wer stattdessen einen anderen Anbieter für Chat oder Einbettung verwenden will, muss die
**Zieladresse überschreiben**:

```env
OPAA_OPENAI_BASE_URL=https://modellserver.example.internal/v1
OPAA_OPENAI_API_KEY=sk-your-key-here
```

Die Adresse hat eine Voreinstellung — sie zeigt aber auf einen lokalen
Endpunkt, nicht nach außen; eine Installation, die im Haus bleiben soll, wird dadurch nicht
stillschweigend nach außen gerichtet. Ein **explizit leer gesetzter** Wert (etwa
`OPAA_OPENAI_BASE_URL=` in einer `.env`-Datei) überschreibt diese Voreinstellung dagegen mit einer
leeren Zeichenkette und lässt das Backend mit einer Meldung abbrechen, die die betroffene Variable
benennt (`io.opaa.config.OpenAiBaseUrlGuard`).

> **Update-Hinweis für Bestandsinstallationen (Modellverwaltung, seit 22.08.2026).** Das Backend
> übernimmt beim ersten Start nach dem Update die obige Konfiguration einmalig als
> verwaltetes Chat-Modell (siehe [„LLM-Anbieter"](#llm-anbieter) unten).
> Das erfordert **keine** neue Variable für den Normalfall — auch nicht für `docker,oidc` oder eine
> reine Ollama-Installation ohne jeden Zugangsschlüssel. Nur wer bereits mit gesetztem
> `OPAA_OPENAI_API_KEY` betrieben wird, sollte vor dem Update `OPAA_SETTINGS_ENCRYPTION_KEY` setzen
> (siehe [„Verschlüsselung der Zugangsschlüssel verwalteter Chat-Modelle"](#verschlüsselung-der-zugangsschlüssel-verwalteter-chat-modelle))
> — ohne ihn scheitert nur diese einmalige Übernahme, der Start selbst nicht.

## vLLM / OpenAI-kompatible Server

Bei Verwendung von vLLM oder anderen OpenAI-kompatiblen Servern, die HTTP/2 nicht unterstützen, HTTP/1.1-Modus aktivieren:

```env
OPAA_HTTP_FORCE_HTTP1=true
OPAA_OPENAI_BASE_URL=http://your-vllm-server:8000/v1
```

Dies ist erforderlich, weil Spring Boots Standard-HTTP-Client HTTP/2 bevorzugt, was bei Uvicorn-basierten Servern wie vLLM zu Verbindungsfehlern führt.

## Reranking einschalten

Reranking ist voreingestellt **aus** (`OPAA_RERANK_ENABLED=false`). Die Modellrolle ist gebaut, aber
nicht aktiviert: Sie braucht einen eigenen Endpunkt, und ob sie auf einem gegebenen Bestand etwas
bringt, wird gemessen und nicht vermutet. Der Qualitätsnutzen ist auf den Korpora des Projekts
gezeigt; die Voreinstellung bleibt trotzdem aus, solange kein Latenz-/Hardwareprofil vorliegt — ein
Qualitätsgewinn, dessen Preis niemand kennt, ist keine Grundlage für eine Aktivierungsempfehlung.
Nach demselben Prinzip — nur, was gemessen besser ist, wird voreingestellt aktiv — ist auch die
Vielfaltsauswahl MMR per Voreinstellung abgeschaltet.

Zum Einschalten gehören drei Angaben zusammen: `OPAA_RERANK_ENABLED=true`, `OPAA_RERANK_BASE_URL`
und `OPAA_RERANK_MODEL`. Fehlt eine der beiden Endpunktangaben oder antwortet der Endpunkt nicht,
meldet die Anwendung das im Log und führt den Zustand auf der Seite „Suche & Indexierung“
fortlaufend mit; die Suche läuft dann ohne Reranking weiter — ein Ausfall kostet die Sortierung,
nie die Antwort.

> **`OPAA_QUERY_FETCH_K` mit anheben.** Der Reranker bewertet nur, was die Suchstufen zuvor
> zurückgegeben haben. Bei `OPAA_QUERY_FETCH_K=25` liefern Vektor- und Volltextpfad je Teilfrage
> zusammen höchstens 50 verschiedene Abschnitte; ein Kandidatenfenster von 50
> (`OPAA_QUERY_RERANK_CANDIDATE_COUNT`) ist damit gerade gedeckt, ein größeres läuft ins Leere. Wer
> dem Reranking mehr Material geben will — und genau dafür ist es da: eine Fundstelle aus der Tiefe
> nach vorn zu holen —, hebt `OPAA_QUERY_FETCH_K` mit an. Reranking allein einzuschalten und die
> Abrufbreite zu lassen, wie sie ist, wirkt nur zur Hälfte.

> **Proxy-Zeitgrenzen mit anheben.** `OPAA_RERANK_TIMEOUT` bindet nur den Rerank-Aufruf selbst,
> nicht die HTTP-Anfrage der Suche als Ganzes: Eine Suche mit CPU-Reranker kann minutenlang still
> warten, bevor die Antwort kommt, und die Modellaufrufe der Anfrage kommen hinzu. Der nginx im
> Frontend-Container wartet `OPAA_BACKEND_READ_TIMEOUT` lang; sein Startwert deckt das
> voreingestellte Rerank-Budget. Wer `OPAA_RERANK_TIMEOUT` anhebt, hebt
> `OPAA_BACKEND_READ_TIMEOUT` mit an, ebenso die Zeitgrenze jedes Reverse-Proxys davor (bei nginx
> `proxy_read_timeout`, dessen Vorgabe nur eine Minute beträgt). Sonst endet die Anfrage beim
> Client mit `504` oder einem Verbindungsabbruch, ohne Fehler im Backend-Log.

## Verschlüsselung der Zugangsschlüssel verwalteter Chat-Modelle

Chat-Modelle liegen in der Tabelle `llm_models`, nicht in
Umgebungsvariablen (siehe [„LLM-Anbieter"](#llm-anbieter) oben). Ein hinterlegter Zugangsschlüssel (optional — ein lokal betriebener Endpunkt
läuft regelmäßig ohne Authentifizierung) liegt **verschlüsselt** in der Datenbank (AES-256-GCM,
zufälliger Initialisierungsvektor je Wert, `io.opaa.security.SettingsEncryptor`) und erscheint in
keiner API-Antwort und keinem Log — die Verwaltung behandelt das Feld als Nur-Schreiben-Feld.

**Ein eigener Schlüssel, getrennt von `OPAA_CREDENTIALS_ENCRYPTION_KEY`:**

```bash
openssl rand -base64 32
```

Das Ergebnis (ein Base64-kodierter 256-Bit-Schlüssel) als `OPAA_SETTINGS_ENCRYPTION_KEY` setzen —
**nicht** ins Repository committen, wie jede andere Zugangsinformation außerhalb von
Umgebungsvariablen behandeln. Ein eigener Schlüssel statt Wiederverwendung von
`OPAA_CREDENTIALS_ENCRYPTION_KEY`, weil beide unterschiedliche Geheimnisse schützen (Zugangsdaten
einer Wissensquelle vs. der Zugangsschlüssel eines Chat-Modells) und keinen Grund haben, dieselbe
Rotation zu teilen.

**Kein Zwang, keinen zu haben:** Ohne gesetzten Schlüssel startet das Backend normal — eine
Installation, die ausschließlich lokale Modelle ohne Zugangsschlüssel führt (der Normalfall), braucht ihn nie.
Erst der Versuch, ein Chat-Modell **mit** Zugangsschlüssel anzulegen oder zu ändern, schlägt ohne
gültigen Schlüssel mit einer klaren deutschen Meldung fehl (`io.opaa.security.SettingsEncryptor`),
die die fehlende oder ungültige Variable benennt. Für die Seed-Migration, die beim ersten Start eine
bestehende `openai`-Konfiguration samt Zugangsschlüssel übernimmt (siehe [„LLM-Anbieter"](#llm-anbieter) oben), gilt das
mit einer Einschränkung: Dort führt derselbe fehlende oder ungültige Schlüssel **nicht** zum
Startabbruch, sondern nur zu einer ERROR-Log-Zeile — siehe den Update-Hinweis unten. Für lokale
Entwicklung und Tests (nur Profil `dev`, das jede Testsuite und `bootRun` aktivieren — nicht
`local`) ist ein
fest hinterlegter, **ausdrücklich nicht produktionstauglicher** Schlüssel voreingestellt
(`backend/src/main/resources/application.yml`), damit beide ohne Betreiber-Eingriff laufen.

> **Update-Hinweis für Bestandsinstallationen.** Eine Installation, die heute
> ausschließlich Ollama oder einen `openai`-kompatiblen Endpunkt ohne Zugangsschlüssel nutzt, läuft
> nach dem Update auf diese Version unverändert weiter, **ohne** `OPAA_SETTINGS_ENCRYPTION_KEY`
> gesetzt haben zu müssen. Nur wer die openai-kompatible Anbindung bereits **mit** gesetztem
> Zugangsschlüssel betreibt, sollte den Schlüssel **vor** dem Update setzen — sonst schlägt die
> einmalige Übernahme dieser Konfiguration als initiales Modell fehl: Der Start selbst bricht nicht
> ab, aber das Modell bleibt unangelegt und es erscheint eine ERROR-Meldung im Log. Kein
> Seed-Marker wird dabei geschrieben — sobald der Schlüssel gesetzt ist, holt der **nächste Neustart**
> die Übernahme automatisch nach, ohne dass etwas anderes zu tun wäre. Wer nicht neu starten will
> oder kann, legt das Modell in der Zwischenzeit **ohne** Zugangsschlüssel über die
> Verwaltungsoberfläche an (`OPAA_SETTINGS_ENCRYPTION_KEY` bleibt auch dafür Voraussetzung, sobald
> tatsächlich ein Zugangsschlüssel eingetragen werden soll — die Oberfläche ruft dafür dieselbe
> `SettingsEncryptor#encrypt`-Prüfung auf wie die Seed-Migration).

**Bei Schlüsselverlust:** Bereits verschlüsselte Zugangsschlüssel sind ohne den ursprünglichen
Schlüssel nicht wiederherstellbar — es gibt keinen Wiederherstellungsweg außerhalb des Schlüssels
selbst. Betroffen ist ausschließlich der Zugangsschlüssel des jeweiligen Modells, nicht der übrige
Modelleintrag (Basis-Adresse, Modell-Kennung, Parameter) oder andere verschlüsselte Werte. Abhilfe:
den Zugangsschlüssel für das betroffene Modell über die Verwaltungsoberfläche neu eintragen.
