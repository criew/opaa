# Modelle

OPAA bringt kein eigenes Sprachmodell mit, sondern ruft Modelle über Endpunkte auf, die das Haus
festlegt — im eigenen Rechenzentrum oder bei einem Anbieter. Dieses Kapitel beschreibt, welche
Modelle es braucht, wie sie angebunden werden und woran es liegt, wenn sie nicht antworten. Wie die
Modelle in die Suche eingreifen, steht im Kapitel [Suche](suche.md).

> **Beispiel.** Ein Haus betreibt einen Modellserver im Rechenzentrum. Die Einbettung läuft dort mit
> `bge-m3` und wird einmal in der Umgebung festgelegt. Für den Chat legt die Systemverwaltung in der
> Oberfläche zwei Modelle an, ein schnelles für den Alltag und ein größeres zum Vergleich, und
> schaltet bei Bedarf um — ohne Neustart und ohne den Index anzufassen.

## Vier Modellrollen

| Rolle | Wofür | Festgelegt über | Wechsel |
|---|---|---|---|
| **Chat** | Antwort, Zerlegung in Teilfragen, Chat-Titel, Gesprächsnotiz, Metadaten-Extraktion | Modellverwaltung in der Oberfläche; die Umgebung nur beim allerersten Start | jederzeit, ohne Neustart; berührt den Index nicht |
| **Embedding** | Vektoren der Chunks und der Suchanfragen | Umgebung (`OPAA_OPENAI_EMBEDDING_*`, `OPAA_PGVECTOR_DIMENSIONS`) | mit Neustart; danach bettet die Systemverwaltung den Bestand neu ein ([Modell wechseln](#modell-wechseln)) |
| **Reranking** | ordnet die Kandidaten der Suche neu, optional | Umgebung (`OPAA_RERANK_*`) | jederzeit mit Neustart; aus ist die Voreinstellung |
| **Transkription** | wandelt eine im Chat diktierte Frage in Text um, optional | Umgebung (`OPAA_TRANSCRIPTION_*`) | jederzeit mit Neustart; aus ist die Voreinstellung ([Spracheingabe einschalten](#spracheingabe-einschalten)) |

## LLM-Anbieter

Es gibt einen einzigen Anbindungsweg für Chat und Einbettung: die **openai-kompatible
Schnittstelle**. (Die optionale Rerank-Rolle hat eigene Angaben, siehe
[Reranking einschalten](#reranking-einschalten).) Das ist eine Protokollangabe, keine Anbieterangabe — Ollama bedient sie unter seinem
eigenen `/v1`-Pfad, ebenso vLLM, LiteLLM, Azure und die üblichen Zwischenschichten.

**Voreingestellt sind lokal betriebene Modelle.** Eine Installation, an der niemand etwas
konfiguriert, ruft kein Modell außerhalb des Hauses auf: `OPAA_OPENAI_BASE_URL` zeigt auf
`http://localhost:11434/v1` (im Profil `docker` auf `http://ollama:11434/v1`), Chat- und
Embedding-Modell sind `phi3:mini` und `bge-m3`, und Ollama braucht keinen Zugangsschlüssel.
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
weiterverarbeitet wird — nur ganz am Anfang. Ist ein führender Block nicht geschlossen, gilt die
Antwort als leer, und unter ihr erscheint der Hinweis auf die fehlende Antwort.

Bei manchen selbst betriebenen Modellen, etwa DeepSeek-R1 oder Qwen3-Thinking auf einem Server ohne
Reasoning-Parser, steht das öffnende `<think>` schon in der Anfrage. Die Antwort beginnt dann direkt
mit dem Denktext und enthält nur das schließende Tag, auf eigener Zeile vor der Antwort. OPAA entfernt
auch diesen Denktext, bis zum ersten `</think>` im Text — aber nur, wenn dieses allein auf seiner
Zeile steht, höchstens drei Leerzeichen eingerückt, und davor weder ein öffnendes Tag noch ein
offener Codeblock steht. Eine Antwort, die das Tag im Satz erwähnt oder in einem Codeblock zitiert,
bleibt so unverändert. Bricht ein solches Modell schon beim Nachdenken ab,
fehlt jedes Tag; der Denktext bleibt dann als Antwort stehen.

Ein Schalter je Modell ist nicht nötig; der Verbindungstest weist darauf hin, wenn die Testantwort
mit einem Denkblock beginnt. Denktext ohne öffnendes Tag erkennt er nicht, weil die Testantwort
dafür zu kurz ist.

## Embedding

Das Embedding-Modell legt die Umgebung fest, fortlaufend und nicht über die Oberfläche:
`OPAA_OPENAI_EMBEDDING_MODEL`, die Adresse aus `OPAA_OPENAI_EMBEDDING_BASE_URL` oder
`OPAA_OPENAI_BASE_URL`, der Schlüssel entsprechend, dazu `OPAA_PGVECTOR_DIMENSIONS` — die Zahl der
Dimensionen, mit der das Modell einbettet. Die Oberfläche zeigt die Einbettung nur an.

Eine Auswahlliste gibt es bewusst nicht: Ein Wechsel macht alle vorhandenen Vektoren unvergleichbar,
und ein versehentlich verstellter Wert legte die Vektorsuche bis zur Neueinbettung still.

> **Ältere Ollama-Fassungen betten lange Chunks falsch ein.** Sie zerlegen eine Eingabe über 512
> Token in mehrere Teile. Embedding-Modelle wie `nomic-embed-text` und `bge-m3` lesen einen Text
> aber als Ganzes, und der Vektor eines längeren Chunks trifft dann seinen Inhalt nicht mehr. Bei
> durchgehendem Fließtext ist mit der voreingestellten Chunkgröße fast jeder Chunk so lang, bei kurzen,
> nach Überschriften gegliederten Abschnitten kaum einer. Betroffen ist etwa Ollama 0.6.5;
> Ollama 0.32.15 und neuer bettet stetig ein, auch die Fassung im Compose-Stapel und im
> Erprobungsaufbau für Kubernetes. Wer Ollama selbst betreibt, nimmt 0.32.15 oder neuer und
> indiziert nach dem Wechsel vollständig neu ([Was ein Update mit dem Index macht](betrieb.md#was-ein-update-mit-dem-index-macht)).

Ein Embedding-Aufruf darf so lange dauern, wie `OPAA_OPENAI_EMBEDDING_TIMEOUT` erlaubt. Ein großes
Modell auf einer CPU braucht für einen Stapel von Chunks leicht länger; dann bricht der Aufruf ab,
wird wenige Male wiederholt und scheitert schließlich mit seinem Dokument. Die Neueinbettung bricht
nach fünf gescheiterten Dokumenten in Folge mit „fehlgeschlagen" ab.

### Welches Embedding-Modell

Das Embedding-Modell entscheidet, ob die Vektorsuche eine Frage und die passende Stelle als ähnlich
erkennt. Wie gut ihm das gelingt, hängt an der Sprache, in der es trainiert wurde. Für die
Suchqualität zählt deshalb vor allem, dass das Modell die Sprache der Dokumente beherrscht.

| Modell | Dimensionen | Empfehlung | Ressourcen |
|---|---|---|---|
| `bge-m3` (Vorgabe) | 1024 | deutschsprachige Bestände | Download rund 1,2 GB. Geladen rund 1,4 GB Arbeitsspeicher, beim Einbetten mehr, je länger der Chunk ist: rund 3,3 GB bei 2.000 Tokens, rund 9,5 GB bei 8.192 Tokens. Auf einer CPU etwa doppelt so lange Indexierung wie `nomic-embed-text` |
| `nomic-embed-text` | 768 | kleine Instanzen mit wenig Arbeitsspeicher, nur CPU | Download rund 0,3 GB, beim Einbetten rund 0,3 GB Arbeitsspeicher. Findet in deutschsprachigen Beständen deutlich schlechter |

> **Beispiel.** In einer Messung auf 109 Verwaltungs-PDFs standen mit `bge-m3` 85 % der gesuchten
> Kernfakten unter den ersten 20 Treffern der Vektorsuche, mit `nomic-embed-text` 37 %.

Wer `nomic-embed-text` wählt, setzt `OPAA_PGVECTOR_DIMENSIONS` auf 768. Auf einem kleinen Server
mit `bge-m3` lässt sich der Spitzenbedarf senken, indem das Modell nicht mit der vollen
Eingabegrenze angelegt wird; OPAA teilt dann längere Chunks
([Eingabegrenze](#eingabegrenze-des-embedding-modells)).

Eine bestehende Installation wechselt das Modell über die Neueinbettung, ohne Neuindizierung und
ohne Zurücksetzen der Datenbank ([Modell wechseln](#modell-wechseln)).

### Eingabegrenze des Embedding-Modells

Ein Embedding-Modell nimmt je Chunk nur eine bestimmte Zahl Tokens auf. Manche Endpunkte kürzen
einen längeren Chunk ohne Meldung. Sein Vektor deckt dann nur den Anfang ab, und der Rest ist für
die Vektorsuche unsichtbar.

> **Beispiel.** Ein Haushaltsplan enthält eine Tabelle voller Zahlen. Ihr Chunk hat nur so viele
> Zeichen wie die anderen, braucht aber fast dreimal so viele Tokens. Der Endpunkt meldet die
> Grenze als Tokenzahl, OPAA teilt den Chunk in höchstens halb so lange Teile, und jeder wird
> ganz eingebettet.

Die Zielgröße der Chunks wird in Zeichen eingestellt (`OPAA_INDEXING_CHUNK_TARGET_CHARACTERS`), die
Grenze des Modells zählt Tokens. Wie viele Zeichen ein Token umfasst, hängt von Modell und Text ab.
Deutscher Fließtext braucht wenige Tokens je Zeichen, Tabellen mit vielen Zahlen deutlich mehr.
Die Zielgröße allein garantiert deshalb nicht, dass jeder Chunk passt.

`OPAA_OPENAI_EMBEDDING_MAX_INPUT_TOKENS` nennt die Grenze des Endpunkts, wie er tatsächlich läuft.
Kann ein Chunk sie erreichen, bettet OPAA ihn einzeln ein. Lehnt der Endpunkt ihn als zu lang ab
oder meldet er eine Tokenzahl an der Grenze, teilt OPAA den Chunk an Absatz-, Zeilen- oder
Satzgrenzen in zwei oder drei Teile mit höchstens halber Länge und prüft sie erneut. Jeder Teil
behält Fundort und Überschrift. Kürzere Chunks laufen wie bisher gesammelt und kosten keinen eigenen
Aufruf. Die Einzelaufrufe laufen so parallel wie die Stapel (`OPAA_INDEXING_EMBEDDING_CONCURRENCY`);
eine zu niedrig eingestellte Grenze kostet trotzdem viele Einzelaufrufe und teilt Chunks, die
gepasst hätten. Jede Teilung steht im Log und in der Kennzahl
`opaa_indexing_embedding_inputs_total` ([Logs und Metriken](betrieb.md#logs-und-metriken)).

Typische Werte: ein unverändert gezogenes Ollama-Modell 2.048, die Embedding-Modelle von OpenAI
8.191.

- **Ohne Tokenzahl:** Meldet der Endpunkt keine Tokenzahl, bleibt der Chunk ganz. Das Log warnt,
  dass eine Kürzung nicht auszuschließen ist.
- **Ohne neues Zerlegen:** Die Neueinbettung nach einem Modellwechsel und der Nachlauf des
  Kontextpräfixes behalten den Zuschnitt. Kürzt der Endpunkt dort einen Chunk, behält OPAA den
  gekürzten Vektor, damit der Lauf durchläuft und die Vektorsuche umschalten kann. Das Log warnt,
  die Kennzahl zählt den Chunk als `cut`, und das Dokument wird für den
  [Nachzug](wissensbibliotheken.md#72-pipeline-versionen-und-nachzug) vorgemerkt. Der Nachzug liest
  es aus der Quelle neu und teilt den Chunk dabei. Nur wenn der Endpunkt den Chunk ablehnt statt
  ihn zu kürzen, scheitert das Dokument.
- **Grenze zu hoch eingestellt:** Kürzt der Endpunkt früher als eingestellt, bemerkt OPAA das nicht.
  Die Einstellung muss zum Endpunkt passen.

**Ollama.** Über den OpenAI-kompatiblen Weg, den OPAA nutzt, kürzt Ollama ohne Meldung und zählt
nur die behaltenen Tokens. Die Grenze ist dabei die Stapelgröße des Modells (`num_batch`), nicht
seine Kontextlänge. Ein unverändertes Modell nimmt deshalb 2.048 Tokens auf, auch wenn es mehr
kann. `OLLAMA_CONTEXT_LENGTH` hebt die Grenze nicht, und Optionen in der Anfrage wirken auf diesem
Weg nicht. Mehr nimmt ein Modell nur auf, wenn es mit `PARAMETER num_ctx` und
`PARAMETER num_batch` in einer Modelfile neu angelegt wird. Der Compose-Stapel legt `bge-m3` so an
([Docker Compose](docker-compose.md#lokal-betriebenes-ollama-im-compose-stack)); bei einem
eigenen Ollama-Server dieselben Schritte:

```bash
ollama pull bge-m3
printf 'FROM bge-m3\nPARAMETER num_ctx 8192\nPARAMETER num_batch 8192\n' > Modelfile
ollama create bge-m3 -f Modelfile
```

Der Name bleibt `bge-m3`. Erst danach `OPAA_OPENAI_EMBEDDING_MAX_INPUT_TOKENS` auf `8192` setzen;
die Vorgabe passt zu jedem unverändert gezogenen Modell. Ein späteres `ollama pull bge-m3` stellt
die alten Werte wieder her; danach das Anlegen wiederholen. Der Arbeitsspeicher wächst mit der Länge der tatsächlichen Eingabe,
nicht mit der eingestellten Grenze.

### Modell wechseln

> **Beispiel.** Ein Haus stellt von `nomic-embed-text` auf `bge-m3` um, das deutsche Texte besser
> trifft. Es setzt Modell und Dimension in der Umgebung, startet neu und lässt den Bestand über
> Nacht neu einbetten. Konten, Rechte, Spaces und Chats bleiben, und der Chat beantwortet Fragen auch
> während der Umstellung — nur ohne die Vektorsuche.

1. **Umgebung ändern und neu starten.** `OPAA_OPENAI_EMBEDDING_MODEL` und
   `OPAA_PGVECTOR_DIMENSIONS` gemeinsam ändern, auf einer CPU auch `OPAA_OPENAI_EMBEDDING_TIMEOUT`
   (siehe unten). Das Modell muss im Endpunkt vorhanden sein.
2. **OPAA erkennt den Wechsel beim Start** und startet trotzdem, auch bei einer anderen Dimension.
   Die Seite „Suche & Indexierung" zeigt im Reiter „Indexstatus" den Hinweis „Index veraltet" mit
   beiden Modellen, der Suchpfad Vektorsuche steht auf „Ausgesetzt". Bis zum Abschluss findet die
   Suche Chunks nur über die Volltextsuche; der Chat zeigt dazu den Hinweis „Eingeschränkte Suche".
   Was in dieser Zeit neu indiziert wird, bekommt schon Vektoren des neuen Modells.
3. **„Neueinbettung starten".** Der Lauf bettet die gespeicherten Chunks mit dem neuen Modell neu
   ein: Text, Zuschnitt, Fundstellen und Dokumente bleiben, Quellen werden nicht erneut gelesen.
   Die Seite zeigt den Fortschritt; Chunks privater Bibliotheken zählt sie nur mit, wenn genug
   Personen solche führen (Mindestgruppengröße). „Neueinbettung abbrechen" hält den Lauf nach dem aktuellen
   Dokument an, das bereits Eingebettete bleibt erhalten, und „Neueinbettung fortsetzen" macht dort
   weiter. Ein Neustart während des Laufs setzt ihn von selbst fort.
4. **Abschluss.** Haben alle Chunks einen neuen Vektor, stellt OPAA die Vektorablage um, bei einer
   anderen Dimension samt Index, und die Vektorsuche läuft wieder. Start, Abbruch und Ende stehen im
   Revisionsprotokoll.
5. **Nachzug, falls gemeldet.** Zählt das neue Modell einen Chunk über der Eingabegrenze, etwa eine
   Zahlentabelle, die `bge-m3` in mehr Tokens zerlegt als `nomic-embed-text`, bettet die
   Neueinbettung ihn gekürzt ein und merkt sein Dokument für den Nachzug vor; das Log nennt die
   Pipeline. Danach den Nachzug dieser Pipeline über die Admin-API aufrufen, mit ihrer Kennung als
   `pipelineId` und ihrer aktuellen Version als `belowVersion`, bis er `done` meldet
   ([Pipeline-Versionen und Nachzug](wissensbibliotheken.md#72-pipeline-versionen-und-nachzug)). Er
   teilt den Chunk.

Ein Wechsel nur des Endpunkts, etwa auf einen anderen Server mit demselben Modell, gilt nicht als
Modellwechsel. Wer vor dem Abschluss zum alten Modell zurückkehrt, verwirft die neuen Vektoren. Die
bereits neu eingebetteten Chunks behalten ihren alten Vektor. Nur Chunks, die in der Zwischenzeit
neu oder mit geändertem Text indiziert wurden, haben keinen; sie findet nur die Volltextsuche, bis
ihr Dokument erneut indiziert wird. Eine unveränderte Datei überspringt der Abgleich dabei
([Falle bei einer Neuindizierung](betrieb.md#was-ein-update-mit-dem-index-macht)).

**Dauer und Ressourcen.** Die Neueinbettung kostet so viele Embedding-Aufrufe wie eine
Indexierung des Bestands, aber kein Lesen, Zerlegen oder Texterkennen. Auf einer CPU bestimmt das
Modell die Dauer; die Ressourcen der beiden Modelle stehen unter
[Welches Embedding-Modell](#welches-embedding-modell). Die Neueinbettung arbeitet ein Dokument
nach dem anderen ab; wie viele Chunks ein Aufruf trägt, begrenzt allein das Token-Budget eines
Aufrufs. Für ein großes Modell auf einer CPU wirkt deshalb nur eine längere
`OPAA_OPENAI_EMBEDDING_TIMEOUT`. Für die Indexierung neuer Dokumente mit demselben Modell helfen
zusätzlich eine kleinere `OPAA_INDEXING_BATCH_SIZE`, `OPAA_INDEXING_EMBEDDING_CONCURRENCY` von 1 und
eine längere `OPAA_INDEXING_STALE_JOB_TIMEOUT` ([Konfiguration](konfiguration.md)). Wie lang ein
Chunk für das neue Modell sein darf, steht unter
[Eingabegrenze des Embedding-Modells](#eingabegrenze-des-embedding-modells).

Lässt sich ein Dokument nicht einbetten, läuft die Neueinbettung über die übrigen weiter und endet
„fehlgeschlagen"; das Log nennt die Dokumente. Scheitern fünf Dokumente in Folge, bricht sie gleich
ab, weil dann eher Endpunkt oder Zeitgrenze die Ursache sind. Die Vektorsuche bleibt ausgesetzt, bis
ein weiterer Start auch die fehlenden eingebettet hat.

## Reranking einschalten

Reranking ist voreingestellt **aus** (`OPAA_RERANK_ENABLED=false`). Die Rolle braucht einen eigenen
Endpunkt. **Empfohlen ist sie überall, wo ein schneller Endpunkt zur Verfügung steht:** Sie holt
Fundstellen, die im Kandidatenfenster liegen, aber nicht weit genug oben, nach vorn. Was die Stufe in
der Suche tut, steht unter [Suche](suche.md#stufe-8-reranking).

Zur Wahl des Endpunkts:

- **GPU im Haus:** ein Rerank-Modell auf eigener GPU; die Daten bleiben im Netz.
- **Gehosteter Dienst:** ein Endpunkt, der `POST {Basisadresse}/rerank` im erwarteten Format
  beantwortet; welche Dienste das tun, steht bei `OPAA_RERANK_BASE_URL` in der
  [Konfiguration](konfiguration.md). Er erhält Frage und Chunk-Texte der Kandidaten. Für
  Behördendaten nur, wenn der Dienst datenschutzrechtlich zulässig ist; sonst bleibt der Endpunkt im
  eigenen Netz. Die Hinweise unter [LLM-Anbieter](#llm-anbieter) gelten für die Rerank-Rolle
  sinngemäß, auch dass es keine technische Sperre gibt.
- **CPU:** möglich, aber nicht empfohlen. Die Antwortzeit steigt so stark, dass sie für den Dialog
  nicht taugt; das voreingestellte `OPAA_RERANK_TIMEOUT` lässt den Fall nur zu, damit er nicht als
  Ausfall erscheint.

Zum Einschalten gehören drei Angaben zusammen: `OPAA_RERANK_ENABLED=true`, `OPAA_RERANK_BASE_URL` und
`OPAA_RERANK_MODEL`. Fehlt eine Endpunktangabe oder antwortet der Endpunkt nicht, steht das im Log und
fortlaufend auf der Seite „Suche & Indexierung"; die Suche läuft dann ohne Reranking weiter — ein
Ausfall kostet die Sortierung, nie die Antwort.

> **`OPAA_QUERY_FETCH_K` mit anheben.** Der Reranker bewertet nur, was die Suchstufen zuvor geliefert
> haben. Bei `OPAA_QUERY_FETCH_K=25` liefern Vektor- und Volltextpfad je Teilfrage zusammen höchstens
> 50 verschiedene Abschnitte; ein Kandidatenfenster von 50 (`OPAA_QUERY_RERANK_CANDIDATE_COUNT`) ist
> damit gerade gedeckt, ein größeres läuft ins Leere. Wer dem Reranking mehr Material geben will, hebt
> `OPAA_QUERY_FETCH_K` mit an.

> **Prüfabstand bei bezahltem Endpunkt groß wählen.** Ohne Fragen prüft OPAA die Erreichbarkeit mit
> einer echten Rerank-Anfrage, sobald die letzte Antwort länger als `OPAA_RERANK_PROBE_INTERVAL`
> zurückliegt. Ein gehosteter Dienst rechnet jede dieser Prüfungen ab, auch wenn niemand eine Frage
> stellt. Bei einem bezahlten Endpunkt den Abstand deshalb groß wählen, etwa `24h`: Jede Frage mit
> Reranking zählt ohnehin als Prüfung, und ein nicht erreichbarer Endpunkt wird unabhängig vom
> Abstand etwa eine Minute nach dem letzten Fehlschlag erneut geprüft.

> **Zeitgrenzen mit anheben.** `OPAA_RERANK_TIMEOUT` bindet nur den Rerank-Aufruf, nicht die Anfrage
> als Ganzes: Eine Suche mit CPU-Reranker kann minutenlang warten, und die übrigen Modellaufrufe kommen
> hinzu. Wer `OPAA_RERANK_TIMEOUT` anhebt, hebt `OPAA_BACKEND_READ_TIMEOUT` des Frontends mit an und
> die Zeitgrenze jedes Proxys davor ([Netzwerkzugang](betrieb.md#netzwerkzugang)). Sonst endet die
> Anfrage beim Client mit `504` oder einem Verbindungsabbruch, ohne Fehler im Backend-Log.

## Spracheingabe einschalten

Mit der Spracheingabe spricht eine Person ihre Frage ein, statt sie zu tippen: Sie nimmt im Chat auf,
beendet die Aufnahme, und der erkannte Text landet im Eingabefeld (Bedienung unter
[Suche](suche.md#frage-diktieren)). Dafür braucht OPAA eine eigene Modellrolle **Transkription**, die
Sprache in Text umwandelt. Sie ist voreingestellt **aus** (`OPAA_TRANSCRIPTION_ENABLED=false`); ohne
sie zeigt der Chat kein Mikrofon.

> **Ollama kann kein Audio.** Der Modellserver für Chat und Einbettung transkribiert nicht. Die
> Spracheingabe braucht immer einen zweiten Dienst.

### Vorhandenen Endpunkt im Haus eintragen

Der Normalfall ist ein Transkriptionsdienst, den die IT des Hauses bereits betreibt, etwa vLLM,
speaches oder ein LiteLLM-Gateway auf einem zentralen Server mit interner Adresse. Er muss zwei Pfade
der openai-kompatiblen Schnittstelle bedienen:

- `POST {Basis-Adresse}/audio/transcriptions` nimmt die Aufnahme an und antwortet mit dem Text;
- `GET {Basis-Adresse}/models` dient OPAA als Erreichbarkeitsprüfung, beim Start und danach
  regelmäßig. Sie überträgt kein Audio und kostet bei Anbietern mit Abrechnung je Aufruf nichts.

Einzutragen sind `OPAA_TRANSCRIPTION_ENABLED=true`, die Basis-Adresse ohne den Pfad
`/audio/transcriptions` (`OPAA_TRANSCRIPTION_BASE_URL`, etwa `https://stt.landesverwaltung.de/v1`),
die Modell-Kennung (`OPAA_TRANSCRIPTION_MODEL`) und, falls der Dienst ihn verlangt, der
Zugangsschlüssel (`OPAA_TRANSCRIPTION_API_KEY`). Der Schlüssel steht nur in der Umgebung und nur im
`Authorization`-Header der Anfrage, nie in der Datenbank, im Log oder einer Zustandsanzeige. Der
Aufruf geht über denselben [Ausgangsproxy](betrieb.md#ausgangsproxy) und dieselben vertrauten
Zertifizierungsstellen wie die übrigen Modellrollen; eine interne CA wird also wie dort ergänzt.

Fehlt eine Angabe oder antwortet der Dienst nicht, steht das im Log und fortlaufend auf der Seite
„Suche & Indexierung" (Rolle „Transkription") sowie in der Gesundheitsgruppe
`GET /actuator/health/transcription-model`. Der Chat zeigt das Mikrofon dann nicht; alles andere
läuft weiter.

**Was an den Dienst geht:** die Aufnahme, die Modell-Kennung, die Sprache
(`OPAA_TRANSCRIPTION_LANGUAGE`) und die Bitte um eine Antwort als JSON — keine Kennung der Person,
kein Kontoname, keine Adresse des Browsers. Der Dienst sieht nur das Backend von OPAA als Absender.
**Was OPAA behält:** nichts. Die Aufnahme liegt nur während der Anfrage im Arbeitsspeicher des
Backends, nie auf der Platte; weder sie noch der erkannte Text stehen in einem Log. Das
Anfrageprotokoll des Backends nennt für eine Aufnahme nur Methode, Pfad, Status und Dauer, keine
Person. Eine Aufnahme
über `OPAA_TRANSCRIPTION_MAX_AUDIO_BYTES` lehnt das Backend ab, ohne sie weiterzureichen.

### Anforderungen an den Betrieb des Dienstes

OPAA speichert und protokolliert keine Aufnahme; der Transkriptionsdienst muss dasselbe zusichern.
Für die Stelle, die ihn betreibt, heißt das:

- **Keine Ablage der Aufnahmen** und **keine Transkripte im Log.** Anfrage- und Inhaltslogs des
  Dienstes sind abgeschaltet oder auf Fehler beschränkt.
- **Auf verbleibende Logs greift nur der Betrieb zu.**
- **Proxys und Gateways dazwischen** protokollieren keinen Inhalt. Ein Proxy-Log, das Konto oder
  IP-Adresse einer Person mit der Anfrage verbindet, stellt einen Personenbezug her; zwischen Browser
  und OPAA gilt das für den vorgelagerten Reverse-Proxy genauso.
- **Proxys halten die Aufnahme im Arbeitsspeicher.** Viele Reverse-Proxys und Ingress-Controller
  schreiben einen Anfrage-Body, der ihren kleinen Standardpuffer übersteigt, in eine temporäre
  Datei. Der Frontend-Container von OPAA hält eine Aufnahme bis 11 MiB im Speicher; ein Proxy davor
  braucht einen ebenso großen Puffer für den Body, bei nginx und ingress-nginx
  `client_body_buffer_size` bzw. die Annotation `nginx.ingress.kubernetes.io/client-body-buffer-size`
  (Kapitel [Kubernetes](kubernetes.md)).

### Erprobung mit speaches im Compose-Stapel

Für eine Erprobung ohne vorhandenen Dienst lässt sich speaches als zusätzlicher Container neben OPAA
betreiben, etwa in einer `docker-compose.override.yml`. Das CPU-Image kommt ohne Grafikkarte aus,
transkribiert dann aber langsam; `OPAA_TRANSCRIPTION_TIMEOUT` muss die Dauer einer Aufnahme auf der
vorhandenen Hardware abdecken. Der Container braucht `ffmpeg` für die Umwandlung der
Browser-Aufnahme; das Image bringt es mit.

```yaml
services:
  speaches:
    image: ghcr.io/speaches-ai/speaches:latest-cpu
    environment:
      # Beispiel: nur Warnungen und Fehler, damit kein Transkript im Log landet
      LOG_LEVEL: warning
    volumes:
      - speaches-models:/home/ubuntu/.cache/huggingface/hub
volumes:
  speaches-models:
```

```env
OPAA_TRANSCRIPTION_ENABLED=true
OPAA_TRANSCRIPTION_BASE_URL=http://speaches:8000/v1
OPAA_TRANSCRIPTION_MODEL=Systran/faster-whisper-large-v3
```

speaches lädt ein Modell nicht von selbst; es wird einmal über
`POST http://speaches:8000/v1/models/Systran/faster-whisper-large-v3` heruntergeladen, aus einem
Container im selben Netz oder mit vorübergehend veröffentlichtem Port. Die Log-Einstellung ist ein
Beispiel; welche Einstellungen Transkripte und Aufnahmen tatsächlich unterdrücken, steht in der
Dokumentation der eingesetzten Version und ist vor dem Betrieb zu prüfen.

### Externer Anbieter

Technisch funktioniert auch ein Cloud-Anbieter mit derselben Schnittstelle, etwa OpenAI
(`https://api.openai.com/v1`) oder OpenRouter (`https://openrouter.ai/api/v1`); es ändert sich nur
die Konfiguration. **Die Stimme verlässt dann das Haus.** Gesprochene Sprache ist ein biometrienahes
Datum und enthält oft Namen und Vorgänge Dritter; eine solche Anbindung braucht eine eigene
datenschutzrechtliche Prüfung und einen Auftragsverarbeitungsvertrag. Die Oberfläche weist nicht
darauf hin, wohin die Aufnahme geht.

### Spracheingabe und Mitbestimmung

- *Worum es geht:* Die Spracheingabe verarbeitet die Stimme der Beschäftigten. Auch beim Betrieb im
  Haus ist die Stimme ein biometrienahes Datum. OPAA speichert weder Aufnahme noch Text der Aufnahme
  und zählt keine Nutzung je Person; eine Chat-Nachricht trägt kein Merkmal, ob sie diktiert wurde.
- *Warum es zählt:* Technische Einrichtungen, die Leistung oder Verhalten überwachen können, berühren
  die Mitbestimmung der Personalvertretung. Ob die Spracheingabe dazu geeignet ist, hängt auch am
  Betrieb des Transkriptionsdienstes und der Proxys davor.
- *Zu klären:* Braucht die Einführung eine Dienstvereinbarung oder die Zustimmung der
  Personalvertretung? Sichert die Stelle, die den Dienst betreibt, zu, dass Aufnahmen und
  Transkripte weder abgelegt noch protokolliert werden? Ist die Nutzung freiwillig geregelt? OPAA
  kennt keine Einstellung, die das Diktieren vorschreibt; abschalten lässt es sich mit
  `OPAA_FEATURES_SPEECH_INPUT_ENABLED=false`.

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
`GET /actuator/health/chat-model`, `…/embedding-model` und `…/transcription-model` ([Bereitschaft](betrieb.md#bereitschaft-und-lebendigkeit))
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
| „Index veraltet", Chat meldet „Eingeschränkte Suche" | Das Embedding-Modell oder seine Dimension wurde gewechselt; Neueinbettung starten ([Modell wechseln](#modell-wechseln)) |
| Neueinbettung endet „fehlgeschlagen" | Endpunkt nicht erreichbar oder Zeitgrenze zu kurz; Log prüfen, beheben und fortsetzen. Lehnt der Endpunkt einzelne Chunks als zu lang ab, statt sie zu kürzen, scheitern deren Dokumente bei jedem Versuch. Dann die Bibliothek neu aufbauen ([Falle bei einer Neuindizierung](betrieb.md#was-ein-update-mit-dem-index-macht)) |
| Log: Chunks gekürzt eingebettet, Dokument für den Nachzug vorgemerkt | Die Neueinbettung hat einen zu langen Chunk mit gekürztem Vektor geschrieben. Das Log nennt die Pipeline. Nach Abschluss deren Nachzug über die Admin-API aufrufen ([Pipeline-Versionen und Nachzug](wissensbibliotheken.md#72-pipeline-versionen-und-nachzug)); er teilt den Chunk |
| Embedding-Aufrufe brechen nach gleicher Zeit ab, Dokumente scheitern | `OPAA_OPENAI_EMBEDDING_TIMEOUT` zu kurz für Modell und Hardware |
| Log: Chunk erreichte die Eingabegrenze und wurde geteilt | Der Chunk war für das Modell zu lang; OPAA hat ihn geteilt, nichts ist verloren. Häufen sich Teilungen, Grenze und Endpunkt prüfen ([Eingabegrenze](#eingabegrenze-des-embedding-modells)) |
| Log: Endpunkt meldete keine Tokenzahl | Eine stille Kürzung ist nicht auszuschließen; einen Endpunkt nutzen, der die Tokenzahl meldet, oder die Zielgröße senken |
| Dokumente enden mit `FAILED`, Fragen scheitern, alles andere läuft | Modellserver nicht erreichbar oder Modell nicht geladen; unter Compose mit Profil `ollama` zuerst `docker compose logs ollama-pull` |
| Nach einem Update scheitert die Einbettung | Eine entfallene Variable steht noch in der Umgebung ([Konfiguration](konfiguration.md#entfallene-und-veraltete-variablen)) |
| Verbindungsfehler gegen vLLM | HTTP/1.1-Modus fehlt ([vLLM](#vllm--openai-kompatible-server)) |
| Reranking „nicht verfügbar" | Endpunkt nicht erreichbar oder Zeitbudget überschritten; die Suche lief ohne Reranking |
| Kein Mikrofon im Chat, obwohl die Spracheingabe eingeschaltet ist | Rolle „Transkription" auf „Suche & Indexierung" prüfen: Endpunkt oder Modell fehlen, oder `GET {Basis-Adresse}/models` antwortet nicht. Der Browser muss außerdem aufnehmen dürfen; ohne HTTPS bieten Browser kein Mikrofon an |
| „Die Texterkennung ist fehlgeschlagen" | Der Dienst hat die Aufnahme abgelehnt oder nicht innerhalb von `OPAA_TRANSCRIPTION_TIMEOUT` geantwortet; das Log nennt Status oder Ursache, nie den Inhalt. Häufig: Modell nicht geladen, Format nicht angenommen, CPU zu langsam |
| Lange Fragen enden beim Client mit `504` | Zeitgrenzen von Frontend und Proxy kürzer als die Modellaufrufe ([Reranking einschalten](#reranking-einschalten)) |
| Ein Modell mit Zugangsschlüssel lässt sich nicht speichern | `OPAA_SETTINGS_ENCRYPTION_KEY` fehlt oder ist ungültig |
| Verbindungen scheitern mit dem Status eines Proxys | [Ausgangsproxy](betrieb.md#ausgangsproxy); ein Modellserver im eigenen Netz gehört auf seine Ausnahmeliste |

## Weiterführende Kapitel

- [Konfiguration](konfiguration.md) — alle `OPAA_OPENAI_*`-, `OPAA_RERANK_*`- und `OPAA_TRANSCRIPTION_*`-Variablen
- [Authentifizierung](authentifizierung.md)
