# Private Bibliotheken

Eine **private Bibliothek** speist sich aus dem verbundenen Konto ihrer Besitzerin auf einem Zugang
für Personen (Kapitel [Zugänge](../zugaenge.md)). Ihr Inhalt ist, was die Person
beim Anbieter selbst sieht, und außer ihr liest ihn niemand. Anlegen lässt sie sich mit einem Konnektor,
der die Besitzart „Person“ anbietet; von den mitgelieferten sind das Nextcloud
([Konnektor Nextcloud](konnektoren/nextcloud.md), „Verbundenes Konto“) und
[OneDrive](konnektoren/onedrive.md).

**Anlegen.** Die Person wählt beim Anlegen „privat“; bei einem Zugang, der Bibliotheken
und Personen zulässt, entscheidet allein diese Wahl, und sie bleibt für immer. Im Assistenten steht
dafür im Schritt „Quelle einrichten“ in der Zeile „Verbindung“ der Zugang mit dem Zusatz
**„· privat“** und dem Hinweis „nur für Sie, später nicht änderbar“. Diese Zeile erscheint nur für
Zugänge, auf denen die Person bereits ein verbundenes Konto hat; ohne ein solches Konto gibt es sie
nicht und auch keinen Hinweis darauf. OPAA wählt sie nicht von selbst, auch nicht, wenn sie der
einzige Weg ist. Ausgenommen ist eine Quellart, die nur Personen zulässt, etwa OneDrive: Ist dort die
private Zeile der einzige Weg, ist sie schon gewählt und steht ohne Auswahlknopf da, mit demselben
Hinweis. Ein Zugang, der Bibliotheken und Personen zulässt, steht dort zweimal: als teilbare
Bibliothek und als private. Ein Zugang nur für Personen steht nur als private Zeile; über ihn ist auch die Quellart wählbar, wenn es sonst keinen Weg zu ihr gibt. Für die
private gibt es kein Feld für Zugangsdaten, und der Schritt „Freigaben“ entfällt. Vorausgesetzt
sind:

- ein verbundenes Konto der Person auf dem Zugang und das Anlegerecht für den Zugang ([Berechtigungen](../../konten-und-rechte/berechtigungen.md), Abschnitt 9);
- keine eigenen Zugangsdaten — die Bibliothek meldet sich mit dem verbundenen Konto an;
- dasselbe Ziel wie das Konto: eine Adresse unter der Server-Adresse des Zugangs und keine eigene
  Bindung, etwa eine andere Freigabe eines Dateiservers. Eine abweichende wird abgewiesen.

Verbindungstest und Auflistung vor dem Anlegen laufen ebenso über das verbundene Konto.

**Erkennbar** ist eine private Bibliothek an der Marke **„Privat“** (Schloss und Wort) auf ihrer
Kachel im Katalog und im Kopf ihrer Detailseite. Im Reiter „Freigaben“ stehen nur die Zeilen
„Eigentümer“ und „Suchdiagnose“; Berechtigungen, Externer Zugang, Obergrenze, Herleitung und
„Übergeben“ fehlen. Im Reiter „Quelle“ fehlt „Zugang lösen“,
und „Zugang wechseln“ bietet nur Zugänge an, auf denen die Besitzerin ein verbundenes Konto hat,
auch solche nur für Personen.

**Im Chat** trägt ein Beleg aus einer privaten Bibliothek im Belegfenster den Zusatz **„Private
Quelle“**, auch wenn die Antwort ihn nicht zitiert. Stand eine solche Quelle im Kontext der Antwort,
steht darunter die Zeile **„Private Quellen im Kontext“**. Beides sieht nur die Besitzerin, denn nur
sie liest ihre privaten Bibliotheken; die Kennzeichnung wird mit der Antwort gespeichert und bleibt
auch, wenn die Bibliothek später gelöscht und ihr Beleg zu „Quelle entfernt“ wird.

**Was nicht geht — auch nicht für die Systemverwaltung:** Rechte an Personen, Gruppen oder „Alle
Konten“, Übertragung, Freigabe für externe KI-Werkzeuge, eine Freigabe-Obergrenze, Nachfolge und „Sicht als“. Die
Diagnosesperre ist bei einer privaten Bibliothek fest gesetzt und lässt sich nicht lösen. Vom Zugang
lösen lässt sie sich nicht; einem anderen Zugang ordnet sie nur ihre Besitzerin zu, und nur einem
Zugang für Personen, auf dem sie ein verbundenes Konto hat. Für alle anderen gibt es die Bibliothek
nicht: Jede Anfrage, die sie nennt, beantwortet OPAA wie die nach einer unbekannten.

