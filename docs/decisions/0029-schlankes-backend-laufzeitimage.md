# ADR-0029: Schlankes Backend-Laufzeitimage — jlink-Laufzeit auf Distroless statt vollem Ubuntu-Basisimage

## Status

Vorgeschlagen

## Kontext

Das Backend-Laufzeitimage baute bisher auf `eclipse-temurin:21-jre` auf — Canonicals Ubuntu-Rock mit
vollem Paketsatz. Die Triage aus #1450 hat gezeigt, dass **alle** Image-Alerts des nächtlichen
Trivy-Scans aus diesem Paketsatz stammen, keiner aus einer Anwendungsabhängigkeit und keiner aus dem
JDK. PR #1452 hat behoben, was ein Update behebt (`apt-get dist-upgrade` im Laufzeit-Stage,
Entfernen von `pebble`); übrig blieben Pakete ohne Upstream-Fix, die das Image nur deshalb trägt,
weil ein Vollbetriebssystem sie mitbringt:

| Gruppe | Paket(e) | Alerts (Messung 10.09.2026) |
|---|---|---|
| A | `libexpat1` (nur von fontconfig gelinkt) | 23 |
| B | `rust-coreutils` | 20 |
| C | `p11-kit`, `p11-kit-modules`, `libp11-kit0` | 3 |
| D | `tar` | 2 |
| E | `wget` | 1 |
| F | `passwd`, `login.defs` | 2 |
| G | `libsystemd0`, `libudev1` | 2 |
| — | `libsqlite3-0` | 1 |
| — | glibc (`libc6`, `libc-bin`, `libc-gconv-modules-extra`, `locales`) | 4 |

