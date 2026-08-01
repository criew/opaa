# 4. Selbst gehostete Frontend-Ressourcen

Datum: 2026-02-19

## Status

Akzeptiert

## Kontext

Das OPAA-Frontend benötigt Schriften (Inter), Icons (Material Icons) und möglicherweise andere
statische Assets. Diese können von externen CDNs (z. B. Google Fonts, cdnjs) geladen oder als
npm-Pakete gebündelt und selbst gehostet werden.

Enterprise-Umgebungen beschränken oft den ausgehenden Netzwerkzugang. Externe CDN-Abhängigkeiten
werfen auch Datenschutzbedenken auf (z. B. Google Fonts überträgt Benutzer-IP-Adressen) und schaffen
eine Laufzeitabhängigkeit von Drittanbieter-Infrastruktur.

## Entscheidung

Alle Schriften, Icons und statische Assets werden als npm-Pakete installiert und mit der
Anwendung gebündelt. Zur Laufzeit werden keine externen CDN-Links verwendet.

Konkret:
- **Schriften:** `@fontsource/inter` (npm-Paket, selbst gehostet)
- **Icons:** `@mui/icons-material` (npm-Paket, gebündelt)
- **Keine `<link>`-Tags** zu externen Diensten in `index.html`

## Konsequenzen

- **Datenschutz:** Keine Benutzerdaten (IP-Adressen, Referrer-Header) werden an externe CDNs gesendet
- **Offline:** Die Anwendung funktioniert nach dem initialen Laden vollständig offline
- **Enterprise:** Kompatibel mit restriktiven Netzwerkrichtlinien und Air-Gap-Umgebungen
- **Bundle-Größe:** Etwas größeres initiales Bundle (Schriften enthalten), gemildert durch Tree-Shaking
  für Icons und Subsetting für Schriften
- **Updates:** Schrift-/Icon-Updates erfordern ein npm-Paket-Update statt automatisch zu sein