**Wenn sie ruht**, trägt sie einen Hinweis, der die zuständige Stelle nennt; der Bestand bleibt
durchsuchbar:

| Hinweis | Ursache | Zuständig |
|---|---|---|
| „Verbindung getrennt“ | Die Besitzerin hat ihr Konto getrennt, oder es wurde getrennt (Notabschaltung, Adressänderung) | Besitzerin: Konto unter „Verbundene Konten“ neu verbinden |
| „Abgelaufen“ | Der Anbieter hat die Anmeldung abgelehnt | Besitzerin: neu verbinden |
| „Ruhend“ | Das Konto der Besitzerin wird derzeit nicht genutzt | Besitzerin bzw. Systemverwaltung |
| „Konto deaktiviert“ | Das Konto der Besitzerin ist deaktiviert; der Hinweis nennt den Tag, ab dem die Bibliothek gelöscht wird, wenn es deaktiviert bleibt | Systemverwaltung |
| „Ziel weicht ab“ | Die Bibliothek erreicht ein anderes Ziel als das, für das das Konto gilt | Besitzerin: neu verbinden, sonst neu anlegen |
| „Zugang nicht mehr nutzbar“ | Der Zugang wurde gelöscht, lässt keine Personen mehr zu oder sein Konnektor lehnte eine Änderung für die Bibliothek ab | Besitzerin: anderem Zugang zuordnen; gibt es keinen, die Systemverwaltung |

Der Hinweis steht im Reiter „Quelle“. Bei „Verbindung getrennt“ und „Abgelaufen“ führt er die
Besitzerin mit „Konto verbinden“ bzw. „Konto neu verbinden“ zu „Verbundene Konten“, bei „Zugang
nicht mehr nutzbar“ bietet er „Zugang zuordnen“ an. Ein Lauf, der daran scheitert, speichert den
Grund als Kategorie ohne Inhaltsbezug.

**Speicherkontingent der Person.** Alle privaten Bibliotheken einer Person teilen sich ein
Kontingent; eine weitere private Bibliothek schafft keinen zusätzlichen Platz. Das Kontingent je
Bibliothek gilt daneben unverändert. Gezählt wird, was tatsächlich gespeichert ist. Passt eine
Datei nicht mehr in das Kontingent, wird sie wie am Kontingent der Bibliothek übersprungen: Der Lauf
nimmt sie nicht auf, läuft aber weiter, übernimmt Löschungen in der Quelle und endet als
unvollständig mit der Kategorie `QUOTA_EXHAUSTED`. Eine übersprungene Datei gilt nicht als gelöscht;
war sie schon aufgenommen und passt nur ihre neue Fassung nicht, bleibt die bisherige Fassung
durchsuchbar. Dieselbe Kategorie trägt ein Lauf, der am Kontingent der Bibliothek selbst endete;
dieses wird zuerst geprüft. Im Reiter „Quelle“ heißt der Lauf deshalb unter „Läufe“ „Kontingent
voll“, sein Ergebnis endet auf „unvollständig“, und aufgeklappt nennt ein Hinweis beide Ursachen
und beide Wege: Platz in dieser Bibliothek schaffen oder ihr
Kontingent erhöhen lassen, das der Betrieb festlegt, oder die privaten Bibliotheken insgesamt
verkleinern oder die persönliche Grenze von der Systemverwaltung erhöhen lassen. Welches Kontingent
erschöpft war, nennt das Laufprotokoll an jeder übersprungenen Datei, mit Verbrauch und Grenze.
Endete der letzte Lauf so, heißt auch der Zustand im Kopf der Detailseite „Kontingent voll“; er
führt zu diesem Lauf. Platz schaffen Dateien, die
in der Quelle gelöscht werden oder durch eine eingegrenzte Quelle herausfallen, das Löschen einer
ganzen privaten Bibliothek oder eine höhere Grenze der Systemverwaltung. Frei wird der Platz erst,
wenn der Abgleich die Löschung übernimmt: bei einem Vollabgleich am Ende des Laufs, bei einer Runde
über mehrere Läufe am Ende des Laufs, der die Runde abschließt, bei einem Änderungslauf am Ende dieses
Laufs. Eine übersprungene Datei – auch ein Anhang – versucht jeder weitere Lauf erneut: Änderungsstand,
Confluence-Anker und Feed-Zustand rücken nicht über sie hinweg, und ein Dokument mit übersprungenem
Anhang wird ohne Änderungsmerkmal gespeichert und im nächsten Lauf erneut gelesen. Sobald Platz ist,
nimmt der nächste Lauf, der sie erreicht, sie auf; bis dahin endet jeder solche Lauf unvollständig,
und der Hinweis bleibt stehen.

