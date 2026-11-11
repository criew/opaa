# ADR-0067: Modellanbindung über eine Schnittstelle und verwaltete Chat-Modelle

## Status

Akzeptiert · zuletzt geändert 2026-11-09

## Kontext

Welches Sprachmodell antwortet, entscheidet in einer Behörde die Systemverwaltung. Solange das Modell
in Umgebungsvariablen stand, verlangte jeder Wechsel eine Änderung an der Betriebskonfiguration und
einen Neustart. Die Entscheidung lag damit faktisch bei der Person mit Zugriff auf die Container.

Daneben gab es zwei Anbindungswege: einen nativen für Ollama und die OpenAI-kompatible Schnittstelle
für alles andere. Jede Fallunterscheidung nach Anbieter zog sich durch Formular, Schnittstelle, Aufruf
und Tests.

Gebaut wurde das in Epic #755 und #762. Die Betriebssicht steht im Handbuch unter
[Modelle](../handbuch/betrieb/modell-endpunkte.md).

## Entscheidung

### 1. Ein Anbindungsweg

Alle Modellrollen werden ausschließlich über die OpenAI-kompatible Schnittstelle angebunden. Einen
eigenen Anbieter-Typ für Ollama gibt es nicht; Ollama wird über seinen Pfad `/v1` eingetragen wie vLLM,
LiteLLM oder Azure. Ein Weg heißt ein Codepfad, ein Verbindungstest und ein Fehlerbild.

Einzige Ausnahme ist die Texterkennung gescannter PDF-Dokumente (OCR): Für sie gibt es keine
OpenAI-kompatible Standardschnittstelle, und spezialisierte OCR-Pipelines lesen Tabellen und
Seitenaufbau deutlich besser als ein allgemeines Vision-Modell. Die Rolle kennt deshalb genau zwei
Verbindungstypen: die API von docling-serve (ganze PDF, Schlüssel als `X-Api-Key`), auf der auch der
mitgelieferte OCR-Container beruht, und ein OpenAI-kompatibles Vision-Modell (je Seite ein Bild,
Schlüssel als Bearer). Weitere Typen kommen nicht hinzu; ein anderer OCR-Dienst wird über einen der
beiden angebunden. Adressprüfung, Schlüsselbehandlung und Protokollierung gelten unverändert. Über
der Rolle steht ein Betriebsschalter (`opaa.ocr.enabled`, Voreinstellung aus):
ausgeschaltet wird die Rolle weder ausgewertet noch kontaktiert, weil ganze Seiten von Dokumenten
einen eigenen Datenfluss bilden, über den der Betrieb entscheidet.

### 2. Zugangsschlüssel optional

Der Zugangsschlüssel ist kein Pflichtfeld. Ein Endpunkt im Haus verlangt oft keine Anmeldung, und ein
erfundener Pflichtwert täuschte einen Schutz vor. Der Schlüssel wird verschlüsselt gespeichert, nie
ausgegeben (nur `apiKeySet`) und nur an denselben Ursprung gesendet, für den er eingetragen wurde.

### 3. Keine Anmeldedaten in der Basis-Adresse

Eine Adresse wie `https://benutzer:geheim@host/v1` wird für alle Modellrollen abgelehnt, beim
Anlegen, Ändern, Verbindungstest, in der Installation und beim Start im Modus `dev`. Die Basis-Adresse
gilt als geheimnisfrei und wird wörtlich in Datenbank, Revisionsprotokoll, API-Antwort und Log
weitergereicht. Abgelehnt statt still entfernt, weil ein stilles Entfernen die Absicht der Person
wortlos zurücknähme; die Fehlermeldung wiederholt den beanstandeten Anteil nicht.

### 4. Chat-Modelle verwaltet die Anwendung

Chat-Modelle legt die Systemverwaltung in der Oberfläche an (`/api/v1/admin/models`, nur
`SYSTEM_ADMIN`); genau eines ist systemweit aktiv. Antwort- und Titelgenerierung lösen das aktive
Modell bei jedem Aufruf auf. Der gebaute Client wird erst nach dem Commit einer Änderung am aktiven
Modell verworfen, damit eine zurückgerollte Änderung nicht wirkt. Ohne aktives oder bei nicht
erreichbarem Modell gibt es eine verständliche Fehlermeldung, nie ein stilles Ausweichen auf ein
anderes Modell. Jede Änderung wird protokolliert.

### 5. Chat-Modelle nur in der Datenbank

Chat-Modelle stehen nur in `llm_models`. Das erste legt die Installation an
([ADR-0060](0060-schlanke-konfiguration-und-einrichtungsassistent.md), Entscheidung 10), im Modus
`dev` die Selbstinstallation aus `OPAA_OPENAI_BASE_URL`. Frühere Umgebungsvariablen für das
Chat-Modell meldet der Start als entfallen und wertet sie nicht aus. Zwei
Orte für dieselbe Entscheidung wären schlechter als jeder einzeln, weil bei abweichenden Werten
niemand mehr sagen kann, welcher gilt.

### 6. Einbettung nicht frei wählbar

Das Embedding-Modell wird in der Installation festgelegt ([ADR-0060](0060-schlanke-konfiguration-und-einrichtungsassistent.md),
Entscheidung 10) und nur als geplanter Vorgang gewechselt
([ADR-0061](0061-wechsel-des-embedding-modells.md)). Ein Wechsel macht alle Vektoren unvergleichbar;
eine Auswahlliste dafür wäre eine Falle.

## Konsequenzen

- Einfacher: ein Codepfad für alle Anbieter; Modellwechsel ohne Neustart, wirksam auf allen Instanzen.
- Schwieriger: Die Texterkennung bringt einen zweiten Codepfad mit eigenem Verbindungstest; er
  bleibt auf diese eine Rolle und zwei Typen begrenzt.
- Schwieriger: Ollama-eigene Optionen wie `num_ctx` oder `keep_alive` sind über die Schnittstelle
  nicht setzbar. Sie bleiben Betriebskonfiguration am Ollama-Server, wo sie als Ressourcenfrage der
  Maschine auch hingehören.

## Verworfene Alternativen

- Nativer Ollama-Weg neben der kompatiblen Schnittstelle — Fallunterscheidung im ganzen System für
  zwei Optionen, die am Server besser aufgehoben sind.
- Maskieren von Anmeldedaten beim Lesen statt Ablehnen beim Schreiben — zweite Darstellungsschicht
  für einen Fall, den es ohne gespeicherte Altadressen nicht gibt.
- Umgebungsvariablen und Datenbank parallel auswerten — unklar, welcher Wert gilt.

## Offen

- Vorgaben je Space oder Bibliothek als Obergrenze der Modellwahl und Voreinstellungen je Aufgabe
  stehen in der [Roadmap](../ROADMAP.md).
