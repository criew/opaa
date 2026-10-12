# Zugänge


Ein **Zugang** ist ein Rahmen, den die Systemverwaltung für einen Konnektor vorgibt: Name,
Server-Adresse, Anmeldeart, bei Anmeldearten mit App-Registrierung Client-ID, Client-Secret,
Mandant, Ablaufdatum des Secrets und Scopes, dazu die Besitzart (Bibliothek, Person oder beides),
die Zertifikatsprüfung und Vorgaben für die Einstellungen des Konnektors. Wer eine Bibliothek auf
einem Zugang anlegt, wählt ihn aus und trägt Server, Registrierung und Vorgaben nicht selbst ein.

Die Systemverwaltung pflegt Zugänge unter **Administration → Anbindungen → Zugänge**. Oben steht,
was zu tun ist („Kein Handlungsbedarf“ oder die Zahl der Zugänge, die Aufmerksamkeit brauchen, und
der Bibliotheken mit ruhender Quelle). Darunter zeigt die Übersicht je Zugang eine Zeile: Name mit
Quellart und den Marken „Gesperrt“ bzw. „Quellart gesperrt“, den Server (nur den Rechnernamen; die
volle Adresse steht im Tooltip und im Panel), **Nutzbar für** (wer den Zugang wählen darf), die Zahl
der Verbindungen und den **Handlungsbedarf**. Als Handlungsbedarf gilt, in dieser Reihenfolge:
„Anmeldung abgelehnt“, „Secret abgelaufen am …“, „Secret läuft ab am …“, „Viele Konten
abgelaufen“ und „Noch für niemanden freigegeben“; eine Sperre ist ein gewollter Zustand und kein
Handlungsbedarf. In schmalen Fenstern stehen dieselben Angaben als Liste.

Ein Klick auf die Zeile oder auf **Details** bzw. **Beheben** öffnet das **Seitenpanel** des
Zugangs; die Adresse der Seite trägt dann `?profile=<id>`, ein solcher Link öffnet das Panel
direkt (so führt die Benachrichtigung „Client-Secret läuft ab“ zum Zugang). Das Panel nennt zuerst
den Handlungsbedarf mit der passenden Handlung, dann **„Wer darf diesen Zugang nutzen?“**, die
**Angaben** mit „Bearbeiten“, die **Nutzung** (Verbindungen und, bei einem Zugang, der Personen
zulässt, die verbundenen Konten) und die **Eingriffe**: „Anmeldung testen“ (nur bei
Client-Credentials und Dienstkonto-Schlüssel), „Sperren“ bzw. „Entsperren“, „Alle Verbindungen
trennen“ und „Zugang löschen“, je mit einem Satz zur Folge und der Rückfrage davor. Nach dem
Anlegen öffnet sich das Panel des neuen Zugangs mit „Noch für niemanden freigegeben“.

Solange keine Quellart Zugänge anbietet, steht über der Übersicht ein Hinweis, und „Neuer Zugang“
ist gesperrt. Sonst fragt „Neuer Zugang“ zuerst die Quellart im Auswahlfeld **Quellart** ab: je Eintrag Name und eine kurze
Beschreibung, unter dem Feld die Beschreibung der gewählten Quellart. Quellarten ohne Zugänge stehen
mit Grund in der Liste und lassen sich nicht wählen; ist nur eine Quellart möglich, ist sie
vorausgewählt. Das Formular richtet sich nach der Quellart:

- **Anmeldeart** bietet nur die Arten an, die die Quellart meldet; **Besitzart** nur die Besitzarten,
  die sie für die gewählte Anmeldeart zulässt.
- **Server-Adresse** verlangt eines der Schemata, die die Quellart meldet; der Hinweis unter dem
  Feld nennt sie. Hat die Quellart eine feste Adresse, entfällt das Feld, und das Formular nennt die
  Adresse.
