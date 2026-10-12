# Zugänge

Eine Wissensbibliothek mit angebundener Quelle muss wissen, **wo** die Dokumente liegen und **wie**
sie sich dort anmeldet. Das kann jede Wissensbibliothek selbst mitbringen — dann trägt, wer sie
anlegt, Serveradresse und Zugangsdaten ein. Oder die Systemverwaltung legt den technischen Zugang
einmal fest, und wer eine Wissensbibliothek anlegt, wählt ihn nur noch aus. Das ist ein **Zugang**.

> **Beispiel.** Das Haus betreibt zwei Confluence-Instanzen, das Intranet und das Projektwiki. Die
> Systemverwaltung legt dafür zwei Zugänge an: „Confluence Intranet" und „Confluence Projektwiki",
> jeweils mit Adresse und Edition. Wer eine Wissensbibliothek anlegt, wählt im Assistenten
> „Confluence Intranet", trägt das eigene Token ein und sucht die Spaces aus. Wo das Intranet liegt
> und welche Edition es hat, muss niemand wissen.

## 1. Wofür es Zugänge gibt

- **Einfacher anlegen.** Wer eine Wissensbibliothek anlegt, muss Serveradresse, Anmeldeart und
  technische Einstellungen nicht kennen.
- **Steuern, was angebunden wird.** Die Systemverwaltung legt fest, wer welchen Zugang nutzen darf,
  und kann einen Zugang jederzeit sperren oder alle seine Verbindungen trennen.
- **Anbindungen, die eine App-Registrierung brauchen.** Quellarten wie SharePoint verlangen, dass
  OPAA beim Anbieter als Anwendung eingetragen ist. Diese Registrierung hinterlegt die
  Systemverwaltung einmal am Zugang; ohne Zugang lassen sich solche Quellen nicht anbinden.
- **Persönliche Konten.** Damit eine Person ihr eigenes Konto verbinden und daraus eine
  [private Bibliothek](private-bibliotheken.md) speisen kann, braucht es einen Zugang, der Personen
  zulässt ([Benutzerverwaltung](benutzerverwaltung.md), Abschnitt 12).

## 2. Wann ein Zugang nötig ist

Ob eine Quellart Zugänge kennt, legt ihr Konnektor fest. Es gibt drei Fälle:

- **nicht möglich** — die Quelle liest nichts aus der Ferne, etwa beim Upload,
- **möglich** — eine Wissensbibliothek kann einen Zugang wählen oder die Quelle selbst eintragen,
- **Pflicht** — die Quelle lässt sich nur über einen Zugang anbinden, weil die App-Registrierung am
  Zugang liegt.

Was für eine Quellart gilt, steht in ihrem Kapitel; die Übersicht der
[Konnektoren](konnektoren/README.md) fasst es zusammen. Wo ein Zugang möglich ist, kann die
Systemverwaltung ihn zur Pflicht machen: **„Nur über Zugänge"** (Abschnitt 7).

## 3. Was ein Zugang festlegt

**Tabelle 3.1: Angaben eines Zugangs**

