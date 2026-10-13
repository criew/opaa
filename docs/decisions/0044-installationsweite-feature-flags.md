# ADR-0044: Installationsweite Feature-Flags

## Status

Vorgeschlagen (13.10.2026; Issue #2587, Epic #2586)

## Kontext

OPAA wächst um Funktionen, die nicht jede Installation zeigen will. Eine Demo-Instanz soll etwa nur
Fragen und Antworten vorführen, ohne Prompt-Bibliotheken und Metadaten; eine Behörde will eine
Funktion erst nach einer Schulung freigeben. Bisher gab es dafür nur Einzelschalter mit eigener
Verdrahtung, etwa `opaa.demo.chat-import.enabled` mit `@ConditionalOnProperty` am Controller. Jeder
weitere Schalter hätte wieder eigene Properties, einen eigenen Weg ins Frontend und eigene Werte im
Helm-Chart gebraucht, und jeder hätte die Vorgabe (an oder aus, `matchIfMissing`) neu und
möglicherweise anders entschieden.

Gebraucht wird ein Mechanismus, über den eine Installation Funktionen abschalten kann und den
Backend, Frontend und Chart gleich verstehen.

## Entscheidung

1. **Je Installation, nicht je Organisation.** Ein Flag gilt für die ganze Installation und alle
   Organisationen darin. Es ist eine Entscheidung des Betriebs, keine Einstellung, die eine
   Organisation pflegt oder sieht. Flags je Organisation sind ausdrücklich nicht Teil dieser
   Entscheidung; sie bräuchten Datenbank, Verwaltungsoberfläche und Revisionsprotokoll.

2. **Jedes Flag ist ohne Angabe an.** Abschalten ist eine bewusste Entscheidung der Installation,
   etwa für eine Demo. Nur ein ausdrückliches `false` schaltet ab. Das gilt an jeder Stelle: in
   `application.yml`, in der Bedingung für Beans, im Frontend für ein Flag, das die Antwort des
   Backends nicht nennt, und im Chart für ein Flag ohne Eintrag. Eine Installation, die nichts
   konfiguriert, sieht damit immer den vollen Funktionsumfang, auch nach einem Update mit neuen
   Flags.

3. **Abschalten ignoriert vorhandene Daten und löscht nie.** Eine abgeschaltete Funktion
   verschwindet aus Oberfläche und Schnittstelle; ihre Daten bleiben unverändert und werden nur
   nicht mehr beachtet. Wieder eingeschaltet, ist der frühere Stand zurück. Ein Flag ist damit
   jederzeit umkehrbar und nie ein Löschweg.

4. **Wirkt nach einem Neustart.** Das Backend liest die Flags beim Start
   (`opaa.features.<schlüssel>.enabled`, Umgebungsvariable `OPAA_FEATURES_<SCHLÜSSEL>_ENABLED`).
   Ein Umschalten zur Laufzeit gibt es nicht: Beans, die beim Start nicht registriert wurden,
   kämen ohne Neustart ohnehin nicht zurück, und ein halb umgeschalteter Zustand zwischen
   registrierten Routen und Laufzeitprüfungen wäre schwer zu überblicken.

5. **Ein Flag kommt ohne neue Verdrahtung hinzu.** Die Aufzählung `io.opaa.common.Feature` ist die
   einzige Quelle der Flag-Namen: Aus der Konstante `PROMPT_LIBRARIES` leiten sich der Schlüssel
   `prompt-libraries` und der Name `promptLibraries` in der API ab. Ein neues Flag ist eine
   Konstante, eine Zeile in `application.yml` mit der Umgebungsvariablen und eine Eigenschaft im
   Schema `FeaturesConfig` der OpenAPI-Spezifikation. Abfragestelle, Bedingung, Auslieferung an
   das Frontend, Hook und Chart-Vorlage bleiben unverändert; im Chart kommen nur ein Eintrag in
   `values.yaml` und einer im Werteschema hinzu (Entscheidung 8). `FeaturesSpecificationTest`
   hält Aufzählung und OpenAPI-Schema im Gleichlauf.

6. **Zwei Wege, ein Flag zu beachten — mit einer Regel, welcher wann gilt.**
   - `@ConditionalOnFeature(Feature.X)` registriert eine Klasse oder eine `@Bean`-Methode nur bei
     eingeschaltetem Flag. Bedingung und `FeatureFlags` lesen ein Flag über dieselbe Regel
     (Spring-Binder mit gelockerter Schreibweise, Vorgabe „an"), sie können sich also nicht
     widersprechen, und es gibt kein `matchIfMissing`, das man vergessen kann. Sie ist für **Blatt-Beans** gedacht, die
     niemand sonst injiziert: Controller, Scheduler, Runner, Listener. Eine abgeschaltete Route
     existiert dann nicht und antwortet wie jede unbekannte Adresse.
   - `FeatureFlags#isEnabled(Feature)` ist die Laufzeitprüfung für Beans, von denen andere
     abhängen, für gemeinsam genutzte Definitionen und für Prüfungen innerhalb von Diensten. Eine
     solche Bean zu entfernen, bräche den Start jeder Bean, die sie braucht.

   Roh-`@ConditionalOnProperty` auf `opaa.features.*` wird nicht verwendet.

7. **Auslieferung an das Frontend über die Anmeldekonfiguration.** `GET /api/v1/auth/config` trägt
   ein Objekt `features` mit einem Wahrheitswert je Flag. Der Endpunkt ist ohne Anmeldung lesbar,
   das Frontend kennt die Flags damit vor und nach der Anmeldung und liest sie über
   `useFeatureEnabled` (Komponenten) oder `isFeatureEnabled` (übriger Code) aus dem `authStore`.
   Die Flags verraten nichts über Personen oder Daten, nur, welche Funktionen die Installation
   anbietet; das zeigt die Oberfläche ohnehin.

8. **Im Helm-Chart ein Abschnitt `features`.** Je Flag ein Eintrag `<name>.enabled`; die Vorlage
   erzeugt die Umgebungsvariable mechanisch aus dem Namen. Das Werteschema zählt die bekannten
   Flags auf und lehnt jeden anderen Namen ab, damit ein Tippfehler nicht still wirkungslos
   bleibt. Ein Flag ohne Eintrag bleibt nach Entscheidung 2 an.

## Konsequenzen

- Folge-Issues schalten ihre Funktion über `Feature` und eine der beiden Formen aus Entscheidung 6
  ab, ohne eigene Konfiguration einzuführen. Die ersten beiden Flags, Prompt-Bibliotheken und
  Metadaten, bekommen ihre Wirkung in eigenen Issues (#2588, #2589); dieser Entscheid liefert nur
  den Mechanismus.
- Weil Abschalten nichts löscht, muss jede abgeschaltete Funktion ihre Daten an allen Lesewegen
  ignorieren, auch dort, wo andere Funktionen sie mitlesen (etwa Metadaten in Suche und Antwort).
  Das ist Aufgabe der Folge-Issues und mit der Laufzeitprüfung zu lösen.
- Ein falsch geschriebener Schlüssel unter `opaa.features` wird ignoriert, die Funktion bleibt an.
  Das ist die sichere Richtung nach Entscheidung 2; das Startprotokoll nennt jedes abgeschaltete
  Flag, sodass eine wirkungslose Angabe auffällt.
- Ein Flag, das das Backend nicht mehr kennt, verschwindet aus der Antwort; ein älteres Frontend
  behandelt es als an. Ein Frontend, das ein Flag kennt, das ein älteres Backend noch nicht
  liefert, behandelt es ebenso als an.
- Das Umschalten braucht einen Neustart; für eine Demo, die zwischen Vorführungen umschaltet, ist
  das ein bewusst hingenommener Aufwand.