- **Zertifikatsprüfung aussetzen** gilt für jede Bibliothek auf dem Zugang und ersetzt deren
  eigene Angabe. Wohin der Konnektor sie anwendet, bestimmt er wie bei einer Bibliothek mit eigener
  Adresse: Beim RSS-Feed etwa gilt sie nur für den Ursprung des Feeds. Es gibt sie nur bei einer
  Server-Adresse mit `http://` oder `https://`; einen Zugang mit `smb://`-Adresse und ausgesetzter
  Zertifikatsprüfung weist OPAA beim Speichern ab. Einen Proxy tragen weder Zugang noch Bibliothek:
  Alle Verbindungen gehen über den hausweiten
  [Ausgangsproxy](deployment.md#ausgangsproxy).
- **Vorgaben für jede Bibliothek** zeigt je Einstellung, die ein Zugang vorgeben darf, ein Feld:
  Text, Ja/Nein oder eine Auswahl, jeweils mit „Keine Vorgabe“. Meldet die Quellart keine solche
  Einstellung, fehlt der Abschnitt. Eine Vorgabe, die die Quellart nicht meldet, weist OPAA ab.
- **Endpunkte** (Autorisierungs-, Token- und Widerrufs-Endpunkt) fragt das Formular nur bei OAuth
  bzw. Client-Credentials ab und nur die, die die Quellart dem Zugang überlässt, etwa weil sie vom
  Realm eines Keycloak abhängen; dann sind sie Pflicht und beginnen mit `https://`, auch auf dem
  eigenen Rechner. Token- und Widerrufs-Endpunkt bekommen Refresh-Tokens und das Client-Secret, die
  bei `http://` jede Station auf dem Weg mitlesen könnte, auch ein Proxy; am
  Autorisierungs-Endpunkt meldet sich die Person an, und eine `http://`-Seite ließe sich unterwegs
  austauschen. Eine andere Adresse markiert das Formular, und OPAA weist sie beim Speichern ab. Endpunkte, die die Quellart selbst festlegt, fragt es nicht ab, und ein Wert dafür wird
  abgewiesen. OPAA schreibt sie beim Speichern fest und erkennt sie nie zur Laufzeit neu.
  Token- und Widerrufs-Endpunkt bekommen das Client-Secret. Ändert die Verwaltung einen davon an
  einem Zugang mit hinterlegtem Secret, verlangt das Formular ein neues Secret: Das hinterlegte geht
  nie an eine neue Adresse. Für einen öffentlichen Client lässt sich stattdessen „Ohne
  Client-Secret speichern“ wählen. Ohne beides weist OPAA die Änderung ab und ändert nichts. Der
  Autorisierungs-Endpunkt bekommt kein Secret; ändert sich nur er, bleibt das Secret. Die Rückfrage
  vor dem Speichern nennt bei jedem geänderten Endpunkt die alte und die neue Adresse.
- **Scopes** nennt unter dem Feld die Vorgabe der Quellart, die gilt, solange das Feld leer bleibt.
- **Dienstkonto-Schlüssel** lädt das Formular als JSON-Schlüsseldatei hoch; eine Client-ID fragt
  es dafür nicht ab, OPAA übernimmt sie aus `client_email` des Schlüssels. Gespeichert werden nur
  `client_email`, `private_key_id` und `private_key`, verschlüsselt.
- Eine **Vorgabe nur des Zugangs** (bei Google Drive das imitierte Konto) setzt keine Bibliothek
  auf dem Zugang selbst, auch wenn der Zugang sie leer lässt; ein Wert der Bibliothek wird
  abgewiesen.
- Liegen die Angaben der Quellart nicht vor (etwa weil sie nicht geladen werden konnten), lässt
  sich ein Zugang nicht speichern; das Formular sagt das, statt die Vorgaben zu verwerfen.

**Anmeldung des Zugangs (Client-Credentials, Dienstkonto-Schlüssel).** Bei diesen Anmeldearten
meldet sich der Zugang selbst an: OPAA holt mit Client-ID und Client-Secret bzw. mit dem
signierten Schlüssel ein Zugriffstoken beim Token-Endpunkt, den der Konnektor nennt, und gibt der
Bibliothek nur dieses Token. Der Abruf geht immer mit Zertifikatsprüfung; der Schalter „Zertifikatsprüfung aussetzen“ gilt nur für die Server-Adresse.
Das Token hält OPAA bis kurz vor seinem Ablauf. Lehnt der Anbieter die Registrierung ab (etwa ein
falsches Client-Secret oder ein widerrufener Schlüssel), zeigt die Übersicht am Zugang den
Handlungsbedarf „Anmeldung abgelehnt“, alle Bibliotheken darauf melden „Abgelaufen“ mit der Systemverwaltung als zuständig,
und kein Lauf fragt den Anbieter erneut. Ein neues Client-Secret bzw. ein neuer Schlüssel oder
**„Anmeldung testen“** im Panel des Zugangs hebt das auf; der Test meldet sich einmal an und nennt bei einem
Fehler den Grund. Jeder Test steht mit seinem Ausgang (gelungen oder abgelehnt) im
Revisionsprotokoll, ohne die Meldung des Anbieters.

**Anmeldung über OAuth (App-Registrierung, Rücksprungadresse).** Bei dieser Anmeldeart stimmt jede
Person beim Anbieter selbst zu; OPAA erhält dafür ein Refresh-Token und ein Zugriffstoken und legt
beide verschlüsselt ab. Dafür braucht der Zugang eine **App-Registrierung beim Anbieter**, die die
Systemverwaltung dort einmal anlegt:

1. Beim Anbieter eine App (einen vertraulichen Client) anlegen und als **Rücksprungadresse**
   (Redirect-URI) genau `{öffentliche Adresse}/connections/callback` eintragen. Das Formular nennt
   die Adresse, sobald OAuth gewählt ist, so wie OPAA sie dem Anbieter nennt; die öffentliche
   Adresse ist die der Installation ([Deployment](deployment.md), `OPAA_PUBLIC_BASE_URL`). Fehlt
   sie, nennt das Formular statt einer Adresse diesen Mangel, kein Konto lässt sich verbinden, und
   die Kontoseite sagt das ebenfalls.
2. Die Scopes so wählen, dass der Anbieter eine **dauerhafte Zustimmung** erteilt (etwa
   `offline_access`); ohne Refresh-Token verbindet OPAA nicht und nennt den Grund.
3. Client-ID und Client-Secret am Zugang eintragen, dazu die Endpunkte, falls das Formular sie
   abfragt.

Wer verbindet, bestätigt vorher, dass er zum Anbieter weitergeleitet wird; dort meldet er sich an
und stimmt zu, dann führt der Anbieter ihn über die Rücksprungadresse zurück
([Benutzerverwaltung](benutzerverwaltung.md), Abschnitt 12). Eine begonnene Zustimmung gilt zehn
Minuten, für genau eine Person und einen Abschluss, und je Person lassen sich nur wenige in kurzer
Zeit beginnen; darüber hinaus bittet die Kontoseite, es später erneut zu versuchen. Ändert die Systemverwaltung den Zugang währenddessen, scheitert der Abschluss mit der
Bitte, erneut zu verbinden. OPAA erneuert das Zugriffstoken selbst kurz vor seinem Ablauf; nimmt der
Anbieter die Zustimmung nicht mehr an, gilt die Verbindung als abgelaufen, und die Person erhält die
Benachrichtigung „Verbindung abgelaufen“. Nennt der Anbieter ein Ende der Zustimmung, warnt OPAA die
Person 14 Tage vorher einmal (Benachrichtigung „Verbindung läuft ab“). Eine neue Zustimmung oder
ein Ende, das die Erneuerung wieder über diese Frist hinausschiebt, warnt erneut; ein Ende, das
schon bei der Zustimmung oder bei jeder Erneuerung innerhalb der Frist liegt (ein kurzes gleitendes
Ende, das sich durch Nutzung verschiebt), warnt nicht. Trennen widerruft das Token beim Anbieter, wo
er das anbietet.

Läuft das Client-Secret bald ab, zeigt die Übersicht das als Handlungsbedarf. 14 Tage vor dem
Ablaufdatum des Secrets erhält zudem jede Systemverwaltung einmal die Benachrichtigung
„Client-Secret läuft ab“, die zum Panel des Zugangs führt; ein neues Secret oder ein neues Datum
warnt erneut. Bei einem Zugang, der Personen zulässt, zählen im Panel unter „Nutzung“ zwei
Angaben die **verbundenen Konten** von Personen auf dem Zugang
([Benutzerverwaltung](benutzerverwaltung.md), Abschnitt 12):

| Angabe | Zählt | Darstellung |
|---|---|---|
| **Verbundene Konten** | die Konten von Personen auf dem Zugang, verbundene und abgelaufene | genau ab der Mindestgruppengröße, darunter — auch bei null — nur „weniger als N“ |
| **Davon abgelaufen** | die abgelaufenen unter ihnen | genau nur, wenn abgelaufene und aktive Konten je mindestens N sind — „niemand aktiv“ gilt wie „wenige aktiv“ —; sonst unter N „weniger als N“, ab N „nicht ausgewiesen“; bei einer Gesamtzahl unter 2N−1 immer „nicht ausgewiesen“. „Viele Konten abgelaufen“ erscheint als Handlungsbedarf, sobald die ausgewiesenen Zahlen den Schwellenwert abgelaufener Konten zulassen; ist die Zahl nicht genau ausgewiesen, heißt die Warnung „Möglicherweise viele Konten abgelaufen“ |

Der Schwellenwert wird nur gegen das geprüft, was die Liste ohnehin zeigt: gegen die genaue Zahl
oder, wo sie nicht genau ausgewiesen ist, gegen die größte Zahl, die Gesamtzahl und Rundungsregel
noch zulassen. Bei „nicht ausgewiesen“ ist das die Gesamtzahl, bei „weniger als N“ die Zahl N−1. Die
Warnung verrät so nichts, was die Zahlen nicht schon sagen, und gleiche Zahlen tragen immer dieselbe
Warnung. Dafür warnt die Übersicht auch dort, wo tatsächlich weniger Konten abgelaufen sind. Ein Zugang,
der keine Personen zulässt, warnt nie.

| Schlüssel | Standard | Wirkung |
|---|---|---|
| `opaa.connection.expired-connections.warning-threshold` | 10 | sobald die ausgewiesenen Zahlen so viele abgelaufene verbundene Konten je Zugang zulassen, zeigt die Übersicht „Viele Konten abgelaufen“ bzw. „Möglicherweise viele Konten abgelaufen“; mindestens 1 |

Das Panel zeigt die Zahlen genau so, wie OPAA sie liefert, und rechnet nichts daraus: „weniger als N“
unterscheidet null nicht von einer kleinen Zahl, und keine Anzeige, kein Hinweistext und keine
Rückfrage nennt eine genauere Zahl oder die Personen. „Alle Verbindungen trennen“ und „Löschen“
nennen vor der Bestätigung die Zahl der betroffenen Verbindungen und, in derselben Rundung, der
verbundenen Konten; eine Änderung, die Geheimnisse verwirft, nennt vorher die Zahl der Betroffenen.
Im Reiter **Einstellungen** stellt die
Systemverwaltung im Abschnitt **„Verbindungsprotokoll“** dessen Aufbewahrungsfrist ein
([Berechtigungen](berechtigungen.md), Abschnitt 12). Solange die Angaben der Quellart beim Bearbeiten eines Zugangs noch geladen
werden, sagt das Formular das und lässt noch nicht speichern.

**Vorschau beim Bearbeiten.** Ändert eine Bearbeitung, was für die Bibliotheken auf dem Zugang gilt
(Server-Adresse, Anmeldeart, Registrierung, Besitzart, Zertifikatsprüfung oder Vorgaben), und
hat der Zugang Verbindungen, heißt der Knopf zuerst „Weiter“. Er zeigt, ohne etwas zu speichern, die
Auswirkungen:

- die Zahl der betroffenen Bibliotheken, bei einem Zugang, der Personen zulässt, auch die
  verbundenen Konten von Personen (in derselben gerundeten Form wie sonst, etwa „weniger als 5“),
- jede Bibliothek, deren Konnektor die Änderung ablehnt, mit der Art der Ablehnung (Verbindung oder
  Einstellungen), dem Grund und einem Verweis auf die Bibliothek,
- was das Speichern verwirft, so wie OPAA es beim Speichern ausführt: ob alle Zugangsdaten und Token
  des Zugangs verworfen werden und wie viele Verbindungen danach neu angemeldet werden müssen, von
  wie vielen Bibliotheken die gespeicherten Zugangsdaten neu einzutragen sind, ob die verbundenen
  Konten von Personen enden (gerundet wie oben), für wie viele Bibliotheken sich die Konfiguration
  ändert und für wie viele der Abgleichstand ganz verworfen wird; verwirft sie nichts davon, sagt
  die Vorschau das,
- bei einer Besitzart ohne Personen, dass alle privaten Bibliotheken vom Zugang gelöst werden,
- die Rückfrage, die OPAA vor dem Speichern stellt, im Wortlaut.

Lehnt der Konnektor die Änderung für eine Bibliothek ab, lässt sie sich so nicht speichern. Sonst
speichert ein zweiter Klick auf „Speichern“; eine Änderung, die etwas davon verwirft, fragt dann mit
genau dem Text aus der Vorschau noch einmal nach. Die Rückfrage nennt Personen ohne Zahl. Eine
Änderung, die keine gespeicherten Zugangsdaten und kein Konto verwirft und den Abgleichstand nicht
ganz verwirft (etwa nur die Zertifikatsprüfung), speichert ohne Rückfrage; den Teil des
Abgleichstands, den die neue Konfiguration ungültig macht, verwirft der Konnektor dabei trotzdem.
Ändert sich ein Feld, gilt die
Vorschau nicht mehr, und der Knopf heißt
wieder „Weiter“. Lehnt der Konnektor die Änderung erst beim Speichern ab (etwa weil sich eine
Bibliothek inzwischen geändert hat), sagt die Meldung, dass nichts gespeichert wurde, und die
Vorschau nennt die Gründe. Ein reines Umbenennen speichert ohne Vorschau.

## Einen Zugang wählen, zuordnen, wechseln, lösen

Die Auswahl, die Aktionen an der Bibliothek und der Schalter „Nur über Zugänge“ erscheinen nur bei
einer Quellart, die Zugänge meldet; welche das sind, zeigt die Tabelle unter „Welcher Konnektor
Zugänge kennt“.

**Im Wissens-Assistenten** steht bei einer solchen Quellart im Schritt „Quelle“ über dem
Formular die Wahl **„Zugang“**, sobald es für die Quellart einen Zugang gibt oder eine eigene
Adresse nicht zulässig ist:

- **„Eigene Adresse“** steht zur Wahl, solange die Quellart keine Profilpflicht hat und die Person
  sie mit eigener Adresse anlegen darf. Sonst sagt ein Satz, warum es sie nicht gibt.
- **Jeder Zugang, den die Person nutzen darf,** steht mit Server-Adresse, Anmeldeart und Vorgaben
  da. Ein Zugang, der für sie nicht freigegeben oder der gesperrt ist, erscheint nicht.
- Gibt es weder einen nutzbaren Zugang noch die eigene Adresse, ist die Quellart schon im Schritt
  „Art des Wissens“ nicht zu sehen. Zugänge trägt allein die Systemverwaltung ein; wer einen
  fehlenden braucht, wendet sich an sie.

Nach der Wahl eines Zugangs richtet sich das Formular nach ihm: Die Adresse ist mit der
Server-Adresse des Zugangs vorbelegt (eine Adresse darunter ist möglich), das Feld für die
Zugangsdaten entfällt bei einem Zugang ohne Anmeldung, und Einstellungen, die der Zugang vorgibt
(etwa die Confluence-Edition oder bei S3 Region und Adressstil), stehen ebenso wie die
Zertifikatsprüfung des Zugangs nur lesbar mit seinem Wert da. Die Oberfläche schickt für sie keinen
Wert mit; ändert die Systemverwaltung den Wert einer Vorgabe, während das Formular offen ist, gilt
beim Speichern der neue. Gibt der Zugang inzwischen eine Einstellung neu vor, die das offene
Formular noch als eigene kennt, kann das Speichern mit dem Hinweis auf die Vorgabe abgewiesen
werden; nach erneutem Öffnen klappt es, die übrigen Eingaben bleiben bis dahin stehen.
Verbindungstest und Auflistung prüfen über den gewählten Zugang. Wechselt die Wahl, entfallen eine
Adresse, die nicht unter dem neuen Weg liegt, und was die Quelle aus ihr gelesen hat (bei
Confluence die erkannte Edition).

**An einer fertigen Bibliothek** steht im Reiter „Quelle“ unter „Anbindung“ ihr Zugang, für die
Verwaltenden mit diesen Aktionen:

| Aktion | Wann | Wirkung |
|---|---|---|
| „Zugang zuordnen“ | Bibliothek mit eigener Adresse, auch nach „Zugang entfernt“ | verbindet sie über einen gewählten Zugang; die Adresse muss unter dessen Server-Adresse liegen. Liegt sie nicht darunter, fragt der Dialog eine neue Adresse unter dem Zugang ab; so lässt sich eine wegen der Profilpflicht gesperrte Bibliothek reparieren, deren eingefrorene Adresse unter keinem Zugang liegt |
| „Zugang wechseln“ | Bibliothek auf einem Zugang | verbindet sie über einen anderen Zugang derselben Quellart; der bisherige steht nicht zur Wahl. Eine Adresse unter dem bisherigen Zugang wandert unter den neuen; der Dialog zeigt die neue Adresse. Ändert sich dabei der Server, werden die hinterlegten Zugangsdaten verworfen; die Meldung danach sagt das und führt zu „Quelle bearbeiten“, wo sie neu eingetragen werden |
| „Zugang lösen“ | Bibliothek auf einem Zugang, ohne Profilpflicht der Quellart | fragt nach; die Bibliothek behält Adresse und Zugangsdaten als eigene, und was der Zugang vorgab (Vorgaben, Zertifikatsprüfung), wird zu ihrer eigenen Einstellung. Sie läuft unverändert weiter |

**Test vor dem Zuordnen und Wechseln.** Der Dialog prüft den gewählten Zugang mit der Adresse und
den Einstellungen der Bibliothek, bevor er etwas speichert: „Verbindung prüfen“ prüft nur,
„Prüfen und zuordnen“ prüft und speichert bei Erfolg. Das Ergebnis steht im Dialog, nach dem
Speichern auch in der Meldung. Gespeicherte Zugangsdaten gehen in den Test nur ein, solange ihr Ziel
gleich bleibt; bei einem Serverwechsel prüft er also ohne sie. Scheitert der Test, speichert der
Dialog nichts und bietet stattdessen **„Trotzdem zuordnen“** an. Die Bibliothek läuft dann erst,
wenn die Verbindung steht, etwa nachdem unter „Quelle bearbeiten“ neue Zugangsdaten eingetragen
sind. Ändern sich Wahl oder Adresse, gilt ein Testergebnis nicht mehr.

**Reparatur einer gesperrten Bibliothek.** Ist eine Bibliothek wegen der Profilpflicht gesperrt und
liegt ihre eingefrorene Adresse unter keinem Zugang, nennt der Dialog nach der Wahl eines Zugangs
die bisherige Adresse und fragt eine neue ab, vorbelegt mit der Server-Adresse des Zugangs. Erst
wenn sie darunter liegt, lässt sich prüfen und zuordnen. Test und Zuordnen bewerten den gewählten
Zugang, nicht die Sperre der Bibliothek; nach dem Zuordnen läuft sie wieder.

Zur Wahl stehen beim Zuordnen und Wechseln nur Zugänge, die die Person nutzen darf; die übrigen
erscheinen nicht. Das gilt auch für Verwaltende einer Bibliothek ohne das Anlegerecht
„Konnektorbibliotheken anlegen“: Sie sehen nur die Zugänge der Quellart, die für sie freigegeben
und nicht gesperrt sind. Der Zustand der eigenen Bibliothek bleibt dagegen sichtbar: Ist ihr Zugang
gesperrt oder entfernt, sagt der Reiter „Quelle“ das mit Grund und Aktion (siehe unten); ist er für
die Verwaltenden nicht mehr freigegeben, nennt der Reiter ihn weiterhin, und die Bibliothek läuft
weiter. Die Hinweise „Zugang entfernt“ und die Sperre
wegen der Profilpflicht stehen im Reiter „Quelle“ für alle Leseberechtigten; den Verwaltenden bieten
sie „Zugang zuordnen“ direkt an. Allgemein trägt ein Sperrhinweis für die Verwaltenden die Aktion,
die ihn aufhebt: „Zugang zuordnen“, „Quelle bearbeiten“ (Geheimnis neu eintragen oder Adresse
korrigieren) oder bei einer privaten Bibliothek „Konto verbinden“ bzw. „Konto neu verbinden“, das
zur Seite „Verbundene Konten“ führt. Wo nur die Systemverwaltung die Sperre aufheben kann, fehlt
eine Aktion. Steht für eine private Bibliothek der Tag fest, ab dem ihr Inhalt gelöscht wird, nennt
der Hinweis ihn als Überschrift „Löschung ab dem …“, die Kachel im Katalog ebenso.

Beim Bearbeiten der Quelle einer Bibliothek auf einem Zugang gelten dieselben Regeln wie im
Assistenten. Die Angaben des Zugangs (Server-Adresse, Anmeldeart, Vorgaben und
Zertifikatsprüfung, nie ein Geheimnis) kommen mit der Bibliothek selbst; ein Anlegerecht braucht es
dafür nicht. Fehlen sie, lässt sich nichts speichern, die eingetragenen Werte bleiben stehen, und
der Hinweis sagt das.

**Welcher Konnektor Zugänge kennt**, meldet er selbst: Zugänge verboten, möglich oder Pflicht,
dazu die Anmeldearten, die er anbietet (ohne Anmeldung, persönliches Geheimnis, OAuth,
Client-Credentials, Dienstkonto-Schlüssel), je mit den zulässigen Besitzarten, die zulässige
Server-Adresse und die Einstellungen, die ein Zugang vorgeben darf. Ein Zugang wählt eine
Anmeldeart. Pflicht sind Zugänge genau bei einer Quellart mit OAuth oder Client-Credentials, weil
die App-Registrierung nur am Zugang steht. Verboten sind sie bei einer Quellart, die nichts
Entferntes liest oder Uploads annimmt; jede entfernte Quellart lässt sie zu. Die mitgelieferten
Konnektoren melden:

| Quellart | Zugänge | Anmeldearten (Besitz: Bibliothek) | Vorgaben des Zugangs | Server-Adresse |
|---|---|---|---|---|
| Webverzeichnis | möglich | ohne Anmeldung; persönliches Geheimnis (Benutzername und Passwort) | keine | `https://`, `http://` |
| RSS-Feed | möglich | ohne Anmeldung; persönliches Geheimnis (Benutzername und Passwort) | keine | `https://`, `http://` |
| Confluence | möglich | persönliches Geheimnis (Token) | Edition | `https://`, `http://` |
| Nextcloud | möglich | persönliches Geheimnis (Benutzername und App-Passwort) | keine | `https://`, `http://` |
| S3-Objektspeicher | möglich | persönliches Geheimnis (Access Key und Secret Key) | Region, Path-Style-Adressierung | `https://`, `http://` |
| Windows-Dateifreigabe (SMB) | möglich | persönliches Geheimnis (Benutzername und Passwort) | keine | `smb://`, ohne Zertifikatsprüfung |
| Google Drive | möglich | Dienstkonto-Schlüssel (am Zugang) | imitiertes Konto (nur am Zugang) | fest `https://www.googleapis.com` |
| SharePoint | Pflicht | Client-Credentials (am Zugang, mit Mandant) | keine | fest `https://graph.microsoft.com` |
| Dropbox | Pflicht | OAuth (App am Zugang, eigene Zustimmung der Bibliothek) | keine | fest `https://api.dropboxapi.com` |
| Upload, Dateisystem | verboten | – | – | – |

Das persönliche Geheimnis gehört der Bibliothek: Ihre Verwaltenden tragen es ein, der Zugang legt
nur Server, Anmeldeart, Zertifikatsprüfung und Vorgaben fest. Den Dienstkonto-Schlüssel von
Google Drive trägt dagegen der Zugang (Kapitel [Google Drive](konnektoren/google-drive.md),
Abschnitt 2.5), ebenso das Client-Secret von SharePoint (Kapitel [SharePoint](konnektoren/sharepoint.md),
Abschnitt 2.5). Bei „ohne Anmeldung“ erreicht
ein Lauf die Quelle ohne Zugangsdaten. Was die einzelnen Quellarten auf einem Zugang beachten,
steht in ihren Kapiteln.

**Was für eine Bibliothek auf einem Zugang gilt:**

- Ihre Adresse liegt unter der Server-Adresse des Zugangs. Beim Anlegen ohne Adresse übernimmt sie
  die Server-Adresse; eine Adresse außerhalb wird abgewiesen.
- Zertifikatsprüfung und Vorgaben bestimmt der Zugang. Beim Anlegen, im Verbindungstest, in
  der Auflistung und beim Ändern gelten sie schon, bevor etwas gespeichert ist; sie müssen nicht
  mitgeschickt werden. Ein abweichender Wert wird abgewiesen, ein gleicher angenommen. Die
  Bibliothek speichert nur, was der Zugang ihr überlässt.
- Ihr persönliches Geheimnis (etwa Benutzername und Passwort) tragen die Verwaltenden der
  Bibliothek ein wie bisher. Meldet sich der Zugang anders an (ohne Anmeldung, OAuth,
  Client-Credentials, Dienstkonto-Schlüssel), weist OPAA mitgeschickte Zugangsdaten ab, und die
  Bibliothek speichert keine.
- Die Verwaltenden der Bibliothek ordnen sie einem anderen Zugang desselben Konnektors zu oder
  lösen sie vom Zugang. **Zuordnen übernimmt die Vorgaben des Zugangs:** Eine ausgesetzte
  Zertifikatsprüfung und eigene Werte für vorgegebene Einstellungen entfallen ohne Rückfrage, bei
  einer Anmeldeart ohne persönliches Geheimnis auch die Zugangsdaten. Eine Adresse unter dem
  bisherigen Zugang wandert dabei unter den neuen; was der bisherige Zugang vorgab und der neue
  nicht vorgibt, behält sie als eigene Einstellung. **Lösen schreibt die Vorgaben in die
  Bibliothek:** Vorgegebene Einstellungen und die Zertifikatsprüfung des Zugangs werden ihre
  eigenen, sie läuft also unverändert weiter.
- **Der Konnektor prüft jeden Übergang.** Ändert Zuordnen, Wechseln oder Lösen, womit die Bibliothek
  ihre Quelle erreicht, prüft ihr Konnektor das Ergebnis wie eine direkte Änderung, bevor etwas
  gespeichert wird. Passt eine eigene Einstellung nicht zu einer Vorgabe des neuen Zugangs, weist
  er das Zuordnen mit seinem Grund ab, und alles bleibt, wie es war. Ein gespeicherter Übergang
  steht wie eine direkte Änderung im Revisionsprotokoll, und der Konnektor verwirft den
  Abgleichstand, den er ungültig macht.
- Das Geheimnis bleibt nur, solange sein Ziel gleich bleibt: Schema, Host und Port (ohne
  Portangabe zählt nur bei `http` und `https` der Standardport) und das, woran der Konnektor es
  zusätzlich bindet, etwa dieselbe Freigabe eines Dateiservers oder dasselbe imitierte Konto;
  sonst wird es verworfen. Ändert eine Bearbeitung der Quelle bei gleichem Server nur diese
  Bindung, verlangt OPAA neue Zugangsdaten, statt die alten weiterzureichen.
- Einen anderen Zugang können die Verwaltenden vor dem Speichern testen. Der Test nutzt dann dessen
  Rahmen und braucht dessen Anlegerecht. Eine Sperre oder ein entfernter Zugang der Bibliothek
  steht ihm nicht im Weg, so lässt sich eine solche Bibliothek reparieren; ist der gewählte Zugang
  oder die Quellart gesperrt, wird auch der Test abgewiesen.

**Was die Systemverwaltung am Zugang auslöst:**

| Handlung | Folge |
|---|---|
| Server-Adresse ändern | Nach Bestätigung werden alle Geheimnisse der Bibliotheken auf dem Zugang verworfen; ihre Adressen wandern unter die neue Server-Adresse. Vorher nennt OPAA die Zahl der betroffenen Verbindungen und der Bibliotheken, deren gespeicherte Zugangsdaten neu einzutragen sind |
| Client-ID, Mandant, Scopes, einen Endpunkt oder die Anmeldeart ändern | wie oben, ohne Adresswechsel: alle Verbindungen müssen neu verbunden werden. Ein neuer Token- oder Widerrufs-Endpunkt verlangt zudem ein neues Client-Secret, wenn eines hinterlegt ist |
| Vorgaben oder Zertifikatsprüfung ändern | Die Bibliotheken behalten ihre Geheimnisse, es sei denn, eine Vorgabe ändert, woran der Konnektor sie bindet; dann gilt die Bestätigung wie oben für die Betroffenen. Entfällt eine Vorgabe, wird ihr bisheriger Wert zur eigenen Einstellung jeder Bibliothek auf dem Zugang; sie laufen unverändert weiter. Das gilt nicht für eine Vorgabe nur des Zugangs (bei Google Drive das imitierte Konto): Entfällt sie, imitiert keine Bibliothek mehr ein Konto |
| Dienstkonto-Schlüssel eines anderen Dienstkontos hochladen | wie eine neue Client-ID |
| Vorgabe nur des Zugangs ändern (bei Google Drive das imitierte Konto) | Nach Bestätigung, die die Zahl der Bibliotheken nennt, verwirft OPAA ihren Abgleichstand; der nächste Lauf jeder Bibliothek liest die Quelle vollständig neu, und ihre Verwaltenden erhalten eine Benachrichtigung. Dokumente des bisherigen Kontos bleiben bis zu diesem Vollabgleich durchsuchbar |
| nur ein neues Client-Secret zur selben Client-ID bzw. ein neuer Schlüssel desselben Dienstkontos | keine; die Verbindungen bleiben, eine abgelehnte Anmeldung ist aufgehoben |
| „Alle Verbindungen trennen“ (Notabschaltung) | alle Geheimnisse sofort verworfen, der Zugang bleibt. Personen mit verbundenem Konto werden benachrichtigt und müssen ihr Konto selbst neu verbinden; die Rückfrage sagt das bei einem Zugang, der Personen zulässt. Meldet sich der Zugang selbst an (Client-Credentials, Dienstkonto-Schlüssel), löscht OPAA auch sein Client-Secret bzw. seinen Schlüssel unwiderruflich; nur die Systemverwaltung kann ihn am Zugang neu hinterlegen. Beim Anbieter bleibt er gültig: Den Schlüssel bei Google bzw. das Secret bei Microsoft Entra widerruft die Verwaltung dort selbst. Die Zustimmungen der Personen und der Bibliotheken (OAuth) zieht OPAA anschließend im Hintergrund auch beim Anbieter zurück. Gelingt das für einzelne nicht, etwa weil der Anbieter nicht erreichbar ist oder OPAA gerade neu startet, steht nur eine Warnung im Betriebslog, und die Zustimmung bleibt beim Anbieter gültig, unter Umständen unbegrenzt; dann das Client-Secret der App-Registrierung beim Anbieter erneuern |
| Zugang löschen | alle Geheimnisse verworfen; die Bibliotheken bleiben mit Bestand und dem Hinweis „Zugang entfernt“ stehen |

Jede Änderung, die die Konfiguration einer Bibliothek auf dem Zugang verändert (Server-Adresse,
Zertifikatsprüfung, Vorgaben), prüft zuerst der Konnektor jeder betroffenen Bibliothek, und
zwar bevor etwas gespeichert wird; Bibliotheken mit gleicher Konfiguration prüft er nur einmal.
Lehnt er die Änderung für eine Bibliothek ab, wird nichts geändert, weder am Zugang noch an einer
Bibliothek, und die Meldung nennt die Zahl der Bibliotheken je Kategorie (Verbindung, Einstellungen).
Die Gründe nennt die Vorschau der Auswirkungen, auch schon vor dem Speichern. Wird die Änderung gespeichert, steht sie
für jede betroffene Bibliothek im Revisionsprotokoll wie eine direkte Änderung ihrer Quelle, und
der Konnektor verwirft den Abgleichstand, den sie ungültig macht; der nächste Lauf gleicht dann
neu ab. Ein reines Umbenennen fragt keinen Konnektor.

**Private Bibliotheken auf dem Zugang** (Kapitel
[Private Bibliotheken](private-bibliotheken.md))
zählen in keiner Zahl dieses Abschnitts mit, haben kein Veto und halten keine Änderung auf, auch
nicht mit einer laufenden Indexierung. Lehnt der Konnektor eine Änderung
für eine private Bibliothek ab oder lässt der Zugang danach keine Personen mehr zu, löst OPAA sie
vom Zugang; sie ruht mit dem Hinweis „Zugang nicht mehr nutzbar“, bis ihre Besitzerin sie einem
anderen Zugang zuordnet, und erhält darüber eine Benachrichtigung. Die Vorschau nennt solche
Ablehnungen nur als Zahl, ohne Bibliothek und Grund, und unterhalb der Mindestgruppengröße an
Besitzerinnen nur als „weniger als N“; wo OPAA die Zahl nicht nennt, steht „nicht ausgewiesen“.
Bei einem Zugang, der Personen zulässt, steht die Zeile immer da, auch ohne Ablehnung.

Das Client-Secret bzw. der Dienstkonto-Schlüssel liegt verschlüsselt mit demselben Schlüssel wie
die Zugangsdaten der Bibliotheken. Keine Antwort, kein Protokoll und kein Revisionseintrag enthält
es, ebenso wenig das Zugriffstoken; angezeigt wird
nur, ob eines hinterlegt ist, und eine Warnung, wenn sein Ablaufdatum in weniger als 14 Tagen
erreicht ist. Anlegen, Ändern, Löschen, die Notabschaltung und der Anmeldetest stehen im
Revisionsprotokoll.

**Wenn ein Lauf nicht starten darf**, endet er vor dem ersten Element mit einer eigenen Meldung,
die die zuständige Stelle nennt; der Bestand bleibt durchsuchbar und wird nicht aktualisiert:

| Meldung enthält | Ursache | Zuständig |
|---|---|---|
| „Zugang entfernt“ | Der Zugang der Bibliothek wurde gelöscht | Verwaltende der Bibliothek: anderen Zugang zuordnen oder löschen |
| „Verbindung getrennt“ | Das Geheimnis fehlt, etwa nach Adressänderung oder Notabschaltung | Verwaltende der Bibliothek: Geheimnis neu eintragen; bei einem Zugang, der sich selbst anmeldet, die Systemverwaltung: Client-Secret bzw. Schlüssel eintragen |
| „Abgelaufen: Der Anbieter hat die Anmeldung des Zugangs … abgelehnt“ | Der Anbieter hat Client-Secret bzw. Schlüssel des Zugangs abgewiesen | Systemverwaltung: neu eintragen oder „Anmeldung testen“ |
| „Nicht verbunden: Die Quelle ist über den Zugang … nicht verbunden“ bzw. „Abgelaufen: Die Verbindung der Quelle …“ | Ein Zugang mit Anmeldung beim Anbieter (OAuth) für Bibliotheken: Die Quelle wurde noch nicht verbunden, getrennt, oder der Anbieter nimmt die Zustimmung nicht mehr an | Verwaltende der Bibliothek: Quelle neu verbinden |
| „Die Adresse der Bibliothek liegt nicht unter …“ | Die Adresse verließ den Zugang | Verwaltende der Bibliothek |
| „Gesperrt – Inhalt wird nicht mehr aktualisiert“ | Die Quellart oder der Zugang ist gesperrt; gilt auch für Bibliotheken ohne Zugang | Systemverwaltung |
| „Die Bibliothek wird gelöscht.“ | Eine private Bibliothek ist zur Löschung vorgemerkt; ein laufender Lauf endet damit beim nächsten Zugriff auf die Quelle ([Benutzerverwaltung](benutzerverwaltung.md), „Private Bibliotheken löschen“) | niemand: Die Löschung schließt von selbst ab |
| „… ist nur noch über Zugänge nutzbar, und diese Bibliothek hat eine eigene Adresse“ | Die Profilpflicht der Quellart ist eingeschaltet, der Bestand gesperrt (siehe [Profilpflicht](#profilpflicht-nur-über-zugänge)) | Verwaltende der Bibliothek: einem Zugang zuordnen |

Umbenennen und das Korrigieren von Adresse oder Geheimnis bleiben in all diesen Fällen möglich.

**Wer einen Zugang nutzen darf**, regelt das Anlegerecht „Konnektorbibliotheken anlegen", erteilt je
Zugang; eine Bibliothek mit eigener Adresse braucht es für ihre Quellart (Kapitel
[Berechtigungen](berechtigungen.md), Abschnitt 9). Ein neu
angelegter Zugang ist für niemanden außer der Systemverwaltung freigegeben. Die Systemverwaltung
legt die Freigabe im Seitenpanel des Zugangs unter **„Wer darf diesen Zugang nutzen?“** fest, mit
denselben drei Antworten wie unter Anlegerechte (nur die Systemverwaltung, alle Konten, bestimmte
Gruppen und Personen); dort führt umgekehrt „Zugang ansehen“ zum Panel des Zugangs.

## Quelle verbinden

Meldet sich ein Zugang über OAuth an und lässt er die Besitzart Bibliothek zu, verbindet eine
Bibliothek ihre Quelle mit einer **eigenen Zustimmung beim Anbieter**: Eine verwaltende Person
meldet sich dort einmal mit einem **Dienstkonto** an und stimmt zu; die Zustimmung gehört danach der
Bibliothek, nicht der Person, und die Läufe erneuern das Zugriffstoken selbst. Unter den
mitgelieferten Konnektoren meldet diese Anmeldeart für Bibliotheken nur
[Dropbox](konnektoren/dropbox.md) (siehe die Tabelle unter „Welcher Konnektor Zugänge kennt“); ohne
einen solchen Zugang erscheint nichts von diesem Abschnitt in der Oberfläche.

**Im Wissens-Assistenten** steht nach der Wahl eines solchen Zugangs im Schritt „Quelle“ über dem
Formular der Abschnitt **„Quelle verbinden“**:

1. Pflicht ist die Bestätigung **„Ich verbinde ein Dienstkonto, kein persönliches Konto“**. Ohne sie
   startet keine Weiterleitung; der Assistent nennt den Grund, und auch OPAA selbst gibt ohne
   Bestätigung keine Adresse des Anbieters heraus.
2. „Quelle verbinden“ führt im selben Tab zum Anbieter. Die bisherigen Eingaben des Assistenten
   bleiben in diesem Tab erhalten, und zwar nur die Felder, die das Quellformular dafür ausdrücklich
   vorsieht, nie ein Passwort, Token oder anderes Geheimnis. Sie werden nach der Rückkehr sofort
   gelöscht, ebenso beim Abbrechen und nach dem Anlegen; wer den Assistenten danach neu lädt,
   beginnt leer und verbindet die Quelle erneut.
3. Nach der Zustimmung kehrt der Assistent auf den Schritt „Quelle“ zurück, mit dem Fokus auf dessen
   Überschrift, und nennt das Konto beim Anbieter („Verbunden als …“). Erst jetzt erscheinen
   Ordnerauswahl und Verbindungstest; beide laufen über die eben erteilte Zustimmung.
4. Die Zustimmung wartet 60 Minuten auf das Anlegen der Bibliothek und gilt nur für die Person, die
   sie erteilt hat, und nur auf diesem Zugang. Ist sie beim Anlegen abgelaufen oder schon verwendet,
   legt OPAA nichts an; der Assistent kehrt auf „Quelle“ zurück und bittet, die Quelle erneut zu
   verbinden. Eine nicht verwendete Zustimmung räumt OPAA ab und widerruft sie beim Anbieter.

Bricht die Person beim Anbieter ab oder scheitert der Abschluss, führt die Rücksprungseite mit
„Zurück zum Assistenten“ zu den bisherigen Eingaben zurück. Findet sie in diesem Fenster keinen
begonnenen Assistenten (etwa weil die Zustimmung in einem anderen Tab begann), sagt sie das und
bittet, die Quelle im Assistenten erneut zu verbinden; die nicht verwendete Zustimmung verfällt.

**Verantwortlich** für die Verbindung ist eine Person oder Gruppe, die die Bibliothek verwaltet. Der
Schritt „Freigaben“ fragt das ab, wenn eine Gruppe die Bibliothek besitzt („Ich“ oder die Gruppe);
sonst ist es die anlegende Person. Die Verantwortlichen erhalten die Benachrichtigungen zur
Verbindung und können sie neu verbinden; ein Klick auf die Benachrichtigung öffnet den Reiter
„Quelle“ der Bibliothek:

| Benachrichtigung | Wann |
|---|---|
| „Verbindung der Quelle läuft ab“ | Der Anbieter nennt ein Ende der Zustimmung; einmal 14 Tage vorher |
| „Quelle nicht mehr verbunden“ | Der Anbieter nimmt die Zustimmung nicht mehr an; die Quelle gilt als abgelaufen |
| „Quelle getrennt“ | Die Systemverwaltung hat die Verbindung beendet: Server-Adresse oder Registrierung des Zugangs geändert, Notabschaltung oder Zugang gelöscht |

Scheidet die Person aus, die zugestimmt hat, oder wird ihr Konto deaktiviert, bleibt die Verbindung
bestehen.

**An der fertigen Bibliothek** steht im Reiter „Quelle“ unter „Anbindung“ für die Verwaltenden der
Abschnitt **„Quellverbindung“**: das Konto beim Anbieter, wann verbunden wurde, wer verantwortlich
ist, das vom Anbieter genannte Ende der Zustimmung und, wenn sie beendet ist, warum und wann.
Leseberechtigte ohne Verwaltungsrecht sehen davon nichts, nur den Sperrhinweis, solange die Quelle
nicht erreicht wird.

| Aktion | Wirkung |
|---|---|
| „Neu verbinden“ | fragt die Bestätigung des Dienstkontos ab und führt zum Anbieter; danach kehrt die Seite zum Reiter „Quelle“ zurück. Meldet der Anbieter ein **anderes Konto** als bisher, verbindet OPAA zunächst nicht und widerruft die frische Zustimmung; die Rücksprungseite fragt nach, ob mit dem anderen Konto verbunden werden soll. Erst „Mit diesem Konto verbinden“, die erneute Bestätigung des Dienstkontos und das Wissen, dass der Abgleichstand der Bibliothek verworfen wird, führen erneut zum Anbieter; der nächste Lauf liest die Quelle dann vollständig neu |
| „Trennen“ | fragt nach, löscht die Zustimmung sofort und widerruft sie beim Anbieter, wo er das anbietet. Der Inhalt bleibt durchsuchbar und wird nicht mehr aktualisiert, bis die Quelle neu verbunden ist. Trennen ist immer möglich, auch an einem gesperrten Zugang |

Ist der Zugang der Bibliothek gelöscht, fehlt „Neu verbinden“; der Abschnitt verweist auf „Zugang
zuordnen“, danach lässt sich die Quelle über den neuen Zugang verbinden.

Ist die Quelle nicht verbunden, getrennt oder abgelaufen, trägt der Sperrhinweis für die
Verwaltenden die Aktion „Quelle verbinden“ bzw. „Quelle neu verbinden“. Zuordnen an einen anderen
Zugang, Adress- oder Registrierungswechsel, Notabschaltung und Löschen des Zugangs oder der
Bibliothek beenden die Zustimmung und widerrufen sie beim Anbieter. Eine private Bibliothek
verbindet ihre Quelle nie so; sie läuft über das verbundene Konto ihrer Besitzerin.

**Bibliotheken mit ruhender Quelle.** Unter **Administration → Anbindungen → Zugänge** listet die
Systemverwaltung unter der Übersicht im Abschnitt **„Bibliotheken mit ruhender Quelle“** die
geteilten Bibliotheken, deren eigene Quellverbindung
gerade nicht erreicht wird (abgelaufen, getrennt, gesperrt oder ohne Zugang), mit Zugang, Konto,
Zustand, Grund und Zeitpunkt des Endes und den Verantwortlichen; der Name führt zum Reiter „Quelle“
der Bibliothek. Private Bibliotheken stehen nie darin. Ruht keine Quellverbindung, entfällt der
Abschnitt; die Zusammenfassung über der Übersicht zählt die Bibliotheken mit.

## Sperre einer Quellart oder eines Zugangs

Die Systemverwaltung kann eine **Quellart** (für alle ihre Bibliotheken, mit und ohne Zugang) oder
einen **Zugang** sperren, unter **Administration → Anbindungen**: je Zugang im Seitenpanel des
Reiters „Zugänge“ unter „Eingriffe“, je Quellart im Seitenpanel des Reiters „Quellarten“ unter
„Zustand“. Beides fragt vorher nach und nennt die Folgen; ein gesperrter Zugang trägt in der
Übersicht die Marke „Gesperrt“, einer, dessen
Quellart gesperrt ist, die Marke „Quellart gesperrt“. Beim Entsperren sagt die Rückfrage, wenn eine
andere Sperre bestehen bleibt. Die Folgen:

- Neue Bibliotheken der Quellart oder auf dem Zugang sind nicht mehr möglich, auch nicht für die
  Systemverwaltung; im Wissens-Assistenten erscheinen die Quellart bzw. der Zugang nicht mehr.
- Ein manuell ausgelöster Lauf endet vor dem ersten Element mit der Meldung „Gesperrt – Inhalt wird
  nicht mehr aktualisiert"; ein Lauf, der beim Sperren schon läuft, endet regulär. Der Zeitplan
  überspringt die Bibliothek, und Push-Ereignisse der Quelle werden verworfen, beides ohne
  fehlgeschlagenen Lauf.
- Verbindungstest, Auflistung und Abruf eines Originals erreichen die Quelle nicht; sie antworten
  mit dem Sperrhinweis. Ein Test auf einem anderen, nicht gesperrten Zugang bleibt möglich.
- Von einem gesperrten Zugang lässt sich eine Bibliothek nicht lösen, auch nicht durch die
  Systemverwaltung. Sie läuft wieder, wenn die Sperre aufgehoben ist oder wenn ihre Verwaltenden sie
  einem anderen, für sie freigegebenen Zugang zuordnen.
- Der Bestand bleibt durchsuchbar. Der Reiter „Quelle" der Bibliothek trägt für alle
  Leseberechtigten den Hinweis „Gesperrt – Inhalt wird nicht mehr aktualisiert" mit der gesperrten
  Quellart oder dem Zugang und der Systemverwaltung als zuständiger Stelle; die Kachel im Katalog
  trägt die Zeile „Gesperrt – Inhalt wird nicht mehr aktualisiert (zuständig: Systemverwaltung)".
- Ein Beleg einer Antwort, der aus einer gesperrten Bibliothek stammt, trägt in den Belegen „Stand
  vom …“ mit dem letzten erfolgreichen Lauf der Bibliothek und der Systemverwaltung als zuständiger
  Stelle. Dasselbe gilt für eine Bibliothek, deren Verbindung getrennt oder deren Zugang entfernt
  ist; zuständig sind dort die Verwaltenden der Bibliothek. Maßgeblich ist der Zustand zum
  Zeitpunkt der Antwort.
- Wird die Sperre aufgehoben, laufen die Bibliotheken ohne Neueinrichtung weiter.

Sperren und Entsperren sind Governance-Ereignisse im Revisionsprotokoll. Der Entzug einer Freigabe
stoppt dagegen keinen Lauf.

## Profilpflicht („Nur über Zugänge“)

Für eine Quellart, deren Konnektor Zugänge als möglich meldet, kann die Systemverwaltung die
**Profilpflicht** einschalten: Danach ist die Quellart nur noch über einen Zugang nutzbar. Eine
Quellart, die Zugänge verbietet oder ohnehin verlangt, lässt sich nicht umschalten. Einschaltbar
ist die Pflicht damit für Webverzeichnis, RSS-Feed, Confluence, Nextcloud, S3-Objektspeicher,
Windows-Dateifreigabe (SMB) und Google Drive.

Die Übersicht unter **Administration → Anbindungen → Quellarten** nennt je Quellart ihren Zustand
(„Nutzbar“ oder „Gesperrt“), in der Spalte „Zugänge“ ihre Regel und die Zahl ihrer Zugänge: Eine
Quellart, die Zugänge verbietet, trägt „Keine Zugänge möglich“, eine, die sie verlangt, „Immer über
Zugänge“, eine mit möglicher Pflicht „Zugang oder eigene Adresse“ bzw. „Nur über Zugänge“. Der
Schalter **„Nur über Zugänge“** steht im Seitenpanel der Quellart (Klick auf die Zeile oder
„Details“; die Adresse trägt dann `?sourceType=<Schlüssel>`), nur bei einer Quellart mit möglicher
Pflicht. Das Panel nennt außerdem die Zugänge der Quellart mit Verweis auf ihr Panel und, wo es
Bibliotheken mit eigener Adresse gibt, wer sie anlegen darf.

- **Einschalten** öffnet einen Dialog. Lässt sich die Pflicht nicht einschalten, nennt er den Grund
  und bietet nichts zu bestätigen an. Sonst listet er die Bibliotheken mit eigener Adresse mit ihren
  Eigentümern, nennt, was die Pflicht für die Quellart nicht festlegt, und fragt nach der Wahl für
  den Bestand: **„Weiterlaufen lassen“** oder **„Sperren“** (Folgen in der Tabelle unten).
- **„Bestandswahl ändern“** öffnet denselben Dialog bei eingeschalteter Pflicht; unter dem Schalter
  steht, welche Wahl gilt.
- **Ausschalten** fragt vorher nach.

Einschalten geht erst, **wenn es einen passenden Zugang gibt**: mindestens einen nicht gesperrten
Zugang der Quellart, der Bibliotheken zulässt. Ein späteres Löschen oder Sperren dieses Zugangs
bleibt möglich; die Abfrage der Betroffenen eines Zugangs warnt dann, dass er der letzte passende
ist.

Mit eingeschalteter Pflicht gilt für die Quellart:

- Neue Bibliotheken mit eigener Adresse, Verbindungstest und Auflistung ohne Zugang sowie das Lösen
  einer Bibliothek von ihrem Zugang werden mit dem Hinweis abgewiesen, dass die Quellart nur über
  einen Zugang nutzbar ist.
- Für die **Bibliotheken mit eigener Adresse**, die es beim Einschalten gibt, wählt die
  Systemverwaltung für den ganzen Bestand:

  | Wahl | Folge |
  |---|---|
  | weiterlaufen | Die Bibliotheken laufen weiter. Ihre Adresse ist eingefroren: Eine neue Adresse erhalten sie nur, indem ihre Verwaltenden sie einem Zugang zuordnen. Zugangsdaten, Umfang und Zeitplan bleiben änderbar |
  | sperren | Die Bibliotheken laufen nicht mehr, wie bei einer gesperrten Quellart: Ein Lauf endet vor dem ersten Element mit dem Sperrhinweis, der Zeitplan überspringt sie. Der Bestand bleibt durchsuchbar; Reiter „Quelle“, Kachel und Belege („Stand vom …“) tragen den Hinweis mit den Verwaltenden der Bibliothek als zuständiger Stelle |

- Eine gesperrte Bibliothek läuft wieder, sobald ihre Verwaltenden sie einem Zugang zuordnen.
- Schaltet die Systemverwaltung die Pflicht aus, laufen alle gesperrten Bibliotheken ohne
  Neueinrichtung weiter, und eine eigene Adresse ist wieder möglich.

Was die Pflicht für eine Quellart nicht festlegt, meldet ihr Konnektor mit seiner Profilangabe;
die Abfrage vor dem Einschalten zeigt es. **Einschränkung RSS:** Die Pflicht legt nur die
Feed-Adresse fest. Die Detailseiten eines Feeds stammen aus seinen Einträgen
und können auf fremden Servern liegen; sie werden weiter abgerufen, aber ohne Zugangsdaten. Die
Zielprüfung gegen private und lokale Adressbereiche gilt daneben unverändert.

Eine Bibliothek, deren Zugang gelöscht wurde („Zugang entfernt“), zählt für die Pflicht als
Bibliothek mit eigener Adresse: Ihre Adresse ist eingefroren, und bei der Wahl „sperren“ ist sie
gesperrt, bis sie einem Zugang zugeordnet ist. Die Liste der Bibliotheken mit eigener Adresse
zeigt nur die der eigenen Organisation.

Ein- und Ausschalten sowie eine geänderte Wahl für den Bestand sind Governance-Ereignisse im
Revisionsprotokoll.
