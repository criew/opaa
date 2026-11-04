# Gruppen verwalten

> **Wer:** Systemverwaltung, „Administration → Gruppen“.

Rechte an Gruppen statt an Personen zu geben hält sie aktuell: Wer morgen in „Referat 50“ kommt,
liest ab dann, wer geht, verliert es mit der Mitgliedschaft. Gruppen verschachteln nicht; eine
Abteilung, die im Verzeichnis nur Untergruppen hat, ist in OPAA leer.

## Woher Gruppen kommen

| Herkunft | Gepflegt von | Mitglieder aktuell |
|---|---|---|
| intern | ihren Verantwortlichen in OPAA | sofort |
| Identitätsanbieter, Gruppen-Claim | dem Anbieter | erst bei der nächsten Anmeldung der Person |
| Identitätsanbieter, Verzeichnisabgleich | dem Verzeichnis | nach dem nächsten Abgleich, auch ohne Anmeldung |

Für ein Haus mit gepflegtem Verzeichnis ist der **Verzeichnisabgleich** der empfohlene Weg: Nur er
sperrt Konten ausgeschiedener Personen von selbst und hält eine Plausibilitätsschwelle gegen
Massenentzug. Die Systemverwaltung richtet ihn unter „Administration → Verzeichnisabgleich“ ein
([Verzeichnisabgleich](../betreiben/authentifizierung.md#verzeichnisabgleich-je-anbieter)). Im
Claim-Betrieb ist eine Umbenennung beim Anbieter ein stiller Gruppenwechsel: Freigaben an der alten
Gruppe erreichen niemanden mehr.

## In der Gruppenverwaltung

Die Tabelle zeigt alle Gruppen, mit Herkunft, Zustand und **Verwendung** („2 Bibliotheken ·
1 Space“). Das Zeilenmenü bietet:

- **Bearbeiten**, auch der Schalter „Geschützte Gruppe“: für Personalvertretung,
  Schwerbehindertenvertretung, Gleichstellung und Personalvorgänge. Eine geschützte Gruppe findet
  niemand über die Suche, und fremde Listen zeigen nur „Geschützte Gruppe“.
- **Mitglieder**. Jeder Abruf der Mitgliederliste durch die Systemverwaltung steht im
  Nachweisprotokoll; der Dialog sagt das.
- **Rechte übertragen** auf eine andere Gruppe ([Ausscheiden und Nachfolge](ausscheiden.md)).
- **Deaktivieren** und **Reaktivieren**, nur bei internen Gruppen: Die Rechte der Gruppe ruhen,
  gespeichert bleibt alles.
- **Löschen**, nur bei internen Gruppen ohne Rechte, Anlegerechte, Eigentum und Space-Mitgliedschaft.

Eine Anbietergruppe lässt sich hier nur schützen; Name und Mitglieder pflegt ihre Quelle.

**Externe Anbieter** sind gekennzeichnet; vor jedem Recht an eine ihrer Gruppen fragt OPAA nach.

## Wer eine Gruppe sieht

| Wer | Name | Größe | Mitglieder |
|---|---|---|---|
| Verantwortliche | ja | ja | ja |
| Systemverwaltung | ja | ja | ja, mit Nachweiseintrag |
| wer ein Recht vergibt | ja, interne Gruppen erst nach Freigabe zur Verwendung | unter der Mindestgruppengröße nur „kleine Gruppe“ | solange die Gruppe an seinem Objekt ein Recht hat; nie bei geschützten |
| Mitglied | eigene Gruppen | ja | nein |

**Gut zu wissen:**

- Das Anlegerecht „Interne Gruppen anlegen“ hat ab Werk niemand; vergeben Sie es an eine benannte
  Gruppe, etwa „Referatsleitungen“ ([Anlegerechte vergeben](anlegerechte.md)).
- Eine Gruppe ohne Verantwortliche erscheint unter „Ohne Zuständigkeit“; benennen Sie dort neue.
- Lokale Konten erreichen Gruppen nur über interne Gruppen.