Alle diese Pakete sind heute nicht erreichbar — aber nur, weil das Image genau einen Java-Prozess
startet. Das ist eine Betriebsannahme, die als Dismiss-Begründung dauerhaft gepflegt werden müsste.
Der Maintainer hat entschieden (#1459), sie stattdessen zu einer Eigenschaft des Images zu machen:
Was nicht enthalten ist, muss nicht begründet werden.

### Erwogene Zielbilder

Alle Zahlen sind eigene Messungen mit `trivy image --scanners vuln` vom 10.09.2026, jeweils gegen
das Basisimage bzw. das fertige Backend-Image.

| Zielbild | Alerts | Schwere | Bewertung |
|---|---|---|---|
| **Ist-Stand** `eclipse-temurin:21-jre` + `dist-upgrade` | 58 | 54 medium, 4 low | Ausgangspunkt |
| `eclipse-temurin:21-jre-alpine` | 34 | 5 high, 11 medium, 18 low | musl statt glibc; bringt eigenes OpenSSL mit (30 der 34 Alerts) und trotzdem wieder `libexpat`. Der Wechsel der C-Bibliothek wäre das größte Laufzeitrisiko der drei Optionen und kauft die wenigste Reduktion. |
| `gcr.io/distroless/java21-debian12` (volles JRE) | 77 | 1 critical, 10 high, 42 medium, 24 low | **Schlechter als der Ist-Stand.** Das Image bringt fontconfig, `libpng16`, `liblcms2` und `libuuid1` für java.desktop mit — `libexpat1` ist damit wieder da, mit 27 statt 23 Alerts. |
| `ubuntu/jre:21-24.04_stable` (Canonicals chiselled JRE) | 0 gemeldet | — | Trivy findet **keine** OS-Pakete, weil das Image keine dpkg-Datenbank hat: „0 Alerts" wäre hier zum Teil Messblindheit, nicht Abwesenheit. Fachlich ohnehin ausgeschlossen — die Laufzeit enthält kein `java.desktop`, das PDFBox und POI brauchen. |
| **jlink-Laufzeit auf `gcr.io/distroless/base-nossl-debian13`** | 18 | 11 medium, 7 low | Gewählt. Ausschließlich glibc, kein Befund mit `high` oder `critical`, und die Pakete der Gruppen A–G sind physisch nicht mehr im Image. |

`gcr.io/distroless/base-nossl-debian12` und `-debian13` messen identisch (18 Alerts, ausschließlich
`libc6`); Debians glibc-Triage ist über beide Releases dieselbe. Gewählt wurde Debian 13 als
aktuelles Stable — gleiche Alert-Fläche, neuere glibc, längerer Support-Horizont.

Das `-nossl`-Basisimage statt `base` spart weitere sieben `libssl3`-Alerts: Die JVM bringt ihre
eigene TLS-Implementierung mit und linkt `libssl` nie.

## Entscheidung

Der Laufzeit-Stage von `backend/Dockerfile` besteht aus zwei Teilen:

1. **Eine mit `jlink` erzeugte Java-Laufzeit** aus demselben `eclipse-temurin:21-jdk`, aus dem auch
   gebaut wird. Modulsatz: `java.se` (das komplette Java SE API einschließlich `java.desktop`, das
   PDFBox und POI benötigen) plus die Module, die der JDK reflektiv als Dienstanbieter lädt und die
   deshalb aus keiner Abhängigkeitsanalyse folgen — `jdk.crypto.ec`/`jdk.crypto.cryptoki` (TLS),
   `jdk.charsets` (Nicht-UTF-8-Zeichensätze importierter Dokumente), `jdk.localedata` (deutsche
   Formatierung), `jdk.naming.dns`, `jdk.unsupported` (`sun.misc.Unsafe`, von Hibernate und Spring
   genutzt), `jdk.zipfs`, `jdk.dynalink`, `jdk.net`, `jdk.security.auth`, `jdk.management` sowie
   `jdk.jfr`, `jdk.management.agent` und `jdk.jdwp.agent` als Diagnosefläche.
2. **`gcr.io/distroless/base-nossl-debian13` als Basis** — glibc und sonst nichts, was die Triage
   oben nennt. Zertifikate (`cacerts`), Zeitzonendatenbank und Locale-Daten kommen aus der
   JDK-Laufzeit, nicht aus OS-Paketen.

Ergänzend wird die eine mitgelieferte glibc-Locale `C.utf8` aus `debian:13-slim` zurückkopiert und
`LANG`/`LC_ALL` darauf gesetzt. Ohne kompilierte Locale meldet glibc den POSIX-Zeichensatz
`ANSI_X3.4-1968`, den die JVM als `sun.jnu.encoding` übernimmt — Datei*namen* mit Umlauten, im
Dokumentenbestand einer Behörde der Normalfall, wären dann unlesbar. `-Duser.language=en`
`-Duser.country=US` im Entrypoint hält die Standard-`Locale` auf dem Wert, den das Temurin-Basisimage
bisher über `LANG=en_US.UTF-8` gesetzt hat.

Der `apt-get dist-upgrade` aus PR #1452 entfällt ersatzlos: Es gibt keinen Paketmanager mehr und
nichts, was er aktualisieren könnte. Die Aktualität von glibc hängt jetzt daran, dass der
`FROM`-Verweis beim Bauen neu aufgelöst wird — was Buildx bei jedem Lauf tut, unabhängig vom
Layer-Cache.

## Konsequenzen

**Einfacher:**

- Die Alert-Dauerlast sinkt von 58 auf 18, alle sieben Gruppen A–G verschwinden vollständig, und
  kein Befund hat mehr die Schwere `high` oder `critical`. Kein Dismiss, keine gepflegte
  Begründung, keine Betriebsannahme, die jemand später verletzt.
- Die Angriffsfläche sinkt real, nicht nur in der Messung: Wer im Container Codeausführung
  erlangt, findet weder Shell noch `wget`, `tar`, `cp` oder einen Paketmanager vor.
- Das Image schrumpft von 935 MB auf 596 MB.

**Schwieriger:**

- **Kein Shell-Zugang mehr.** `docker exec … sh` funktioniert nicht. Diagnose läuft über Logs,
  `/actuator`, JFR und `docker cp`; die Betriebsanleitung beschreibt das
  ([`docs/handbuch/deployment.md`](../handbuch/deployment.md)).
- **Ein fehlendes jlink-Modul fällt erst zur Laufzeit auf.** Der Build bleibt grün, ein
  Codepfad wirft `ClassNotFoundException` oder `ServiceConfigurationError`. Absicherung ist die
  E2E- und `demo-smoke`-Suite, die alle Formate und Konnektoren durchläuft — nicht der Build.
  Eine neue Abhängigkeit mit nativem oder reflektivem JDK-Bedarf muss den Modulsatz mit prüfen.
- **glibc bleibt und wird als Fläche größer.** Debians glibc trägt 18 dauerhaft ungefixte Befunde
  (11 medium, 7 low), Ubuntu 26.04 trug an derselben Stelle 4. Diese Gruppe ist durch kein
  Basisimage lösbar; sie war in #1459 bereits als Sonderfall benannt.
- **Die Abhängigkeit wandert zu Google.** Distroless-Images kommen nicht aus dem Ubuntu-Ökosystem;
  ihre Aktualität hängt an Googles Rebuild-Takt statt an Canonicals Paketarchiv. Renovate verfolgt
  den Tag wie jede andere Docker-Referenz.
