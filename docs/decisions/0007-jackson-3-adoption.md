# ADR-0007: Vollständige Umstellung auf Jackson 3 statt Jackson-2-Kompatibilitätsschicht

## Status

Akzeptiert

## Kontext

Spring Boot 4 macht Jackson 3 (Group-ID `tools.jackson`) zum Standard. Die automatisch
konfigurierte Bean ist nicht mehr `com.fasterxml.jackson.databind.ObjectMapper`, sondern
`tools.jackson.databind.json.JsonMapper`. Für Anwendungen, die auf Jackson 2 bleiben wollen,
liefert Spring Boot 4 das als deprecated markierte Modul `spring-boot-jackson2` als
Übergangslösung mit.

Im OPAA-Backend wird Jackson direkt an genau zwei Stellen verwendet: `RateLimitConfiguration`
injiziert den Mapper und reicht ihn an `RateLimitFilter` weiter, der damit die 429-Fehlerantwort
serialisiert. Alle übrigen (De-)Serialisierung läuft über Spring MVCs Message Converter, also
ohne direkten Kontakt zum Mapper-Typ.

Zusätzlich ist Jackson 2 weiterhin transitiv auf dem Classpath — `jjwt-jackson` bringt es als
eigene Abhängigkeit mit. Jackson 2 verschwindet also ohnehin nicht aus dem Build.

## Entscheidung

Wir migrieren die beiden direkten Verwendungsstellen auf Jackson 3 (`JsonMapper`) und verzichten
auf das Kompatibilitätsmodul `spring-boot-jackson2`.

Die von OpenAPI Generator erzeugten DTOs bleiben unverändert: Jackson 3 verwendet weiterhin das
Annotations-Artefakt `com.fasterxml.jackson.core:jackson-annotations`, sodass `@JsonProperty` und
Verwandte ohne Anpassung funktionieren. Die Generator-Konfiguration in `build.gradle.kts` muss
dafür nicht geändert werden.

`jjwt-jackson` bleibt auf Jackson 2 und versorgt sich selbst mit `jackson-databind`. Ein Wechsel
des JJWT-Serializers ist nicht Teil dieser Entscheidung.

## Konsequenzen

**Einfacher:**

- Kein deprecated Modul im Build, dessen Entfernung in einer künftigen Spring-Boot-Version erneut
  Arbeit erzeugen würde.
- Nur ein auto-konfigurierter Mapper im Kontext; keine Mehrdeutigkeit, welcher Mapper injiziert
  wird.
- Der Aufwand war gering: zwei Produktionsklassen und eine Testklasse.

**Schwieriger:**

- Jackson 2 und Jackson 3 liegen gleichzeitig auf dem Classpath (über `jjwt-jackson`). Beim
  Hinzufügen neuer Jackson-Verwendungen muss bewusst `tools.jackson.*` importiert werden — ein
  versehentlicher `com.fasterxml.jackson.databind.ObjectMapper`-Import kompiliert, findet zur
  Laufzeit aber keine Bean.
- Bibliotheken von Drittanbietern, die eine `ObjectMapper`-Bean im Kontext erwarten, funktionieren
  nicht ohne zusätzliche Konfiguration.

## Verwandte Entscheidungen

- [ADR-0002](0002-mvp-technology-stack.md) — Technologie-Stack (Spring Boot 4.x / Spring AI 2.0.0)
- [ADR-0006](0006-openapi-dto-generation.md) — Generierung der API-DTOs aus der OpenAPI-Spezifikation
