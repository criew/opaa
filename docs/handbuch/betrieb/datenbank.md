# Datenbank

PostgreSQL ist die einzige Wahrheit einer Installation: Dokumente, Chunks samt Vektoren, Volltext,
Metadaten, Konten, Rechte, Chats und Protokolle liegen in einer Datenbank. Daneben steht nur die
[Originalablage](originalablage.md). Dieses Kapitel beschreibt, was eine Datenbank für opaa mitbringen
muss; wie sie im Compose-Stapel läuft, steht unter [Docker Compose](docker-compose.md#datenbank-im-stapel),
wie sie gesichert wird, unter [Sicherung und Wiederherstellung](sicherung-und-wiederherstellung.md).

## Vektorspeicher: pgvector

opaa speichert Vektoren in PostgreSQL mit der Erweiterung pgvector; das ist der einzige unterstützte
Vektorspeicher, ein Wechsel ist nicht vorgesehen. Metadaten und Vektorindex liegen damit in derselben
Datenbank: ein Sicherungslauf, eine Wiederherstellung, ein Nachweispfad. Bekannte Grenze: pgvector
stößt bei sehr großen Beständen im Bereich von Millionen Vektoren an Grenzen.

- **pgvector 0.8.0 oder neuer.** Das Image `pgvector/pgvector:pg18` bringt eine passende Fassung mit;
  bei einer älteren Erweiterung bricht der Start mit einer Meldung ab. Wer die Datenbank selbst
  betreibt und pgvector aktualisiert, führt danach `ALTER EXTENSION vector UPDATE;` aus.
- **Die Vektortabelle** legt nicht die Migration an, sondern der Abschluss der Installation, mit
  der Dimension, die die Probeeinbettung des Embedding-Modells ergibt. Eine bestehende Installation
  behält ihre Vektortabelle. Nach einem Wechsel des Embedding-Modells stellt die Neueinbettung sie
  auf die neue Dimension um ([Modelle](modell-endpunkte.md#modell-wechseln)); bis dahin stehen alte und neue
  Vektoren nebeneinander, und den Platz der alten gibt PostgreSQL erst frei, wenn die Zeilen neu
  geschrieben werden.
- **Die Distanzfunktion** (`OPAA_PGVECTOR_DISTANCE_TYPE`) bestimmt, wofür der Vektorindex beim
  Anlegen der Vektortabelle gebaut wird. Passt der Wert später nicht mehr zum bestehenden Index,
  bricht der Start mit einer Meldung ab, die beide Werte und den Namen des Index nennt: Die Suche
  würde den Index sonst nicht mehr nutzen. Siehe [„Distanzfunktion ändern“](#distanzfunktion-ändern).
- **Eine neue PostgreSQL-Hauptversion** (etwa ein Image-Tag `pg18` → höher) ist ein eigener
  Migrationsvorgang der Datenbank mit Sicherung und Wiederherstellung, kein Image-Update: Das
  Datenverzeichnis ist nicht aufwärtskompatibel.

### Distanzfunktion ändern

Der einfachste Weg nach dem Abbruch ist, den alten Wert von `OPAA_PGVECTOR_DISTANCE_TYPE` wieder
einzutragen. Soll die neue Distanzfunktion gelten, wird der Index in der Datenbank neu gebaut,
bei gestopptem Backend, mit dem Indexnamen aus der Meldung und der Operator-Klasse der neuen
Distanzfunktion (`vector_cosine_ops` für `cosine_distance`, `vector_l2_ops` für
`euclidean_distance`, `vector_ip_ops` für `negative_inner_product`):

```sql
DROP INDEX CONCURRENTLY <schema>.<index>;
CREATE INDEX CONCURRENTLY <index> ON <schema>.vector_store USING hnsw (embedding vector_cosine_ops);
```

- **Immer mit `CONCURRENTLY`.** Ohne den Zusatz sperrt der Neuaufbau eines HNSW-Index die
  Schreibzugriffe auf die Tabelle, bei großen Beständen lange. Mit dem Zusatz dauert er länger,
  sperrt aber nicht; die beiden Befehle laufen außerhalb einer Transaktion.
- **Während einer Neueinbettung** nach einem Wechsel des Embedding-Modells
  ([Modelle](modell-endpunkte.md#modell-wechseln)) gibt es einen zweiten Index, `embedding_next_vector_index`
  auf der Spalte `embedding_next`. Er kann ebenso betroffen sein und wird auf dieselbe Weise neu
  gebaut, mit `embedding_next` statt `embedding`. Die Meldung nennt je Start einen Index; passt
  danach ein weiterer nicht, nennt ihn der nächste Start.
- **Den Index nur zu löschen beseitigt den Abbruch, aber nicht das Problem:** Ohne Index liest jede
  Suche still die ganze Tabelle, auch wenn alle Antworten weiter stimmen.

## Voraussetzungen einer eigenen PostgreSQL

Der mitgelieferte Compose-Stapel startet PostgreSQL mit einem Superuser-Konto; dort ist nichts
vorzubereiten. Eine Datenbank des Rechenzentrums stellt opaa dagegen meist ein Konto ohne
Superuser-Rechte zur Verfügung. Die Schemamigration braucht dann zwei Dinge, die ein
Datenbankverwalter **vor dem ersten Start** einmalig anlegt:

```sql
-- in der Datenbank von opaa, als Superuser:
CREATE EXTENSION vector;
-- nur, wenn das Konto von opaa nicht CREATEROLE hat:
CREATE ROLE opaa_audit_owner NOLOGIN;
GRANT opaa_audit_owner TO opaa WITH ADMIN OPTION;
```

- **Erweiterung `vector`** (pgvector 0.8.0 oder neuer). Sie muss auf dem Datenbankserver installiert
  sein, etwa über das Image `pgvector/pgvector` oder das Paket `postgresql-<Version>-pgvector`.
  Anlegen darf sie ein Superuser; verwaltete Dienste (etwa Amazon RDS, Azure Database for
  PostgreSQL, Google Cloud SQL) erlauben es auch ihrer eigenen Verwaltungsrolle.
- **Rolle `opaa_audit_owner`.** Ihr gehören die Protokolltabellen, damit das Konto von opaa sie
  nicht löschen kann. Hat das Konto `CREATEROLE`, legt die Migration die Rolle selbst an. Eine
  vorab angelegte Rolle braucht `WITH ADMIN OPTION`, sonst scheitert die Migration mit
  `permission denied to grant role`.

Daneben muss das Konto in der Datenbank Objekte anlegen dürfen, am einfachsten als Eigentümer der
Datenbank. Für ein eigenes Schema gelten die Regeln im folgenden Abschnitt.

Neben dem Connection Pool hält jede Instanz eine weitere, dauerhafte Verbindung (Anwendungsname
`opaa-invalidation-bus`). Über sie erfährt sie Änderungen an Rechten, Anmeldeanbietern und
Einstellungen sofort, auch wenn eine andere Instanz sie vorgenommen hat. Sie braucht eine direkte
Verbindung oder einen Verbindungs-Proxy im Sitzungsmodus; im Transaktionsmodus (etwa PgBouncer mit
`pool_mode = transaction`) kommt sie nicht zustande. opaa liest Rechte und Einstellungen dann bei
jeder Anfrage aus der Datenbank und meldet im Protokoll `The invalidation bus does not listen`. Die
Verbindung zählt bei `max_connections` mit.

Fehlt ein Recht, bricht die Migration mit der Meldung von PostgreSQL ab, etwa
`permission denied to create extension "vector"`. Ist pgvector auf dem Server gar nicht installiert,
prüft opaa das vor der Migration und bricht mit einem eigenen Bericht ab, weil die Meldung von
PostgreSQL in diesem Fall verloren ginge:

```text
    APPLICATION FAILED TO START

    Description:
    The PostgreSQL server has the pgvector extension not installed; the schema migration of opaa needs it (CREATE EXTENSION vector).

    Action:
    Install pgvector 0.8.0 or later on the database server - e.g. the image pgvector/pgvector or the package postgresql-<version>-pgvector - and start opaa again. See docs/handbuch/betrieb/datenbank.md, section "Voraussetzungen einer eigenen PostgreSQL".
```

## Eigenes Datenbankschema

Ohne weitere Angabe legt opaa alles im Schema `public` an. Mit `OPAA_DB_SCHEMA` lässt sich ein
anderes Schema wählen, etwa um opaa neben anderen Anwendungen in einer gemeinsam genutzten Datenbank
zu betreiben. Das Backend setzt dann den `search_path` jeder Verbindung auf `<schema>, public`;
Migrationen, Tabellen der Anwendung, die Vektortabelle und die Verwaltungstabelle der Migrationen landen
im gewählten Schema. `public` bleibt nur als Rückfall im Suchpfad, damit eine dort bereits
installierte Erweiterung `vector` auffindbar bleibt.

Voraussetzungen:

- Das Schema **muss vor dem ersten Start existieren**. Fehlt es, bricht die Migration beim Start
  ab, statt stillschweigend in `public` anzulegen.
- Empfohlen: Das Schema gehört einer Verwaltungsrolle, und das Konto von opaa erhält nur `USAGE`
  und `CREATE`, und zwar mit Weitergaberecht. Die Migration braucht dieses Recht, um es an die
  Eigentümerrolle der Protokolltabellen (`opaa_audit_owner`) weiterzugeben:

  ```sql
  CREATE SCHEMA opaa AUTHORIZATION dba;
  GRANT USAGE, CREATE ON SCHEMA opaa TO opaa WITH GRANT OPTION;
  ```

  Gehört das Schema stattdessen dem Konto von opaa (`CREATE SCHEMA opaa AUTHORIZATION opaa`),
  läuft die Anwendung ebenso. Das Konto darf dann aber als Schema-Eigentümer auch die Tabellen von
  `opaa_audit_owner` löschen, und der Löschschutz des Protokolls entfällt. Dasselbe gilt in `public`,
  wenn das Konto Eigentümer der Datenbank ist.

- Das Schema wird **vor der Erstinstallation** festgelegt. Ein späterer Wechsel verschiebt keine
  Daten; die Installation im neuen Schema beginnt leer.
- Die Rolle `opaa_audit_owner` (Eigentümerin der Protokolltabellen) gilt für die ganze
  PostgreSQL-Instanz. Eine zweite opaa-Installation in derselben Instanz, aber mit anderem Konto,
  startet nicht: Ihre Migration darf die bestehende Rolle nicht vergeben
  (`permission denied to grant role`). Mit demselben Konto startet sie, kann dann aber das
  Protokoll der anderen Installation verändern. Getrennte Installationen gehören deshalb in
  getrennte PostgreSQL-Instanzen.
- Die `psql`-Beispiele dieses Handbuchs lösen Tabellen über den Standard-Suchpfad auf. Bei einem
  eigenen Schema wird es vorangestellt, etwa `PGOPTIONS='-c search_path=opaa' psql …`.

## Migration als eigener Schritt

Ohne weitere Angabe migriert jede Instanz die Datenbank beim Start, bevor sie Anfragen annimmt;
Docker Compose und das Helm-Chart arbeiten mit einer Instanz so. Mit mehreren Instanzen trennen
beide die Migration vom Start der Instanzen, damit kein Instanzstart eine Migration abbricht: der Dienst `backend-migrate` der Mehrinstanz-Variante
([Docker Compose](docker-compose.md#mehrere-backend-instanzen)) und der Migrations-Job des Charts
([Kubernetes](kubernetes.md#mehrere-backend-instanzen)). Von Hand:

1. Das Backend-Image einmal mit `OPAA_MODE=migrate` und denselben Datenbankwerten starten. Es wendet
   die Migrationen an, legt die Vektortabelle an und endet mit Exit-Code 0. Jeder andere Exit-Code
   heißt: Die Migration ist gescheitert, die Instanzen bleiben auf dem alten Stand.
2. Die Instanzen mit `SPRING_FLYWAY_ENABLED=false` starten. Sie migrieren nicht und warten nicht
   auf die Sperre der Migration.

Wer die Reihenfolge nicht selbst abwarten kann, stellt jeder Instanz das Backend-Image mit
`OPAA_MODE=await-migration` voran, wie es der Chart als Init-Container tut. Es liest nur, wartet,
bis die Datenbank jede Migration seiner Version enthält, und endet dann mit Exit-Code 0; bis dahin
nennt es im Protokoll alle 5 s, wie viele Migrationen noch fehlen. Ist die Datenbank nicht
erreichbar, wartet es weiter.

Eine Migration wartet höchstens fünf Sekunden auf die Sperre einer Tabelle und bricht dann ab, statt
die Anfragen aller Instanzen hinter sich aufzustauen; der abgebrochene Lauf lässt sich wiederholen.
Mehrere Instanzen, die gleichzeitig starten, wenden eine Antwortdatei nacheinander an und schließen
die Installation genau einmal ab, nie doppelt.

**Update auf `v0.1.0`:** Von einem Vorabstand (`v0.1.0-rc.1` bis `rc.4`) aus ist es kein Rolling
Update. Vorher alle Instanzen anhalten; die Migrationen dieser Stände entfernen Spalten und Tabellen,
die eine noch laufende ältere Instanz braucht.

### Verbindungen bei mehreren Instanzen

Jede Instanz öffnet bis zu `OPAA_DB_POOL_SIZE` Verbindungen in ihrem Pool und dazu eine dauerhafte
Verbindung, über die sie Änderungen der anderen Instanzen erfährt. Der Migrationsschritt braucht zwei.
PostgreSQL muss also für

(Instanzen + 1) × (`OPAA_DB_POOL_SIZE` + 1) + 2

Verbindungen Platz haben, zusätzlich zu allem anderen auf demselben Server. Die 1 neben den
Instanzen ist die, die ein Rolling Update startet, bevor es eine alte beendet.

Mehr öffnet eine Instanz auch unter Last nicht: Sind alle Verbindungen ihres Pools belegt, warten
weitere Anfragen auf eine freie, höchstens 30 Sekunden, und enden danach mit einem Fehler. Wie viele
Verbindungen eine Instanz wirklich hält, zeigt `pg_stat_activity`, gruppiert nach `client_addr`.

## Replizierte Datenbank

Eine PostgreSQL mit Replikation und automatischer Umschaltung, etwa mit dem Operator CloudNativePG
oder mit Patroni, ist für opaa weiterhin eine Datenbank: Alle Instanzen schreiben und lesen auf dem
Primärserver, eine Replik nutzt opaa nicht. opaa braucht deshalb eine Adresse, die nach einer
Umschaltung auf den neuen Primärserver zeigt.

| Anbindung | Adresse | Wann |
|---|---|---|
| Adresse des Primärservers | ein Name oder eine IP, die der Datenbankbetrieb dem Primärserver nachführt: bei CloudNativePG der Service `<Cluster>-rw`, bei Patroni etwa eine virtuelle IP oder ein Load Balancer, der nur den Primärserver anspricht | die Vorgabe; `database.host` bzw. `OPAA_DB_URL` bleiben, wie sie sind |
| Verbindungs-URL mit mehreren Hosts | `jdbc:postgresql://db-1:5432,db-2:5432/opaa?targetServerType=primary&connectTimeout=2&prepareThreshold=0` in `OPAA_DB_URL` | wenn es keine solche Adresse gibt; der Treiber probiert die Hosts der Reihe nach und bleibt bei dem, der Schreibzugriffe annimmt |

- **Ohne `targetServerType=primary`** nimmt der Treiber den ersten Host, der antwortet, auch eine
  Replik; jede schreibende Anfrage scheitert dann. Der Wert gehört in jede URL mit mehreren Hosts,
  `prepareThreshold=0` ebenso wie in jede andere.
- **`connectTimeout`** begrenzt, wie lange der Treiber auf einen Host wartet, bevor er den nächsten
  probiert; ohne den Wert wartet er zehn Sekunden. Ein alter Primärserver, der während einer
  geplanten Umschaltung keine Verbindung annimmt, verlängerte die längste Lücke ohne Antwort damit
  in der Messung auf 12 bis 13 Sekunden; mit `connectTimeout=2` lag sie bei 4 Sekunden, so kurz
  wie mit der Adresse des Primärservers. Beim Ausfall des Primärservers machte die Anbindung keinen
  Unterschied.
- **Nicht geeignet** sind die Adresse einer einzelnen Instanz, ein Service für Lesezugriffe (bei
  CloudNativePG `<Cluster>-ro` und `<Cluster>-r`) und ein Verbindungs-Proxy im Transaktionsmodus.
- **Die Hörverbindung** (Anwendungsname `opaa-invalidation-bus`) nutzt dieselbe Adresse. Sie
  braucht wie ohne Replikation eine direkte Verbindung oder einen Proxy im Sitzungsmodus.
- **`max_connections`** nach [Verbindungen bei mehreren Instanzen](#verbindungen-bei-mehreren-instanzen)
  gilt auf jedem Server, der Primärserver werden kann.

### Was während einer Umschaltung geschieht

Gemessen mit CloudNativePG (zwei Instanzen, asynchrone Replikation), zwei Backend-Instanzen unter
Last (rund 80 Anfragen je Sekunde) und dem Service `<Cluster>-rw`, je zwei Läufe:

| Anlass | ohne Primärserver | längste Lücke ohne Antwort | Fehlerantworten |
|---|---|---|---|
| Geplante Umschaltung | 3 Sekunden | 5 Sekunden | 2 bis 4 |
| Räumen des Knotens mit dem Primärserver | 3 bis 4 Sekunden | 6 Sekunden | 4 bis 5 |
| Ausfall des Primärservers | 21 Sekunden | 20 Sekunden | 3 bis 5 |

- **Fehlerantworten** bekommen nur Anfragen, die im Moment der Umschaltung eine Verbindung zum alten
  Primärserver benutzten: Sie enden mit `500`, das Protokoll nennt
  `terminating connection due to administrator command` (geplant) oder
  `An I/O error occurred while sending to the backend` (Ausfall). Alle übrigen Anfragen warten im
  Connection Pool, bis der neue Primärserver antwortet, und laufen dann normal weiter. Dauert eine
  Umschaltung länger als die 30 Sekunden, die eine Anfrage auf eine Verbindung wartet, enden auch
  die wartenden Anfragen mit einem Fehler. Die Readiness prüft die Datenbank; in den gemessenen
  Umschaltungen blieben alle Instanzen bereit.
- **Änderungen zwischen den Instanzen:** Die Hörverbindung reißt ab, jede Instanz meldet
  `The invalidation bus does not listen` und liest Rechte und Einstellungen direkt aus der
  Datenbank. Sie steht wieder, sobald der neue Primärserver antwortet
  (`Listening on the invalidation bus again`): nach einer geplanten Umschaltung nach fünf Sekunden,
  nach einem Ausfall nach 20 bis 30 Sekunden. Die Meldung
  `Dropping the cache … failed` dazwischen ist harmlos: Die Instanz liest den Speicher nach dem
  Wiederanschluss neu.
- **Instanzregister:** Der Herzschlag setzt für die Dauer der Umschaltung aus und liegt weit unter
  der Frist `OPAA_INSTANCE_DEAD_AFTER`. Keine Instanz fällt aus dem Register, keine gibt ihre Arbeit
  ab oder übernimmt fremde.
- **Geplante Läufe und Mails:** Ein Termin, der in die Umschaltung fällt, läuft genau einmal; jede
  Mail der Warteschlange geht genau einmal hinaus. Ein Lauf, der genau in der Umschaltung scheitert,
  zählt als Fehler (`opaa_scheduled_failures_total`) und läuft beim nächsten Termin.
- **Indexierung und Uploads:** Laufende Indexierungen setzen nach der Umschaltung fort. Ein Dokument
  oder ein Upload, dessen Schreibvorgang genau in die Umschaltung fällt, wird nach dem
  Wiederanschluss noch einmal verarbeitet; das Protokoll nennt
  `Lost the database connection while storing …`. Wiederholt wird nur, solange der Lauf oder der
  Upload noch der Instanz gehört, und nur begrenzt (`OPAA_INDEXING_WRITE_RETRY_*`). Dauert der
  Ausfall länger, zählt das Dokument im Lauf als fehlgeschlagen und wird beim nächsten Lauf erneut
  gelesen, ein Upload steht auf „Fehlgeschlagen“ und wird neu hochgeladen. Abschnitte, die ein
  gescheiterter Versuch schon geschrieben hatte, ersetzt der nächste Versuch oder die nächste
  Verarbeitung desselben Dokuments.
- **Asynchrone Replikation:** Fällt der Primärserver aus, fehlen dem neuen die Transaktionen, die
  er noch nicht empfangen hatte. In den Läufen ging keine verloren; wer das ausschließen muss,
  schaltet im Datenbankbetrieb synchrone Replikation ein.

## Volltextsuche (lexikalischer Suchpfad)

Neben der Vektorsuche läuft eine klassische Volltextsuche direkt in PostgreSQL, mit der
`german`-Konfiguration und einem GIN-Index. Es gibt **kein zweites System**: derselbe Sicherungslauf,
dieselbe Wiederherstellung, derselbe Verschlüsselungsnachweis. Der Pfad findet, woran eine
Vektorsuche strukturell scheitert — Paragrafenverweise, Aktenzeichen, Erlassnummern, seltene
Fachbegriffe. Wie seine Treffer mit denen der Vektorsuche zusammengeführt werden, steht im Kapitel
[Suche](../referenz/wie-die-suche-arbeitet.md#die-stufen); scheitert die Volltextabfrage, scheitert die Frage, statt
still eine halbe Suche zu liefern.

### Was zu tun ist

Im laufenden Betrieb nichts. Jeder Chunk bekommt seinen Volltexteintrag in derselben Transaktion wie
seinen Vektor.

**Ändert ein Update die Bildung des Volltextindex**, liegen die betroffenen Einträge in einer älteren
Fassung, und die Seite „Suche & Indexierung" weist die Bibliotheken mit **Nachzug ausstehend** aus.
Der Pfad findet diese Einträge weiter, ihnen fehlen nur die Lexeme der neuen Fassung. Nachgezogen wird
über den [Pipeline-Nachzug](../administration/suche-und-indexierung.md#bestand-nachziehen)
(`POST /api/v1/admin/indexing/pipeline-reindex`, eine Oberfläche dafür gibt es noch nicht); er erfasst
solche Abschnitte auch dann, wenn sich an der Aufbereitung des Dokuments sonst nichts geändert hat.
Ein Update, das ihn nötig macht, nennen die Notizen des Release.

**Was das kostet:** Der Nachzug liest jedes betroffene Dokument neu ein, zerlegt es und **bettet es
neu ein** — in derselben Größenordnung teuer wie eine Neuindizierung dieser Dokumente. Er läuft
stapelweise, ist unterbrechbar und wiederaufnehmbar; bis er durch ist, sucht der Pfad mit den Lexemen
der alten Fassung.

### Bekannte Grenze: `ts_rank` ist kein BM25

PostgreSQL bewertet Volltexttreffer mit `ts_rank`. Das ist **nicht** das BM25-Verfahren, das
spezialisierte Suchmaschinen verwenden, und der Unterschied ist im Betrieb spürbar:

- **Keine Normalisierung auf die Textlänge.** Lange Abschnitte werden systematisch überbewertet.
- **Keine Gewichtung nach Seltenheit.** Ein Wort, das in fünf von 50 000 Abschnitten vorkommt, zählt
  kaum mehr als eines, das überall steht. Bei einem Bestand aus vielen ähnlich formulierten Satzungen
  ist genau das die schwache Stelle.

Diese Grenze ist bewusst in Kauf genommen, aus zwei Gründen. Erstens braucht die Ergebnis-Fusion keine
richtige *Bewertung*, sondern nur eine brauchbare *Reihenfolge* — eine deutlich schwächere Anforderung.
Zweitens wird die Schwäche dort, wo der Pfad seinen Zweck erfüllt, kaum wirksam: Bei einer exakten
Kennung ist der richtige Abschnitt meist der einzige, der die Zeichenfolge überhaupt enthält.

Gegen den zweiten Punkt ist zusätzlich vorgesorgt: Paragrafenverweise, Aktenzeichen, Dienstanweisungs-
und Formularnummern, Erlass- und Drucksachennummern sowie Kennungen aus Buchstaben und Ziffern
werden **zusätzlich als unzerlegte Kennungen** geführt und höher gewichtet als Fließtext. Ohne das
gewönne ein Abschnitt, der die Wörter der Frage nur oft genug wiederholt, gegen den einen Abschnitt,
der die gesuchte Kennung tatsächlich führt — genau die Schwäche von `ts_rank`. Dabei ist gleichgültig, ob das Dokument die Kennung hinter einem Schlüsselwort
nennt („mit dem Aktenzeichen BAU-DA-2/2024") und die Frage sie nackt („Was regelt die Dienstanweisung
BAU-DA-2/2024?") — beide Schreibweisen führen auf dieselbe Kennung. Ebenso führen „AB08", „AB-08",
„AB 08", „ab_08" und „AB.08" auf eine Kennung; mit Leerzeichen oder Punkt gilt das nur für zwei bis
sechs Großbuchstaben und ein bis drei Ziffern, damit „SEITE 3“, „JANUAR 2024“ oder „ab 8 Uhr“ Text
bleiben.

**Wann diese Grenze relevant wird:** Wenn Fragen mit exakten Kennungen die falsche Fundstelle liefern,
ist sie der erste Verdacht. Ob der Wechsel auf eine echte BM25-Erweiterung nötig ist, wird gemessen und
nicht vermutet. Die festgelegte Eintrittsbedingung: Die Fallgruppen „Kompositum" und „exakte Kennung"
des Suchqualitäts-Benchmarks bleiben — mit aktivem Kennungsschutz und aktivem Reranker — je einzeln
unter Hit Rate@5 = 0,80, obwohl der Vektorpfad die betreffenden Kandidaten im gemessenen Fenster zeigt.
Erst dann ist eine BM25-Erweiterung im selben PostgreSQL (eigenes Datenbank-Image) der nächste Schritt;
eine externe Suchengine neben PostgreSQL bleibt die letzte Stufe, weil sie den Rechtefilter verdoppelt.

**Keine Kompositazerlegung.** „Genehmigung" findet „Baugenehmigungsverfahren" im Volltextpfad nicht.
Auch das ist eine bewusste Festlegung: Eine Zerlegung, die „Gebührenordnung" in „Gebühr" und „Ordnung"
auflöst, verwässert auch Treffer. Ob sich der Tausch lohnt, entscheidet eine Messung, keine Vermutung.
