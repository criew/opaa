# Branding

Produktname, Claim, Hinweis zur KI-Nutzung, Akzentfarbe, Farbschema-Vorgabe und drei Bilder
gehören der Installation, nicht dem Programm. Gepflegt werden sie unter **Administration →
Branding**, von einem Systemverwalter; gespeichert wird in der Datenbank, nicht im Dateisystem.
Jede Änderung ist sofort für alle wirksam — ohne Neustart, ohne neues Image — und steht im
Nachweisprotokoll. Ein leer gelassenes Feld bedeutet nicht „leer", sondern „der OPAA-Standard gilt
wieder", und zwar je Feld: Wer nur ein Logo hinterlegt, behält den Produktnamen „OPAA". Welche Anmeldewege die Anmeldeseite zeigt und in welcher
Reihenfolge, steht unter [Authentifizierung](authentifizierung.md#anmeldewege-im-betriebsmodus-oidc).

## Hinweis zur KI-Nutzung

Das Feld **Hinweis zur KI-Nutzung** enthält den Text, mit dem das Haus seinen Nutzenden sagt, dass
Antworten von einer KI erzeugt werden (Art. 50 KI-Verordnung). Es ist **ein** Text für beide Orte,
an denen er gebraucht wird: als Hinweis im Chat vor der ersten Frage und als Vermerk, den der
Kopierknopf einer Antwort anfügt. Beide Anzeigen sind noch nicht gebaut (#2826, #3012); bis dahin
wird der Text gepflegt und über die Schnittstelle bereitgestellt, aber nicht angezeigt.

Ohne eigenen Text gilt der Standard: „Antworten werden von einer KI erzeugt und können Fehler
enthalten. Prüfen Sie wichtige Angaben anhand der Belege." Das Haus kann ihn in eigenen Worten
ersetzen, bis zu 500 Zeichen, ohne Zeilenumbruch (die Oberfläche macht aus einem Umbruch ein
Leerzeichen).

Der Hinweis lässt sich **ändern, aber nicht abschalten**. Ein leeres Feld oder eines ohne sichtbares
Zeichen (nur Leerzeichen, geschützte oder unsichtbare Zeichen) wird nicht als „kein Hinweis" gespeichert, sondern stellt den Standardtext wieder
her; ebenso die Schaltfläche **Hinweis auf Standard zurücksetzen**. Wird der Standardtext
unverändert gespeichert, bleibt der Hinweis „Standard" und folgt einer späteren Änderung des
Standards. Der Text ist wie die anderen Felder ohne Anmeldung lesbar und steht im
Nachweisprotokoll, wenn er sich ändert.

## Die drei Bilder

| Feld | Wo es erscheint | Grenzen |
|---|---|---|
| **Logo** | Seitenleiste und Kopf der Anwendung | PNG/JPEG, ≤ 512 KiB, ≤ 2000 × 2000 Bildpunkte |
| **Logo der Anmeldeseite** | Anmeldeseite, deutlich größer als in der Seitenleiste | PNG/JPEG, ≤ 512 KiB, ≤ 2000 × 2000 Bildpunkte |
| **Hintergrundbild der Anmeldeseite** | hinter der Markenfläche der Anmeldeseite | PNG/JPEG, ≤ 2 MiB, ≤ 4000 × 4000 Bildpunkte |

Drei Punkte dazu, die regelmäßig Rückfragen auslösen:

- **SVG wird nicht angenommen.** Eine SVG-Datei ist ein Dokument und kann Skripte enthalten; die
  Anmeldeseite ist zugleich die einzige Seite, die ein nicht angemeldeter Besucher erreicht. Statt
  eine SVG-Datei zu säubern — wobei ein einziges übersehenes Konstrukt genügt —, nimmt OPAA nur
  Rasterformate an. Eine Wortmarke als PNG in doppelter Darstellungsgröße sieht auf jedem Bildschirm
  gut aus und kostet wenige Kilobyte.
- **Entscheidend sind die Bytes, nicht der Dateiname.** Was als `logo.png` hochgeladen wird, aber
  keines ist, wird abgelehnt; ausgeliefert wird später genau der Typ, den der Dienst selbst erkannt
  hat.
- **Über dem Hintergrundbild liegt ein fester dunkler Schleier.** Welches Bild eine Installation
  hochlädt, weiß OPAA vorher nicht — der Schleier sorgt dafür, dass Produktname und Claim darüber
  auch über einem rein weißen Bild lesbar bleiben (mindestens 4,5:1 Kontrast). Das Bild wirkt damit
  als Atmosphäre, nicht als Motiv in voller Leuchtkraft. Wer ein ruhiges, eher dunkles Motiv wählt,
  bekommt das bessere Ergebnis.

Ohne hinterlegtes Hintergrundbild bleibt die Anmeldeseite bei ihrer bisherigen Fläche. Ohne eigenes
Anmeldelogo greift sie auf das Logo der Anwendung zurück, und ohne beides auf die OPAA-Marke.

## Über die API

Lesen darf jeder, auch ohne Sitzung — die Anmeldeseite braucht das Branding, bevor es eine Sitzung
gibt. Sichtbar wird dabei genau das, was ohnehin auf der Anmeldeseite steht, dazu der Hinweis zur
KI-Nutzung, der ohnehin für alle bestimmt ist.

```bash
# Der wirksame Stand, ohne Anmeldung lesbar
curl http://localhost:8081/api/v1/branding

# Die Bilder selbst (404, solange keines hinterlegt ist)
curl http://localhost:8081/api/v1/branding/logo
curl http://localhost:8081/api/v1/branding/login-logo
curl http://localhost:8081/api/v1/branding/login-background
```

Das Feld `aiNotice` der Antwort enthält den wirksamen Hinweistext, auch wenn keiner hinterlegt ist
(dann den Standard).

Schreiben ist Systemverwaltern vorbehalten: `PUT /api/v1/system/branding` für die Textfelder,
`PUT`/`DELETE` auf `/api/v1/system/branding/logo`, `/login-logo` und `/login-background` für die
Bilder.
