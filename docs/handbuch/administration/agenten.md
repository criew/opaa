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

## Agent oder Vorlage sperren

Jeder Agent und jede Vorlage nennt eine verantwortliche Stelle: eine Gruppe, Organisationseinheit
oder Funktion, nie eine Person allein. Wer ein Objekt anlegt, muss sie angeben. Ein einzelnes
Objekt nehmen Sie so aus dem Betrieb, ohne es zu löschen:

1. Auf der Seite des Agenten oder der Vorlage den Reiter „Freigaben“ öffnen.
2. In der Zeile „Sperre“ auf „Sperren“, einen Grund eintragen und „Sperren“ bestätigen.

Ein gesperrter Agent fehlt in der Auswahl im Chat, und jeder Aufruf wird mit einer Meldung
abgewiesen. Eine gesperrte Vorlage bietet kein Agent mehr an; er antwortet stattdessen im Chat und
sagt das. Die Sperre gilt auf allen Instanzen ab dem nächsten Aufruf. „Entsperren“ in derselben
Zeile hebt sie auf.

## Wenn die verantwortliche Stelle fehlt

Wird die Gruppe der Stelle gelöscht oder steht der Eigentümer auf „Nachfolge offen“, fehlt die
Stelle. Die Seite des Objekts zeigt dann, wann es gesperrt wird. Wird bis dahin keine neue Stelle
benannt, sperrt OPAA das Objekt, löscht es aber nicht. Die Frist stellen Sie unter
„Administration → Ohne Zuständigkeit“ im Abschnitt „Sperre ohne verantwortliche Stelle“ ein, ab
Werk 30 Tage. Nach dem Benennen einer neuen Stelle heben Sie die Sperre mit „Entsperren“
auf; fehlt die Stelle dann noch, sperrt OPAA beim nächsten Lauf wieder.

## Grenzen

Jeder Lauf hat Grenzen für Werkzeugaufrufe, Suchen, Laufzeit und Lesemenge. Ein Agent kann sie
unter „Ablauf → Erweitert“ senken; ein Lauf nimmt immer den kleineren Wert aus Agent und
Installation.

**Gut zu wissen:**

- Ein Agent liest nie mehr als die aufrufende Person; eine Freigabe am Agenten öffnet kein Wissen.
- Ändern der Stelle, Sperren und Entsperren stehen im
  [Nachweisprotokoll](nachweisprotokoll-lesen.md), mit dem Grund der Sperre; das Nutzen nicht.
- Wie ein Agent angelegt und eingerichtet wird: [Agent anlegen](../benutzer/agent-anlegen.md).
