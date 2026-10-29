# E-Mail-Versand (SMTP)

OPAA versendet E-Mails über einen SMTP-Server, den ein Systemverwalter in der Anwendung einträgt.
Ohne diese Einstellung versendet OPAA nichts — und meldet das als Ergebnis, statt einen Vorgang
scheitern zu lassen: Wer ein Konto anlegt, bekommt den Einladungslink dann zur Weitergabe angezeigt.
Wozu Mails gebraucht werden, beschreibt die [Benutzerverwaltung](../konten-und-rechte/benutzerverwaltung.md); der
Mailserver ist einer der optionalen Bausteine der [Betriebsübersicht](README.md). Für Erprobung und
Entwicklung bringt der Compose-Stapel einen Mailserver zum Abfangen mit
([Mailpit](docker-compose.md#mailpit-im-lokalen-stapel)).

Zwei Dinge werden getrennt gehalten:

- **Der Mailserver ist eine Verwaltungseinstellung.** Server, Port, Verschlüsselung, Zugangsdaten
  und Absender stehen in der Oberfläche unter „Administration → E-Mail → SMTP-Zugang" und wirken
  ohne Neustart. Das Passwort liegt verschlüsselt in der Datenbank, erscheint in keiner Antwort und
  in keinem Protokoll. Wer den Mailserver schon bei der Einrichtung kennt, trägt ihn im optionalen
  Schritt „E-Mail-Versand“ der [Installation](installation.md#schritte) ein; der Abschluss übernimmt
  ihn mit denselben Regeln und schaltet den Versand ein. Übersprungen bleibt der Versand aus.
- **Die öffentliche Adresse ist eine Bereitstellungsentscheidung.** `OPAA_PUBLIC_BASE_URL` steht in
  der Umgebung, nicht in der Oberfläche. Jeder Link in einer E-Mail wird daraus gebildet; die
  Adresse aus dem `Host`-Kopf einer Anfrage wird nie verwendet. Ohne Wert baut OPAA keinen Link —
  die Verwaltung der Konten prüft das vor jedem Ablauf, der auf einen Link angewiesen ist, und
  bietet ihn erst gar nicht an.

## Einrichtung

1. `OPAA_PUBLIC_BASE_URL` setzen — vollständig mit Schema, ohne abschließenden Schrägstrich, genau
   die Adresse, unter der Nutzende OPAA im Browser öffnen.
2. Als Systemverwalter unter „Administration → E-Mail → SMTP-Zugang" eintragen: Server, Port,
   Verschlüsselung, bei Bedarf
   Zugangsdaten, Absenderadresse und Absendername. Der Versand lässt sich erst einschalten, wenn
   Server, Port und Absenderadresse gesetzt sind. Fehlt `OPAA_PUBLIC_BASE_URL`, weist die Seite
   darauf hin, dass Links in E-Mails unbrauchbar blieben.
3. Testnachricht auslösen. Sie geht an die eigene Adresse des aufrufenden Verwalters. Das Ergebnis
   steht sofort in der Oberfläche: zugestellt, übersprungen oder fehlgeschlagen mit Grund.

| Feld | Bedeutung |
|---|---|
| Versand aktiv | Hauptschalter. Aus bedeutet: OPAA versendet nichts und meldet jeden Versand als übersprungen |
| Server, Port | Adresse des Mailservers. In der Verwaltung findet keine Adressprüfung statt — ein interner Mailserver ist der Regelfall, und die Einstellung ist Systemverwaltern vorbehalten. Der Schritt E-Mail-Versand der Installation, den nur das Installations-Token schützt, prüft dagegen und lässt einen internen Mailserver nur über `OPAA_INSTALLATION_MAIL_TARGET_ALLOWLIST` zu ([Adressprüfung der Mailserver](installation.md#schritte)) |
| Verschlüsselung | `STARTTLS` verlangt die Aufwertung der Verbindung und bricht ab, wenn der Server sie nicht anbietet; `SSL` verschlüsselt ab dem ersten Byte; `NONE` überträgt unverschlüsselt und ist nur für einen Mailserver im selben vertrauenswürdigen Netz vertretbar. Bei `STARTTLS` und `SSL` muss der eingetragene Server im Zertifikat des Mailservers stehen; eine IP-Adresse oder ein interner Alias, den das Zertifikat nicht nennt, lässt den Versand scheitern |
| Benutzername, Passwort | Leerer Benutzername bedeutet: Verbindung ohne Anmeldung. Das Passwort wird verschlüsselt gespeichert und in Antworten als `***` angezeigt; wer `***` zurückschickt, lässt es unverändert, ein leeres Feld löscht es. Ändern sich Server, Port, Benutzername oder Verschlüsselung, geht das gespeicherte Passwort nicht mit: Es ist neu einzugeben oder zu löschen, sonst lehnt OPAA das Speichern ab |
| Absenderadresse, Absendername | Stehen als Absender in jeder Nachricht |

Das SMTP-Passwort schützt `OPAA_SETTINGS_ENCRYPTION_KEY`, derselbe Schlüssel wie die Zugangsschlüssel
der Chat-Modelle ([Schlüssel und Geheimnisse](netz-und-sicherheit.md#schlüssel-und-geheimnisse)).
Ohne gültigen Schlüssel schlägt das Speichern eines Passworts fehl; der Start bricht nicht ab.

## Zustellung über die Warteschlange

OPAA verschickt auf zwei Wegen:

- **Sofort, mit Ergebnis an der Aktion:** die Testnachricht sowie Einladung, Zurücksetzen durch die
  Verwaltung und Übergabelink. Schlägt der Versand fehl, zeigt die Oberfläche den Link zur
  Weitergabe.
- **Über die Warteschlange:** alle übrigen Mails — „Passwort vergessen", Bestätigung der
  Selbstregistrierung, Sperre und Entsperrung, abgeschlossene Übergabe, Benutzung des
  Notanker-Kontos, Erinnerungen und Ablaufhinweise. Die Mail wird zusammen mit dem Vorgang
  gespeichert, den sie ankündigt, und erst nach dessen Abschluss verschickt; scheitert der Vorgang,
  entfällt auch die Mail.

Die Warteschlange liegt in der Datenbank, Empfänger und Inhalt verschlüsselt mit
`OPAA_SETTINGS_ENCRYPTION_KEY`. Ohne gültigen Schlüssel geht keine Mail in die Warteschlange; das
Log nennt den Grund. Eine wartende Mail übersteht einen Neustart des Backends, und
bei mehreren Backend-Instanzen stellt jede zu, eine Mail aber immer nur eine zur selben Zeit.

- **Wiederholung:** Lehnt der Mailserver ab oder ist er nicht erreichbar, versucht OPAA es erneut,
  zuerst nach einer Minute, dann mit jeweils doppeltem Abstand bis höchstens einer Stunde, insgesamt
  achtmal (rund zwei Stunden). Danach wird die Mail verworfen.
- **Versand ausgeschaltet:** Solange der Versand aus ist, kommt keine neue Mail in die Warteschlange,
  und wartende Mails bleiben liegen, bis er wieder eingeschaltet ist.
- **Doppelte Zustellung:** Bricht ein Backend genau zwischen der Annahme durch den Mailserver und dem
  Vermerk ab, kann eine Mail ein zweites Mal ankommen. Beide tragen dieselbe `Message-ID`.

## Vorlagen

Vierzehn Vorlagen werden mit deutschem Text ausgeliefert: Einladung, Passwort vergessen,
administratives Zurücksetzen, Bestätigung der Selbstregistrierung, Sperrung, Entsperrung,
Ablaufhinweis, Übergabe angestoßen, Übergabe abgeschlossen, Benutzung des Notanker-Kontos,
Erinnerung an die Kontenprüfung, Ablaufhinweis einer Freigabe für externe KI-Werkzeuge und
eines Zugangstokens und die Testnachricht.

Eine Vorlage lässt sich überschreiben (Betreff, Textteil, wahlweise ein eigener HTML-Teil) und
jederzeit auf den ausgelieferten Stand zurücksetzen. Es lassen sich weder Vorlagen anlegen noch
löschen — der Satz ist fest, damit keine Installation mit einer Vorlage ohne Inhalt enden kann.

- **Platzhalter sind je Vorlage festgelegt.** Jede Vorlage nennt die Platzhalter, die sie kennt
  (etwa Produktname, Anrede, Link, Ablaufhinweis). Ein Platzhalter, den die Vorlage nicht kennt,
  wird beim Speichern abgelehnt — mit Angabe des Feldes und der erlaubten Namen. Fehlt beim Versand
  ein Wert für einen verwendeten Platzhalter, wird nicht versendet, statt eine Nachricht mit einer
  Lücke an der Stelle des Links zu verschicken.
- **Der Produktname kommt aus dem Branding.** Er muss nirgends eingetragen werden.
- **Der HTML-Teil trägt den Rahmen der Installation**: Produktname und Akzentfarbe aus dem Branding.
  Ein Logo wird nicht eingebettet. Wer einen eigenen HTML-Teil speichert, ersetzt den Rahmen
  vollständig.
- **Vorschau und Testnachricht je Vorlage**: Die Vorschau unter „Administration → E-Mail →
  Vorlagen" zeigt das Ergebnis mit Beispielwerten, ohne etwas zu versenden — Textfassung und
  HTML-Fassung nebeneinander, die HTML-Fassung in einem abgeschotteten Rahmen ohne Skripte. Die
  Testnachricht schickt **die gespeicherte Fassung** an die eigene Adresse — ein Entwurf im Editor
  muss dafür erst gespeichert werden; die Schaltfläche wartet, solange ungespeicherte Änderungen
  offen sind. „Mit Standard vergleichen" stellt die eigene Fassung dem ausgelieferten Text
  gegenüber.

Jede Änderung, jedes Zurücksetzen, jede Änderung der Einstellungen und jede Testnachricht steht im
Nachweisprotokoll; eine Übernahme aus der Installation unter dem Systemprozess `installation`.

## Fehlerbilder

Der Zustand des Versands steht an drei Stellen: als „letzter erfolgreicher Versand" und „letzter
Fehler" auf der Einstellungsseite — auch für Mails aus der Warteschlange —, als Ergebnis unmittelbar
an der auslösenden Aktion (nur beim sofortigen Versand), und als
eigene Gruppe `GET /actuator/health/mail` — dort nur der Zustand, ohne Einzelheiten: `UP` nach einem
erfolgreichen Versand, `DOWN`, wenn der letzte Versuch fehlschlug, `UNKNOWN` ohne eingerichteten
Versand oder vor dem ersten Versuch. Bewusst **nicht** im Gesamtstatus unter `/actuator/health` —
sonst nähme eine einzelne abgelehnte Nachricht die Instanz aus dem Load Balancing, während Chat und
Suche weiterlaufen; dieselbe Aufteilung wie beim Objektspeicher der Originalablage. Die Ursache
steht im Log, dort ohne die Empfängeradresse.

| Beobachtung | Ursache | Abhilfe |
|---|---|---|
| Ergebnis „übersprungen" | Versand ist ausgeschaltet oder kein Server eingetragen | Einstellungen vervollständigen und einschalten |
| Einladung zeigt nur den Link an, statt zu versenden | Kein Versand möglich — Versand aus, Versand fehlgeschlagen oder `OPAA_PUBLIC_BASE_URL` nicht gesetzt | Link weitergeben; parallel Einstellung und Umgebungsvariable prüfen |
| „Connection refused" | Falscher Server oder Port, oder der Mailserver ist aus dem Backend-Container nicht erreichbar | Im Compose-Stapel den Servicenamen statt `localhost` verwenden; Erreichbarkeit aus dem Container prüfen |
| Zeitüberschreitung | Mailserver antwortet nicht innerhalb der Timeouts | Erreichbarkeit prüfen; notfalls `OPAA_MAIL_CONNECT_TIMEOUT`, `OPAA_MAIL_READ_TIMEOUT`, `OPAA_MAIL_WRITE_TIMEOUT` anpassen |
| Testnachricht scheitert mit „Can't verify identity of server: …“, im Assistenten „Die verschlüsselte Verbindung zum Mailserver ist fehlgeschlagen …“ | Das Zertifikat des Mailservers nennt den eingetragenen Server nicht, etwa weil eine IP-Adresse oder ein interner Alias eingetragen ist | Den Namen eintragen, auf den das Zertifikat lautet, oder das Zertifikat um den eingetragenen Namen ergänzen |
| Meldung zu STARTTLS | Der Server bietet STARTTLS nicht an; OPAA bricht ab, statt unverschlüsselt fortzufahren | Beim Mailserver TLS einrichten, oder — nur im vertrauenswürdigen Netz — Verschlüsselung `NONE` wählen |
| „535" oder Authentifizierungsfehler | Benutzername oder Passwort falsch | Zugangsdaten neu eintragen; das Feld `***` lässt das gespeicherte Passwort unverändert, zum Ersetzen den neuen Wert eintragen |
| Speichern abgelehnt, Hinweis am Passwortfeld | Server, Port, Benutzername oder Verschlüsselung wurden geändert, während ein Passwort hinterlegt ist | Passwort für die neue Verbindung eintragen oder löschen und erneut speichern |
| Passwort lässt sich nicht speichern | Verschlüsselungsschlüssel fehlt oder ist ungültig | `OPAA_SETTINGS_ENCRYPTION_KEY` setzen |
| Testnachricht kommt an, „Passwort vergessen" oder Erinnerungen nicht | Ohne Verschlüsselungsschlüssel geht keine Mail in die Warteschlange; das Protokoll meldet „Mailversand abgebrochen" mit dem Schlüssel als Grund | `OPAA_SETTINGS_ENCRYPTION_KEY` setzen |
| Mail aus der Warteschlange kommt verspätet an | Der Mailserver war beim ersten Versuch nicht erreichbar oder hat abgelehnt; die Wiederholung läuft | „Letzter Fehler" auf der Einstellungsseite prüfen; nach acht Versuchen meldet das Protokoll die Mail als verworfen (Vorlage, gekürzte Empfängeradresse, Grund) |
| Nachrichten kommen an, Links führen ins Leere | `OPAA_PUBLIC_BASE_URL` zeigt auf eine Adresse, die Nutzende nicht erreichen | Variable auf die tatsächlich genutzte Adresse setzen und Backend neu starten |
