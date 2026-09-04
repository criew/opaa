# Modelle und zentrale Steuerung

> **Status: Entwurf — wesentliche offene Fragen verbleiben.**

**Themenbereich E** der [Produktvision](../VISION.md). **Phasenlage:** Modellverwaltung, Vorrang eigener
Modelle, Vorgaben als Obergrenze, Voreinstellungen je Aufgabe und der Schutz vor Weitergabe
personenbezogener Daten gehören in **Phase 1**. Sie sind die Voraussetzung dafür, dass eine Behörde OPAA
überhaupt in Betrieb nehmen kann — nicht ein späterer Ausbau.

## Motivation

Die Frage, welches Sprachmodell antwortet, ist in der öffentlichen Verwaltung keine
Geschmacksentscheidung. Steuerdaten dürfen das Haus nicht in eine fremde Cloud verlassen; Sozialdaten
und Personalvorgänge ebenso wenig. Zugleich soll eine Sachbearbeiterin nicht vor einer Auswahlliste
sitzen, deren Einträge sie fachlich nicht beurteilen kann.

Die bisherige Fassung dieser Spezifikation ging vom Gegenteil aus: Anbieter seien gleichwertig,
Cloud-Modelle die Regel, die Konfiguration eine Sache von Umgebungsvariablen beim Aufsetzen. Das trägt
nicht. Es fehlen drei Dinge:

1. **Modelle müssen verwaltbar sein**, nicht in der Konfiguration eines Dienstes verdrahtet — mit
   Eigenschaften, Zuständigkeit und Freigabestatus.
2. **Eigene, lokal betriebene Modelle sind der Standard.** Ein Cloud-Modell ist die begründete Ausnahme,
   die eine Behörde ausdrücklich erlaubt.
3. **Eine Beschränkung muss an den Daten hängen**, nicht am Arbeitsraum. Eine Regel, die sich durch einen
   Raumwechsel umgehen lässt, ist keine.

Dieses Dokument beschreibt die Modellverwaltung und die zentrale Steuerung, die daraus folgt — und
zugleich den zweiten Hebel der Verteilbarkeit: Was einmal zentral festgelegt ist, gilt überall, ohne dass
irgendein Team seine Agenten anfassen muss.

**Lesehinweis zum Umsetzungsstand.** Diese Spezifikation beschreibt überwiegend das Zielbild. Wo sie
bereits ausgelieferte Funktionalität beschreibt, ist das ausdrücklich mit **(gebaut)** gekennzeichnet.
Alles ohne diese Kennzeichnung ist noch nicht vorhanden.

