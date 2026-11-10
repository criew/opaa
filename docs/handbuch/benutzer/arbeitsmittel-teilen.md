# Bibliothek teilen und für KI-Werkzeuge freigeben

**Ziel:** Andere Personen, Gruppen oder KI-Werkzeuge nutzen Ihre Bibliothek.
{: .kopf }

**Wer:** *Verwalter* oder *Eigentümer* der Bibliothek, übergeben nur der *Eigentümer*
{: .kopf }

1. Die Detailseite öffnen, Reiter „Freigaben“, „Freigeben“.
2. Person, Gruppe oder „Alle Konten“ suchen und eine Rolle wählen.
3. „Freigeben“. Widerrufen geht über „Widerrufen“ im Menü „⋯“ der Zeile.

| Rolle | Darf zusätzlich |
|---|---|
| Leser | lesen, durchsuchen, nutzen |
| Bearbeiter | Dokumente hochladen und löschen, Läufe anstoßen, Metadaten setzen, Prompts schreiben |
| Verwalter | Name, Rechte, Quellverbindung, Freigabe für externe KI-Werkzeuge (nur Wissensbibliothek) |
| Eigentümer | löschen und das Eigentum übergeben |

„Alle Konten“ erhalten höchstens *Bearbeiter*.

## Eigentum übergeben

Unter „Eigentümer“ „Übergeben“ wählen und Person oder Gruppe suchen. Das wirkt sofort, und Ihre
eigene Rolle endet, wenn Sie keine eigene Freigabe haben. Eine Gruppe als Eigentümerin übersteht den
Weggang einzelner Personen.

## Für externe KI-Werkzeuge freigeben

Nur Wissensbibliotheken: Wer sie lesen darf, kann sie danach in ein [Zugangstoken](ki-werkzeug-einrichten.md#token-anlegen) aufnehmen und
aus Werkzeugen wie OpenCode durchsuchen. Fundstellen verlassen damit OPAA und werden im fremden
Werkzeug mit dessen Modell weiterverarbeitet. Das geht erst, wenn die Systemverwaltung externe
KI-Werkzeuge eingeschaltet hat, und braucht die Rolle *Verwalter*.

1. Im Reiter „Freigaben“, Abschnitt „Externer Zugang“, unter „Freigabe bis“ ein Datum wählen.
2. „Für externe KI-Werkzeuge nutzbar“ einschalten.

Die Freigabe erlischt am gewählten Datum; vorher kommt eine Erinnerung per Mail, sofern der
Mailversand eingerichtet ist. Schalter aus nimmt sie sofort für alle Tokens zurück.

## Dienstkonto aufnehmen

Ein Fachverfahren wie ein Antragsportal sucht über ein
[Dienstkonto](../administration/externe-ki-werkzeuge.md#dienstkonten) der Systemverwaltung. Die
Freigabe für externe KI-Werkzeuge zählt dafür nicht: Jedes Dienstkonto braucht Ihre eigene,
befristete Zustimmung.

1. Im Reiter „Freigaben“ in der Zeile „Dienstkonten“ „Verwalten“ wählen.
2. Oben das Dienstkonto wählen, „Zugriff bis“ setzen und „Aufnehmen“.
3. Frist ändern oder entfernen geht über das Menü „⋯“ der Zeile darunter.

Vor Ablauf kommt eine Erinnerung an die verantwortliche Person des Dienstkontos und die
Systemverwaltung. Nach dem Entfernen findet das Verfahren beim nächsten Aufruf nichts mehr.

**Gut zu wissen:**

- Jede Freigabe, Zustimmung für ein Dienstkonto und Übergabe steht im Nachweisprotokoll.
- Ein KI-Werkzeug sieht nie mehr als die Person, der das Token gehört.
- Eine private Bibliothek lässt sich nicht teilen.