Ihren **eigenen Verbrauch** sieht die Besitzerin in den Kennzahlen im Kopf der Detailseite jeder
ihrer privaten Bibliotheken: „… von … privat“, bei unbegrenzter Grenze „… privat“; der Tooltip
sagt, dass die Zahl alle privaten Bibliotheken meint. Die Zahl gilt für alle ihre privaten Bibliotheken
zusammen (`GET /api/v1/me/private-storage`); im Einzelnen sieht sie niemand sonst.

Die Grenze ist ein **hausweiter Wert**, den nur die Systemverwaltung setzt: unter **Administration →
Anbindungen → Einstellungen**, Abschnitt „Speicher privater Bibliotheken“, im Feld „Grenze je Person (GB)“ oder mit „Unbegrenzt“
und „Grenze speichern“ (`PUT /api/v1/admin/private-libraries/quota`). Der Abschnitt nennt, ob die
Werkseinstellung oder eine eigene Grenze gilt. Das Feld nimmt Komma und Punkt als
Dezimaltrenner an und weist eine Grenze unter 1 MB ab; unbegrenzt wird sie nur über „Unbegrenzt“;
„Werkseinstellung wiederherstellen“ führt zur Werkseinstellung zurück. Der Wert lebt nur in der
Verwaltung, eine Umgebungsvariable dafür gibt es nicht. `0` bedeutet unbegrenzt. Vor dem Speichern fragt OPAA nach. Eine
Änderung wirkt ab dem nächsten aufgenommenen Dokument und löscht nichts. Jede Änderung steht im
Revisionsprotokoll (`PRIVATE_STORAGE_QUOTA_CHANGED`).

| Einstellung | Werkseinstellung | Wo sie sich ändern lässt |
|---|---|---|
| Speicherkontingent je Person über alle privaten Bibliotheken | 10 GiB | Administration → Anbindungen → Einstellungen |

Darunter steht die **Übersicht der Verwaltung** (`GET /api/v1/admin/private-libraries/summary`). Sie
zeigt für die eigene Organisation nur Summen: die Zahl der Besitzerinnen, den belegten Speicher
insgesamt, eine Tabelle „Belegter Speicher je Zugang“ für Zugänge für Personen und eine Tabelle
der Laufabbrüche je Ursache im Zeitfenster. Die Ursache „Speicherkontingent erschöpft“ umfasst
beide Kontingente: Läufe privater Bibliotheken, die an der persönlichen Grenze endeten, und solche,
die am Kontingent ihrer Bibliothek endeten. Sie zeigt deshalb auch dann Läufe, wenn die persönliche
Grenze auf „Unbegrenzt“ steht. Jede dieser Zahlen ruht auf Personen und folgt der
Mindestgruppengröße: Die Gesamtzahlen erscheinen erst ab N Besitzerinnen, sonst als „weniger als
N“. Eine Zahl je Zugang oder je Kategorie ist exakt nur, wenn ihre Besitzerinnen und die aller
übrigen privaten Bibliotheken je mindestens N sind, und nur, solange das, was die exakten Zahlen je
Zugang zusammen übrig lassen, auf mindestens N Personen ruht — ein leerer Rest gilt als wenige —;
sonst steht dort „nicht ausgewiesen“. Ein Speicherwert unter der Mindestgruppengröße erscheint als
„nicht ausgewiesen (weniger als N Personen)“. Die Oberfläche zeigt jede Zahl so, wie der Server sie
liefert, und rechnet nichts zusammen. Einen Weg zum Verbrauch einer einzelnen Person gibt es für die
Verwaltung nicht.

