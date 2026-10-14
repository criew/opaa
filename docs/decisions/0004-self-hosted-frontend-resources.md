# ADR-0004: Selbst gehostete Frontend-Ressourcen

## Status

Akzeptiert · zuletzt geändert 2026-10-14

## Kontext

Das Frontend braucht Schriften und Icons. Diese können von externen CDNs geladen oder als npm-Pakete
gebündelt werden. Behördliche und andere Enterprise-Umgebungen beschränken ausgehenden Netzzugang;
externe CDNs übertragen Nutzerdaten (IP-Adresse, Referrer) an Dritte und schaffen eine
Laufzeitabhängigkeit von fremder Infrastruktur.

## Entscheidung

### 1. Alle Assets werden gebündelt

Schriften, Icons und statische Assets kommen als npm-Pakete und werden mit der Anwendung gebündelt.
Zur Laufzeit wird kein externer Link geladen: Schriften über `@fontsource/*` (importiert in
`frontend/src/main.tsx`), Icons über `@mui/icons-material`, keine `<link>`-Tags zu externen Diensten
in `frontend/index.html`. Die Content-Security-Policy in `frontend/nginx.conf` setzt `font-src 'self'`
und `default-src 'self'`, sodass ein versehentlicher Fremdlink im Browser scheitert statt still zu
laden.

## Konsequenzen

Einfacher: keine Nutzerdaten an CDNs; die Anwendung funktioniert in Air-Gap-Umgebungen und offline
nach dem ersten Laden.

Schwieriger: etwas größeres Bundle (Tree-Shaking für Icons, Subsetting für Schriften mildern das);
Schrift- und Icon-Updates sind npm-Updates statt automatisch.

## Verworfene Alternativen

- Google Fonts, cdnjs und andere CDNs — Datenabfluss und Netzabhängigkeit.

## Offen

Nichts.

## Änderungsverlauf

| Datum | Issue/PR | Änderung (eine Zeile) | Entscheidungen |
|---|---|---|---|
| 2026-02-19 | — | Erstfassung | 1 |
| 2026-10-14 | #2697 | Neufassung nach ADR-Vorlage | — |
