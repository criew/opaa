# Lokale Konten verwalten

> **Wer:** Systemverwaltung, „Administration → Benutzer“.

Lokale Konten führt OPAA selbst. Sie werden gebraucht, solange kein Identitätsanbieter angebunden
ist, und immer für das Notanker-Konto der Systemverwaltung. Konten eines Identitätsanbieters stehen
in derselben Liste, ihren Lebenszyklus führt aber der Anbieter; hier lässt sich nur ihre
[Systemrolle](systemrollen.md) ändern.

**Läuft ein Identitätsanbieter, die lokale Verwaltung abschalten**, sobald er steht (Reiter
„Einstellungen“, Karte „Lokale Anmeldung“). Jedes lokale Konto ist ein Zugang, den kein Austritt
im Haus automatisch entzieht. Abgeschaltet melden sich nur noch lokale Systemverwalter an, über
`/login/system`.

## Konto anlegen

1. Reiter „Konten“, „Konto anlegen“.
2. E-Mail-Adresse, Anzeigename und Rolle eintragen.
3. **Anlagegrund** eintragen, etwa „Vertretung Meldewesen bis Jahresende“. Die Person liest ihn in
   ihren eigenen Einstellungen; nichts zu Gesundheit, Leistung oder Beschäftigungsverhältnis.
4. **Ablaufdatum** prüfen; es ist vorbelegt. Ohne Datum verlangt das Formular „Kein Ablaufdatum“.
5. Weg wählen: „Einladung per E-Mail“ (die Person setzt ihr Passwort selbst) oder ein
   Anfangspasswort, das OPAA einmal anzeigt und beim ersten Anmelden wechseln lässt.

Ohne Mailversand zeigt OPAA den Einladungslink **einmal** an; Sie geben ihn auf anderem Weg weiter.
Ein Link gilt einmal, ein neuer macht den alten ungültig.

## Sperren statt löschen

| Handlung | Wirkung |
|---|---|
| „Sperren“ | beendet alle Sitzungen und Zugangstokens sofort; die Person erhält eine Mail, auf Wunsch mit einem Satz von Ihnen |
| „Entsperren“ | hebt jede Sperre auf: durch die Verwaltung, nach Fehlversuchen, nach Inaktivität |
| „Rücksetz-Link per E-Mail“ oder „Passwort erzeugen“ | neues Passwort; beendet ebenfalls alle Sitzungen |
| Ablaufdatum erreicht | das Konto kann sich nicht anmelden; ein neues Datum gibt es frei |
| „Löschen“ | nur für ein Konto, auf das nichts mehr verweist: kein Chat, keine Bibliothek, keine Freigabe, kein Nachweiseintrag. Praktisch nur für Tippfehler und Testkonten |

**Ausscheiden heißt sperren.** Wer je Mitglied einer Gruppe war, hinterlässt einen Nachweis, der
das Löschen blockiert, solange er liegt. Was mit Bibliotheken und Spaces der Person geschieht,
regelt [Ausscheiden und Nachfolge](ausscheiden.md).

**Chats gesperrter Konten** löscht OPAA nach einer Frist ab der Sperre durch die Verwaltung, in
allen Spaces. Das Datum steht im Dialog „Bearbeiten“ unter „Chats nach der Sperre“. Entsperren
beendet die Frist. Muss etwas aufgehoben werden, etwa auf behördliche Anordnung, beantragt eine
Person der Systemverwaltung „Anhaltung beantragen“ mit Zweck und Begründung, und erst eine
**zweite** Person gibt frei. Gelöschte Chats kommen über die Oberfläche nicht zurück. Frist und
Schalter stehen unter [Datenschutz](datenschutz.md).

## Liste prüfen

Die Filter „ohne Ablaufdatum“, „länger nicht genutzt“ und „offene Einladungen“ zeigen die Konten,
an denen eine Entscheidung fällig ist. Ein Hinweis neben „Konto anlegen“ nennt die Zahlen, einmal
im Quartal kommt eine Wiedervorlage per Mail. Die Liste zeigt bewusst kein Datum der letzten
Nutzung und hat keinen Export.

## Das Notanker-Konto

Das Konto aus dem Erststart ist der Weg zurück in eine ausgesperrte Installation. Es lässt sich
weder sperren noch löschen, befristen, herabstufen oder übergeben, und die Verwaltung kann ihm kein
Passwort setzen. Sein Passwort ändert es selbst; ohne Anmeldung hilft nur die
[Notfallprozedur](../betrieb/authentifizierung.md#notfallprozedur-wieder-hereinkommen). Auch das
**letzte anmeldefähige Systemverwalterkonto** lässt sich nicht sperren, befristen, herabstufen
oder löschen.

**Gut zu wissen:**

- Die Anmeldeseite nennt nie den Grund einer Ablehnung; die Person erfährt ihn per Mail oder beim
  nächsten Aufruf.
- Die Sperre nach Fehlversuchen schickt keine Mail und endet von selbst; „Passwort vergessen“
  bleibt wirksam.
- Selbstregistrierung, Passwortregeln und Fristen: [Anmeldung einstellen](anmeldung-einstellen.md).
