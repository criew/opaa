---
name: Epic
about: Tracking-Issue für eine Gruppe zusammengehöriger Issues (Muster aus Epic #107)
title: 'feat: '
labels: ['enhancement', 'epic']
assignees: ''
---

Einleitungsabsatz: Was dieses Epic liefert und warum jetzt.

### Hintergrund

Links zu den Feature-Spezifikationen in `docs/features/` und etwaigen Diskussionsdokumenten in `docs/discussions/`.

### Phasen

<!--
Die Tickets selbst werden als Sub-Issues eingetragen (Seitenleiste „Create sub-issue"
bzw. bestehendes Issue verknüpfen), nicht als Checkliste im Body. GitHub führt damit
Status und Fortschritt selbst; der Tagesreport liest dieselbe Beziehung.

Hier steht nur, was die Sub-Issue-Liste nicht ausdrücken kann: warum in dieser
Reihenfolge geschnitten wurde. Die Reihenfolge der Sub-Issues lässt sich passend
sortieren.
-->

**Phase 1 — <name>.** Was diese Phase liefert und warum sie zuerst kommt.

**Phase 2 — <name>.** Worauf sie aufbaut.

### Abhängigkeiten

```
#A ──> #B ──> #D
        └──> #C
```

### Abnahmekriterien (Epic-Ebene)

- [ ] ...

### Außerhalb des Umfangs (separate Epics)

- ...

### Referenzen

- `docs/features/<spec>.md`
