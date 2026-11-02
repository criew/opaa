# Ausscheiden und Nachfolge regeln

> **Entwurf** — gegen den Code geprüft, vom Maintainer noch nicht abgenommen.

> **Wer darf das:** die Systemverwaltung, unter „Administration → Ohne Zuständigkeit“ und
> „Administration → Gruppen“. Eigenes Eigentum und eigene Verantwortung gibt jede Person selbst ab,
> unter „Einstellungen → Meine Gruppen“.

Scheidet eine Person aus oder wird ein Referat aufgelöst, sollen Bibliotheken, Spaces und Gruppen
nicht verwaist liegen bleiben. Dafür gibt es die **Übertragung**: eine Handlung statt Arbeit Objekt
für Objekt.

## Wenn niemand mehr zuständig ist

Wird ein Konto gesperrt oder verliert eine Gruppe ihr letztes aktives Mitglied, steht, was daran
hängt, auf **„Nachfolge offen“**. Das Objekt bleibt nutzbar, nichts wird gelöscht. Eingefroren ist
nur die Reichweite: keine neuen Freigaben, keine neuen Space-Mitglieder, keine neue Freigabe für
externe KI-Werkzeuge. Einschränken geht weiter.

„Ohne Zuständigkeit“ hat drei Reiter:

| Reiter | Darin | Nächster Schritt |
|---|---|---|
| Inhalte ohne Verantwortliche | Arbeitsmittel, Spaces, interne Gruppen, deren Eigentümer nicht handeln kann | „Nachfolge bestimmen“, „Rechte übergeben“ oder „Zur Gruppenverwaltung“ |
| Gruppen ohne aktive Mitglieder | Gruppen mit Rechten, die niemanden erreichen | „Rechte übergeben“ |
| Leere Gruppen | interne Gruppen ohne Mitglieder und Rechte | in der Gruppenverwaltung löschen |

Ein Eintrag bleibt, bis wieder jemand zuständig ist; ab einem einstellbaren Alter heißt er „lange
offen“. Die Liste geht vom Objekt aus: „Was gehörte Frau Vogt?“ beantwortet sie nicht.

## Rechte übertragen

1. „Rechte übertragen“ in der Gruppenverwaltung, „Nachfolge bestimmen“ oder „Rechte übergeben“ in
   der Liste.
2. Ziel und Umfang wählen.
3. Die **Vorschau** lesen, etwa „12 Berechtigungen an 7 Objekten, Mitglied in 2 Spaces, Eigentum an
   3 Objekten“.
4. Bestätigen. Hat sich inzwischen etwas geändert oder ist die Vorschau älter als 30 Minuten,
   verlangt OPAA eine neue.

| Umfang | Gruppe → Gruppe | Gruppe → Person | Person → Person |
|---|---|---|---|
| Berechtigungen, Space-Mitgliedschaften, Anlegerechte | ja | — | — |
| Eigentum an Arbeitsmitteln | ja | ja | ja |
| Eigentum an Spaces, Verantwortung für Gruppen | — | — | ja |

Von einer Person gehen nur Eigentum und Verantwortung über; ihre übrigen Rechte enden mit dem Konto.
Ein Space geht nur an ein aktives Konto, das dabei Administrator wird; der persönliche Space bleibt
außen vor. Hat das Ziel schon eine Rolle, bleibt die stärkere. Höchstens 500 Zeilen je Übertragung;
darüber in Schritten.

**Gut zu wissen:**

- Für ein einzelnes Arbeitsmittel genügt „Übergeben“ im Reiter „Freigaben“
  ([Ein Arbeitsmittel teilen](../pflegen/arbeitsmittel-teilen.md#das-eigentum-übergeben)).
- Eine Übertragung läuft nie automatisch, auch nicht nach einer Reorganisation im Verzeichnis.
- Vorschau und Übertragung stehen im Nachweisprotokoll, auch eine Vorschau, die niemand ausführt.
