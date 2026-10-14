# ADR-0044: Installationsweite Feature-Flags

## Status

Akzeptiert · zuletzt geändert 2026-10-13

## Kontext

OPAA wächst um Funktionen, die nicht jede Installation zeigen will: Eine Demo-Instanz soll nur Fragen
und Antworten vorführen, eine Behörde eine Funktion erst nach einer Schulung freigeben. Bisher gab es
dafür nur Einzelschalter mit eigener Verdrahtung (`opaa.demo.chat-import.enabled` mit
`@ConditionalOnProperty` am Controller). Jeder weitere Schalter hätte eigene Properties, einen eigenen
Weg ins Frontend, eigene Werte im Helm-Chart und eine eigene, womöglich abweichende Vorgabe gebraucht.
Gebraucht wird ein Mechanismus, den Backend, Frontend und Chart gleich verstehen.

## Entscheidung

### 1. Je Installation, nicht je Organisation

Ein Flag gilt für die ganze Installation und alle Organisationen darin — eine Entscheidung des
Betriebs, keine Einstellung, die eine Organisation pflegt oder sieht. Flags je Organisation bräuchten
Datenbank, Verwaltungsoberfläche und Revisionsprotokoll und sind nicht Teil dieser Entscheidung.

### 2. Jedes Flag ist ohne Angabe an

Nur ein ausdrückliches `false` schaltet ab — in `application.yml`, in der Bedingung für Beans, im
Frontend für ein Flag, das die Antwort nicht nennt, und im Chart für ein Flag ohne Eintrag. Eine
Installation, die nichts konfiguriert, sieht immer den vollen Umfang, auch nach einem Update mit neuen
Flags.

### 3. Abschalten ignoriert vorhandene Daten und löscht nie

Eine abgeschaltete Funktion verschwindet aus Oberfläche und Schnittstelle; ihre Daten bleiben und
werden nur nicht beachtet. Wieder eingeschaltet, ist der frühere Stand zurück. Ein Flag ist jederzeit
umkehrbar und nie ein Löschweg.

### 4. Wirkt nach einem Neustart

Das Backend liest die Flags beim Start (`opaa.features.<schlüssel>.enabled`,
`OPAA_FEATURES_<SCHLÜSSEL>_ENABLED`). Kein Umschalten zur Laufzeit: Beim Start nicht registrierte Beans
kämen ohne Neustart nicht zurück, und ein halb umgeschalteter Zustand wäre schwer zu überblicken.

### 5. Ein Flag kommt ohne neue Verdrahtung hinzu

`io.opaa.common.Feature` ist die einzige Quelle der Flag-Namen: Aus `PROMPT_LIBRARIES` leiten sich der
Schlüssel `prompt-libraries` und der API-Name `promptLibraries` ab. Ein neues Flag ist eine Konstante,
eine Zeile in `application.yml` und eine Eigenschaft im Schema `FeaturesConfig` (`auth.yaml`);
`FeaturesSpecificationTest` hält beides im Gleichlauf. Im Chart kommen ein Eintrag in `values.yaml` und
einer im Werteschema hinzu (Entscheidung 8).

| Flag | Wirkung | seit |
|---|---|---|
| `PROMPT_LIBRARIES` | Prompt-Bibliotheken aus Katalog, Chat und Oberfläche; der Asset-Typ bleibt für Bestandsdaten registriert (`AssetTypes#active`) | #2588 |
| `METADATA` | Metadaten von Dokumenten und Bibliotheken; aus bleibt nur der Titel, kein Filter verengt | #2589 |
| `SPEECH_INPUT` | Diktat im Chat; das Mikrofon erscheint nur, solange auch die Transkription konfiguriert ist | #2629 |

### 6. Zwei Wege, ein Flag zu beachten

- `@ConditionalOnFeature(Feature.X)` registriert eine Klasse oder `@Bean`-Methode nur bei
  eingeschaltetem Flag — für **Blatt-Beans**, die niemand injiziert (Controller, Scheduler, Runner,
  Listener). Eine abgeschaltete Route existiert dann nicht und antwortet wie jede unbekannte Adresse.
- `FeatureFlags#isEnabled(Feature)` ist die Laufzeitprüfung für Beans, von denen andere abhängen, für
  gemeinsame Definitionen und für Prüfungen in Diensten.

Bedingung und `FeatureFlags` lesen über dieselbe Regel (Spring-Binder, gelockerte Schreibweise,
Vorgabe an), es gibt kein `matchIfMissing`. Roh-`@ConditionalOnProperty` auf `opaa.features.*` wird
nicht verwendet (`backend/src/main/java/io/opaa/common/AGENTS.md`).

### 7. Auslieferung an das Frontend über die Anmeldekonfiguration

`GET /api/v1/auth/config` trägt ein Objekt `features` mit einem Wahrheitswert je Flag; der Endpunkt ist
ohne Anmeldung lesbar. Das Frontend liest über `useFeatureEnabled` (Komponenten) oder
`isFeatureEnabled` aus dem `authStore`. Die Flags verraten nur, welche Funktionen die Installation
anbietet.

### 8. Im Helm-Chart ein Abschnitt `features`

Je Flag `<name>.enabled`; die Vorlage erzeugt die Umgebungsvariable mechanisch aus dem Namen. Das
Werteschema zählt die bekannten Flags auf und lehnt jeden anderen Namen ab, damit ein Tippfehler nicht
still wirkungslos bleibt.

## Konsequenzen

- Eine abgeschaltete Funktion muss ihre Daten an allen Lesewegen ignorieren, auch dort, wo andere
  Funktionen sie mitlesen (Metadaten in Suche und Antwort) — Aufgabe des jeweiligen Flags, mit der
  Laufzeitprüfung.
- Ein falsch geschriebener Schlüssel unter `opaa.features` wird ignoriert, die Funktion bleibt an; das
  Startprotokoll nennt jedes abgeschaltete Flag, sodass eine wirkungslose Angabe auffällt.
- Ein dem Backend unbekanntes Flag fehlt in der Antwort, ein älteres Frontend behandelt es als an; ein
  Frontend, das ein Flag kennt, das ein älteres Backend nicht liefert, ebenso.
- Umschalten braucht einen Neustart; für eine Demo, die zwischen Vorführungen umschaltet, bewusst
  hingenommen.

## Verworfene Alternativen

- Flags je Organisation — Datenbank, Verwaltungsoberfläche und Revisionsprotokoll für eine Betriebsentscheidung.
- Umschalten zur Laufzeit — halb umgeschalteter Zustand zwischen registrierten Routen und Laufzeitprüfungen.
- Vorgabe „aus" oder `matchIfMissing` je Schalter — jede Vorgabe neu und möglicherweise anders entschieden.

## Offen

- `opaa.demo.chat-import.enabled` (Einspielweg der Demo-Instanz, `ChatImportController`) bleibt ein
  Einzelschalter mit Vorgabe **aus** und passt damit nicht in Entscheidung 2. Ob er Einzelschalter
  bleibt oder in ein Flag mit abweichender Vorgabe überführt wird, ist nicht entschieden.

## Änderungsverlauf

| Datum | Issue/PR | Änderung (eine Zeile) | Entscheidungen |
|---|---|---|---|
| 2026-10-13 | #2587 | Erstfassung mit `PROMPT_LIBRARIES` und `METADATA` (Wirkung: #2588, #2589) | 1–8 |
| 2026-10-13 | #2629 | drittes Flag `SPEECH_INPUT` | 5 |
| 2026-10-14 | #2701 | Neufassung auf den gültigen Endstand | — |
