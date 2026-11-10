# Agenten einschalten

**Ziel:** Agenten stehen in dieser Installation zur Verfügung, mit den Werkzeugen, die Sie zulassen.
{: .kopf }

**Wer:** Systemverwaltung, „Administration → Agenten“
{: .kopf }

Ein Agent arbeitet eine Frage in mehreren Schritten ab: Er sucht, liest nach und antwortet mit
Fundstellen. Ab Werk sind Agenten ausgeschaltet.

1. „Agenten in dieser Installation anbieten“ einschalten.
2. Unter „Werkzeuge“ wählen, welche ein Lauf überhaupt nutzen darf. „Rechnen“ und „Vorlage
   ausfüllen“ sind ab Werk aus, weil sie eine abgeschottete Umgebung des Betriebs brauchen.
3. „Speichern“.

Die Änderung wirkt sofort auf allen Instanzen, ohne Neustart, und steht mit altem und neuem Wert im
[Nachweisprotokoll](nachweisprotokoll-lesen.md). Beim ersten Einschalten legt OPAA den Agenten
„Recherche“ an: Eigentümer ist, wer einschaltet, sichtbar ist er für alle Konten. Ein späteres
Ein- und Ausschalten legt ihn nicht erneut an.

Ausgeschaltet sind Agenten, ihr Anlegerecht und ihre Seiten nirgends zu sehen und über die
Schnittstelle nicht erreichbar. Angelegte Agenten bleiben gespeichert und sind nach dem
Wiedereinschalten unverändert da.

## Wer Agenten anlegt

Das Anlegerecht „Agenten anlegen“ hat ab Werk niemand außer der Systemverwaltung. Vergeben Sie es
unter [Anlegerechte vergeben](anlegerechte.md) an die Personen oder Gruppen, die Agenten für
andere einrichten sollen.

## Grenzen

Jeder Lauf hat Grenzen für Werkzeugaufrufe, Suchen, Laufzeit und Lesemenge. Ein Agent kann sie
unter „Ablauf → Erweitert“ senken; ein Lauf nimmt immer den kleineren Wert aus Agent und
Installation.

**Gut zu wissen:**

- Ein Agent liest nie mehr als die aufrufende Person; eine Freigabe am Agenten öffnet kein Wissen.
- Wie ein Agent angelegt und eingerichtet wird: [Agent anlegen](../benutzer/agent-anlegen.md).
