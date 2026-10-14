# ADR-0029: Schlankes Backend-Laufzeitimage — jlink-Laufzeit auf Distroless statt vollem Ubuntu-Basisimage

## Status

Akzeptiert · zuletzt geändert 2026-09-10

## Kontext

Das Laufzeitimage baute auf `eclipse-temurin:21-jre` (Ubuntu mit vollem Paketsatz). Die Triage aus
#1450 zeigte, dass alle Image-Alerts des nächtlichen Trivy-Scans aus diesem Paketsatz stammten,
keiner aus einer Anwendungsabhängigkeit und keiner aus dem JDK; nach dem `dist-upgrade` aus #1452
blieben Pakete ohne Upstream-Fix (`libexpat1`, `rust-coreutils`, `p11-kit`, `tar`, `wget`,
`passwd`, `libsystemd0`, `libsqlite3-0`, glibc), die das Image nur trägt, weil ein
Vollbetriebssystem sie mitbringt. Sie sind nicht erreichbar, weil das Image genau einen Java-Prozess
startet — eine Betriebsannahme, die als Dismiss-Begründung dauerhaft zu pflegen wäre. Der Maintainer
hat entschieden (#1459), sie zu einer Eigenschaft des Images zu machen: Was nicht enthalten ist,
muss nicht begründet werden. Geprüfte Zielbilder: Alpine (musl, eigenes OpenSSL, wieder `libexpat`),
`distroless/java21` (bringt fontconfig für `java.desktop` mit, schlechter als der Ist-Stand),
Canonicals chiselled JRE (ohne `java.desktop`, das PDFBox und POI brauchen; „0 Alerts" wäre
Messblindheit ohne dpkg-Datenbank).

## Entscheidung

### 1. Eine mit `jlink` erzeugte Java-Laufzeit

Aus demselben `eclipse-temurin:21-jdk`, aus dem gebaut wird (`backend/Dockerfile`, Stage `jlink`):
`java.se` (einschließlich `java.desktop`) plus die Module, die der JDK reflektiv als Dienstanbieter
lädt und die aus keiner Abhängigkeitsanalyse folgen — `jdk.crypto.ec`/`jdk.crypto.cryptoki` (TLS),
`jdk.charsets`, `jdk.localedata`, `jdk.naming.dns`, `jdk.unsupported` (`sun.misc.Unsafe` für
Hibernate und Spring), `jdk.zipfs`, `jdk.dynalink`, `jdk.net`, `jdk.security.auth`,
`jdk.management` sowie als Diagnosefläche `jdk.jcmd` (zieht `jdk.attach` mit), `jdk.jfr`,
`jdk.management.agent`, `jdk.jdwp.agent`. `jdk.jcmd` ist nicht optional: Ohne Shell gäbe es sonst
keinen Weg zu einem Thread- oder Heap-Dump; JFR allein lässt sich nur beim Start scharfschalten.

### 2. `gcr.io/distroless/base-nossl-debian13` als Basis

glibc und sonst nichts; `-nossl`, weil die JVM ihre eigene TLS-Implementierung mitbringt und
`libssl` nie linkt. Zertifikate, Zeitzonen und Locale-Daten kommen aus der JDK-Laufzeit. Die glibc-Locale
`C.utf8` wird aus `debian:13-slim` zurückkopiert und `LANG` darauf gesetzt — ohne kompilierte
Locale meldet glibc `ANSI_X3.4-1968`, das die JVM als `sun.jnu.encoding` übernimmt, und Dateinamen mit
Umlauten wären unlesbar; `-Duser.language=en -Duser.country=US` im Entrypoint hält die
Standard-`Locale` auf dem bisherigen Wert. Kein Paketmanager, kein `dist-upgrade`: Die Aktualität
von glibc hängt daran, dass Buildx den `FROM`-Verweis bei jedem Lauf neu auflöst.

## Konsequenzen

- **Einfacher:** Kein Dismiss, keine gepflegte Begründung, keine Betriebsannahme; die
  Angriffsfläche sinkt real (keine Shell, kein `wget`, `tar`, `cp`, kein Paketmanager); das Image
  ist deutlich kleiner.
- **Schwieriger:** Kein Shell-Zugang — Diagnose über Logs, `/actuator`, `jcmd`, `docker kill -s
  QUIT` und `docker cp` ([Betrieb](../handbuch/betrieb.md#backend-laufzeitimage)). Ein fehlendes
  jlink-Modul fällt erst zur Laufzeit auf (`ClassNotFoundException`, `ServiceConfigurationError`);
  Absicherung sind E2E- und `demo-smoke`-Suite, die bewusst nicht auf `pull_request` laufen — wer
  den Modulsatz ändert, stößt beide auf seinem Branch von Hand an. Eine neue Abhängigkeit mit
  nativem oder reflektivem JDK-Bedarf muss den Modulsatz mit prüfen.
- **Restrisiken:** glibc bleibt und trägt dauerhaft ungefixte Befunde, durch kein Basisimage lösbar.
  Die Aktualität der Basis hängt an Googles Rebuild-Takt; Renovate verfolgt den Tag.

## Verworfene Alternativen

- `eclipse-temurin:21-jre-alpine` — musl statt glibc, eigenes OpenSSL, trotzdem `libexpat`; größtes Laufzeitrisiko für die geringste Reduktion.
- `gcr.io/distroless/java21-debian12` — bringt fontconfig und Bildbibliotheken für `java.desktop` mit, mehr Befunde als der Ist-Stand.
- `ubuntu/jre:21-24.04_stable` — kein `java.desktop`; Trivy sieht ohne dpkg-Datenbank nichts.
- `base-nossl-debian12` — misst identisch; Debian 13 ist das aktuelle Stable mit längerem Support.

## Offen

—

## Änderungsverlauf

| Datum | Issue/PR | Änderung (eine Zeile) | Entscheidungen |
|---|---|---|---|