Der nächste Umsetzungsschritt ist gesondert gekennzeichnet: Der Abschnitt
[Stufe 1: Verwaltete Chat-Modelle](#stufe-1-verwaltete-chat-modelle-in-umsetzung) trägt **(in Umsetzung)**
und beschreibt, was aus diesem Zielbild als Erstes entsteht. Alles Übrige bleibt als spätere Ausbaustufe
bestehen und ist nicht gestrichen.

---

## Überblick

1. **Modelle sind verwaltete Objekte**, keine Konfigurationszeilen. Sie werden hinterlegt, beschrieben,
   freigegeben, ersetzt und abgeschaltet. Der erste Schritt dorthin — verwaltete Chat-Modelle mit genau
   einem aktiven Eintrag — ist als
   [Stufe 1](#stufe-1-verwaltete-chat-modelle-in-umsetzung) beschrieben und in Umsetzung.
2. **Lokal betriebene Modelle sind die Voreinstellung** — das ist umgesetzt und entschieden. Im Zielbild
   ist ohne ausdrückliche Freigabe der Behörde kein Aufruf außerhalb des Hauses möglich; heute trägt
   diese Zusicherung die Konfiguration, nicht eine technische Durchsetzung (siehe
   [Was heute gilt und was nicht](#was-heute-gilt-und-was-nicht-gebaut)). Der Betrieb ohne
   Netzanbindung ist vorgesehen, nicht behelfsweise.
3. **Vorgaben wirken ausschließlich als Obergrenze.** Es gilt immer die restriktivste Festlegung aus
   Systemvorgabe, Space, den beteiligten Wissensbibliotheken und dem eingesetzten Agenten. **Keine Ebene
   kann erweitern, was eine andere eingeschränkt hat.**
4. **Datenschutzrelevante Beschränkungen hängen an den Daten.** Eine Wissensbibliothek führt ihre Vorgabe
   „nur lokale Modelle" selbst mit sich, unabhängig davon, wer wo fragt.
5. **Je Aufgabe gibt es eine Voreinstellung** — Antwort, Einbettung, Reranking, Zusammenfassung,
   Klassifizierung — samt Parametern. Nutzende bekommen eine sinnvolle Vorgabe statt einer Auswahl.
6. **Mehrere Modelle nebeneinander sind der Normalfall**, weil die Aufgaben verschiedene Eigenschaften
   verlangen.
7. **Personenbezogene Daten werden vor der Weitergabe an ein Modell außerhalb des Hauses geprüft** — und
   im Zweifel wird der Aufruf verweigert statt bereinigt.
8. **Eine zentrale Änderung wirkt sofort überall**, ohne Nacharbeit in Spaces und Agenten.
9. **Die Passagen werden einzeln und mit ihrer Herkunft übergeben**, und die Belege kommen im Text
   zurück, nicht als Liste am Ende. Nur so lässt sich die Belegprüfung überhaupt ansetzen.
10. **Die Ausgabe läuft im Fluss.** Ein Modus, der stattdessen erst nach vollständiger Belegprüfung
    ausgibt, war Teil des am 21.08.2026 verworfenen Zitierzwang-Verweigerungsapparats (siehe
    [Zitierzwang](./data-indexing-rag.md#zitierzwang)) und ist nicht gebaut.
11. **Die Absicherung gegen Missbrauch liegt nicht im Modell**, sondern in der Rechteprüfung davor, dem
    unveränderlichen Systemvorspann und der Belegprüfung danach.

---

## Modellverwaltung

### Der Modelleintrag

Ein Modell ist ein verwaltetes Objekt mit Eigenschaften, das die Systemverwaltung anlegt und pflegt. Der
Eintrag hält, was für Auswahl, Vorgabe und Nachweis gebraucht wird:

| Angabe | Wozu |
|---|---|
| Bezeichnung und Zweck | wofür das Modell im Haus vorgesehen ist, in Fachsprache |
| Betriebsart | im eigenen Haus betrieben, im eigenen Rechenzentrumsverbund, oder außerhalb |
| Endpunkt und Zugangsdaten | technische Anbindung |
| Aufgabenarten | Antwort, Einbettung, Reranking, Bildverständnis |
| Fähigkeiten | Kontextlänge, Werkzeugaufrufe, Bild- und Handschriftenverständnis, unterstützte Sprachen |
| Freigabestatus | freigegeben, eingeschränkt freigegeben, gesperrt |
| Datenklassen | welche Schutzstufen mit diesem Modell verarbeitet werden dürfen |
| Zuständigkeit | wer im Haus für dieses Modell einsteht |
| Stand und Nachfolge | wann eingeführt, wodurch ersetzt |

Die technische Anbindung erfolgt über eine **OpenAI-kompatible Schnittstelle**. Das ist hier eine
Protokollbezeichnung und keine Aussage über einen Anbieter: Lokal betriebene Modellserver stellen
dieselbe Schnittstelle bereit, weshalb sich Modelle unterschiedlicher Herkunft ohne
Anwendungsänderung anbinden lassen.

Was bewusst **nicht** stattfindet: eine Empfehlung bestimmter Modelle oder Anbieter durch das Produkt.
Welche Modelle geeignet und zulässig sind, entscheidet die Behörde; OPAA liefert dafür die Verwaltung,
die Vorgaben und die Messbarkeit (siehe [Suchqualität](./search-quality-evaluation.md)).

### Eigene Modelle zuerst

Die Grundeinstellung einer Installation ist: **Es sind nur Modelle nutzbar, die im eigenen Haus oder im
eigenen Rechenzentrumsverbund betrieben werden.**

Ein Modell außerhalb wird erst nutzbar, wenn die Behörde es ausdrücklich erlaubt. Diese Erlaubnis ist
ein Verwaltungsvorgang mit Zuständigem, Zeitpunkt und Begründung — kein Häkchen, das beiläufig gesetzt
wird — und sie steht im Protokoll.

Daraus folgen drei Eigenschaften, die zusammengehören:

- **Betrieb ohne Netzanbindung ist der vorgesehene Fall**, nicht der Notbetrieb. Es gibt keine Fähigkeit,
  die zwingend einen Aufruf nach außen verlangt. Fähigkeiten, die nur bestimmte Modelle mitbringen —
  etwa Handschriftenverständnis —, entfallen dann sichtbar, statt heimlich ersetzt zu werden.
- **Kein automatisches Ausweichen nach außen.** Ist das vorgesehene Modell nicht verfügbar, wird nicht
  auf ein Cloud-Modell umgeschaltet. Ein Ausweichweg bleibt immer innerhalb dessen, was die Vorgaben für
  den konkreten Vorgang zulassen.
- **Keine automatische Auswahl des jeweils stärksten Modells.** Ein Verfahren, das je nach Frage das
  beste verfügbare Modell wählt, führt genau an dem Punkt nach außen, an dem es fachlich anspruchsvoll
  wird — und das ist regelmäßig der Punkt mit den schutzbedürftigsten Daten.

### Was heute gilt und was nicht **(gebaut)**

Das Zielbild oben beschreibt verwaltete Modelle mit Freigabestatus. Davon ist heute die
Voreinstellung umgesetzt, und zwar in der Konfiguration:

**Lokal betriebene Modelle sind die Voreinstellung, für Chat und für Einbettung.** Eine Installation,
an der niemand etwas konfiguriert, ruft kein Modell außerhalb des Hauses auf. Das ist entschieden und
bleibt so; es ist keine Zwischenlösung.

**Eine technische Durchsetzung gibt es nicht.** Es existiert kein Mechanismus, der einen Modellaufruf
an ein Ziel außerhalb festgelegter Netzbereiche verweigert. Wer die Voreinstellung ändert, kann jedes
erreichbare Ziel eintragen, und OPAA hält ihn nicht auf. Das ist bewusst so entschieden: Die
Voreinstellung ist bereits lokal, und wer sie ändert, tut es absichtlich.

Daraus folgt eine Aussage, die nicht beschönigt gehört: **Die Zusicherung, dass keine Daten an ein
Modell außerhalb des Hauses gehen, ruht heute auf der Konfiguration und nicht auf einer technischen
Durchsetzung.** Wer sie gegenüber Prüfern nachweisen muss, weist die Konfiguration nach — und sichert
den Netzweg außerhalb von OPAA ab, etwa über die Firewall-Regeln der Umgebung, in der das Backend
läuft.

Das ist eine Festlegung für den heutigen Stand, kein Verzicht auf Dauer. Die
[zentralen Vorgaben als Obergrenze](#vorgaben-als-obergrenze) bleiben Teil von Phase 1; sie sind der
Ort, an dem eine Durchsetzung später sinnvoll einhängt, weil dort ohnehin entschieden wird, welche
Modelle für einen Vorgang zulässig sind.

**Was sich mit Stufe 1 daran ändert und was nicht.** Der Ort der Entscheidung wandert von der
Betriebskonfiguration in die Administrationsoberfläche, und damit wird jede Änderung protokolliert und
einer Person zurechenbar — das ist gegenüber heute ein Gewinn an Nachweisbarkeit. An der Zusicherung
selbst ändert sich nichts: Wer in der Oberfläche eine Adresse außerhalb des Hauses einträgt, kann das,
und OPAA hält ihn weiterhin nicht auf. Die Absicherung des Netzwegs bleibt außerhalb von OPAA.

### Ein einziger Anbindungsweg **(gebaut, #762)**

Es gibt **keine Anbieterangabe mehr, die zwischen zwei Konfigurationswegen umschaltet.** Bis
einschließlich der vorigen Version wählte eine eigene Variable zwischen einem nativen Weg für
Ollama und der openai-kompatiblen Schnittstelle für alles andere; dieser native Weg entfiel mit
#762 vollständig (siehe [„Ein Anbindungsweg, nicht zwei"](#ein-anbindungsweg-nicht-zwei) — was dort
bereits für den Modelleintrag der Modellverwaltung galt, gilt seither auch für die
Betriebskonfiguration, aus der er hervorgeht). Ollama wird seitdem wie jeder andere
openai-kompatible Endpunkt über seinen eigenen `/v1`-Pfad angesprochen.

Die Basis-Adresse hat deshalb wieder eine **Voreinstellung** — anders als in einer früheren Fassung
dieses Abschnitts, die bewusst keine vorsah: Ein lautes Scheitern beim Start ohne gesetzte Adresse
war die richtige Entscheidung, solange die openai-kompatible Schnittstelle die Ausnahme war und ein
nativer, lokal voreingestellter Ollama-Weg daneben bestand — eine geerbte Voreinstellung hätte dort
eine Installation, die im Haus bleiben sollte, stillschweigend nach außen gerichtet. Seit die
openai-kompatible Schnittstelle der **einzige** Weg ist, trifft dieses Risiko nicht mehr zu: Die
Voreinstellung selbst zeigt auf einen lokal betriebenen Ollama-Server, nicht nach außen. Ein lautes
Scheitern bliebe hier ein Fehlschlag ohne Gegenwert — jede Installation, an der niemand etwas
konfiguriert, bräuchte sonst eine Adresse, nur um überhaupt zu starten, obwohl die richtige Adresse
längst feststeht.

Wer stattdessen einen anderen Anbieter verwenden will — für Chat, für Einbettung, oder für beides —,
überschreibt diese Voreinstellung mit der jeweiligen Zieladresse. Ein **explizit leer gesetzter**
Wert (eine Umgebungsvariable, die zwar gesetzt, aber ohne Inhalt ist) überschreibt die Voreinstellung
ebenfalls — mit einer leeren Zeichenkette statt eines gültigen Ziels — und führt weiterhin zum
lauten Scheitern beim Start: Das ist der eine Fall, in dem die ursprüngliche Begründung unverändert
gilt, weil hier eine bewusste Angabe vorliegt, die nur zufällig leer ist.

Die Ableitung je Funktion bleibt erhalten: Eine Adresse für Chat und eine für Einbettung sind
getrennt setzbar; ohne sie gilt die gemeinsame Adresse für beide. Die Betriebssicht dazu steht in
[deployment.md](../handbuch/deployment.md#llm-anbieter).

Für das **Chat-Modell** wird diese Konfiguration mit
[Stufe 1](#stufe-1-verwaltete-chat-modelle-in-umsetzung) von der Verwaltung in der Anwendung abgelöst; sie
bleibt dann nur noch die Quelle, aus der beim ersten Start der initiale Eintrag entsteht. Für die
**Einbettung** gilt sie unverändert weiter.

---

## Stufe 1: Verwaltete Chat-Modelle **(gebaut)**

Das Zielbild oben ist vollständig, aber nicht in einem Zug baubar. Stufe 1 schneidet daraus den Teil
heraus, der für sich genommen einen Nutzen hat und alles Weitere trägt: **Chat-Modelle werden in der
Administrationsoberfläche verwaltet, statt in Umgebungsvariablen zu stehen.**

Der Grund für diesen Zuschnitt ist nicht Bequemlichkeit, sondern Zuständigkeit. Wer in einer Behörde für
das Modell einsteht, ist die Systemverwaltung. Solange ein Modellwechsel eine Änderung an der
Betriebskonfiguration und einen Neustart verlangt, liegt die Entscheidung faktisch bei demjenigen, der
Zugriff auf die Container hat — und das ist regelmäßig jemand anderes.

Die Umsetzung wird in Epic [#755](https://github.com/criew/opaa/issues/755) geführt.

**Admin-API (gebaut, #757).** `/api/v1/admin/models` bietet Auflisten, Anlegen, Ändern, Löschen,
Aktivieren (`POST .../{modelId}/activate`) und Verbindungstest (`POST .../test`, auch für einen noch
nicht gespeicherten Entwurf) — ausschließlich für `SYSTEM_ADMIN`. Der Zugangsschlüssel ist in jeder
Antwort schreibend: keine Antwort enthält ihn, auch nicht gekürzt, nur das Kennzeichen `apiKeySet`.
Löschen des aktiven Modells wird mit 409 verweigert; eine gleichzeitige Aktivierung zweier Modelle
löst ebenfalls 409 statt eines Serverfehlers aus. Jede Änderung erzeugt ein Audit-Ereignis, und die
Deaktivierung eines Modells beim Umschalten bekommt seit #757 ein eigenes (`LLM_MODEL_DEACTIVATED`).
`GET .../embedding-info` (#759) ergänzt den read-only Block: Anbieter, Modell und Dimensionen der
Einbettungskonfiguration, gelesen aus derselben Betriebskonfiguration, aus der auch das aktive
`EmbeddingModel` entsteht — nicht editierbar, siehe [Warum die Einbettung nicht
mitkommt](#warum-die-einbettung-nicht-mitkommt).

**Administrationsoberfläche (gebaut, #759).** `admin/models`, erreichbar über die Seitenleiste nur
für `SYSTEM_ADMIN`: Liste mit deutlich erkennbarem aktivem Eintrag, Anlegen/Bearbeiten/Löschen,
„Aktiv setzen" und Verbindungstest je Eintrag, darunter der schreibgeschützte
Einbettungsblock aus `embedding-info`.

**Laufzeitauflösung (gebaut, #758).** Antwortgenerierung (`io.opaa.query.AnswerGenerationService`)
und Titelgenerierung (`io.opaa.chat.ChatTitleGenerationService`) lösen den `ChatClient` bei jedem
Aufruf über `io.opaa.llm.ActiveChatModelResolver` aus dem systemweit aktiven `llm_models`-Eintrag
auf — programmatisch über die OpenAI-kompatible Anbindung (Basis-Adresse, Modell-Kennung, Temperatur,
maximale Antwortlänge, entschlüsselter Zugangsschlüssel), nicht mehr aus der beim Start einmalig
gebauten Spring-AI-Autoconfiguration. Der gebaute Client wird zwischengespeichert und erst
invalidiert, wenn `io.opaa.llm.LlmModelService` das aktive Modell tatsächlich ändert (Aktivierung,
Änderung des aktiven Eintrags) — und zwar erst nach dem Commit der auslösenden Transaktion, damit eine
zurückgerollte Änderung den Zwischenspeicher nicht fälschlich verwirft. Ohne aktives Modell erhält die
fragende Person eine verständliche deutsche Fehlermeldung (503) statt eines technischen Fehlers; die
Health-Anzeige (`io.opaa.observability.ChatHealthIndicator`) benennt Basis-Adresse und Modell-Kennung
des aktiven Modells und steht auf „down", solange keines aktiv ist. Ein nicht erreichbares aktives
Modell führt zu einer Fehlermeldung, nie zu einem stillschweigenden Ausweichen auf ein anderes
Modell. Die Einbettung ist davon unberührt und läuft unverändert über die native Autoconfiguration.

### Was Stufe 1 umfasst

1. **Eine Liste hinterlegter Chat-Modelle**, nicht eine einzelne Einstellung. Mehrere Einträge
   nebeneinander sind der Normalfall — ein lokales Modell und ein zweites zum Vergleich, oder ein
   Nachfolger, der schon eingetragen, aber noch nicht aktiv ist.
2. **Genau ein Modell ist systemweit aktiv.** Die Systemverwaltung schaltet um; die Umschaltung wirkt
   beim nächsten Vorgang, ohne Neustart (Laufzeitauflösung, #758, siehe oben).
3. **Verbindungstest je Eintrag.** Eine falsch eingetragene Adresse fällt sonst erst dem nächsten
   fragenden Menschen auf — als leere Antwort, nicht als Konfigurationsfehler.
4. **Zugangsdaten verschlüsselt und nicht rücklesbar.** Ein hinterlegter Schlüssel verlässt die Datenbank
   nicht wieder; die Oberfläche zeigt „gesetzt" oder „nicht gesetzt".
5. **Jede Änderung ist protokolliert** — Anlegen, Ändern, Löschen, Aktivieren.
6. **Die Einbettungskonfiguration ist sichtbar, aber nicht änderbar**, mit Begründung.

### Der Eintrag in Stufe 1

Der Zielbild-Modelleintrag oben führt Freigabestatus, Datenklassen, Zuständigkeit, Fähigkeiten und
Nachfolge. Stufe 1 nimmt davon nur, was für den Aufruf gebraucht wird — die übrigen Felder bekommen erst
mit den Vorgaben-Ebenen eine Wirkung und wären vorher ein Formular ohne Folge.

| Angabe | Bedeutung |
|---|---|
| Anzeigename | wie das Modell in der Verwaltung heißt, in Fachsprache |
| Basis-Adresse | der Endpunkt der OpenAI-kompatiblen Schnittstelle — **ohne Anmeldedaten** (siehe unten) |
| Modell-Kennung | welches Modell an diesem Endpunkt angesprochen wird |
| Zugangsschlüssel | optional; verschlüsselt abgelegt, nie zurückgegeben |
| Streuung der Erzeugung | Temperatur |
| Längenbegrenzung | maximale Antwortlänge |
| Aktiv | ob dieses Modell derzeit antwortet |

### Keine Anmeldedaten in der Basis-Adresse

Eine Basis-Adresse der Form `https://benutzer:geheim@host/v1` wird **abgelehnt** — für alle
Modellrollen gleich, die verwaltete Chat-Rolle ebenso wie die über Umgebungsvariablen konfigurierte
Rerank-Rolle ([#1147](https://github.com/criew/opaa/issues/1147)). Der Grund ist keine Formalie: Eine
Basis-Adresse gilt an mehreren Stellen ausdrücklich als geheimnisfrei und wird deshalb wörtlich
weitergereicht — in die Datenbank, in das Audit-Log, in die Schnittstellenantwort und damit in die
Administrationsoberfläche, bei der Rerank-Rolle zusätzlich in die Startmeldung im Log und in die
Statusanzeige „Suche & Indexierung". Anmeldedaten in der Adresse landen so in genau den Ausgaben, aus
denen sie herausgehalten werden sollen.

Abgelehnt statt stillschweigend entfernt: Wer Anmeldedaten einträgt, will, dass sie verwendet werden.
Ein stilles Entfernen nähme diese Absicht wortlos zurück und hinterließe einen Endpunkt, der ohne
erkennbaren Grund mit „nicht authentifiziert" antwortet. Die Ablehnung sagt es stattdessen — und
wiederholt den beanstandeten Anteil dabei nicht, weil die Fehlermeldung selbst wieder in Antwort und
Log wandert. Der Zugangsschlüssel gehört in das dafür vorgesehene Feld (Chat-Rolle) bzw. in
`OPAA_RERANK_API_KEY` (Rerank-Rolle); er wird als `Authorization`-Kopfzeile gesendet.

Die Prüfung greift **beim Schreiben** — beim Anlegen und Ändern eines Modells, beim Verbindungstest,
bei der einmaligen Übernahme aus der Umgebungskonfiguration und beim Start für die
Umgebungs-Basis-Adressen. Eine Maskierung auf der Leseseite gibt es bewusst nicht: Es existieren
keine Bestandsinstallationen mit bereits gespeicherten Adressen, und eine zweite Darstellungsschicht
für einen Fall, den es nicht gibt, wäre Aufwand ohne Nutzen.

Die Einbettungs-Rolle führt **keine Basis-Adresse in Anzeige, Audit oder Schnittstelle**:
`io.opaa.llm.EmbeddingInfoService` liest nur Anbieter, Modell-Kennung und Dimensionszahl. Eine
Basis-Adresse hat sie aber sehr wohl — `OPAA_OPENAI_EMBEDDING_BASE_URL` —, und diese verlässt den
Prozess auch: Ein fehlgeschlagener Einbettungsaufruf trägt die Ziel-Adresse im Meldungstext der
Spring-Ausnahme und landet mit dem Stacktrace im Log. Sie wird deshalb beim Start geprüft
(`io.opaa.config.OpenAiBaseUrlGuard`, gemeinsam mit der Chat-Basis-Adresse aus derselben
Umgebungskonfiguration) — hier ist ein **Startabbruch** richtig, anders als bei der Rerank-Rolle:
Ohne Einbettungen findet die Suche nichts, es gibt also keinen sinnvoll eingeschränkten Weiterbetrieb.

### Ein Anbindungsweg, nicht zwei

Angebunden wird **ausschließlich über die OpenAI-kompatible Schnittstelle**. Das ist, wie oben bereits
festgehalten, eine Protokollangabe und keine Aussage über einen Anbieter.

Insbesondere gibt es **keinen gesonderten Anbieter-Typ für Ollama**. Ollama stellt dieselbe Schnittstelle
unter dem Pfad `/v1` bereit; ein lokal betriebenes Modell wird deshalb mit einer Basis-Adresse der Form
`http://ollama:11434/v1` eingetragen wie jeder andere Endpunkt auch. Dasselbe gilt für vLLM, LiteLLM,
Azure und die üblichen Zwischenschichten.

Der Verzicht auf einen zweiten, nativen Weg ist eine bewusste Entscheidung mit einem benennbaren Preis:

- **Dafür spricht**, dass jede Fallunterscheidung nach Anbieter sich durch das gesamte System zieht — in
  das Formular, in die Schnittstelle, in den Aufbau des Aufrufs, in die Tests. Ein Weg heißt: ein
  Codepfad, ein Verbindungstest, ein Fehlerbild.
- **Dagegen spricht**, dass Ollama-eigene Optionen wie die Kontextgröße (`num_ctx`) oder die Haltedauer
  eines geladenen Modells (`keep_alive`) über die kompatible Schnittstelle nicht setzbar sind. Sie
  bleiben **Betriebskonfiguration am Ollama-Server** und sind dort auch besser aufgehoben: Sie betreffen
  die Ressourcen der Maschine, nicht die fachliche Verwendung des Modells.

Sollte sich diese Grenze im Betrieb als hinderlich erweisen, ist ein nativer Weg als spätere Ausbaustufe
nachrüstbar — der Eintrag müsste dann um eine Angabe zum Anbindungsweg ergänzt werden. Vorher wird er
nicht gebaut.

Aus derselben Entscheidung folgt, dass der **Zugangsschlüssel optional** ist. Ein lokal betriebener
Endpunkt verlangt regelmäßig keine Authentifizierung; ein Pflichtfeld würde dort zu einem erfundenen Wert
führen, und ein erfundener Wert ist schlechter als kein Wert, weil er einen Schutz vortäuscht.

### Übergang aus der heutigen Konfiguration

Bestehende Installationen — einschließlich der Demo-Instanz — sind heute über Umgebungsvariablen
konfiguriert. Sie dürfen durch die Umstellung nicht stehenbleiben.

Deshalb gilt: **Beim ersten Start nach der Umstellung wird die vorhandene Konfiguration als initiales,
aktives Modell übernommen**, sofern noch kein Modell hinterlegt ist. Zwei Fälle, weil #762 den
Anbindungsweg der Betriebskonfiguration selbst verändert hat, bevor Stufe 1 sie ganz ablöst:

- Eine Bestandsinstallation, die beim Update noch die inzwischen entfallene Anbieterangabe
  `OPAA_AI_CHAT_PROVIDER=ollama` **und** eine der ebenfalls entfallenen `OPAA_OLLAMA_*`-Variablen
  gesetzt hat, wird daraus übernommen — Adresse samt `/v1`-Suffix, Modell, ohne Schlüssel (Ollamas
  eigener openai-kompatibler Endpunkt verlangt keinen).
- Jede andere Installation — einschließlich einer, die `OPAA_AI_CHAT_PROVIDER=openai` gesetzt hatte,
  oder einer frischen, die nie eine dieser Variablen kannte — übernimmt Adresse, Modell und
  Schlüssel unverändert aus der openai-kompatiblen Konfiguration, die seit #762 der einzige
  laufende Anbindungsweg ist.

Danach ist die Datenbank für das Chat-Modell **führend**. Die Umgebungsvariablen werden nicht mehr
ausgewertet — ein zweiter Ort für dieselbe Entscheidung wäre schlimmer als beide einzeln, weil bei
abweichenden Werten niemand mehr sagen kann, welcher gilt.

### Warum die Einbettung nicht mitkommt

Die Einbettungskonfiguration bleibt in Stufe 1 in der Betriebskonfiguration und wird in der Oberfläche nur
**angezeigt** — Anbieter, Modell, Dimensionen — mit dem Hinweis, warum sie dort nicht änderbar ist.

Der Grund steht bereits unter [Sofortige Wirkung](#sofortige-wirkung): Ein Wechsel des
Einbettungsmodells macht alle vorhandenen Vektoren unvergleichbar und erzwingt eine vollständige
Neuindizierung. Eine Auswahlliste, die eine Wissensbasis unbrauchbar macht, sobald jemand sie versehentlich
verstellt, ist keine Erleichterung, sondern eine Falle. Der Wechsel bleibt deshalb ein geplanter Vorgang
und bekommt eine eigene Behandlung, wenn er ansteht.

Die Anzeige entfällt trotzdem nicht. Ohne sie entstünde der Eindruck, OPAA arbeite mit genau einem Modell,
und die naheliegende Frage „warum kann ich das Einbettungsmodell nicht wechseln?" bliebe unbeantwortet.

### Was Stufe 1 ausdrücklich nicht enthält

Die folgenden Teile des Zielbilds bleiben unverändert bestehen und sind **spätere Ausbaustufen**, keine
gestrichenen Ideen:

| Später | Warum nicht jetzt |
|---|---|
| [Vorgaben als Obergrenze](#vorgaben-als-obergrenze) aus System, Space, Bibliothek und Agent | Setzt Modellvorgaben an Spaces und Bibliotheken voraus, die es noch nicht gibt; die Schnittmengenregel hängt am Modelleintrag, der hier erst entsteht |
| Auswahl eines Modells durch Nutzende im Chat | Ohne Obergrenze wäre eine freie Auswahl die Umgehung jeder späteren Beschränkung — die falsche Reihenfolge |
| Freigabestatus, Datenklassen, Zuständigkeit, Nachfolge, Fähigkeiten am Eintrag | Felder ohne wirksame Verwendung; sie bekommen ihre Bedeutung erst mit den Vorgaben-Ebenen |
| Voreinstellungen je Aufgabe — Zusammenfassung, Klassifizierung, Bildverständnis | Diese Aufgaben sind im Produkt noch nicht als eigene Modellaufrufe vorhanden. **Reranking ist seit [#1050](https://github.com/criew/opaa/issues/1050) die Ausnahme**: Die Rerank-Rolle ist gebaut, aber über Umgebungsvariablen konfiguriert (`OPAA_RERANK_ENABLED`, `OPAA_RERANK_BASE_URL`, `OPAA_RERANK_MODEL`, `OPAA_RERANK_API_KEY`) und nicht als verwalteter Eintrag — dieselbe Ebene wie die Einbettungsrolle, aus demselben Grund: eine Installationsentscheidung, keine laufende Verwaltungsaufgabe. Siehe [Hybride Suche mit Reranking](./hybrid-retrieval.md#arbeitspaket-4-reranking-als-modellrolle) |
| Verwaltbare Einbettungsmodelle | Erzwingt eine Neuindizierung, siehe oben |
| Technische Durchsetzung, dass kein Aufruf das Haus verlässt | Hängt sinnvoll an den Vorgaben-Ebenen, nicht an einer einzelnen Modellliste |
| [Grenzen und Kontingente](#grenzen-und-kontingente) | Eigener Themenbereich |
| Native Ollama-Optionen (`num_ctx`, `keep_alive`) | Betriebskonfiguration am Modellserver, siehe oben |

Stufe 1 ist damit ausdrücklich **kein Ersatz** für die zentrale Steuerung, sondern deren Fundament: Sie
schafft den Modelleintrag, gegen den die Vorgaben später schneiden.

---

## Vorgaben als Obergrenze

### Die Verrechnungsregel

Vier Ebenen können festlegen, welche Modelle für einen Vorgang zulässig sind. Sie werden **geschnitten**,
nie vereinigt:

```
erlaubte Modelle = Systemvorgabe
                 ∩ Vorgabe des Space
                 ∩ Vorgabe jeder Wissensbibliothek im Suchbereich
                 ∩ Vorgabe des eingesetzten Agenten
```

Dieselbe Regel gilt für die übrigen an Modelle gebundenen Vorgaben — zulässige Werkzeuge, Weitergabe nach
außen. Es gibt genau eine Richtung: **Jede Ebene kann verschärfen, keine kann lockern.** Ein
Zitierzwang-Schalter auf einer dieser Ebenen war Teil des am 21.08.2026 verworfenen Verweigerungsapparats
(siehe [Zitierzwang](./data-indexing-rag.md#zitierzwang)) und ist nicht gebaut; die deterministische
Belegvalidierung, die stattdessen gebaut ist, greift unabhängig von Space, Bibliothek oder Agent.

Der Vorteil ist, dass sich die Frage „warum wurde hier dieses Modell verwendet?" immer beantworten lässt,
und zwar ohne Kenntnis der Reihenfolge, in der die Einstellungen entstanden sind. Der Preis ist, dass
eine einzelne strenge Bibliothek einen ganzen Vorgang einschränken kann — und genau das ist beabsichtigt.

Eine **Erklärung ist Teil der Antwort**: Nutzende können einsehen, welches Modell verwendet wurde und
welche Ebene die Auswahl begrenzt hat. Ohne das entsteht der Eindruck von Willkür.

### Wenn die Schnittmenge leer ist

Der Fall tritt auf: Eine Bibliothek verlangt lokale Modelle, der Agent ist auf ein Modell festgelegt, das
außerhalb läuft. Dann gibt es kein zulässiges Modell.

OPAA **verweigert den Vorgang und benennt den Grund**. Es wird nicht auf ein anderes Modell
zurückgefallen, und es wird nicht stillschweigend die strengere Bibliothek aus dem Suchbereich genommen,
um den Vorgang doch noch zu ermöglichen — das wäre die gefährlichere Auflösung, weil sie eine
Schutzvorgabe durch eine schlechtere Antwort ersetzt, ohne dass es jemand merkt.

Die Meldung nennt die Ebene, an der es scheitert, und die zuständige Stelle. Sie nennt **keine
Anzahlen** von Beständen, auf die die fragende Person keinen Zugriff hat.

### Beschränkungen hängen an den Daten

> **Datenschutzrelevante Modellbeschränkungen gehören an die Daten, nicht an den Raum.**

Eine Wissensbibliothek mit Steuerdaten führt ihre Vorgabe „nur lokale Modelle" selbst mit sich. Sie gilt
überall, wo diese Daten verwendet werden — in jedem Space, mit jedem Agenten, für jede Person.

Der Grund liegt im Rechtemodell: Der Space hat keine Hoheit darüber, welche Bibliotheken in ihm auftauchen.
Wer in einem Space kuratieren darf, kann jede Bibliothek bereitstellen, auf die er selbst Zugriff hat.
Eine Bibliothek mit besonders geschützten Daten kann damit in einem Space landen, dessen Vorgabe
Cloud-Modelle erlaubt. **Eine ausschließlich raumgebundene Vorgabe schützt genau diesen Fall nicht** —
und es ist der Fall, der zählt.

Die Vorgabe des Space bleibt sinnvoll: Ein Raum kann strenger sein als das Haus, etwa in der Revision.
Aber er ist nicht die Sicherung. Dieselbe Festlegung steht aus der Sicht des Rechtemodells in
[Spaces, Assets und Zugangskontrolle](./spaces-and-assets.md#modell-policies).

**Praktische Folge:** Die Modellvorgabe ist eine Eigenschaft der Bibliothek und wird beim Anlegen
gesetzt — von der Stelle, die den Bestand verantwortet. Bei konnektorgespeisten Bibliotheken setzt sie
die Systemverwaltung mit der Zuordnung (siehe
[Wissensquellen und Konnektoren](./knowledge-sources.md#eine-quelle-eine-wissensbibliothek)); der
Eigentümer kann sie verschärfen, nicht lockern.

### Sofortige Wirkung

Eine zentrale Änderung wirkt **beim nächsten Vorgang**, überall. Wird ein Modell abgeschaltet — weil es
abgekündigt wurde, weil ein Nachfolger bereitsteht oder weil eine Prüfung es untersagt —, greift das
sofort, ohne dass ein Team seine Agenten anfassen muss.

Damit das nicht zum Betriebsrisiko wird, gehören drei Dinge dazu:

- **Vorher sichtbare Auswirkung.** Vor dem Abschalten zeigt die Systemverwaltung, wie viele Agenten,
  Bibliotheken und Spaces betroffen sind und was an ihre Stelle tritt.
- **Benannte Nachfolge.** Ein abgeschaltetes Modell trägt einen Nachfolger; Vorgänge laufen auf diesem
  weiter, soweit die Vorgaben es zulassen. Wo kein zulässiger Nachfolger existiert, wird der Vorgang
  verweigert — und die betroffene Stelle wird benachrichtigt, statt es an schlechteren Antworten zu
  merken.
- **Nachvollziehbarkeit alter Vorgänge.** Zu jeder erzeugten Antwort ist festgehalten, mit welchem Modell
  und welchen Parametern sie entstanden ist. Ein Modellwechsel ändert nichts rückwirkend an dieser
  Angabe.

Ein Wechsel des **Einbettungsmodells** ist die eine Ausnahme von der sofortigen Wirkung: Er erfordert
eine vollständige Neuindizierung, weil bestehende Vektoren nicht vergleichbar bleiben. Er wird deshalb
als geplanter Vorgang behandelt und ist in
[Wissensschicht und Retrieval](./data-indexing-rag.md#speicherung-und-filterachse) sowie in der
Qualitätsmessung verankert.

---

## Voreinstellungen und Parameter je Aufgabe

Nutzende sollen keine Modellauswahl treffen müssen. Sie sollen eine Aufgabe haben und ein Ergebnis
bekommen. Die Zuordnung von Aufgabe zu Modell und Parametern trifft die Systemverwaltung einmal.

| Aufgabe | Was zählt | Typische Vorgabe |
|---|---|---|
| **Antwort im Chat** | Belegtreue, Sprachqualität, Kontextlänge | wenig Streuung in der Erzeugung, ausreichende Kontextlänge für die übergebenen Passagen |
| **Einbettung** | Trefferqualität, Stabilität über die Zeit | ein Modell, das selten gewechselt wird — jeder Wechsel kostet eine Neuindizierung |
| **Reranking** (gebaut, #1050) | Genauigkeit bei kurzen Texten, Geschwindigkeit | ein spezialisiertes, kleineres Modell; per Voreinstellung abgeschaltet |
| **Zusammenfassung** | Treue zum Ausgangstext | geringe Streuung, längenbegrenzt |
| **Klassifizierung und Erkennung** | Verlässlichkeit, Geschwindigkeit | kleines Modell, feste Ausgabestruktur |
| **Bildverständnis** | Fähigkeit des Modells | nur, wenn ein Modell mit dieser Fähigkeit freigegeben ist |

Von dieser Tabelle betrifft [Stufe 1](#stufe-1-verwaltete-chat-modelle-in-umsetzung) nur die Zeile
**Antwort im Chat**, und dort auch nur Streuung und Längenbegrenzung. Die Einbettung bleibt in der
Betriebskonfiguration; die übrigen Aufgaben sind im Produkt noch nicht als eigene Modellaufrufe
vorhanden und bekommen ihre Zuordnung, sobald sie es sind.

Zu jeder Aufgabe gehören **Parameter** — Streuung der Erzeugung, Längenbegrenzung der Antwort,
Kontextgrenze — und ein **Systemvorspann**, der Verhalten und Ton festlegt. Beides ist Teil der zentralen
Vorgabe, nicht der Entscheidung im Einzelfall.

Ein Agent kann davon abweichen, aber nur innerhalb der Obergrenze. Wo eine Aufgabe besondere Parameter
braucht, ist das Teil seiner Aufgabenbeschreibung und damit prüfbar und versionierbar.

Für den Systemvorspann gilt eine harte Regel: **Er ist nicht über den Chat änderbar.** Anweisungen aus
Nutzereingaben oder aus dem Inhalt abgerufener Dokumente ersetzen ihn nicht. Das ist die Grundlage
dafür, dass ein geprüfter Agent auch nach der Prüfung noch das tut, wofür er geprüft wurde.

### Mehrere Modelle nebeneinander

Es ist der Normalfall, dass eine Installation mehrere Modelle betreibt — und zwar nicht, um die stärkste
Antwort zu finden, sondern weil die Aufgaben unterschiedliche Eigenschaften verlangen. Ein Modell für
Einbettungen muss stabil sein, eines für das Reranking schnell, eines für die Antwort sprachfähig.

Die Aufteilung hat zwei Nebenwirkungen, die bewusst in Kauf genommen werden:

- **Mehr Betriebsaufwand.** Mehrere Modelle brauchen Rechenleistung, Überwachung und Pflege. Deshalb ist
  die Zahl der Aufgabenarten begrenzt und jede Zuordnung begründet.
- **Getrennte Beurteilung der Qualität.** Ein Wechsel am Einbettungsmodell wirkt anders als einer am
  Antwortmodell; beide werden getrennt gegen dieselben Referenzfälle gemessen.

Was ausdrücklich nicht vorgesehen ist: eine Verteilung von Anfragen auf Modelle nach geschätzter
Schwierigkeit. Sie macht das Ergebnis unvorhersehbar, ist nicht reproduzierbar und würde die
Modellvorgaben faktisch aushöhlen.

---

## Schutz vor Weitergabe personenbezogener Daten

Wo ein Modell außerhalb des Hauses erlaubt ist, entsteht die Frage, was ihm übergeben wird. Frage,
abgerufene Passagen und Verlauf enthalten in der Verwaltung regelmäßig Namen, Aktenzeichen,
Steuernummern, Anschriften und Gesundheitsangaben.

Drei Umgangsweisen kommen in Betracht.

**Option 1 — Verweigern.** Wird in einem Aufruf an ein Modell außerhalb des Hauses ein personenbezogenes
Merkmal erkannt, wird der Aufruf nicht ausgeführt. Die Person erhält den Hinweis, dass die Anfrage nur
mit einem lokalen Modell möglich ist. Wirksam und einfach zu erklären, aber im Alltag hinderlich, wenn
die Erkennung übervorsichtig ist.

**Option 2 — Ersetzen und zurückübersetzen.** Erkannte Merkmale werden vor dem Aufruf durch Platzhalter
ersetzt und in der Antwort wieder eingesetzt. Erhält die Arbeitsfähigkeit, verlagert aber das Risiko auf
die Erkennungsgüte: Was nicht erkannt wird, geht hinaus. Und in der Verwaltung ist der Personenbezug oft
nicht an einem Merkmal festzumachen, sondern ergibt sich aus dem Zusammenhang, den ein Erkennungsverfahren
nicht sieht.

**Option 3 — Nur lokale Modelle für alles.** Vollständig sicher und für viele Häuser die richtige
Entscheidung. Als Produktvorgabe zu grob, weil sie auch dort greift, wo eine Behörde bewusst anders
entschieden hat.

**Empfehlung:** Option 1 als Verhalten in der Voreinstellung, kombiniert mit der eigentlichen Sicherung —
den Beschränkungen an den Daten. Ein Bestand mit Personenbezug trägt „nur lokale Modelle" ohnehin selbst,
sodass der Prüfschritt an der Grenze nach außen nur noch die Reste auffängt: Freitext in der Frage,
eingefügte Ausschnitte, Anhänge.

Option 2 wird ausdrücklich **nicht** verworfen, aber nur als bewusst zuschaltbare Erleichterung mit klar
benannter Restunsicherheit — und nie als Ersatz für die Beschränkung am Bestand. Ein Verfahren, das
Vertraulichkeit auf eine Mustererkennung stützt, ist keine Zusicherung, sondern eine Wahrscheinlichkeit.

Unabhängig von der gewählten Option gilt:

- **Jeder Aufruf nach außen ist protokolliert** — Modell, Zeitpunkt, Anlass, Umfang. Ohne Inhalte, aber
  nachweisbar.
- **Nutzende sehen vorher, dass ein Vorgang das Haus verlässt.** Diese Anzeige ist nicht abschaltbar.
- **Kein Training mit Hausdaten.** Übergebene Daten dürfen beim Betreiber eines externen Modells nicht in
  ein Training einfließen; wo das nicht zugesichert ist, kommt das Modell nicht in Frage. Sicherstellen
  kann das nur der Betreibervertrag — OPAA kann es lediglich als Eigenschaft am Modelleintrag führen und
  sichtbar machen.

---

## Antwortgenerierung

Die Erzeugung der Antwort ist der Punkt, an dem Modellsteuerung und Belegbarkeit zusammentreffen.

```
Frage
  ↓
Suchbereich und Rechteprüfung          → spaces-and-assets.md
  ↓
Hybride Suche, Reranking, Auswahl      → data-indexing-rag.md
  ↓
Bestimmung des zulässigen Modells      ← Schnitt aller Vorgaben
  ↓
Zusammenstellung: Systemvorspann + Passagen mit Fundstellen + Frage
  ↓
Aufruf; Ausgabe im Fluss
  ↓
Belegvalidierung: zeigt jeder Beleg auf eine tatsächlich abgerufene Fundstelle?
  ↓
Ausgabe mit Fundstellen, ungültige Belege gekennzeichnet
```

Wesentlich ist die Reihenfolge: **Die Bestimmung des Modells folgt der Bestimmung des Suchbereichs.**
Erst wenn feststeht, aus welchen Beständen geantwortet wird, steht fest, welche Modellvorgaben gelten.
Eine Installation, die das Modell vorher festlegt, kann die datengebundene Beschränkung nicht einhalten.

Die **Belegprüfung nach der Erzeugung** ist in
[Wissensschicht und Retrieval](./data-indexing-rag.md#zitierzwang) beschrieben. Für dieses Dokument ist
nur festzuhalten: Sie ist kein Bestandteil des Systemvorspanns und verlässt sich nicht darauf, dass das
Modell die Anweisung befolgt.

**Bei Ausfall des Modells** wird nicht auf ein unzulässiges ausgewichen. Steht kein zulässiges Modell
bereit, gibt OPAA die gefundenen Fundstellen ohne erzeugten Text aus und sagt, dass keine Antwort
formuliert werden konnte. Das ist ein brauchbares Zwischenergebnis — die Recherche ist getan, nur die
Formulierung fehlt.

### Übergabe der Passagen und Form der Antwort

Wie die abgerufenen Passagen an das Modell übergeben werden, ist keine Feinheit der Umsetzung, sondern
die **Nahtstelle zwischen Retrieval und Belegprüfung**: Woran die Prüfung ansetzt, entsteht genau hier.
Ohne eine beschriebene Übergabe ist der Zitierzwang nicht beschreibbar.

Die Zuständigkeit ist deshalb so geschnitten: **[Wissensschicht und Retrieval](./data-indexing-rag.md)
bestimmt, welche Passagen übergeben werden** — Suche, Zusammenführung, Reranking, Schwelle. **Dieses
Dokument bestimmt, wie sie übergeben werden**, weil das eine Eigenschaft des Modellaufrufs ist und mit
Systemvorspann, Parametern und Kontextgrenze zusammen festgelegt wird.

Der Aufruf wird aus vier Teilen zusammengesetzt:

1. **Systemvorspann** — Rolle, Ton, Umgang mit Nichtwissen und die verbindlichen Belegregeln. Nicht über
   den Chat änderbar (siehe [Absicherung des Modells](#absicherung-des-modells-gegen-missbrauch)).
2. **Die Passagen, jede mit einem eigenen Kopf.** Der Kopf trägt die Angaben, die den späteren Beleg
   tragen: Dokument, Stelle im Dokument, Bezeichnung — und die **Zeichenfolge, mit der genau diese
   Passage zu zitieren ist**. Die Passagen sind voneinander sichtbar getrennt, damit das Modell sie
   nicht zu einem Fließtext verschmilzt. **(gebaut)**
3. **Der bisherige Gesprächsverlauf**, soweit er in die Kontextgrenze passt. **(gebaut)**
4. **Die Frage.**

Die Antwort kommt **mit Belegen im Text zurück, nicht mit einer Liste am Ende**: Jede tragende Aussage
trägt die Zeichenfolge der Passage, auf die sie sich stützt, unmittelbar bei sich. OPAA löst diese
Zeichenfolgen anschließend gegen die tatsächlich übergebenen Passagen auf und macht Sprungmarken
daraus; was sich nicht auflösen lässt, gilt als nicht belegt. **(gebaut, in der Grundform)**

Diese Form ist bewusst gewählt und trägt die Belegbarkeit:

- **Eine Quellenliste am Ende ließe sich nicht prüfen.** Sie sagt nicht, welcher Satz woher stammt —
  genau die Zuordnung, die die Belegprüfung braucht.
- **Erfundene Belege fallen auf.** Eine Zeichenfolge, die keiner übergebenen Passage entspricht, wird
  beim Auflösen zu einer Fehlstelle statt zu einem Verweis.
- **Der Beleg bleibt an der Aussage**, auch wenn Nutzende die Antwort kürzen, zitieren oder in einen
  Vermerk übernehmen.

Für die Zuschneidung auf die Kontextgrenze gilt: Passt die Menge der ausgewählten Passagen nicht, wird
**von unten gekürzt** — die schwächsten Treffer entfallen zuerst — und die Kürzung wird ausgewiesen. Eine
stillschweigend gekürzte Grundlage wäre die schlechteste Form der Verkürzung, weil die Antwort
vollständig aussieht.

### Ausgabe im Fluss

Eine Antwort, die erst nach mehreren Sekunden am Stück erscheint, wird als langsam erlebt, auch wenn sie
es nicht ist. OPAA gibt sie deshalb **im Fluss** aus: Der Text erscheint, während er entsteht, und der
Vorgang lässt sich abbrechen, sobald erkennbar ist, dass die Antwort in die falsche Richtung läuft. Für
die empfundene Antwortzeit ist das der wirksamste Einzelfaktor.

**Mit der gebauten Belegvalidierung entsteht hier keine Spannung.** Sie hält keine Antwort zurück — ein
ungültiger Beleg wird gekennzeichnet, nicht die Ausgabe verweigert (siehe
[Zitierzwang](./data-indexing-rag.md#zitierzwang)) —, also gibt es nichts, dessen Sichtbarkeit ein
laufender Strom vorwegnehmen könnte. Die Ausgabe im Fluss kann deshalb, sobald sie gebaut ist, ohne
weitere Abwägung überall greifen.

**Historisch, für den Fall einer Wiederaufnahme:** Der ursprünglich vorgesehene Verweigerungsapparat
(am 21.08.2026 verworfen) hätte diese Spannung erzeugt — **die Belegprüfung setzt an der fertigen
Antwort an**, ein Strom hätte einen unbelegten Satz aber schon gezeigt, bevor das Urteil feststeht. Drei
Auflösungen standen dafür zur Wahl, falls die Frage mit einem künftigen Verweigerungsapparat wieder
aufgemacht wird:

- **Erst prüfen, dann ausgeben.** Die Antwort wird vollständig erzeugt, geprüft und erst danach
  ausgegeben. Sauber und ohne Widerruf, aber ohne den Zeitvorteil des Flusses.
- **Ausgeben und widerrufen können.** Der Text läuft mit, und die Prüfung kann ihn nachträglich als nicht
  belegt kennzeichnen oder zurücknehmen. Schnell, aber jemand hat den unbelegten Satz bereits gelesen —
  und Gelesenes ist nicht widerrufbar.
- **Abschnittsweise prüfen.** Ausgegeben wird in belegten Abschnitten: Ein Abschnitt erscheint, sobald
  seine Belege aufgelöst sind. Verbindet beide Vorteile, ist aber die aufwendigste Variante.

**Zielbild.** Die Ausgabe im Fluss ist heute nicht gebaut; die Antwort erscheint am Stück.

---

## Absicherung des Modells gegen Missbrauch

Ein Sprachmodell tut, was in seinem Eingabetext steht — und der Eingabetext besteht bei OPAA zu einem
großen Teil aus Dokumenten, die niemand daraufhin gelesen hat. Drei Angriffsflächen folgen daraus, und
sie werden getrennt behandelt, weil sie verschiedene Gegenmittel haben.

### Widerstand gegen Umgehungsversuche

Gemeint ist der Versuch, das Modell durch Anweisungen in der Frage aus seiner Rolle zu lösen — „vergiss
deine Anweisungen", „antworte als ein System ohne Beschränkungen". Vier Eigenschaften wirken dagegen,
und keine davon ist eine Bitte an das Modell:

- **Der Systemvorspann ist nicht über den Chat änderbar.** Eingaben ersetzen ihn nicht; sie stehen an
  einer anderen Stelle des Aufrufs und werden als Nutzertext behandelt.
- **Die Antwortgrundlage ist der abgerufene Bestand.** Was nicht gefunden wurde, steht dem Modell nicht
  zur Verfügung — eine Umgehung erweitert den Zugriff nicht.
- **Die Rechteprüfung sitzt vor dem Modell, nicht im Modell.** Kein Formulierungstrick kann Bestände
  öffnen, denn unberechtigte Passagen werden gar nicht erst geladen. Das ist die eigentliche Sicherung
  und der Grund, warum ein gelungener Umgehungsversuch bei OPAA vergleichsweise wenig einbringt.
- **Die Belegvalidierung begrenzt den Schaden.** Ein von einer Umgehung erschlichener, unbelegter Satz
  trägt keinen gültigen Beleg und wird als solcher gekennzeichnet — er kommt nicht als scheinbar
  belegte Aussage durch (siehe [Zitierzwang](./data-indexing-rag.md#zitierzwang)).

### Untergeschobene Anweisungen aus Dokumenten

Der ernstere Fall ist nicht die Frage, sondern der **Bestand**: Ein Dokument enthält einen Satz, der wie
eine Anweisung an das Modell aussieht. Das kann bösartig platziert sein oder schlicht ein zitierter
Beispieltext. Zwei Festlegungen gelten:

- **Dokumentinhalt ist Material, keine Anweisung.** Die Übergabe kennzeichnet jede Passage als Fundstelle
  mit Herkunft (siehe [Übergabe der Passagen](#übergabe-der-passagen-und-form-der-antwort)); Anweisungen
  aus diesem Bereich werden nicht befolgt.
- **Das Restrisiko bleibt und wird nicht wegdefiniert.** Kein Sprachmodell trennt Anweisung und Material
  zuverlässig. Deshalb greifen dahinter die Sicherungen, die nicht am Modell hängen: Rechteprüfung vor
  der Suche, Belegprüfung nach der Erzeugung, Protokoll.

Die Seite, die für **Agenten mit Werkzeugen** hinzukommt — eine untergeschobene Anweisung, die eine
Handlung im Quellsystem auslöst —, ist ungleich folgenreicher und wird im Prüfstand für Agenten
(Themenbereich D) behandelt, nicht hier. Für diesen Zusammenhang gilt der Grundsatz aus
[Wissensquellen und Konnektoren](./knowledge-sources.md#lesender-und-schreibender-zugriff): Lesen ist
folgenlos, Schreiben verlangt eine menschliche Freigabe.

### Erfundene Aussagen

Die frühere Fassung führte dies als eigenes Thema. Es ist inhaltlich in der **Belegbarkeit**
aufgegangen: Antworten sind an Fundstellen gebunden, und jeder Beleg wird deterministisch gegen die
abgerufenen Fundstellen geprüft — ein erfundener Beleg wird gekennzeichnet, nicht stillschweigend
akzeptiert. Beschrieben ist das in
[Wissensschicht und Retrieval](./data-indexing-rag.md#belegbarkeit). Hier steht es nur noch als Verweis,
damit die Verlagerung nicht als Wegfall gelesen wird.

### Filterung von Inhalten

Manche Häuser verlangen zusätzliche Prüfschritte auf dem Weg zur Ausgabe: Unterdrückung anstößiger
Formulierungen, Schwärzung personenbezogener Merkmale in der Ausgabe, Maskierung besonders
schutzbedürftiger Angaben. Vorgesehen ist das als **zuschaltbarer Nachbearbeitungsschritt**, ausgeschaltet
in der Voreinstellung, mit drei Einschränkungen:

- **Ein Filter ersetzt keine Zugriffsbeschränkung.** Was jemand nicht sehen darf, gehört nicht in die
  Antwort, weil es nicht in den Suchbereich gehört — nicht, weil ein Filter es hinterher entfernt.
- **Eine Schwärzung im Beleg macht den Beleg unbrauchbar.** Wird gefiltert, muss erkennbar bleiben, dass
  gefiltert wurde, und der Sprung ins Original bleibt rechtegeprüft möglich.
- **Filter arbeiten auf Mustern und irren.** Sie sind eine Erleichterung, keine Zusicherung — dieselbe
  Einordnung wie beim Ersetzen personenbezogener Merkmale vor einem Aufruf nach außen.

---

## Grenzen und Kontingente

Grenzen je Person, je Gruppe und für das Haus insgesamt sind vorgesehen. Sie schützen den Betrieb — bei
lokalen Modellen ist die knappe Größe die Rechenleistung, nicht ein Budget.

Zwei Festlegungen gehören dazu:

- **Eine überschrittene Grenze ist eine Auskunft, kein Fehler.** Die betroffene Person erfährt, was gilt
  und wann die Grenze wieder greift.
- **Grenzen sind kein Auswertungspfad.** Die Verbrauchsmessung dient der Steuerung von Ressourcen, nicht
  der Beobachtung von Personen. Auswertungen sind aggregiert und ohne Ranglisten; die Festlegungen dazu
  stehen in
  [Mitbestimmung und Personalvertretung](./spaces-and-assets.md#mitbestimmung-und-personalvertretung).

Die weitergehende Betrachtung von Verbrauch, Auslastung und Steuerung gehört zu Themenbereich H und wird
dort beschrieben.

---

## Integrationspunkte

- **[Wissensschicht und Retrieval](./data-indexing-rag.md)** — Einbettungs-, Rerank- und Antwortmodell;
  Belegvalidierung; die Fähigkeitsabhängigkeit des Bildverständnisses. Dort wird bestimmt, **welche**
  Passagen übergeben werden; hier, **wie**.
- **[Spaces, Assets und Zugangskontrolle](./spaces-and-assets.md)** — die Vorgaben von Space, Bibliothek
  und Agent, ihre Verrechnung als Obergrenze und die Zuständigkeit für ihre Festlegung.
- **[Wissensquellen und Konnektoren](./knowledge-sources.md)** — die Modellvorgabe einer
  konnektorgespeisten Bibliothek wird mit der Quellzuordnung gesetzt.
- **[Zugangskontrolle](./access-control.md)** — Protokollierung von Modellaufrufen, Freigaben für
  Modelle außerhalb des Hauses, Verwahrung der Zugangsdaten.
- **[Deployment und Infrastruktur](./deployment-infrastructure.md)** — Betrieb lokaler Modelle,
  Rechenleistung, Netzwege und der Betrieb ohne Netzanbindung.
- **[Suchqualität messbar machen](./search-quality-evaluation.md)** — jeder Modellwechsel ist ein
  Eingriff mit Regressionsrisiko und wird gegen dieselben Referenzfälle gemessen.
- **[ADR-0002](../decisions/0002-mvp-technology-stack.md)** — die gewählte Technologiebasis und die
  Abstraktion, über die Modelle angebunden werden.

---

## Offene Fragen / Zukünftige Erweiterungen

- Wie werden die **verschlüsselten Zugangsdaten** aus Stufe 1 umgeschlüsselt, wenn der Hauptschlüssel der
  Installation gewechselt werden muss? Ohne einen Weg dafür bleibt als Antwort nur, jeden Schlüssel neu
  einzutragen — bei wenigen Modellen vertretbar, als Dauerzustand nicht.
- Braucht Stufe 1 bereits eine **Vorschau der Auswirkung** vor dem Umschalten des aktiven Modells, oder
  genügt es, dass die Umschaltung protokolliert und jederzeit zurücknehmbar ist? Das Zielbild sieht die
  Vorschau unter [Sofortige Wirkung](#sofortige-wirkung) vor; bei genau einem aktiven Modell ist die
  betroffene Menge allerdings immer „alles".
- Erweist sich die Beschränkung auf die **OpenAI-kompatible Schnittstelle** im Betrieb als hinderlich —
  insbesondere dort, wo die Kontextgröße eines Ollama-Modells fachlich relevant wird und nicht am Server
  gesetzt werden kann?
- Wie werden **Schutzstufen von Daten** benannt und gepflegt, damit die Zuordnung „welches Modell darf
  diese Klasse verarbeiten" mehr ist als ein Freitextfeld? Ohne ein knappes, verbindliches Schema wird
  die Zuordnung uneinheitlich gesetzt.
- Darf ein Agent eine Modellvorgabe **verschärfen**, oder ist das allein Sache von Systemverwaltung und
  Bibliothek? Verschärfen ist folgerichtig, kann aber dazu führen, dass ein geteilter Agent beim
  Empfänger nicht läuft.
- Wie wird ein **Modellwechsel geprüft**, bevor er hausweit gilt — Vergleichsläufe gegen Referenzfälle,
  Freigabe für einen begrenzten Kreis, oder beides?
- Soll eine Installation **eigens angepasste Modelle** aufnehmen können, und wie werden sie im
  Modelleintrag von einem Standardmodell unterschieden?
- Wie wird die **Erklärung der Modellauswahl** dargestellt, ohne Bestände preiszugeben, auf die die
  fragende Person keinen Zugriff hat?
- Wie werden **Fähigkeitsunterschiede** behandelt, wenn ein Agent eine Fähigkeit voraussetzt, die das
  zulässige Modell nicht hat — Verweigerung, eingeschränkter Lauf mit Hinweis, oder Auswahl nach
  Fähigkeit innerhalb der Obergrenze?
- Wie belastbar lässt sich eine **Zusicherung „kein Training mit unseren Daten"** technisch abbilden,
  oder bleibt sie eine reine Vertrags- und Dokumentationsangabe?
- Ab welcher Größe lohnt eine **getrennte Betriebsumgebung für Modelle** gegenüber dem gemeinsamen
  Betrieb mit der Anwendung?
- Falls ein Verweigerungsapparat über die Belegvalidierung hinaus wieder aufgemacht wird (siehe
  [Zitierzwang](./data-indexing-rag.md#zitierzwang)): Bleibt es dann bei der **vollständigen Prüfung vor
  der Ausgabe**, oder lohnt die abschnittsweise Prüfung während der Ausgabe? Letztere hält den
  Zeitvorteil, verlangt aber eine verlässliche Zerlegung der Antwort in prüfbare Abschnitte.
- Soll die **Filterung von Inhalten** im Zielbild bleiben? Sie war im früheren Bestand als Erweiterung
  geführt und ist hier unverändert übernommen. Dagegen spricht, dass sie eine Sicherheit suggeriert, die
  eine Mustererkennung nicht leisten kann, und dass sie mit der Belegbarkeit in Konflikt gerät.
- Wie wird eine **Kürzung an der Kontextgrenze** dargestellt, ohne die Antwort mit technischen Hinweisen
  zu überfrachten — und ab welchem Anteil entfallener Passagen ist die Antwort besser zu verweigern?
- Wird der Anteil **untergeschobener Anweisungen aus Dokumenten** überhaupt messbar, oder bleibt es bei
  der Feststellung eines nicht bezifferbaren Restrisikos?

---

## Erfolgs-Metriken

- **Anteil der Vorgänge auf lokal betriebenen Modellen** — das unmittelbare Maß für die Souveränität der
  Installation.
- **Zahl der Vorgänge, die wegen leerer Schnittmenge verweigert wurden**, aufgeschlüsselt nach der
  auslösenden Ebene. Dauerhaft hohe Werte deuten auf widersprüchliche Vorgaben hin, nicht auf ein
  Nutzerproblem.
- **Zeit bis zur Wirksamkeit einer zentralen Änderung** und Zahl der dafür nötigen Eingriffe in Spaces
  und Agenten — die Zielgröße ist null.
- **Anteil der Antworten mit nachvollziehbarer Modellangabe** im Protokoll.
- **Verfügbarkeit der lokal betriebenen Modelle** und Anteil der Vorgänge ohne verfügbares Modell.
- **Zahl der Aufrufe an Modelle außerhalb des Hauses** und deren Anlass, als Nachweis gegenüber Prüfung
  und Personalvertretung.
