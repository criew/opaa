# Nachvollziehen, wer warum Zugriff hat

> **Entwurf** — gegen den Code geprüft, vom Maintainer noch nicht abgenommen.

> **Wer darf das:** den eigenen Weg sieht jede Person mit Zugriff. Den Weg anderer in einem Space
> sehen dessen *Administratoren* und sein Eigentümer. Die Mitglieder einer Gruppe sieht, wer am
> Objekt Freigaben verwaltet (ab *Verwalter*; im Space *Administrator* oder Eigentümer), nur bei einer
> zur Verwendung freigegebenen und nie bei einer geschützten Gruppe.

## Der eigene Weg

Auf der Detailseite einer Bibliothek (Reiter „Freigaben“) oder einer Prompt-Bibliothek steht die
Zeile **„Herleitung“**; „Anzeigen“ öffnet sie, „Schließen“ klappt sie zu. Im Space öffnet
**„Warum sehe ich diesen Space?“** dasselbe.

```
Sie sind Administrator in diesem Space.
  Direkt aufgenommen: Kurator
  Über die Gruppe Meldewesen: Administrator
Es gilt die höhere Rolle.
```

Der erste Satz nennt die wirksame Rolle, darunter steht je Weg eine Zeile: „direkt freigegeben“,
„über die Gruppe …“, „für alle Konten freigegeben“, „direkt aufgenommen“ oder „als Eigentümer“.
Den Zeitpunkt eines Wegs zeigt ein Tooltip.

## Der Weg einer anderen Person in einem Space

1. „Mitglieder“ des Space öffnen.
2. Im Zeilenmenü der Person **„Warum hat … Zugriff?“**.

Führt ein Weg über eine **geschützte Gruppe**, nennt die Antwort nur die Rolle und dass ein Weg
nicht benannt wird. Die eigene Herleitung bleibt immer vollständig.

## Wer in einer Gruppe ist

An einer Gruppe in den Freigaben oder in der Mitgliederliste eines Space: **„Mitglieder anzeigen“**
bzw. im Zeilenmenü „Mitglieder der Gruppe anzeigen“. Bei einer geschützten Gruppe steht dort, wer
verantwortlich ist oder dass die Systemverwaltung Auskunft gibt. Ruft die Systemverwaltung die Liste
ab, steht das mit der Zahl der Mitglieder im Nachweisprotokoll.

**Gut zu wissen:**

- Die Systemverwaltung erscheint an einer Bibliothek mit eigenem Satz: Sie verwaltet, liest aber
  nicht ([Rollen am Objekt](arbeitsmittel-teilen.md)).
- Wer an einem vergangenen Tag Zugriff hatte, beantwortet die
  [Stichtagsauskunft](../verwalten/stichtagsauskunft.md) der Revision.
- Warum eine Antwort ein Dokument nicht nennt, klärt die Systemverwaltung mit der
  [Suchdiagnose](../verwalten/suche-und-indexierung.md#ein-fehlendes-dokument-finden).