**Was die Verwaltung sieht:** nur Zusammenfassungen. Indexstatus und Pipeline-Stand zeigen private
Bibliotheken als eine Zeile ohne Namen und Kennung; jede Zahl über sie richtet sich nach der Zahl
ihrer Besitzerinnen und steht unterhalb der Mindestgruppengröße nur als „weniger als N“, ohne
Summen. Eine Teilzahl, etwa die abgelehnten privaten Bibliotheken eines Zugangs, nennt OPAA nur
exakt, wenn sowohl ihre Besitzerinnen als auch die Besitzerinnen aller übrigen privaten Bibliotheken
mindestens N sind — je Organisation, falls ein Zugang Bibliotheken mehrerer trägt; ein leerer Rest
gilt als wenige —; sonst entfällt sie. Die Speicherbereiche der Bereinigung, die Prüfung
chunk-armer Dokumente, die Zahlen am Zugang und die Zahl der diagnosegesperrten
Bibliotheken zählen sie nicht mit. Der Neuaufbau nach einem Pipeline-Wechsel bezieht sie ein, nennt
sie aber in keiner Zahl seiner Antwort: Jeder Aufruf merkt alle ihre veralteten Dokumente auf einmal
für den nächsten Lauf der Bibliothek vor, der sie mit dem verbundenen Konto der Besitzerin neu
einliest.
Einträge im Revisionsprotokoll nennen weder Dateinamen noch Pfade noch Metadatenwerte. Lehnt der Konnektor eine Änderung des Zugangs für eine private
Bibliothek ab, erfährt die Verwaltung nur die Anzahl, ohne Bibliothek und Grund, und die Änderung
gilt trotzdem; die Besitzerin erhält eine Benachrichtigung. Ist ihr Konto gerade nicht nutzbar
(ruhend, deaktiviert), wird der Konnektor für ihre Bibliothek nicht gefragt, und sie bleibt am
Zugang. Verwirft eine Änderung den Abgleichstand des Zugangs, zählt OPAA private Bibliotheken
dabei nicht mit und wartet nicht auf ihre laufenden Indexierungen; ihr Abgleichstand wird ebenso
verworfen, und die Besitzerin wird benachrichtigt. Schreibt ein laufender Lauf den Stand mit den
alten Einstellungen weiter, verwirft ihn der nächste Lauf selbst. Die Space-Zuordnung einer privaten Bibliothek sehen andere Mitglieder nicht, auch
nicht in Zählern und Hinweisen oder beim Löschen des Space. Im Revisionsprotokoll und in der
Stichtagsauskunft heißt sie „Private Bibliothek“ und ist nur an ihrer Kennung zu erkennen.

**Löschen.** Löscht die Besitzerin ihre private Bibliothek, wird sie mit allem gelöscht, was von ihr
irgendwo liegt, und das Revisionsprotokoll hält es ohne Namen fest; dasselbe tut der tägliche
Löschlauf, wenn ihr Konto länger als die Löschfrist ausdrücklich deaktiviert ist ([Benutzerverwaltung](../../konten-und-rechte/benutzerverwaltung.md),
„Private Bibliotheken löschen“). Eine laufende Indexierung hält das Löschen nicht auf. Die
Indexübersicht nennt die Zahl der zur Löschung anstehenden privaten Bibliotheken nach denselben
Regeln wie jede Teilzahl.

In der Oberfläche heißt die Aktion **„Sofort löschen“** und steht im Menü „⋯“ im Kopf der
Detailseite. Die Rückfrage nennt, was gelöscht wird (Dokumente, Index, Originale, Ordner, Metadaten,
Läufe und die Zuordnungen zu Spaces), dass Belege in ihren Chats danach „Quelle entfernt“ heißen, der Text der Antworten aber
stehen bleibt, dass die Dateien beim Anbieter unverändert bleiben und dass sich das nicht rückgängig
machen lässt; der Fokus liegt zunächst auf „Abbrechen“. Ist die Bibliothek gelöscht, führt die Seite
zurück in den Katalog und bestätigt es. Solange die Anfrage läuft, ist „Sofort löschen“ gesperrt.
Läuft gerade eine Indexierung, meldet OPAA, dass die Bibliothek zur Löschung vorgemerkt ist, und die
Seite bleibt stehen: Die
Bibliothek trägt im Kopf die Marke **„Wird gelöscht“** und als Zustand „Wird gelöscht · `<Tag der
Vormerkung>`“, ohne einen Zeitpunkt für den Abschluss zu versprechen. Bis dahin bietet die Seite weder
„Jetzt indizieren“ noch ein erneutes Löschen, kein Bearbeiten von Name, Quelle, Zugang, Zeitplan,
Metadatenfeldern oder Zuordnungen und kein Hochladen oder Löschen von Dokumenten. Ein Dokument
lässt sich nicht mehr öffnen; der Versuch meldet „Die Bibliothek wird gelöscht – ihre Dokumente
lassen sich nicht mehr öffnen.“ Die Katalogkachel trägt jetzt ebenfalls „Wird gelöscht“.
