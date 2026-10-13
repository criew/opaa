---
name: Feature Request
about: Ein neues Feature oder eine Verbesserung vorschlagen (eine Schicht/ein Baustein pro Issue)
title: 'feat(scope): '
labels: ['enhancement']
assignees: ''
---

<!--
Titel: Conventional-Commit-Stil, z. B. `feat(workspace): workspace CRUD API`.
Labels: Bereich hinzufügen (backend/frontend/setup/ci), Domäne (auth/workspace/security/...),
und Größe (size:S = Konfigurations-/Migrationsebene, size:M = ein Feature-Baustein,
size:L = querschnittlich). Issues werden auf Deutsch verfasst; nur der Conventional-Commit-Typ
und -Scope im Titel bleiben englisch.
-->

## Zusammenfassung

Kurze Beschreibung des Features.

## Motivation

Warum wird dieses Feature benötigt? Link zur Vision, zum Epic oder zur Feature-Spezifikation in `docs/features/`.

## Umfang

Was genau enthalten ist. Für APIs: Endpunkt für Endpunkt, einschließlich Authentifizierungsregeln und Fehlerbehandlung.

## Außerhalb des Umfangs

Explizite Grenzen — was dieses Issue bewusst nicht abdeckt.

## Abnahmekriterien

<!-- Jedes Kriterium einzeln testbar. „Dokumentation aktualisiert" für
     benutzerseitige oder architektonische Änderungen einschließen. -->

- [ ] ...
- [ ] Dokumentation aktualisiert (falls zutreffend)

## Abhängigkeiten

- #N (blockierende Issues, falls vorhanden)

## Teil von Epic

#N — Epic-Titel (Phase X), oder „Keines"

## Feature-Flag

<!--
Größere, in der Oberfläche sichtbare Features werden hinter einem installationsweiten
Feature-Flag gebaut, damit eine Installation sie abschalten kann (z. B. für Demos; Epic #2586).
Flags sind immer per Vorgabe an; Ausschalten ignoriert vorhandene Daten, löscht aber nichts.
`<name>` in kebab-case. Ist ein Flag vorgesehen, Label `feature-flag` setzen.
-->

`opaa.features.<name>.enabled` — was bei abgeschaltetem Flag verschwindet (Oberfläche, Endpunkte, Hintergrundläufe), oder „Keines"

## Technische Hinweise (optional)

Implementierungshinweise, Design-Kompromisse, relevante ADRs. Bei Frontend-Issues einen
`## UI-Referenz`-Abschnitt mit einem Mockup oder einer ASCII-Skizze hinzufügen.