| Angabe | Bedeutung |
|---|---|
| Name und Quellart | etwa „Confluence Intranet" |
| Server-Adresse | wo die Quelle liegt, sofern die Quellart eine braucht; die Adresse jeder Wissensbibliothek auf dem Zugang liegt darunter |
| Anmeldeart | wie sich OPAA bei der Quelle anmeldet, je nach Quellart etwa ohne Anmeldung, mit Benutzername und Passwort oder Token, mit einer App-Registrierung oder über eine Zustimmung beim Anbieter (OAuth) |
| Für wen er da ist | für geteilte Wissensbibliotheken, für persönliche Konten, aus denen private Bibliotheken entstehen, oder für beides (in der Oberfläche „Besitzart") |
| Vorgaben | Einstellungen, die der Zugang für alle Wissensbibliotheken darauf festlegt, damit sie niemand selbst eintragen muss — etwa die Confluence-Edition. Sie erscheinen dort nur lesbar |
| Wer ihn nutzen darf | alle Konten oder bestimmte Gruppen und Personen. Ein neuer Zugang ist zunächst nur für die Systemverwaltung freigegeben, damit sie ihn prüfen kann, bevor andere ihn wählen |

## 4. Einen Zugang einrichten

Zugänge pflegt die Systemverwaltung unter **Administration → Anbindungen → Zugänge**.

1. **„Neuer Zugang"** fragt zuerst die Quellart ab; das Formular zeigt danach nur, was diese
   Quellart braucht.
2. Name, Server-Adresse, Anmeldeart und Vorgaben eintragen; bei einer App-Registrierung
   Client-ID und Client-Secret samt Ablaufdatum, bei Google Drive die Schlüsseldatei des
   Dienstkontos.
3. Bei einer Zustimmung beim Anbieter (OAuth): beim Anbieter eine App anlegen und dort als
   **Rücksprungadresse** `{öffentliche Adresse}/connections/callback` eintragen; das Formular nennt
   die genaue Adresse. Die Scopes so wählen, dass der Anbieter eine dauerhafte Zustimmung erteilt
   (etwa `offline_access`).
4. Festlegen, **wer den Zugang nutzen darf**. Ein neuer Zugang ist zunächst für niemanden außer der
   Systemverwaltung freigegeben.
5. Bei Dienstkonto-Schlüssel und App-Registrierung prüft **„Anmeldung testen"**, ob der Anbieter
   die Angaben annimmt.

Die Übersicht nennt je Zugang, wofür er dient, wer ihn nutzen darf, wie viele Verbindungen auf ihm
laufen und ob etwas zu tun ist: **„Anmeldung abgelehnt"**, ein abgelaufenes oder bald ablaufendes
Client-Secret, viele abgelaufene verbundene Konten oder „Noch für niemanden freigegeben". Ein Klick
öffnet das Seitenpanel des Zugangs mit seinen Angaben, seiner Nutzung und den Eingriffen. 14 Tage
vor Ablauf eines Client-Secrets erhält die Systemverwaltung zusätzlich eine Benachrichtigung.

Zahlen über verbundene Konten von Personen zeigt OPAA nur gerundet: unterhalb der
Mindestgruppengröße heißt es „weniger als N", damit sich nicht auf einzelne Personen schließen lässt.

## 5. Eine Bibliothek über einen Zugang anlegen

Im Assistenten wählt die Person im Schritt „Quelle" zuerst den **Zugang** — oder „Eigene Adresse",
wenn das für die Quellart erlaubt ist. Angeboten werden nur Zugänge, die sie nutzen darf und die nicht
gesperrt sind. Danach ist die Adresse vorbelegt, Vorgaben stehen nur lesbar da, und je nach
Anmeldeart trägt sie eigene Zugangsdaten ein oder keine.

**Quelle verbinden.** Meldet sich ein Zugang über eine Zustimmung beim Anbieter an, etwa bei
[Dropbox](konnektoren/dropbox.md), verbindet die Bibliothek ihre
Quelle selbst: Eine verwaltende Person bestätigt, dass sie ein **Dienstkonto** verbindet, kein
persönliches, meldet sich beim Anbieter damit an und stimmt zu. Die Zustimmung gehört danach der
Bibliothek, nicht der Person; sie bleibt auch bestehen, wenn die Person ausscheidet. Für die
Verbindung ist eine verantwortliche Person oder Gruppe benannt; sie erhält die Benachrichtigungen,
wenn die Zustimmung abläuft oder endet, und kann neu verbinden oder trennen.

**An der fertigen Bibliothek** steht der Zugang im Reiter „Quelle". Wer die Bibliothek verwaltet,
kann ihn ändern:

**Tabelle 5.1: Zugang einer Bibliothek ändern**

| Aktion | Wirkung |
|---|---|
| „Zugang zuordnen" | eine Bibliothek mit eigener Adresse auf einen Zugang umstellen; ihre Adresse muss unter dessen Server-Adresse liegen |
| „Zugang wechseln" | auf einen anderen Zugang derselben Quellart umstellen; ändert sich der Server, sind die Zugangsdaten neu einzutragen |
| „Zugang lösen" | die Bibliothek läuft mit Adresse, Zugangsdaten und den bisherigen Vorgaben als eigenen Einstellungen weiter; nicht möglich, wenn die Quellart nur über Zugänge nutzbar ist |

Vor dem Zuordnen und Wechseln prüft der Dialog die Verbindung; scheitert die Prüfung, lässt sich
trotzdem zuordnen, und die Bibliothek läuft, sobald die Verbindung steht.

## 6. Einen Zugang ändern

Was eine Änderung am Zugang für die Bibliotheken darauf bedeutet, zeigt die **Vorschau**, bevor
etwas gespeichert wird: wie viele Bibliotheken betroffen sind, ob eine davon die Änderung nicht
verträgt und ob Verbindungen neu hergestellt werden müssen. Lehnt der Konnektor die Änderung für
eine Bibliothek ab, wird nichts gespeichert.

**Tabelle 6.1: Was eine Änderung am Zugang auslöst**

| Änderung | Folge für die Bibliotheken auf dem Zugang |
|---|---|
| Name | keine |
| Vorgaben | Sie laufen weiter. Fällt eine Vorgabe weg, behält jede Bibliothek den bisherigen Wert als eigene Einstellung |
| neues Client-Secret oder neuer Schlüssel desselben Kontos | keine; eine abgelehnte Anmeldung ist damit aufgehoben |
| Server-Adresse, Anmeldeart, Client-ID, Mandant, Scopes oder ein Endpunkt | Alle Zugangsdaten und Zustimmungen auf dem Zugang werden verworfen; die Bibliotheken müssen neu verbunden werden. OPAA fragt vorher nach und nennt die Zahl |
| **„Alle Verbindungen trennen"** (Notabschaltung) | Alle Zugangsdaten und Zustimmungen werden sofort verworfen; Zustimmungen zieht OPAA auch beim Anbieter zurück. Ein Schlüssel oder Client-Secret beim Anbieter selbst bleibt gültig, bis die Verwaltung ihn dort widerruft |
| Zugang löschen | Die Bibliotheken bleiben mit ihrem Bestand stehen und tragen den Hinweis „Zugang entfernt", bis sie einem anderen Zugang zugeordnet werden |

Private Bibliotheken halten keine Änderung auf: Verträgt eine die Änderung nicht, wird sie vom
Zugang gelöst, ruht und ihre Besitzerin wird benachrichtigt.

## 7. Sperren und „Nur über Zugänge"

Unter **Administration → Anbindungen** kann die Systemverwaltung zweierlei:

- **Sperren** — einen einzelnen Zugang oder eine ganze Quellart. Neue Bibliotheken darauf sind nicht
  mehr möglich, Läufe starten nicht mehr, und der Bestand bleibt mit dem Hinweis „Gesperrt – Inhalt
  wird nicht mehr aktualisiert" durchsuchbar. Belege aus einer solchen Bibliothek tragen im Chat
  „Stand vom …". Nach dem Entsperren läuft alles ohne Neueinrichtung weiter.
- **„Nur über Zugänge"** — für eine Quellart, bei der ein Zugang möglich ist. Danach lassen sich
  neue Bibliotheken nur noch über einen Zugang anlegen. Für die Bibliotheken mit eigener Adresse,
  die es schon gibt, wählt die Systemverwaltung: **weiterlaufen lassen** (ihre Adresse ist dann
  eingefroren) oder **sperren**, bis ihre Verwaltenden sie einem Zugang zuordnen. Einschalten geht
  erst, wenn es einen passenden Zugang gibt.

Sperren, Entsperren und das Umschalten stehen im Revisionsprotokoll.

## 8. Wenn eine Bibliothek nicht mehr aktualisiert wird

Kann ein Lauf seine Quelle nicht erreichen, endet er vor dem ersten Dokument mit einer Meldung, die
die zuständige Stelle nennt. Der Bestand bleibt durchsuchbar, und im Reiter „Quelle" bietet der
Hinweis den Verwaltenden die Aktion an, die ihn aufhebt.

**Tabelle 8.1: Meldungen und wer sie beheben kann**

| Meldung | Ursache | Zuständig |
|---|---|---|
| „Zugang entfernt" | Der Zugang wurde gelöscht | Verwaltende der Bibliothek: anderen Zugang zuordnen |
| „Verbindung getrennt" | Die Zugangsdaten fehlen, etwa nach einer Adressänderung oder der Notabschaltung | Verwaltende der Bibliothek: neu eintragen; meldet sich der Zugang selbst an, die Systemverwaltung |
| „Abgelaufen: Der Anbieter hat die Anmeldung des Zugangs … abgelehnt" | Client-Secret oder Schlüssel des Zugangs wurde abgewiesen | Systemverwaltung: neu eintragen oder „Anmeldung testen" |
| „Nicht verbunden" bzw. „Abgelaufen: Die Verbindung der Quelle …" | Die Quelle wurde noch nicht verbunden, getrennt, oder der Anbieter nimmt die Zustimmung nicht mehr an | Verwaltende der Bibliothek: Quelle neu verbinden |
| „Die Adresse der Bibliothek liegt nicht unter …" | Die Adresse passt nicht mehr zum Zugang | Verwaltende der Bibliothek |
| „Gesperrt – Inhalt wird nicht mehr aktualisiert" | Zugang oder Quellart ist gesperrt | Systemverwaltung |
| „… ist nur noch über Zugänge nutzbar, und diese Bibliothek hat eine eigene Adresse" | „Nur über Zugänge" ist eingeschaltet, und der Bestand wurde gesperrt | Verwaltende der Bibliothek: einem Zugang zuordnen |

## 9. Sicherheit und Nachweis

- Client-Secrets, Schlüssel, Zugangsdaten und Token liegen verschlüsselt. Keine Antwort, kein
  Protokoll und kein Revisionseintrag enthält sie; angezeigt wird nur, ob eines hinterlegt ist.
- Ein hinterlegtes Client-Secret geht nie an eine neue Adresse: Wer einen Token-Endpunkt ändert,
  muss ein neues Secret eintragen.
- Endpunkte, an die Geheimnisse gehen, müssen `https://` verwenden.
- Anlegen, Ändern, Löschen, Sperren, die Notabschaltung und jeder Anmeldetest stehen im
  Revisionsprotokoll. Das Verbindungsprotokoll hält fest, wann Konten und Quellen verbunden und
  getrennt wurden ([Berechtigungen](berechtigungen.md), Abschnitt 12).

## 10. Konfiguration

**Tabelle 10.1: Konfigurationsschlüssel der Zugänge**

| Schlüssel | Standard | Wirkung |
|---|---|---|
| `opaa.connection.expired-connections.warning-threshold` | 10 | ab wie vielen abgelaufenen verbundenen Konten auf einem Zugang die Übersicht „Viele Konten abgelaufen" zeigt; mindestens 1 |

Die Aufbewahrungsfrist des Verbindungsprotokolls stellt die Systemverwaltung unter
**Administration → Anbindungen → Einstellungen** ein.

## Tabellenverzeichnis

- [Tabelle 3.1: Angaben eines Zugangs](#3-was-ein-zugang-festlegt)
- [Tabelle 5.1: Zugang einer Bibliothek ändern](#5-eine-bibliothek-über-einen-zugang-anlegen)
- [Tabelle 6.1: Was eine Änderung am Zugang auslöst](#6-einen-zugang-ändern)
- [Tabelle 8.1: Meldungen und wer sie beheben kann](#8-wenn-eine-bibliothek-nicht-mehr-aktualisiert-wird)
- [Tabelle 10.1: Konfigurationsschlüssel der Zugänge](#10-konfiguration)
