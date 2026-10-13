# Datenbank

PostgreSQL ist die einzige Wahrheit einer Installation: Dokumente, Chunks samt Vektoren, Volltext,
Metadaten, Konten, Rechte, Chats und Protokolle liegen in einer Datenbank. Daneben steht nur die
[Originalablage](originalablage.md). Dieses Kapitel beschreibt, was eine Datenbank für OPAA mitbringen
muss; wie sie im Compose-Stapel läuft, steht unter [Docker Compose](docker-compose.md#datenbank-im-stapel),
wie sie gesichert wird, unter [Sicherung und Wiederherstellung](betrieb.md#sicherung-und-wiederherstellung).

## Vektorspeicher: pgvector

OPAA speichert Vektoren in PostgreSQL mit der Erweiterung pgvector; das ist der einzige unterstützte
Vektorspeicher, ein Wechsel ist nicht vorgesehen. Metadaten und Vektorindex liegen damit in derselben
Datenbank: ein Sicherungslauf, eine Wiederherstellung, ein Nachweispfad. Bekannte Grenze: pgvector
stößt bei sehr großen Beständen im Bereich von Millionen Vektoren an Grenzen.

- **pgvector 0.8.0 oder neuer.** Das Image `pgvector/pgvector:pg18` bringt eine passende Fassung mit;
  bei einer älteren Erweiterung bricht der Start mit einer Meldung ab. Wer die Datenbank selbst
  betreibt und pgvector aktualisiert, führt danach `ALTER EXTENSION vector UPDATE;` aus.
- **Die Vektortabelle** entsteht beim ersten Start, wenn sie fehlt, mit der Dimension aus
  `OPAA_PGVECTOR_DIMENSIONS`. Nach einem Wechsel des Embedding-Modells stellt die Neueinbettung sie
  auf die neue Dimension um ([Modelle](modelle.md#modell-wechseln)).
- **Eine neue PostgreSQL-Hauptversion** (etwa ein Image-Tag `pg18` → höher) ist ein eigener
  Migrationsvorgang der Datenbank mit Sicherung und Wiederherstellung, kein Image-Update: Das
  Datenverzeichnis ist nicht aufwärtskompatibel.

## Voraussetzungen einer eigenen PostgreSQL

Der mitgelieferte Compose-Stapel startet PostgreSQL mit einem Superuser-Konto; dort ist nichts
vorzubereiten. Eine Datenbank des Rechenzentrums stellt OPAA dagegen meist ein Konto ohne
Superuser-Rechte zur Verfügung. Die Schemamigration braucht dann zwei Dinge, die ein
Datenbankverwalter **vor dem ersten Start** einmalig anlegt:

```sql
-- in der Datenbank von OPAA, als Superuser:
CREATE EXTENSION vector;
-- nur, wenn das Konto von OPAA nicht CREATEROLE hat:
CREATE ROLE opaa_audit_owner NOLOGIN;
GRANT opaa_audit_owner TO opaa WITH ADMIN OPTION;
```

- **Erweiterung `vector`** (pgvector 0.8.0 oder neuer). Sie muss auf dem Datenbankserver installiert
  sein, etwa über das Image `pgvector/pgvector` oder das Paket `postgresql-<Version>-pgvector`.
  Anlegen darf sie ein Superuser; verwaltete Dienste (etwa Amazon RDS, Azure Database for
  PostgreSQL, Google Cloud SQL) erlauben es auch ihrer eigenen Verwaltungsrolle.
- **Rolle `opaa_audit_owner`.** Ihr gehören die Protokolltabellen, damit das Konto von OPAA sie
  nicht löschen kann. Hat das Konto `CREATEROLE`, legt die Migration die Rolle selbst an. Eine
  vorab angelegte Rolle braucht `WITH ADMIN OPTION`, sonst scheitert die Migration mit
  `permission denied to grant role`.

Daneben muss das Konto in der Datenbank Objekte anlegen dürfen, am einfachsten als Eigentümer der
Datenbank. Für ein eigenes Schema gelten die Regeln im folgenden Abschnitt.

Fehlt ein Recht, bricht die Migration mit der Meldung von PostgreSQL ab, etwa
`permission denied to create extension "vector"`. Ist pgvector auf dem Server gar nicht installiert,
prüft OPAA das vor der Migration und bricht mit einem eigenen Bericht ab, weil die Meldung von
PostgreSQL in diesem Fall verloren ginge:

```text
APPLICATION FAILED TO START

Description:
The PostgreSQL server has the pgvector extension not installed; the schema migration of OPAA needs it (CREATE EXTENSION vector).

Action:
Install pgvector 0.8.0 or later on the database server - e.g. the image pgvector/pgvector or the package postgresql-<version>-pgvector - and start OPAA again. See docs/handbuch/datenbank.md, section "Voraussetzungen einer eigenen PostgreSQL".
```

## Eigenes Datenbankschema

Ohne weitere Angabe legt OPAA alles im Schema `public` an. Mit `OPAA_DB_SCHEMA` lässt sich ein
anderes Schema wählen, etwa um OPAA neben anderen Anwendungen in einer gemeinsam genutzten Datenbank
zu betreiben. Das Backend setzt dann den `search_path` jeder Verbindung auf `<schema>, public`;
Migrationen, Tabellen der Anwendung, die Vektortabelle und die Liquibase-Verwaltungstabellen landen
im gewählten Schema. `public` bleibt nur als Rückfall im Suchpfad, damit eine dort bereits
installierte Erweiterung `vector` auffindbar bleibt.

Voraussetzungen:

- Das Schema **muss vor dem ersten Start existieren**. Fehlt es, bricht die Migration beim Start
  ab, statt stillschweigend in `public` anzulegen.
- Empfohlen: Das Schema gehört einer Verwaltungsrolle, und das Konto von OPAA erhält nur `USAGE`
  und `CREATE`, und zwar mit Weitergaberecht. Die Migration braucht dieses Recht, um es an die
  Eigentümerrolle der Protokolltabellen (`opaa_audit_owner`) weiterzugeben:

  ```sql
  CREATE SCHEMA opaa AUTHORIZATION dba;
  GRANT USAGE, CREATE ON SCHEMA opaa TO opaa WITH GRANT OPTION;
  ```

  Gehört das Schema stattdessen dem Konto von OPAA (`CREATE SCHEMA opaa AUTHORIZATION opaa`),
  läuft die Anwendung ebenso. Das Konto darf dann aber als Schema-Eigentümer auch die Tabellen von
  `opaa_audit_owner` löschen, und der Löschschutz des Protokolls entfällt. Dasselbe gilt in `public`,
  wenn das Konto Eigentümer der Datenbank ist.

- Das Schema wird **vor der Erstinstallation** festgelegt. Ein späterer Wechsel verschiebt keine
  Daten; die Installation im neuen Schema beginnt leer.
- Die Rolle `opaa_audit_owner` (Eigentümerin der Protokolltabellen) gilt für die ganze
  PostgreSQL-Instanz. Eine zweite OPAA-Installation in derselben Instanz, aber mit anderem Konto,
  startet nicht: Ihre Migration darf die bestehende Rolle nicht vergeben
  (`permission denied to grant role`). Mit demselben Konto startet sie, kann dann aber das
  Protokoll der anderen Installation verändern. Getrennte Installationen gehören deshalb in
  getrennte PostgreSQL-Instanzen.
- Die `psql`-Beispiele dieses Handbuchs lösen Tabellen über den Standard-Suchpfad auf. Bei einem
  eigenen Schema wird es vorangestellt, etwa `PGOPTIONS='-c search_path=opaa' psql …`.

## Volltextsuche (lexikalischer Suchpfad)

Neben der Vektorsuche läuft eine klassische Volltextsuche direkt in PostgreSQL, mit der
`german`-Konfiguration und einem GIN-Index. Es gibt **kein zweites System**: derselbe Sicherungslauf,
dieselbe Wiederherstellung, derselbe Verschlüsselungsnachweis. Der Pfad findet, woran eine
Vektorsuche strukturell scheitert — Paragrafenverweise, Aktenzeichen, Erlassnummern, seltene
Fachbegriffe. Wie seine Treffer mit denen der Vektorsuche zusammengeführt werden, steht im Kapitel
[Suche](suche.md#stufe-5-volltextsuche); scheitert die Volltextabfrage, scheitert die Frage, statt
still eine halbe Suche zu liefern.

### Was zu tun ist

Im laufenden Betrieb nichts. Jeder Chunk bekommt seinen Volltexteintrag in derselben Transaktion wie
seinen Vektor.

**Ändert ein Update die Bildung des Volltextindex**, liegen die betroffenen Einträge in einer älteren
Fassung, und die Seite „Suche & Indexierung" weist die Bibliotheken mit **Nachzug ausstehend** aus.
Der Pfad findet diese Einträge weiter, ihnen fehlen nur die Lexeme der neuen Fassung. Nachgezogen wird
über den [Pipeline-Nachzug](wissensbibliotheken.md#72-pipeline-versionen-und-nachzug)
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
und Formularnummern sowie Erlass- und Drucksachennummern werden **zusätzlich als unzerlegte Kennungen**
geführt und höher gewichtet als Fließtext. Ohne das gewönne ein Abschnitt, der die Wörter der Frage nur
oft genug wiederholt, gegen den einen Abschnitt, der die gesuchte Kennung tatsächlich führt — genau die
Schwäche von `ts_rank`. Dabei ist gleichgültig, ob das Dokument die Kennung hinter einem Schlüsselwort
nennt („mit dem Aktenzeichen BAU-DA-2/2024") und die Frage sie nackt („Was regelt die Dienstanweisung
BAU-DA-2/2024?") — beide Schreibweisen führen auf dieselbe Kennung.

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
