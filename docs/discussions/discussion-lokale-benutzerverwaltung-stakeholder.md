# Diskussion: Lokale Benutzerverwaltung — Stakeholder-Bewertungen zum Entwurf von ADR-0033

Zwei Bewertungen des ADR-Entwurfs vom 11.09.2026 (Issue #1531, Epic #1529), erstellt vor der
Freigabe des Implementierungs-Backlogs nach dem Verfahren in
[`docs/AGENT-ORGANIZATION.md`](../AGENT-ORGANIZATION.md#stakeholder-review). Beide Berichte beziehen
sich auf die **erste Fassung** des Entwurfs; welche Befunde übernommen wurden und welche nicht, steht
im Abschnitt „Stakeholder-Bewertung" von
[ADR-0033](../decisions/0033-lokale-benutzerverwaltung.md). Die Berichte sind unverändert übernommen;
Zeilenangaben und Zitate beziehen sich auf den damaligen Stand.

---

# Teil 1: Betriebs- und Informationssicherheitsverantwortlicher


**Gegenstand:** `docs/decisions/0033-lokale-benutzerverwaltung.md` (Entwurf vom 11.09.2026, Issue #1531, Epic #1529)
**Mitgelesen:** ADR-0005 (Historie `basic`), ADR-0025 (Entscheidung 3), `docs/features/access-control.md`
(„Anmeldung und Identität", „Systemverwaltung", „Sitzungsverwaltung"), `docs/handbuch/deployment.md`
(„Authentifizierung", „Härtung für erreichbare Deployments", „Migrationen aus älteren Ständen")
**Nicht Gegenstand:** der Beschluss des Maintainers vom 10.09.2026 (Systemverwalter immer lokal,
Einmalpasswort ins Log, Mail-Flüsse im Umfang). Bewertet wird ausschließlich, wie der ADR ihn umsetzt.

---

## 1. Gesamturteil

**Tragfähig mit Auflagen.** Der Entwurf ist handwerklich der sorgfältigste, den ich in diesem Projekt
gesehen habe — er löst jede einzelne Lehre aus `basic` an einer benannten Stelle ein und nimmt mir mit
dem lokalen Systemverwalter den Datenbankeingriff als Notweg ab. Er scheitert aber an drei Stellen an
meiner Wirklichkeit: Er beschreibt den eingeschwungenen Zustand und nicht den Übergang von rund
einem Dutzend Bestandsinstallationen (Befunde 1–4), er verwandelt seine zentrale Zusage „nie ohne
anmeldefähigen Systemverwalter" in einen Bestätigungsdialog statt in eine geprüfte Bedingung
(Befunde 14, 25), und er öffnet mit dem Übergabepfad einen Weg, auf dem Inhalte den Besitzer wechseln,
den die eigene Feature-Spezifikation ausdrücklich ausschließt (Befund 9). Alles drei ist mit
begrenztem Aufwand zu schließen, bevor der Implementierungs-Backlog freigegeben wird.

---

## 2. Was funktioniert

Das sind die Punkte, die meinen Betrieb tatsächlich vereinfachen — ich nenne sie zuerst, damit der
Umfang der Befundliste darunter nicht als Gesamturteil missverstanden wird.

- **Der Notweg ist endlich eine Anmeldung und kein Datenbankeingriff** (Entscheidung 5). Heute bedeutet
  „letzter Anbieter falsch konfiguriert" für mich: `OPAA_OIDC_BOOTSTRAP=force` setzen, Container neu
  starten, Variable wieder entfernen — drei Schritte am Wochenende, jeder einzeln vergessbar. Künftig
  melde ich mich an und korrigiere die Anbieterzeile.
- **Die Erstadministrator-Regel für OIDC entfällt** (Entscheidung 5). Das ist der größte
  Angriffsflächengewinn im ganzen Dokument: Bisher konnte der Betreiber des Standardanbieters
  jederzeit ein Konto mit der Erstadmin-Adresse ausstellen und damit Systemverwalter in OPAA werden
  (ADR-0025, Entscheidung 3 nennt das selbst als bleibende Grenze). Diese Tür geht zu.
- **Identität als UUID unter einem installationsunabhängigen URN** (Entscheidung 2). Keine Migration an
  `users`, ein Umzug der Installation ändert keine Kontoidentität, und der Audit-Pseudonymschlüssel
  hängt ohnehin an `users.id` (`AuditEventRecorder#pseudonymFor(userId, organizationId)`) — die
  Nachweiskette einer Person bricht also selbst bei der Übergabe aus Entscheidung 12 nicht ab. Das ist
  genau die Eigenschaft, an der `basic` gescheitert ist.
- **Abgeleiteter Kontozustand statt `status`-Spalte** (Entscheidung 3). Für mich der Unterschied
  zwischen „Rücksicherung ergibt konsistente Rechte" und „Rücksicherung ergibt einen Zustand, den es
  nie geben durfte" — jedenfalls für den Kontozustand selbst (zum Sitzungszustand siehe Befund 15).
- **Der Schalter wirkt sofort auf laufende Sitzungen** (Entscheidungen 4 und 8), ohne dass jemand
  Sitzungen aufzählen muss. Das ist der Hebel, den ich in einem Vorfall um 22 Uhr brauche.
- **Der zweistufige Widerruf** (Entscheidung 7) löst die Zusage aus `access-control.md`,
  „Sitzungsverwaltung" ein, dass eine Rechteänderung sofort wirkt und nicht erst mit dem Ablauf.
- **Die Trusted-Proxy-Korrektur** (Entscheidung 9) behebt einen echten, heute vorhandenen Defekt:
  `RateLimitFilter#resolveClientIp` liest `X-Forwarded-For` tatsächlich ungeprüft und nimmt den ersten
  Eintrag. Hinter dem Compose-nginx sind damit heute alle IP-Grenzen wirkungslos — auch die der
  sitzungslos erreichbaren Webhook-Eingänge. Die Wirkung dieser Änderung reicht über das Epic hinaus.
- **SMTP-Passwort verschlüsselt, `***`-Konvention in beide Richtungen, Basis-URL in der Umgebung statt
  in der Oberfläche** (Entscheidung 10). Die Begründung — eine kompromittierte Verwaltersitzung darf
  keine Phishing-Links umlenken — ist die richtige Vertrauensstufenabwägung; bei
  `qnop general.base_url` wäre sie falsch herum gewesen.
- **Sperren als Regelweg, Löschen nur ohne Besitz** (Entscheidung 11) fügt sich in den bestehenden
  DSGVO-Pfad ein, statt einen zweiten Löschweg aufzumachen.
- **Keine Komplexitätsregeln, kein erzwungener periodischer Wechsel** (Entscheidung 9). Das ist der
  aktuelle Stand und erspart mir die Diskussion mit dem Prüfer in die andere Richtung.

---

## 3. Was nicht funktioniert

Jeder Befund mit Schwere, betroffener Entscheidungsnummer, der Betriebs- oder Prüfsituation, in der er
scheitert, und einem konkreten Änderungsvorschlag.

### Migrierbarkeit für Bestandsinstallationen

**Befund 1 — Der Seed legt auf jeder Bestandsinstallation ungefragt ein privilegiertes lokales Konto an
und schreibt ein gültiges Passwort ins Log.**
Schwere: **blockierend** · Entscheidung 5

Der ADR sagt zu Bestandsinstallationen nur: „Bestandsinstallationen verlieren nichts […] der Seed legt
das lokale Konto **zusätzlich** an, der Betrieb entscheidet, ob er es nutzt oder sperrt." Das
beantwortet die falsche Frage. Sie verlieren nichts, aber sie *gewinnen* etwas, das niemand bestellt
hat: Ein Haus, das OPAA bewusst ausschließlich hinter dem Verwaltungs-Keycloak betreibt, weil seine
Kontenrichtlinie lokale Konten verbietet, bekommt beim nächsten `docker compose pull` ein
`SYSTEM_ADMIN`-Konto und ein gültiges Passwort im Anwendungslog. Mein Log läuft in ein SIEM mit
Aufbewahrungsfrist; das Passwort liegt damit ab Sekunde eins an einem Ort, den ich nicht ohne
Vorgangsaufwand wieder sauber bekomme, und es ist bis zur ersten Anmeldung gültig — die nie stattfindet,
weil niemand von dem Konto weiß. Ein Opt-out gibt es nicht. Die einzige Gegenmaßnahme, die der ADR
anbietet (`OPAA_INITIAL_ADMIN_PASSWORD` vorher setzen), setzt voraus, dass ich vor dem Update weiß, dass
es sie gibt.

Der Beschluss vom 10.09.2026 spricht vom **allerersten Start** einer Installation. Der ADR dehnt ihn auf
Bestände aus; das ist eine Auslegung, keine Vorgabe, und genau sie bewerte ich hier.

*Vorschlag:* Den Bestandsfall unterscheiden (die Markierungszeile des OIDC-Seeders existiert, bzw.
`users` ist nicht leer): Dort legt der Seed das Konto als `INVITED` **ohne Passwort und ohne
Log-Ausgabe** an — es ist als Notanker vorhanden, aber nicht scharf. Aktiviert wird es bewusst mit
`OPAA_LOCAL_ADMIN_RESET=force`, dem Weg, den der ADR ohnehin vorsieht. Zusätzlich
`OPAA_LOCAL_ADMIN_SEED=false` für Häuser mit einem Verbot lokaler Konten (dann bleibt der Notanker das,
was er heute ist: eine Variable plus Neustart). Für Neuinstallationen bleibt alles wie beschlossen.

**Befund 2 — Der ausgelieferte Vorgabewert `admin@opaa.local` wird zum Anmeldenamen eines privilegierten
Kontos.**
Schwere: wichtig · Entscheidung 5

`opaa.auth.initial-admin-email` hat in `application.yml:110` den Vorgabewert `admin@opaa.local`, und
`.env.docker.example:26` liefert ihn gesetzt aus. Jede Installation, die die Variable nie bewusst
angefasst hat — das sind alle, die ihre Systemverwalter über den Verzeichnisdienst führen — bekommt ein
Systemverwalterkonto mit einer öffentlich im Repository stehenden Adresse. Diese Adresse ist ab sofort
nicht mehr nur eine Kennzeichnung, sondern der **Anmeldename** an einem erreichbaren Login-Formular.
Sie ist zudem nicht zustellbar: „Passwort vergessen" für dieses Konto geht ins Leere.

*Vorschlag:* Den Seed mit dem ausgelieferten Vorgabewert im `oidc`-Profil ablehnen — als Fehler
protokollieren, welche Variable zu setzen ist, keine Markierung schreiben und die Anlage beim nächsten
Start nachholen (exakt das Verhalten, das ADR-0025, Entscheidung 3 für den fehlenden OIDC-Bootstrap
bereits festlegt). `OPAA_INITIAL_ADMIN_EMAIL` in `.env.docker.example` auskommentieren; das Handbuch
verlangt ein echtes, zustellbares Postfach (am besten ein Funktionspostfach der IT, kein persönliches).

**Befund 3 — „Eine Version lang" ist in diesem Projekt kein bestimmbarer Zeitraum, und die beiden
Variablen sind keine Äquivalente.**
Schwere: wichtig · Entscheidung 5

Der ADR löst `OPAA_OIDC_BOOTSTRAP=force` durch `OPAA_LOCAL_ADMIN_RESET=force` ab und lässt die alte
Variable „eine Version lang" funktionsfähig. OPAA hat keinen Releasezyklus — das Handbuch beschreibt
die Aktualisierung auf „einen neuen `main`-Stand" aus GHCR-Images. „Eine Version" ist für mich kein
Datum, an dem ich etwas eintragen kann. Schwerer wiegt: Die beiden Variablen tun nicht dasselbe. Die
alte repariert eine vertippte Issuer-URI des einzigen Anbieters, die neue entsperrt ein Konto. Wer die
Ersetzung wörtlich nimmt, sucht die Anbieterreparatur künftig an der falschen Stelle.

*Vorschlag:* Ein Kalenderdatum für die Entfernung nennen. Eintrag in die bestehende Tabelle
„Migrationen aus älteren Ständen" (`deployment.md`, Spalten „Entfallene Variable | Ersatz") mit dem
Klartext: Ersatz für die Anbieterreparatur ist **die Anmeldung als lokaler Systemverwalter**, nicht die
neue Variable; `OPAA_LOCAL_ADMIN_RESET=force` ersetzt nur den Zugangsweg dorthin. Solange die alte
Variable noch wirkt, eine `WARN`-Zeile beim Start, die den Ersatz und das Entfernungsdatum nennt.

**Befund 4 — `OPAA_AUTH_JWT_SECRET` ist ein Startabbruch für jede Bestandsinstallation, und das
Handbuch behauptet heute das Gegenteil.**
Schwere: wichtig · Entscheidung 6

Fail-fast ist richtig — ein unvalidiertes Signaturgeheimnis war einer der Gründe, aus denen `basic`
fiel. Die Betriebsfolge ist trotzdem: Beim ersten Start nach dem Update steht das Backend, bis ich die
Variable gesetzt habe. Wer Images automatisiert zieht, hat einen unbeaufsichtigten Ausfall mit einer
Meldung, die nur im Containerlog steht. Und im Härtungskapitel von `deployment.md` steht heute
wörtlich: „Ein anwendungsseitiges JWT-Secret existiert nicht — […] es gibt kein eigenes
Signier-Geheimnis, das rotiert werden müsste." Dieser Absatz wird mit dem ADR falsch. Er steht genau
dort, wo ich als Betreiber nachschlage, was ich vor einer Inbetriebnahme ersetzen muss.

*Vorschlag:* Fail-fast beibehalten. Zusätzlich: (a) `OPAA_AUTH_JWT_SECRET` als Pflicht-Vorbereitungsschritt
in den Abschnitt „Aktualisierung auf einen neuen `main`-Stand" **und** in die Variablentabelle
aufnehmen, mit Erzeugungsbefehl (`openssl rand -base64 48`); (b) den zitierten Absatz im Härtungskapitel
im selben Zug ersetzen, inklusive der Rotationswirkung (eine Rotation beendet alle lokalen Sitzungen —
Access- und Refresh-Tokens); (c) die Abbruchmeldung nennt Variable und Erzeugungsbefehl, nicht nur die
Variable. Das Sub-Issue #1543 erwähnt „Variablen", benennt diese drei Stellen aber nicht.

### Nachweisbarkeit gegenüber Prüfern

**Befund 5 — Die Nutzung des Notfallzugangs ist nicht nachweisbar.**
Schwere: wichtig · Entscheidung 13

Erfolgreiche Anmeldungen werden bewusst nicht auditiert; die Begründung (Verhaltenskontrolle) teile ich
für Beschäftigtenkonten uneingeschränkt. Für das lokale Systemverwalterkonto trägt sie nicht: Das ist
kein Beschäftigter, sondern ein privilegierter technischer Zugang, und „Wurde der Notfallzugang seit
der Inbetriebnahme benutzt, wann und von welcher Adresse?" ist die Frage, die in jeder Prüfung zu
einem Notfallkonto gestellt wird. `users.last_login_at` ist ein einziger, bei jeder Anmeldung
überschriebener Zeitstempel — er beantwortet sie nicht.

*Vorschlag:* Erfolgreiche Anmeldungen **lokaler Konten mit `SYSTEM_ADMIN`** auditieren
(`LOCAL_ADMIN_LOGIN_SUCCEEDED`, mit aufgelöster Client-Adresse), alle übrigen wie beschlossen nicht.
Die Abgrenzung ist erklärbar und deckt sich mit der Trennung, die der ADR selbst zieht.

**Befund 6 — `LOCAL_USER_CHANGED` ist undifferenziert; nur die Übergabe trägt Vorher/Nachher.**
Schwere: wichtig · Entscheidung 13 (mit 11)

Prüfsituation: „Seit wann trägt dieses Konto kein Ablaufdatum mehr, und wer hat es entfernt?" Mit einem
Sammelereignis ohne Vorher/Nachher kann ich das nicht beantworten. Ebenso offen: Ob eine Rollenänderung
an einem lokalen Konto als `LOCAL_USER_CHANGED` oder über die bestehenden
`SYSTEM_ADMIN_ROLE_GRANTED`/`_REVOKED` läuft. Zwei Ereignisarten für dieselbe Tatsache machen jede
spätere Auswertung unvollständig, und zwar unbemerkt.

*Vorschlag:* Im ADR festhalten, dass `LOCAL_USER_CHANGED` Vorher/Nachher für `expires_at`, E-Mail,
Anzeigename und `created_reason` trägt, und dass Rollenänderungen an lokalen Konten **die bestehenden**
Rollenereignisse verwenden. Ein Auswertungstest über die Ereignisliste gehört in #1537.

**Befund 7 — Die Link-Übergabe hinterlässt keine unterscheidbare Spur.**
Schwere: wichtig · Entscheidungen 10 und 11

Wenn SMTP aus ist oder der Versand scheitert, zeigt die Oberfläche dem Verwalter die Einladungs- oder
Rücksetz-URL „genau einmal", damit er sie „auf anderem Weg übergibt". Das ist praktisch richtig und
betrieblich unvermeidlich — es ist aber eine Weitergabe von Zugangsdaten außerhalb des Systems, per
Telefon, Zettel oder Chat. Im Protokoll sieht sie aus wie eine Einladung per Mail.

*Vorschlag:* `LOCAL_USER_INVITED` und `LOCAL_USER_PASSWORD_RESET_REQUESTED` tragen den Zustellweg
(`MAIL` | `LINK_ANGEZEIGT`) und bei `MAIL` das Ergebnis (`Sent` | `Failed`). Das ist ein Feld, und es
ist der Unterschied zwischen „nachvollziehbar" und „plausibel".

**Befund 8 — Der Zähler „Konten ohne Ablaufdatum" ist eine Zahl, kein Nachweis.**
Schwere: wichtig · Entscheidung 11

Der ADR nennt ihn „die sichtbare Form der Auflage ‚begründet und regelmäßig überprüft'" aus
`access-control.md`. Der Prüfer fragt aber nicht nach der Zahl, sondern nach der Liste: welche Konten,
mit welcher Begründung, seit wann, zuletzt benutzt wann, mit welcher Rolle. Und „regelmäßig überprüft"
ist ein Vorgang, den niemand auslöst — eine Zahl auf einer Seite, die ich alle drei Monate zufällig
sehe, ist keine Wiedervorlage. Zusätzlich ist offen, ob `created_reason` („Anlagegrund, freier Text")
überhaupt Pflichtfeld ist; als nullbares Feld ist es in sechs Monaten leer.

*Vorschlag:* Den Zähler zu einer filterbaren, exportierbaren Liste machen (`created_reason`,
`expires_at`, `last_login_at`, Rolle, Zustand), `created_reason` als Pflichtfeld führen, und die
Mail-Infrastruktur nutzen, die dieses Epic ohnehin baut: Erinnerung an alle Systemverwalter X Tage vor
Ablauf eines Kontos und einmal im Quartal über die Konten ohne Ablaufdatum. Ohne diesen Anstoß macht
die Prüfung am Ende doch wieder der Prüfer.

### Rechteexplosion und Angriffsfläche

**Befund 9 — Der Übergabepfad hebt eine ausdrücklich zugesicherte Trennung auf.**
Schwere: **blockierend** · Entscheidung 12

`access-control.md`, „Systemverwaltung" sichert zu: „System-Admins verwalten das System, sind aber
**nicht automatisch berechtigt, jeden Inhalt zu lesen**. […] Wo eine Übernahme nötig ist […], ist sie
ein protokollierter Verwaltungsakt und keine stillschweigende Leseberechtigung. Private Inhalte bleiben
auch dabei unlesbar — in jedem Space."

Entscheidung 12 erlaubt einem Systemverwalter, ein lokales Konto zu sperren (das darf er) und es
anschließend einer Anbieteridentität zuzuweisen, deren `subject` er selbst eintippt. Kontrolliert er
ein Konto bei einem der aktivierten Anbieter — bei einem Partner-IdP ist das kein theoretischer Fall —,
meldet er sich danach als diese Person an und arbeitet in deren persönlichem Space, mit deren privaten
Chats. Die ADR-Begründung „es ist dasselbe Vertrauen, das die Rollenvergabe an ihn stellt" trifft
deshalb nicht zu: Die Rollenvergabe verschafft gerade keinen Inhaltszugriff. Das Ereignis
`LOCAL_USER_HANDED_OVER` belegt den Vorgang hinterher — es verhindert ihn nicht, und der Betroffene
erfährt nichts, weil er sich nicht mehr anmelden kann. Denselben Weg beschreitet man auch versehentlich:
Der ADR akzeptiert das Fehlzuordnungsrisiko eines abgetippten Subjects ausdrücklich.

*Vorschlag:* Die Übergabe in zwei Schritte teilen und das `subject` nicht mehr eintippen lassen. Der
Verwalter erzeugt einen Übergabe-Code (Aktionstoken `HANDOVER`, 72 Stunden) an die **hinterlegte**
Adresse des Kontos; die Bindung entsteht erst, wenn sich die Person beim Anbieter anmeldet und den Code
einlöst — das `subject` kommt dann aus ihrem Token. Damit ist die Übergabe weiterhin administrativ
angestoßen, eng, einmalig und auditiert (ADR-0025, „keine Zusammenführung über die E-Mail" bleibt
gewahrt: die Adresse wählt niemanden aus, sie ist nur der Zustellweg eines Einmalcodes), aber der
Verwalter allein kann keine Identität mehr übernehmen und sich nicht mehr vertippen. Fällt Mail aus,
gilt derselbe Link-Rückfall wie bei der Einladung — mit Befund 7 dann auch nachweisbar.

**Befund 10 — Selbstregistrierung ohne Domänenbeschränkung.**
Schwere: wichtig · Entscheidung 11

Der Schalter steht auf aus, die Ratenbegrenzungen sind vernünftig, die Antworten leaken nichts. Was
fehlt, ist die einzige Einstellung, ohne die ich den Schalter nie anfassen werde: Auf einer aus dem
Landesnetz erreichbaren Instanz bekommt sonst jeder mit irgendeinem Postfach ein Konto, einen
persönlichen Space und ein Kontingent am Modellanbieter. Und selbstregistrierte Konten haben per
Definition kein Ablaufdatum — es sind genau die Konten, die den Zähler aus Befund 8 füllen.

*Vorschlag:* `local_auth_settings.self_registration_allowed_domains` (leer bedeutet **aus**, nicht
„alle Domänen") und `local_auth_settings.default_expiry_days`, angewendet auf selbstregistrierte und
eingeladene Konten. Beides ist je eine Spalte und ein Formularfeld.

**Befund 11 — Keine automatische Sperre bei Inaktivität.**
Schwere: wichtig · Entscheidungen 9 und 11

Der ADR benennt unter „Negativ" korrekt: „Lokale Konten laufen am Verzeichnis vorbei. Ablaufdatum,
Anlagegrund und der dauerhafte Zähler […] machen das sichtbar, verhindern es aber nicht." Genau das ist
die Stelle, an der mein Haus in der Prüfung angreifbar wird: Der Verzeichnisabgleich entzieht ein
ausgeschiedenes Konto automatisch, „ohne Ticket, ohne Handgriff und ohne Bedingung"
(`access-control.md`). Für lokale Konten gibt es diese Automatik nicht, nur ein freiwilliges,
händisch gesetztes Datum.

*Vorschlag:* `local_auth_settings.inactive_days` (Vorgabe 90): Ein lokales Konto ohne Anmeldung in
diesem Zeitraum wird automatisch gesperrt, Audit `LOCAL_USER_LOCKED` mit Grund `INACTIVITY`, Entsperren
wie bisher. Systemverwalterkonten eingeschlossen — der Notanker wird ohnehin über
`OPAA_LOCAL_ADMIN_RESET=force` wieder geöffnet. Das ist die eine Automatik, die den Satz „läuft am
Ausscheideprozess vorbei" entschärft, statt ihn nur sichtbar zu machen.

**Befund 12 — Die harte Kontosperre ist eine wiederholbare Aussperrung und macht mich zum Hebel des
Angreifers.**
Schwere: wichtig · Entscheidung 9

Der Anmeldename ist die E-Mail-Adresse, und die kennt in einer Behörde jeder. Fünf falsche Versuche
sperren ein benanntes Konto für 15 Minuten; wer das im Takt wiederholt, sperrt eine Person dauerhaft
aus. Der Ausweg über „Passwort vergessen" ist ausdrücklich verschlossen: „gesperrte, abgelaufene und
eingeladene Konten erhalten keine Mail." Also ruft die Person bei mir an — und in einer Installation
ohne IdP, also genau der Zielgruppe dieses Epics, sind das alle Beschäftigten.

*Vorschlag:* `locked_reason` auswerten. Bei `FAILED_LOGINS` bleibt „Passwort vergessen" wirksam, und
ein erfolgreich eingelöster Rücksetzlink hebt die Fehlversuchssperre auf — der Besitz des Postfachs ist
der Nachweis, den die Sperre eigentlich verlangt. Nur bei `ADMIN`-Sperren bleibt der Fluss stumm. Die
konstante Antwortzeit und die identische Antwort nach außen ändern sich dadurch nicht.

**Befund 13 — Den neuen Grenzen fehlt die globale Obergrenze, die jeder bestehende Endpunkt hat.**
Schwere: wichtig · Entscheidung 9

`RateLimitProperties.EndpointLimit` führt neben `maxRequests` ein `globalMaxRequests`
(`OPAA_RATE_LIMIT_QUERY_GLOBAL_MAX_REQUESTS=100` in `.env.docker.example`). Die Auth-Grenzen der
Entscheidung 9 nennen nur „je IP" und „je Konto". Gegen verteiltes Credential Stuffing — viele
Adressen, je zwei Versuche — wirkt eine IP-Grenze nicht, und die Kontosperre schlägt dann für alle
gleichzeitig zu (siehe Befund 12).

*Vorschlag:* Login, Registrierung und „Passwort vergessen" bekommen zusätzlich eine globale Grenze je
Fenster, mit demselben Feld und demselben Mechanismus wie die bestehenden Endpunkte. Beim Überschreiten
gehört ein Log-/Audit-Ereignis dazu — das ist mein einziges Frühwarnsignal.

### Wiederherstellbarkeit

**Befund 14 — Der Notanker ist nicht gegen die eigenen Verwaltungswege geschützt.**
Schwere: **blockierend** · Entscheidung 5 (mit 11 und 12)

`OPAA_LOCAL_ADMIN_RESET=force` „setzt beim Start einmalig **das Seed-Konto** zurück". Was „das
Seed-Konto" ist, wenn sich die Installation zwischenzeitlich bewegt hat, sagt der ADR nicht — und sie
bewegt sich auf drei Wegen, die er selbst vorsieht:

- Das Konto wurde über Entscheidung 12 an eine Anbieteridentität übergeben; es ist dann kein lokales
  Konto mehr und hat keine `local_credentials`-Zeile.
- Das Konto wurde gelöscht (Entscheidung 11 erlaubt das, sobald ein zweiter Systemverwalter existiert
  und es keine Assets besitzt).
- `OPAA_INITIAL_ADMIN_EMAIL` trägt inzwischen einen anderen Wert, oder die Adresse des Kontos wurde in
  der Verwaltung geändert.

In allen drei Fällen läuft mein dokumentierter Notweg ins Leere, und zwar genau in dem Moment, in dem
ich ihn brauche: nachts, ausgesperrt, ohne zweiten Verwalter.

*Vorschlag:* (a) Das Seed-Konto über eine Markierungszeile identifizieren, nicht über die Adresse —
dasselbe Muster wie `OidcProviderSeedMarker`. (b) Findet der Wiederanlauf **kein lokales, anmeldefähiges
Systemverwalterkonto**, legt er ein neues an (mit der konfigurierten Adresse), statt nichts zu tun. Erst
damit ist die Variable wirklich ein Notanker. (c) Die Übergabe aus Entscheidung 12 für das Seed-Konto
ablehnen, solange kein zweites lokales Systemverwalterkonto mit gesetztem Passwort existiert.

**Befund 15 — Keine Aussage zur Wirkung einer Datenbank-Rücksicherung.**
Schwere: wichtig · Entscheidungen 3 und 7

Mit diesem ADR hält OPAA erstmals Zugangsdaten und Sitzungszustand; damit bekommt die Frage „Was
passiert, wenn ich eine Sicherung von vorgestern einspiele?" eine neue Antwort. Widerrufene
Refresh-Familien werden wieder gültig, verbrauchte Einladungs- und Rücksetz-Token wieder einlösbar,
`password_invalidated_before` und ein nach einem Verdachtsfall geänderter Passworthash gehen auf den
alten Stand zurück. Konkret: Ich setze montags ein Konto wegen eines Verdachts zurück und widerrufe
seine Sitzungen; mittwochs spiele ich wegen eines ganz anderen Vorfalls die Sicherung von Sonntag ein —
und der kompromittierte Zugang lebt wieder, ohne dass irgendetwas darauf hinweist. Bisher konnte mir
das nicht passieren, weil der Identitätsanbieter diesen Zustand hielt.

*Vorschlag:* ADR und Handbuch nennen die Nacharbeit nach jeder Rücksicherung als verbindlichen Schritt,
und zwar mit den Mitteln, die der ADR schon hat: `OPAA_AUTH_JWT_SECRET` rotieren (beendet nach
Entscheidung 6 alle lokalen Sitzungen in einem Zug, weil auch der Lookup-Hash mitwechselt) und offene
Aktionstoken verwerfen. Für den zweiten Teil genügt ein Startschalter (`OPAA_LOCAL_TOKENS_PURGE=force`)
— ohne ihn muss ich im Container ohne Shell (ADR-0029) an die Datenbank, also genau das, was dieser ADR
abschaffen will.

**Befund 16 — Sitzungen haben keine Höchstdauer.**
Schwere: wichtig · Entscheidung 7

Jede Vorlage eines Refresh-Tokens stellt einen Nachfolger aus. Ob der Nachfolger eine frische
7-Tage-Frist bekommt oder die der Familie erbt, steht nicht da. Im ersten Fall bleibt eine Sitzung
unbegrenzt offen, solange der Browser alle sieben Tage einmal aufgeht — und `access-control.md`,
„Sitzungsverwaltung" beginnt mit dem Satz „**Höchstdauer** und Leerlauffrist sind organisationsweit
gesetzt". Das ist außerdem die erste Frage, die der Prüfer zu einer selbstausgestellten Sitzung stellt.

*Vorschlag:* Die Familie trägt eine absolute Höchstdauer (`opaa.auth.local.session-max-lifetime`), die
die Rotation erbt; die Refresh-TTL wirkt als Leerlauffrist. Für lokale `SYSTEM_ADMIN`-Sitzungen eine
eigene, deutlich kürzere Höchstdauer — ein Notfallkonto mit siebentägiger Sitzung ist kein Notfallkonto.

### Laufender Betriebsaufwand

**Befund 17 — Die Trusted-Proxy-Vorgabe ist richtig, ihr Fehlerbild ist still und sieht wie ein Ausfall
aus.**
Schwere: wichtig · Entscheidung 9

Vorgabe leer, Header ignoriert — das ist die sichere Wahl. Die Folge hinter jedem Reverse-Proxy: Alle
Anfragen fallen auf eine Client-Adresse zusammen, und „Login 10/60 s je IP" bedeutet dann zehn
Anmeldeversuche pro Minute **für das ganze Haus**. Montagmorgen um acht ist das ein Ausfall, der wie
ein Anwendungsfehler aussieht und den ich ohne Kenntnis dieses Mechanismus nicht finde. Der ADR gibt
dem Compose-Stack ein festes Subnetz und eine Vorgabe mit; die hausinterne Installation hinter dem
Apache des Rechenzentrums bekommt nichts davon.

*Vorschlag:* (a) `WARN` beim Start, wenn im `oidc`-Profil Rate-Limiting aktiv, aber die CIDR-Liste leer
ist — mit der Folge im Klartext. (b) `0.0.0.0/0` beim Start ablehnen (sonst ist die Prüfung wieder
aus, und niemand sieht es). (c) Die aufgelöste Client-Adresse in der bestehenden Diagnose-Ansicht
anzeigen, damit die Einstellung ohne Rätselraten prüfbar ist. Das Handbuch allein reicht hier nicht;
die Variable wird beim ersten Aufsetzen vergessen und fällt erst unter Last auf.

**Befund 18 — Scheiternder Mailversand bleibt unsichtbar.**
Schwere: wichtig · Entscheidung 10

`forgot-password` antwortet „immer 204 nach konstanter Zeitklasse", `MailService` liefert `Failed`
zurück und wirft nie. Wenn das SMTP-Passwort abläuft oder der Relay-Server die Absenderadresse nicht
mehr annimmt, erfährt das niemand — die Nutzer bekommen keine Mail, die Oberfläche meldet Erfolg, und
ich erfahre es aus Anrufen. Der Testversand hilft nur, wenn ich ihn aus einem Verdacht heraus auslöse.

*Vorschlag:* Jeder `Failed` erzeugt eine Log-Zeile mit Grund und Vorlagenschlüssel (ohne Adresse), und
die Mail-Einstellungsseite zeigt dauerhaft „letzter erfolgreicher Versand" und „letzter Fehler" mit
Zeitpunkt. Optional ein Health-Indikator, damit die Überwachung ihn abgreifen kann. Das ist der
Unterschied zwischen „Mailversand ist eingerichtet" und „Mailversand funktioniert".

**Befund 19 — Den täglichen Aufräumlauf, auf den sich der ADR beruft, gibt es nicht.**
Schwere: wichtig · Entscheidungen 3 und 7

Entscheidung 7 sagt: „Abgelaufene Zeilen räumt der bestehende Scheduler täglich ab." Im Backend gibt es
keinen täglichen Lauf. Vorhanden sind `AuditRetentionScheduler` (`0 0 3 1 * *`, monatlich),
`DiagnosticContextRetentionScheduler` (`0 30 3 1 * *`, monatlich), `LibraryIndexingScheduler`
(minütlich) und `IndexingJobRecoveryScheduler` (alle 15 Minuten). Drei neue Tabellen —
`local_refresh_tokens`, `local_revoked_tokens`, `local_action_tokens` — haben damit keinen zugewiesenen
Aufräumlauf und wachsen monoton; bei rotierenden Refresh-Tokens ist das je aktiver Sitzung eine Zeile
pro Erneuerung.

*Vorschlag:* Einen eigenen, täglichen `LocalTokenCleanupScheduler` benennen und einem Sub-Issue
zuordnen (#1533 wäre der Ort), mit Aufbewahrungsfrist je Tabelle in der Konfigurationstabelle des
Handbuchkapitels. Keine große Sache — aber sie fällt sonst zwischen die Sub-Issues, weil der ADR sie
für erledigt erklärt.

**Befund 20 — `local_auth_settings` ist im Datenmodell nicht definiert und keinem Sub-Issue
zugeordnet.**
Schwere: Hinweis · Entscheidungen 3, 9, 11 und 13

Die Tabelle taucht in Entscheidung 9 (`password_min_length`), Entscheidung 11
(`reset_token_ttl_minutes`, `selfRegistrationEnabled`, `passwordResetEnabled`) und im
Konfigurationsendpunkt der Entscheidung 4 auf, fehlt aber in der Datenmodell-Aufstellung (Entscheidung
3) und in #1532 („Schema und Krypto"); #1537 setzt sie voraus. Solche Lücken fallen erst auf, wenn zwei
Sub-Issues gleichzeitig daran scheitern.

*Vorschlag:* Die Tabelle in Entscheidung 3 mit Spalten und Vorgaben aufführen (einschließlich der aus
Befund 10 und 11 vorgeschlagenen Felder) und #1532 zuordnen; im Ereignisabschnitt festhalten, dass jede
Änderung an ihr `LOCAL_ACCOUNTS_SETTINGS_CHANGED` auslöst.

**Befund 21 — `cookie-secure` ist ein abschaltbarer Sicherheitsschalter ohne Gegenwehr.**
Schwere: Hinweis · Entscheidung 7

`opaa.auth.local.cookie-secure` ist „abschaltbar für lokales HTTP". Solche Schalter überleben den Weg
von der Erprobung in den Betrieb erfahrungsgemäß; das Härtungskapitel des Handbuchs führt sieben
Fundstellen genau dieser Art.

*Vorschlag:* Im `oidc`-Profil nur mit lauter `WARN`-Zeile beim Start zulassen und als eigene Zeile in
die Härtungstabelle von `deployment.md` aufnehmen (Fundstelle, Vorgabewert, Risiko, Gegenmaßnahme) —
in derselben Form wie die vorhandenen sieben.

**Befund 22 — Ohne `OPAA_PUBLIC_BASE_URL` hat „Passwort vergessen" keinen Rückfall.**
Schwere: Hinweis · Entscheidung 10

Bei der Einladung sieht der Verwalter den Link, wenn Mail nicht geht — bei „Passwort vergessen" gibt es
niemanden, dem etwas angezeigt werden könnte. Die Antwort ist dieselbe 204, die Mail geht nicht oder
mit unbrauchbarem Link, und niemand erfährt es (siehe auch Befund 18).

*Vorschlag:* Fehlt die Basis-URL, meldet `/auth/config` `passwordResetEnabled = false`, die Oberfläche
bietet den Weg nicht an, und der Schalter lässt sich in der Verwaltung nicht einschalten, solange sie
fehlt — mit der Begründung im Formular. #1542 hat den Hinweis bereits als Korrektur; er gehört als
Verhalten in den ADR, nicht nur als Anzeigehinweis.

**Befund 23 — Ohne Komplexitätsregeln fehlt das übliche ausgleichende Mittel.**
Schwere: Hinweis · Entscheidung 9

Der Verzicht auf Komplexitätsregeln ist richtig. Der übliche Ausgleich dafür ist eine Sperrliste
trivialer Passwörter; „nicht gleich der E-Mail-Adresse" allein lässt `Sommer2026!` und
`Verwaltung123` zu — an einem Konto, das ohne zweiten Faktor arbeitet.

*Vorschlag:* Eine kleine mitgelieferte Sperrliste (die 1000 häufigsten Passwörter, Abgleich in
Kleinschreibung, Datei im Image) zusätzlich prüfen. Ob BSI ORP.4.A8 das in der aktuellen Fassung
ausdrücklich verlangt: **zu prüfen** — ich nenne es als üblichen Prüfmaßstab, nicht als belegte
Anforderung.

**Befund 24 — Kein zweiter Faktor für das eine privilegierte Konto, das auf jeder Installation
existiert.**
Schwere: wichtig · Entscheidung 9

MFA ist ausdrücklich vertagt, und das ist für Beschäftigtenkonten vertretbar. Für ein
`SYSTEM_ADMIN`-Konto mit Passwortanmeldung, das der ADR auf **jeder** Installation anlegt, ist es die
Zeile, die mir im Prüfbericht als Feststellung zurückkommt. Der Verweis auf die Handbuchempfehlung
(„Systemverwalter-Konten befristen und die Verwaltung im Regelbetrieb aus lassen") hilft nicht: Der
Systemverwalter-Anmeldeweg bleibt auch bei abgeschalteter Verwaltung offen — das ist ja gerade seine
Aufgabe.

*Vorschlag:* Als Zwischenstand bis zum MFA-Issue eine Netzbeschränkung für den lokalen Anmeldeweg:
`OPAA_LOCAL_AUTH_ALLOWED_CIDRS` (leer = keine Beschränkung), das `/login/system` und
`/api/v1/auth/local/login` nur aus dem Verwaltungsnetz erreichbar macht. Dieselbe CIDR-Auswertung wie
beim Trusted-Proxy aus Entscheidung 9, kein neuer Mechanismus — und genau die Kompensation, nach der
gefragt wird, solange MFA fehlt.

### Deaktivieren des letzten OIDC-Anbieters

**Befund 25 — Vertretbar, aber nicht als Bestätigungsdialog.**
Schwere: **blockierend** · Entscheidung 4

Dass der letzte OIDC-Anbieter deaktivierbar wird, halte ich fachlich für richtig: Die heutige Regel
zwingt mich bei einem Anbieterwechsel zu Verrenkungen, und der lokale Systemverwalter ist der bessere
Anker als ein Anbieter, den ich nicht kontrolliere. Aber die Absicherung ist ein Klick:
`acknowledgeLastProvider = true` prüft nichts. Die Zusage „weil der lokale Systemverwalter immer
anmeldefähig bleibt" ist in dem Moment unwahr, in dem das Seed-Konto `INVITED` ist (nie ein Passwort
gesetzt — nach meinem Vorschlag aus Befund 1 der Regelfall im Bestand), `EXPIRED` (die eigene
Handbuchempfehlung lautet, Systemverwalter-Konten zu befristen) oder `LOCKED`. Dann sperrt ein
bestätigter Klick die gesamte Installation aus, und der Rückweg ist doch wieder eine Umgebungsvariable
plus Neustart — genau das, was dieser ADR abschaffen will.

Dasselbe gilt für die bestehende `LAST_ADMIN`-Prüfung aus Entscheidung 11: Sie zählt Systemverwalter,
ohne zu prüfen, ob sie sich anmelden können. Ein `SYSTEM_ADMIN`-Konto eines deaktivierten
OIDC-Anbieters zählt mit und schützt nichts.

*Vorschlag:* Aus der Zusage eine geprüfte Vorbedingung machen. Das Deaktivieren oder Löschen des
letzten aktivierten OIDC-Anbieters antwortet mit 409, solange **kein anmeldefähiges lokales
Systemverwalterkonto** existiert (`ACTIVE`, Passwort gesetzt, nicht abgelaufen, nicht gesperrt); die
Fehlermeldung nennt den fehlenden Schritt („zuerst ein lokales Systemverwalterkonto mit Passwort
einrichten"). `acknowledgeLastProvider` bleibt zusätzlich erforderlich, aber es ersetzt die Prüfung
nicht.

---

## 4. Die eine Änderung

**Aus „nie ohne anmeldefähigen Systemverwalter" eine an genau einer Stelle geprüfte Invariante machen —
statt einer Zusage, die sich über fünf Entscheidungen verteilt und an keiner davon geprüft wird.**

Alle Wege, die die letzte benutzbare Systemverwalter-Anmeldung entfernen können, prüfen dieselbe
Bedingung über denselben bedingten `UPDATE` und denselben Fehlercode:

- Deaktivieren oder Löschen des letzten aktivierten OIDC-Anbieters (Entscheidung 4, Befund 25)
- Sperren, Befristen, Rollenentzug und Löschen des letzten Systemverwalters (Entscheidung 11) — die
  Prüfung zählt künftig nur **anmeldefähige** Konten, nicht jedes Konto mit der Rolle
- Übergabe des Seed-Kontos an eine Anbieteridentität (Entscheidung 12, Befund 14)
- Wiederanlauf über `OPAA_LOCAL_ADMIN_RESET=force`, der das Konto neu anlegt, wenn keines mehr da ist
  (Entscheidung 5, Befund 14)

Bedingung: „Es existiert mindestens ein lokales Konto mit `SYSTEM_ADMIN`, das anmeldefähig ist —
`ACTIVE`, Passwort gesetzt, nicht abgelaufen, nicht gesperrt." Eine Abfrage, ein Fehlercode, ein Test.
Danach ist der Satz aus dem Konsequenzen-Abschnitt — „Eine Installation ist **nie** ohne anmeldefähigen
Systemverwalter" — eine zugesicherte Eigenschaft und nicht mehr eine Erwartung an das Verhalten der
handelnden Person. Für mich ist das der Unterschied zwischen einem Notanker und einem Notanker, von dem
ich erst im Ernstfall erfahre, dass ihn jemand vorletzten Monat weggeräumt hat.

---

## 5. Was ich nicht beurteilen kann

- **Ob die konkreten Zahlen der Ratenbegrenzung passen** (10/60 s Login je IP, 30/60 s Refresh,
  5/3600 s Registrierung). Ohne Lastzahlen einer echten Zielinstallation — Beschäftigtenzahl,
  Adressverteilung hinter dem Proxy, Browserverhalten — ist das Raten. Ich sehe nur das Fehlerbild aus
  Befund 17 und dass eine globale Grenze fehlt (Befund 13).
- **Ob BSI ORP.4 in der aktuellen Fassung einen zweiten Faktor für privilegierte lokale Konten und eine
  Sperrliste trivialer Passwörter verlangt.** Ich nenne beides als üblichen Prüfmaßstab (Befunde 23,
  24); den genauen Anforderungstext habe ich für diese Bewertung nicht geprüft — **zu prüfen**, bevor
  jemand es als Anforderung zitiert. Dass ORP.4.A8 den erzwungenen periodischen Wechsel nicht mehr
  verlangt (Entscheidung 9), deckt sich mit meinem Kenntnisstand.
- **Die datenschutzrechtliche Bewertung des Fehlversuchszählers und des Mailversands an
  Beschäftigtenadressen.** Das entscheidet der behördliche Datenschutzbeauftragte, nicht ich. Mir fehlt
  im ADR allerdings jede Aufbewahrungsfrist für `LOCAL_LOGIN_FAILED` — die Audit-Aufbewahrung greift
  (monatliche Partitionslöschung), aber welche Frist dort konfiguriert ist, entscheidet, wie lange ein
  Fehlversuchsprotokoll je Person existiert. Das gehört in die Abstimmung mit dem Personalrat, nicht in
  meine Bewertung.
- **Die Laufzeitkosten des Widerrufs-Validators.** Entscheidung 8 prüft „bei jeder Anfrage, gegen den
  Datenbankzustand" (Kontozustand, `password_invalidated_before`, Schalter), mit Cache nur für die
  `jti`-Denylist. Ob das neben dem ohnehin je Anfrage lesenden `UserProvisioningFilter` spürbar ist,
  kann ich ohne Messung nicht sagen — **zu prüfen** im Rahmen von #1533.
- **Ob sich das qnop-Vorbild im Betrieb bewährt hat.** Der ADR übernimmt Muster aus einem fremden
  Projekt; ich kenne dessen Betriebserfahrung nicht und kann nur beurteilen, wie sie hier eingepasst
  sind.
- **Die Benutzerführung** der Anmeldeseite, der Verwaltungsseiten und der Mailvorlagen — ausdrücklich
  außerhalb meiner Rolle.

---

# Teil 2: Personalrat


**Bewertete Fassung:** `docs/decisions/0033-lokale-benutzerverwaltung.md`, Stand 11.09.2026 (Status
„Vorgeschlagen", 576 Zeilen), im Worktree `1531_adr-lokale-benutzerverwaltung`.
**Mitgelesen:** `docs/features/access-control.md` (Abschnitte „Anmeldung und Identität",
„Der Lebenszyklus eines Kontos", „Sitzungsverwaltung", „Erzwungene Neuanmeldung", „Offboarding",
jeweils im Stand dieses Worktrees inkl. der bereits geänderten Passagen),
`docs/features/security-and-compliance.md` (Protokollsatz, „Was ausdrücklich nicht protokolliert
wird", Aufbewahrung, Zugriffswege, „Der Auszug für die Personalvertretung", „Löschung eines
Benutzerkontos", „Mitbestimmungsfähigkeit"), `docs/decisions/0015-eigentuemertrennung-protokollablage.md`
(Kopf), GitHub-Issue #1541 (Benutzerverwaltungsseite) und, zur Prüfung einer Tatsachenbehauptung des
ADR, `backend/src/main/java/io/opaa/auth/UserService.java`.

Der Beschluss des Maintainers vom 10.09.2026 (Zuschnitt B+C, Systemverwalter nach Modell I, Einladung
und Passwort-vergessen per E-Mail, optionale Selbstregistrierung, Sicherheitsmindestmaß) ist gesetzt
und wird hier nicht in Frage gestellt. Bewertet wird ausschließlich, **wie** der ADR ihn umsetzt.

---

## 1. Gesamturteil

**Tragfähig mit Auflagen.** Der ADR hat die Mitbestimmungsfrage erkennbar mitgedacht — die
Entscheidung, erfolgreiche Anmeldungen ausdrücklich **nicht** zu protokollieren, begründet er selbst
mit der Personalratsperspektive, und die Voreinstellungen (lokale Verwaltung aus, Selbstregistrierung
aus) sind die richtigen. An drei Stellen greift er jedoch in Zusagen ein, die in
`security-and-compliance.md` als Grundlage einer Dienstvereinbarung stehen, ohne diese Dokumente
anzufassen: Fehlversuche wandern in das Nachweisprotokoll, obwohl sie dort ausdrücklich ausgeschlossen
sind; ein administrativer Übergabepfad kann ein Konto samt privater Inhalte einer anderen Identität
zuschreiben, ohne dass die betroffene Person es je erfährt; und mehrere neue personenbeziehbare Felder
(Anlagegrund als Freitext, letzte Aktivität, Sitzungsjournal) entstehen ohne Zweckbindung und ohne
Aufbewahrungszusage. Das ist heilbar — die Änderungen sind klein und berühren den Beschluss nicht —,
aber ohne sie ist der ADR in dieser Fassung nicht zustimmungsfähig.

---

## 2. Was funktioniert

Das gehört ausdrücklich gewürdigt, weil es nicht selbstverständlich ist:

- **Keine Einzelprotokollierung erfolgreicher Anmeldungen** (Entscheidung 13, und in „Verworfene
  Alternativen": „Erfolgreiche Anmeldungen auditieren: Verhaltenskontrolle ohne Nachweisgewinn"). Das
  ist die eine Entscheidung, an der Produkte dieser Art üblicherweise scheitern, und sie ist hier
  richtig getroffen — auch ohne dass jemand danach gefragt hätte.
- **Die Voreinstellungen sind die richtige Aussage.** Lokale Verwaltung aus, Selbstregistrierung aus,
  `X-Forwarded-For` ohne vertrauten Proxy ignoriert (Entscheidung 9). Eine Konfigurierbarkeit, deren
  Voreinstellung „an" wäre, hätte die Verantwortung still auf die Dienststelle geschoben.
- **Kein periodischer Passwortwechsel, keine Komplexitätsregeln** (Entscheidung 9). Beides erzeugt in
  der Praxis Zettel am Monitor und Helpdesk-Vorgänge, und beides wird in Dienstvereinbarungen
  regelmäßig erfolglos verhandelt. Dass das Produkt es gar nicht erst anbietet, erspart uns diese
  Runde.
- **Sperren statt Löschen als Regelweg** (Entscheidung 11) und der Verweis auf den DSGVO-Pfad statt
  einer harten Kaskade. Das schützt Beschäftigte davor, dass ihre Arbeitsergebnisse mit einem
  Verwaltungsakt verschwinden.
- **Ablaufdatum, Anlagegrund und der dauerhafte Zähler „Konten ohne Ablaufdatum"** (Entscheidung 11)
  machen die Ausnahme sichtbar, statt sie zu verbieten. Der Zähler ist aggregiert und damit
  unproblematisch — das ist genau die Form, in der wir Kennzahlen akzeptieren können.
- **Die Rolle steht nicht im Token, sie kommt aus der Datenbank** (Entscheidung 6), und die
  Erstadministrator-Regel für OIDC entfällt (Entscheidung 5). Beides verkleinert den Kreis derer, die
  unbemerkt Systemverwalter werden können.
- **Sofortige Wirkung von Abmeldung und Sperre** (Entscheidung 7) löst die Zusage aus
  `access-control.md`, „Sitzungsverwaltung", technisch ein statt sie zu behaupten.

---

## 3. Was nicht funktioniert

Jeder Befund mit Schwere, betroffener Entscheidungsnummer im ADR und konkretem Änderungsvorschlag.

### Befund 1 — Fehlversuche wandern in das Nachweisprotokoll, obwohl es dort ausgeschlossen ist

**Schwere: blockierend. Betrifft: Entscheidung 13** (Tabelle, Zeile „Anmeldung und Sitzung":
`LOCAL_LOGIN_FAILED`, `LOCAL_ACCOUNT_LOCKED_AFTER_FAILED_LOGINS`).

`security-and-compliance.md`, Abschnitt „Was ausdrücklich nicht protokolliert wird", sagt wörtlich:

> **Fehlgeschlagene Anmeldungen und abgewiesene Verbindungsversuche** — Sicherheitsereignisse, die in
> das zentrale Sicherheitsmonitoring gehören und nicht in das Nachweisprotokoll. Sie kommen mit der
> SIEM-Anbindung, nicht mit dieser Stufe.

Der ADR kehrt das um („Fehlversuche werden auditiert, weil sie Angriffe sichtbar machen"), ohne den
Widerspruch auch nur zu erwähnen. Die Liste der Ereignisarten ist laut demselben Dokument
**geschlossen** — sie ist der Gegenstand, über den eine Dienstvereinbarung geschlossen wird, und sie
ist der Kern des „Auszugs für die Personalvertretung". Eine geschlossene Liste, die ein Nachtrags-ADR
still erweitert, ist keine geschlossene Liste.

Dazu kommt die Frist: Die Aufbewahrung ist **eine einzige, systemweite** Einstellung mit Untergrenze
1 Jahr, Voreinstellung 3 Jahre, Obergrenze 10 Jahre. Ein Fehlversuch eines Beschäftigten liegt damit
im Regelfall **drei Jahre** in einer bewusst nicht änderbaren Ablage. Das ist für ein vertipptes
Passwort keine angemessene Speicherdauer, und eine kürzere Frist nur für diese Ereignisart ist im
bestehenden Partitionsmodell nicht vorgesehen.

Die konkrete Situation: Eine Kollegin kommt aus dem Urlaub zurück, vertippt sich fünfmal, ihr Konto
wird für 15 Minuten gesperrt. In der Protokollablage stehen danach sechs Einträge mit ihrem Pseudonym
und Minutenzeitstempeln, drei Jahre lang, nicht löschbar. Der Weg „nach Objekt" ist für Konten zwar
gesperrt (`AuditQueryService`, #393) — der Weg **nach Ereignisart mit Zeitraum** ist es nicht. Eine
Abfrage „alle `LOCAL_LOGIN_FAILED` im letzten Quartal" liefert den kompletten Strom mit stabilen
Pseudonymen; die Gruppierung je Pseudonym macht danach jede Tabellenkalkulation, und die Zuordnung
eines Pseudonyms zu einer Person ergibt sich für einen Systemverwalter aus dem Zeitpunkt, zu dem er
selbst das Konto angelegt oder entsperrt hat. Die Zusage „keine Zählung je Person, auch nicht als
Nebenprodukt" ist damit umgangen — nicht durch eine gebaute Funktion, aber durch einen
Rohdatenbestand, den es vorher nicht gab.

**Änderungsvorschlag:** `LOCAL_LOGIN_FAILED` **nicht** in `audit_log` schreiben. Der einzelne
Fehlversuch gehört in das Anwendungslog (technisches Log, kurze Frist, keine Auswertungsoberfläche)
und später in die SIEM-Ausleitung, so wie es die Spezifikation vorsieht. Im Nachweisprotokoll bleibt
allein `LOCAL_ACCOUNT_LOCKED_AFTER_FAILED_LOGINS` — das ist eine Zustandsänderung des Kontos, selten,
und ohne sie wäre eine Sperre nicht erklärbar. Soll der Maintainer die Einzelereignisse dennoch im
Protokoll wollen, dann nicht über einen Nachtrags-ADR, sondern über eine ausdrückliche Änderung von
`security-and-compliance.md` mit (a) einer eigenen, kürzeren Frist für diese Ereignisart, (b) der
Aufnahme in den Auszug für die Personalvertretung und (c) einer technischen Sperre der Abfrage „nach
Ereignisart" für genau diese Art — analog zur bestehenden Abweisung von `objectType = USER_ACCOUNT`.

### Befund 2 — Der Übergabepfad ist ein stiller Weg in private Inhalte einer anderen Person

**Schwere: blockierend. Betrifft: Entscheidung 12** (Übergabe an eine Anbieteridentität), mittelbar
Entscheidung 11 (Sperren).

Der Pfad lautet: Ein `SYSTEM_ADMIN` sperrt ein lokales Konto und schreibt danach `users.issuer` und
`users.subject` auf eine frei eingegebene Anbieteridentität um. Der ADR sagt dazu: „Das Risiko einer
Fehlzuordnung liegt beim Verwalter und ist auditiert; es ist dasselbe Vertrauen, das die Rollenvergabe
an ihn stellt."

Das ist nicht dasselbe Vertrauen. Eine Rollenvergabe gibt Rechte an künftigen Objekten. Diese Übergabe
gibt den **persönlichen Space** des Kontos mitsamt seinen privaten Chats und Artefakten an eine andere
Identität weiter. `security-and-compliance.md`, „1. Sichtbarkeit ist eine Handlung, keine Automatik",
sagt dazu: „**Private Inhalte sind verbindlich unbeobachtet** — in jedem Space, nicht nur in einem
dafür vorgesehenen, und auch gegenüber Systemverwaltung, Revision und Dienststellenleitung." Die
Übergabe ist der erste gebaute Weg, der diese Zusage aushebeln kann, und sie braucht dafür weder einen
Zugriff auf Inhalte noch eine Rechtevergabe — nur einen Schreibvorgang auf zwei Spalten.

Die konkrete Situation, und sie ist nicht konstruiert: Ein Systemverwalter legt sich beim
Identitätsanbieter ein Testkonto an — der ADR nennt diesen Weg sogar selbst („wer das Subject nicht
kennt, kann es beim Anbieter oder in einem Testkonto ablesen"). Er sperrt das lokale Konto eines
Kollegen, übergibt es an das Subject seines Testkontos, meldet sich damit an und liest dessen private
Chats. Der Kollege erfährt nichts: Er wird nicht benachrichtigt, seine Sperre ist am Anmeldeformular
nicht von einem falsch getippten Passwort unterscheidbar (Entscheidung 9), und der Protokolleintrag
`LOCAL_USER_HANDED_OVER` ist für ihn nicht einsehbar — es gibt bewusst keine Sicht „alle Ereignisse,
bei denen Person X betroffen war". Der Vorgang ist protokolliert und trotzdem faktisch unsichtbar.
Derselbe Ablauf entsteht ohne jede böse Absicht durch einen Tippfehler im Subject-Feld, und der ADR
nennt keinen Rückweg: Nach der Übergabe ist das Konto ein OIDC-Konto, `local_credentials` sind
gelöscht, und einen Weg zurück gibt es nicht.

**Änderungsvorschlag:** Vier Ergänzungen zu Entscheidung 12, alle klein:

1. **Vier-Augen-Prinzip.** Die Übergabe verlangt die Freigabe durch zwei verschiedene Systemverwalter
   — dasselbe Muster, das die anlassbezogene Klärung bereits als Datenbank-Constraint umsetzt
   (`security-and-compliance.md`, „Zugriffswege", Stand #393). Der Aufwand ist einmalig, der Vorgang
   ist selten.
2. **Pflicht-`reason`.** Der Protokollsatz sieht das Feld `reason` bereits vor und verlangt es dort,
   „wo ein Anlass verlangt ist". Hier ist einer verlangt.
3. **Unterrichtung der betroffenen Person.** Mail an die bisherige Adresse des lokalen Kontos **und**
   an die Adresse der Zielidentität, zum Zeitpunkt der Übergabe, plus ein Hinweis bei der nächsten
   Anmeldung des übergebenen Kontos („Ihr Konto wurde am … auf die Anbieteridentität … umgestellt; bei
   Fragen wenden Sie sich an …"). Die Mail-Infrastruktur wird in Entscheidung 10 ohnehin gebaut; es
   fehlt nur ein `MailTemplateKey`.
4. **Der Bestätigungsdialog benennt, was mitgeht** — persönlicher Space, Zahl der privaten Chats und
   Artefakte, Mitgliedschaften, Systemrolle —, und der Protokolleintrag hält dieselben Zahlen fest.
   Eine Übergabe, deren Umfang der Handelnde nicht sieht, ist keine bewusste Entscheidung.

Wünschenswert zusätzlich: ein Rückweg (Übergabe auch von einer Anbieteridentität zurück auf ein
lokales Konto) oder ein Rückabwicklungsfenster, damit der Tippfehler nicht endgültig ist.

### Befund 3 — Die Dokumente, die unsere Zusagen tragen, stehen nicht in der Nachzugsliste

**Schwere: blockierend. Betrifft: Abschnitt „Konsequenzen / Neutral"** (letzter Punkt), mittelbar
Entscheidung 13.

Der ADR kündigt an, `docs/features/user-frontends.md` und `docs/features/access-control.md`
nachzuziehen. `docs/features/security-and-compliance.md` fehlt — und genau dort stehen die
geschlossene Ereignisliste, die Tabelle „Was ausdrücklich nicht protokolliert wird", die
Aufbewahrungsfristen, die Liste der vorhandenen und der ausdrücklich nicht vorhandenen Abfragewege,
der Inhalt des „Auszugs für die Personalvertretung" und die Liste der Exporte („Auskunft über die
Datenerhebung"). Der ADR fügt zweiundzwanzig neue Ereignisarten und mindestens sechs neue
personenbeziehbare Felder hinzu (`created_reason`, `failed_login_attempts`, `lockout_until`,
`locked_reason`, `expires_at`, `email_verified_at`, dazu die drei Token-Tabellen), ohne dass eines der
Dokumente, aus denen der Auszug gespeist wird, davon erführe.

Die konkrete Situation: Wir verhandeln die Dienstvereinbarung auf Grundlage des Auszugs. Ein Jahr
später stellen wir bei der jährlichen Vorlage fest, dass die Ereignisliste gewachsen ist und niemand
uns davon unterrichtet hat — genau der Fall, gegen den der jährliche Auszug erfunden wurde („danach
jährlich als Nachweis, dass sich nichts stillschweigend verschoben hat"). Das kostet uns das Vertrauen
in alle übrigen Zusagen des Produkts, auch in die eingehaltenen.

**Änderungsvorschlag:** `security-and-compliance.md` in die Nachzugsliste aufnehmen, mit benannten
Abschnitten: geschlossene Ereignisliste (die neuen `LOCAL_*`-Arten mit Zweck je Art), „Was
ausdrücklich nicht protokolliert wird" (Fehlversuche — je nach Ausgang von Befund 1 entweder bestätigt
oder begründet geändert), die Feldliste des Protokollsatzes, und „Export und Auskunft" um die neuen
personenbeziehbaren Felder. Zusätzlich ein Abnahmekriterium im Sammel-Issue #1543: Der Auszug für die
Personalvertretung ist vollständig, **bevor** die lokale Benutzerverwaltung erstmals eingeschaltet
werden kann. (Nach meiner Durchsicht existiert der Auszug bisher nur als Spezifikation, nicht als
Funktion — das ist hinnehmbar, solange er vor dem Einschalten wenigstens als Dokument vorliegt.)

### Befund 4 — Freitext und direkt identifizierende Werte in einer unveränderlichen Ablage

**Schwere: wichtig. Betrifft: Entscheidung 13** (`LOCAL_USER_HANDED_OVER` „mit Vorher/Nachher",
`LOCAL_USER_CHANGED`), im Zusammenspiel mit **Entscheidung 3** (`created_reason` als freier Text) und
**Entscheidung 12**.

Die Pseudonymisierung ist die tragende Zusage der Protokollablage: „Beim Löschen eines Kontos entfällt
dieser Eintrag — das Protokoll bleibt unverändert und ist danach nicht mehr auf eine Person
zurückführbar." Diese Zusage hält nur, solange **im Protokollsatz selbst** nichts steht, was eine
Person benennt. `before`/`after` sind laut Spezifikation „eng begrenzt auf das rechtlich Erhebliche
(Rolle, Frist, Sichtbarkeit) — kein vollständiger Objektabzug".

Der ADR durchbricht das an zwei Stellen, ohne es zu bemerken:

- `LOCAL_USER_HANDED_OVER` soll „Vorher/Nachher" tragen. Vorher/Nachher sind hier `issuer` und
  `subject`. Ein IdP-Subject ist in vielen Verzeichnissen kein Zufallswert, sondern die
  Verzeichniskennung, der Anmeldename oder die E-Mail-Adresse. Damit stünde ein direkt
  identifizierendes Merkmal dauerhaft und unlöschbar im Protokoll — auch nach der Kontolöschung, die
  nur die Pseudonymzuordnung entfernt.
- `LOCAL_USER_CHANGED` würde nach demselben Muster den geänderten `created_reason` tragen, also
  **freien Text über eine Person**, geschrieben von einem Verwalter, unlöschbar, drei Jahre.

**Änderungsvorschlag:** Entscheidung 13 um eine ausdrückliche Regel ergänzen: In den
`LOCAL_*`-Ereignissen erscheinen **keine E-Mail-Adressen, keine Namen, keine IdP-Subjects und kein
Freitext**. `LOCAL_USER_HANDED_OVER` trägt die Anbieter-ID (`oidc_providers.id`, ein interner
Schlüssel) und die Tatsache der Umschreibung, nicht das Subject. `LOCAL_USER_CHANGED` trägt die
geänderten **Feldnamen**, nicht deren Werte („`created_reason` geändert", nicht der Text). Der ADR
sieht bereits einen Test nach dem Muster von qnops `LogPrivacyTest` für Geheimnisse im Log vor —
derselbe Test prüft diese Regel für den Protokollsatz mit.

### Befund 5 — „Letzte Anmeldung" ist in Wahrheit „letzte Aktivität", und sie steht in einer sortierbaren Liste

**Schwere: wichtig. Betrifft: Entscheidung 13** (Begründung „`users.last_login_at` genügt der
Nachweisführung") und die Ausgestaltung in **Entscheidung 11** / Issue #1541.

Der ADR begründet den Verzicht auf ein Anmeldeprotokoll damit, dass `users.last_login_at` der
Nachweisführung genüge. Diese Tatsachenbehauptung trifft so nicht zu. In
`backend/src/main/java/io/opaa/auth/UserService.java` wird das Feld nicht bei der Anmeldung
geschrieben, sondern in `updateExistingUser`, das über `UserProvisioningFilter` →
`provisionFromToken` bei **jeder** Anfrage läuft und nur durch eine Schwelle von fünf Minuten
gedrosselt ist. `last_login_at` ist damit ein fortlaufend nachgeführter **Aktivitätszeitstempel mit
Fünf-Minuten-Auflösung**, kein Anmeldezeitpunkt. Wer die Spalte liest, sieht nicht „hat sich heute
früh angemeldet", sondern „war vor sieben Minuten zuletzt am System".

Issue #1541 stellt dieses Feld als Spalte „letzte Anmeldung" in eine Tabelle mit Suche, Filter,
Sortierung und Paging — und lässt ausdrücklich offen, ob die Seite nur lokale Konten oder **alle**
Konten einschließlich der OIDC-Konten listet („Vorschlag alle … Entscheidung im PR mit UX-Review").
Fällt diese Entscheidung auf „alle", entsteht für jede Beschäftigte eine nach Aktivität sortierbare
Zeile in der Verwaltungsoberfläche. `security-and-compliance.md` schließt „Erfolgreiche Anmeldungen
und Sitzungsverläufe" als „reines Anwesenheitsmerkmal" aus dem Protokoll aus — und dieselbe Aussage
entsteht hier auf einem anderen Weg, komfortabler als im Protokoll, denn sie ist sortierbar.

Die konkrete Situation: Eine Referatsleitung mit Systemverwalterrechten sortiert die Liste nach
„letzte Anmeldung" und sieht, wer morgens um 7:05 und wer um 9:40 zuletzt aktiv war. Das ist keine
Funktion, die jemand gebaut hat, um sie so zu benutzen; sie fällt als Nebenprodukt an, und genau davor
warnt unsere Erfahrung.

**Änderungsvorschlag:** Drei Punkte, alle im ADR zu entscheiden statt im PR:

1. **Der ADR entscheidet, dass die Kontenliste nur lokale Konten führt.** Der Bedarf, für den die
   Seite gebaut wird (lokale Konten befristen und prüfen), verlangt nichts anderes. Ein fehlender
   Rollen-Aufrufer für OIDC-Konten ist ein eigenes Thema und rechtfertigt keine Beschäftigtenliste.
2. **Kein exakter Zeitstempel in der Verwaltungsansicht.** Ausgabe als Klasse — „nie genutzt",
   „länger als 90 Tage nicht genutzt", „aktiv" — und ein Filter „seit mehr als 90 Tagen ungenutzt".
   Das erfüllt den Zweck (schlafende Konten finden und befristen) vollständig und erzeugt kein
   Anwesenheitsmerkmal. Keine Sortierung nach dem exakten Wert.
3. **Die Begründung in Entscheidung 13 korrigieren.** Sie soll nicht behaupten, das Feld belege eine
   Anmeldung. Wenn ein Anmeldenachweis fachlich gebraucht wird, ist das eine eigene Entscheidung — und
   eine, über die wir reden müssten.

### Befund 6 — Wer gesperrt wird, erfährt es nicht und kommt nicht mehr an die Selbsthilfe

**Schwere: wichtig. Betrifft: Entscheidung 9** („ein gesperrtes Konto antwortet auf Login identisch
zum falschen Passwort, der Zustand ist nur in der Verwaltung sichtbar") und **Entscheidung 11**
(„gesperrte, abgelaufene und eingeladene Konten erhalten keine Mail").

`access-control.md`, „Erzwungene Neuanmeldung", formuliert den Maßstab selbst: „Er ist ein
Verwaltungsakt gegenüber der betroffenen Person und kein stiller Eingriff: Wer neu anmelden muss,
erfährt beim nächsten Aufruf, dass und warum." Für laufende Sitzungen löst der ADR das sauber (die
Marker `account_locked`, `session_revoked`, `local_accounts_disabled` in Entscheidung 8). Für alles,
was zwischen zwei Sitzungen passiert, löst er es nicht.

Zwei Ausprägungen, beide mit einer konkreten Situation:

- **Fehlversuchssperre schneidet die Selbsthilfe ab.** Der abgeleitete Zustand `LOCKED` umfasst laut
  Entscheidung 3 ausdrücklich auch `lockout_until` in der Zukunft. Entscheidung 11 schließt gesperrte
  Konten vom Versand der „Passwort vergessen"-Mail aus. Also: Eine Kollegin hat ihr Passwort
  vergessen, probiert fünfmal, ist 15 Minuten gesperrt, klickt auf „Passwort vergessen", bekommt ein
  204 und **keine Mail** — ohne jede Erklärung. Sie wiederholt das, wird durch das Rate-Limit
  (3/3600 s je Adresse) zusätzlich ausgebremst, hält das System für kaputt und ruft den Helpdesk an.
  Das Zurücksetzen des Passworts ist genau die richtige Antwort auf „ich habe es vergessen"; es
  auszuschließen kehrt den Zweck um.
- **Administrative Sperre ist nicht erkennbar.** Wer gesperrt wurde, während er nicht angemeldet war,
  gibt sein korrektes Passwort ein und erhält dieselbe Meldung wie bei einem Tippfehler. Er erfährt
  weder, dass noch warum.

**Änderungsvorschlag:**

1. Nur `locked_at` (administrative Sperre) unterdrückt die Rücksetz-Mail; `lockout_until`
   (Fehlversuche) unterdrückt sie **nicht**. Ein erfolgreiches Zurücksetzen löscht Zähler und
   Fehlversuchssperre.
2. Nach **korrekt** eingegebenem Passwort darf die Antwort den Zustand benennen („Ihr Konto ist
   gesperrt / abgelaufen — bitte wenden Sie sich an …"). Das ist kein Aufzählungskanal: Wer das
   Passwort kennt, weiß bereits, dass das Konto existiert. Bei falschem Passwort bleibt die Antwort
   unverändert ununterscheidbar.
3. Benachrichtigungsmail bei **administrativer Sperre, Entsperrung, administrativem Zurücksetzen und
   bevorstehendem Ablauf** (z. B. 14 Tage vorher). Vier weitere `MailTemplateKey`-Einträge; die
   Infrastruktur steht in Entscheidung 10 ohnehin.

### Befund 7 — Der erzwungene Passwortwechsel sagt „dass", nicht „warum"

**Schwere: wichtig. Betrifft: Entscheidung 8** (`PasswordChangeRequiredFilter`, 403
`PASSWORD_CHANGE_REQUIRED`), mittelbar Entscheidung 3 (`password_change_required`).

Der Filter weist jede Anfrage außerhalb von `/api/v1/auth/local/` mit einem Fehlercode ab, der nur den
Zustand nennt. Die drei Anlässe, die ihn auslösen können (Erstpasswort des Seeds, erzeugtes
Anfangspasswort, administratives Zurücksetzen — in Entscheidung 3 selbst aufgezählt), sind für die
betroffene Person nicht unterscheidbar. Für jemanden, der morgens plötzlich sein Passwort ändern muss,
ist das der Unterschied zwischen „das war der geplante Ablauf meiner Einrichtung" und „jemand hat an
meinem Konto etwas getan, und ich weiß nicht was".

**Änderungsvorschlag:** `local_credentials` erhält neben `password_change_required` ein
`password_change_reason` (`INITIAL` | `ADMIN_RESET` | `SECURITY`); der Fehlercode trägt ihn, und die
Oberfläche zeigt ihn als Klartextsatz. Dieselbe Ergänzung für den Marker `session_revoked` aus
Entscheidung 8: Er sollte den Anlass mitführen (`admin_lock`, `password_changed`,
`local_accounts_disabled`), sonst ist die Zusage „dass und warum" nur zur Hälfte eingelöst.

### Befund 8 — Der Anlagegrund ist ein Freitextfeld über einen Menschen, ohne jede Regel

**Schwere: wichtig. Betrifft: Entscheidung 3** (`created_reason`, „Anlagegrund, freier Text") und
**Entscheidung 11** (Anlegen „mit … Anlagegrund").

Der ADR sagt über dieses Feld genau vier Wörter. Keine Längenbegrenzung, keine Zweckbindung, keine
Aussage darüber, wer es liest, keine darüber, ob die betroffene Person es je zu sehen bekommt, und
keine darüber, ob es in die Selbstauskunft gehört. Issue #1541 zeigt es gekürzt in der Tabelle und
vollständig im Bearbeitungsdialog — für jeden Systemverwalter.

Die konkrete Situation: Ein Verwalter legt ein Konto an und schreibt in den Anlagegrund, was ihm für
die spätere Befristung nützlich erscheint — „Krankheitsvertretung für Frau M., befristet bis
Wiedereintritt", „auf Weisung der Amtsleitung nach dem Vorfall im Mai", „externe Beraterin, Vertrag
Projekt X". Nichts davon ist böse gemeint, jedes davon ist eine Angabe über einen Menschen, die dort
nicht hingehört, jedes ist für alle Systemverwalter sichtbar, und die betroffene Person erfährt nie,
was über sie im System steht. Mit Befund 4 zusammen landet dieser Text zusätzlich unlöschbar im
Protokoll.

**Änderungsvorschlag:** Entscheidung 3 präzisiert das Feld:

- **Zweckbindung im ADR und als Hilfetext im Formular:** dienstlicher Anlass der Kontoanlage und Grund
  der Befristung. Ausdrücklich unzulässig sind Angaben zu Gesundheit, Vertrags- und
  Beschäftigungsverhältnis, Leistung und Disziplinarsachverhalten sowie Angaben über Dritte.
- **Längenbegrenzung** (Vorschlag: 200 Zeichen) — sie ist die einzige technisch durchsetzbare
  Schranke gegen die Aktennotiz.
- **Das Feld ist für die betroffene Person in ihren eigenen Einstellungen sichtbar** und Teil der
  Selbstauskunft. Was über jemanden gespeichert wird, muss er sehen können; das ist die billigste und
  wirksamste Disziplinierung eines Freitextfelds.
- **Nie als Wert im Protokoll** (siehe Befund 4).

### Befund 9 — Die Token-Tabellen sind ein Sitzungsjournal ohne Aufbewahrungszusage

**Schwere: wichtig. Betrifft: Entscheidung 3** (drei Token-Tabellen) und **Entscheidung 7**
(Refresh-Familien, Rotation, `opaa.auth.local.refresh-token-ttl`).

`local_refresh_tokens` hält je Sitzung Familie, Ausstellungszeitpunkt, Ablauf, Widerruf mit Grund und
Nachfolger. Bei 15-Minuten-Access-Tokens entsteht pro aktiver Arbeitsstunde eine Handvoll Zeilen mit
Zeitstempeln — ein lückenloses Journal darüber, **wann** jemand am System war. Genau das, was
`security-and-compliance.md` als „Sitzungsverläufe" aus dem Protokoll ausschließt, entsteht hier in
der Betriebsdatenbank. `local_action_tokens` hält zusätzlich fest, wann jemand ein Zurücksetzen
angefordert und den Link eingelöst hat.

Der ADR sagt dazu nur: „Abgelaufene Zeilen räumt der bestehende Scheduler täglich ab." Das ist keine
Aufbewahrungszusage. Die Lebensdauer hängt an einer frei konfigurierbaren Eigenschaft
(`refresh-token-ttl`, Vorgabe 7 Tage) ohne Obergrenze — wer sie auf 365 Tage stellt, hat ein
Jahresjournal. Und „abgelaufen" räumt nicht auf, was rotiert oder widerrufen wurde und noch nicht
abgelaufen ist.

**Änderungsvorschlag:** In Entscheidung 7 aufnehmen: (a) eine **harte Obergrenze** für
`refresh-token-ttl` (Vorschlag: 30 Tage), validiert wie das Secret in Entscheidung 6; (b) Zeilen
werden spätestens sieben Tage nach Ablauf **oder Widerruf** gelöscht, nicht nur die abgelaufenen; (c)
`revoked_reason` ist ein Enum, kein Freitext; (d) die ausdrückliche Zusage, dass es **keine Oberfläche
und keine Schnittstelle gibt, die dieses Journal je Person ausgibt** — mit der einen Ausnahme der
Selbstauskunft „Übersicht der eigenen Sitzungen", die `access-control.md` der betroffenen Person
selbst zusichert („für niemanden sonst sichtbar").

### Befund 10 — `LOCAL_SESSION_REVOKED` ist nicht abgegrenzt

**Schwere: wichtig. Betrifft: Entscheidung 13** (Zeile „Anmeldung und Sitzung") in Verbindung mit
**Entscheidung 7** (Logout, Widerruf).

Der ADR sagt nicht, wann dieses Ereignis entsteht. Wird es auch bei der **eigenen Abmeldung**
geschrieben, dann steht im Protokoll je Person und Tag der Zeitpunkt des Feierabends — mit drei Jahren
Frist. Zusammen mit Befund 5 („letzte Aktivität") ergäbe das ein Anwesenheitsbild, das der ADR an
anderer Stelle ausdrücklich vermeiden will.

**Änderungsvorschlag:** In Entscheidung 13 klarstellen: Die **eigene Abmeldung** und der routinemäßige
Ablauf eines Tokens erzeugen **kein** Protokollereignis. `LOCAL_SESSION_REVOKED` entsteht
ausschließlich beim fremdveranlassten Widerruf (administrative Sperre, administratives Zurücksetzen,
Abschalten der lokalen Verwaltung, Rotation des Signaturgeheimnisses) — also dort, wo ein
Verwaltungsakt gegenüber einer Person vorliegt, den sie nachvollziehen können muss.

### Befund 11 — Das Notanker-Konto ist ein Sammelkonto mit Vollrechten

**Schwere: Hinweis. Betrifft: Entscheidung 5** (Seed, Anzeigename „Systemverwaltung",
`OPAA_INITIAL_ADMIN_EMAIL`, `OPAA_LOCAL_ADMIN_RESET=force`).

Der Beschluss vom 10.09.2026 sagt ausdrücklich: „Mehrere Systemverwalter mit je eigenem Konto, keine
Sammelkonten." Der Seed legt jedoch ein Konto mit dem Anzeigenamen „Systemverwaltung" an, dessen
Adresse in der Praxis häufig ein Funktionspostfach sein wird (`it-betrieb@…`), und das Handbuch soll
laut ADR sagen: „nutzen, denn es ist der Notanker". Wenn drei Personen dieses Konto benutzen,
erscheinen alle Verwaltungshandlungen unter einem Pseudonym, und die Zurechenbarkeit von
Verwaltungsakten ist dahin. Das ist kein Randthema für uns, sondern ein **Schutz der Beschäftigten**:
`security-and-compliance.md` begründet die Protokollpflicht für Verwaltungsaktionen genau damit, dass
„jeder Übernahme- und Verwaltungsakt sichtbar sein" muss.

**Änderungsvorschlag:** Entscheidung 5 (und das Handbuchkapitel aus #1543) hält fest: Nach der
Einrichtung werden **persönliche Verwalterkonten** angelegt; das Seed-Konto wird danach gesperrt und
befristet und dient nur noch als Notanker. Für dieses eine Konto ist die **erfolgreiche Anmeldung** zu
auditieren und löst eine Mail an alle übrigen Systemverwalter aus. Das ist ausdrücklich **keine**
Ausnahme von Befund 1: Es geht um ein privilegiertes Notfallzugangsmittel, nicht um die Tätigkeit
einer Beschäftigten. Diese Ausnahme trägt der Personalrat mit — sie liegt in unserem Interesse.

### Befund 12 — Der Fehlversuchszähler darf keine Kennzahl werden

**Schwere: Hinweis. Betrifft: Entscheidung 9** (`failed_login_attempts`, „Zähler atomar") und
Entscheidung 3.

Der ADR sagt, dass „Entsperren Zähler zurücksetzt", aber nicht, dass eine **erfolgreiche Anmeldung**
das tut. Wird der Zähler nicht zurückgesetzt, summiert er über Monate — und ein Wert wie „37" neben
einem Namen ist im Zweifel genau die Zahl, die jemand als Kennzahl liest.

**Änderungsvorschlag:** Ausdrücklich zusichern: `failed_login_attempts` geht bei jeder erfolgreichen
Anmeldung, bei jedem erfolgreichen Zurücksetzen und beim Entsperren auf null; der Wert wird **weder
über die API noch in der Oberfläche ausgegeben**, es gibt keine Historie. Die Verwaltung zeigt nur den
Zustand „Gesperrt (Fehlversuche)", wie in #1541 vorgesehen — der Grund ist nötig, die Zahl nicht.

### Befund 13 — Selbstregistrierung ohne Grenze und ohne Befristung

**Schwere: Hinweis. Betrifft: Entscheidung 11** (Selbstregistrierung), Entscheidung 4 (Schalter).

Die Voreinstellung „aus" ist richtig, und der Schalter ist auditiert — gut. Ist er an, entstehen
jedoch Konten ohne Anlagegrund und ohne Ablaufdatum, an jedem Verzeichnis vorbei, für jede beliebige
E-Mail-Adresse. Das ist weniger ein Mitbestimmungs- als ein Ordnungsproblem, betrifft uns aber
mittelbar: Der Zähler „Konten ohne Ablaufdatum", den der ADR als sichtbare Form der Auflage anbietet,
läuft dann strukturell nach oben und wird als Dauerwarnung ignoriert.

**Änderungsvorschlag:** Selbstregistrierte Konten erhalten verpflichtend ein Ablaufdatum
(konfigurierbare Vorgabe, z. B. 90 Tage) und den festen Anlagegrund „Selbstregistrierung"; optional
eine Domänen-Allowlist für zulässige Adressen. Das Einschalten des Schalters gehört als
zustimmungspflichtiger Punkt in die Dienstvereinbarung.

### Befund 14 — Die Kontenliste ist nicht gegen Export abgesichert

**Schwere: Hinweis. Betrifft: Entscheidung 11** (Verwaltungsansicht) / Issue #1541.

Die Zusammenstellung aus Name, Adresse, Rolle, Zustand, Sperrgrund, Ablauf, letzter Aktivität,
Anlagegrund und „Passwortwechsel ausstehend" ist in der Summe eine kleine Personalübersicht. #1541
führt „Massenaktionen, Export" unter „Außerhalb des Umfangs" — das ist eine Terminplanung, keine
Zusage.

**Änderungsvorschlag:** Der ADR schließt Export und Massenabruf der Kontenliste ausdrücklich aus
(keine CSV-/Excel-Ausgabe, keine unbegrenzte API-Seitengröße) und benennt das als dauerhafte
Eigenschaft, nicht als Umfangsentscheidung eines einzelnen Issues.

---

## 4. Die eine Änderung

Wenn ich genau eine Sache ändern dürfte: **Kein stiller Eingriff in ein Konto.**

Jeder Verwaltungsakt an einem Konto — Sperre, Entsperrung, administratives Zurücksetzen,
bevorstehender und eingetretener Ablauf, Übergabe an eine Anbieteridentität — wird der betroffenen
Person mitgeteilt: per Mail zum Zeitpunkt der Handlung und als Hinweis mit Grund bei der nächsten
Anmeldung. Die Übergabe verlangt zusätzlich vier Augen und einen Pflichtgrund.

Das ist keine neue Anforderung, sondern die Anwendung eines Satzes, den `access-control.md` bereits
enthält („Er ist ein Verwaltungsakt gegenüber der betroffenen Person und kein stiller Eingriff: Wer
neu anmelden muss, erfährt beim nächsten Aufruf, dass und warum"), auf die Vorgänge, die dieser ADR
neu schafft. Es kostet vier Mail-Vorlagen und ein Enum-Feld — die Mail-Infrastruktur wird in
Entscheidung 10 ohnehin gebaut. Und es erledigt die Befunde 2, 6 und 7 in einem Zug.

---

## 5. Was ich nicht beurteilen kann

- **Die kryptografische und sicherheitstechnische Angemessenheit.** HS256 mit HKDF-abgeleitetem
  Schlüssel, BCrypt mit Kostenfaktor 12, die konkreten Rate-Limit-Werte, die Wirksamkeit der
  Wiederverwendungserkennung — das gehört dem Betriebsverantwortlichen und dem Code Reviewer, nicht
  mir. Ich stelle nur fest, dass die Sicherheitsmaßnahmen ihrerseits personenbeziehbare Daten
  erzeugen, und bewerte diese.
- **Die rechtliche Einordnung.** Ob und in welchem Umfang hier der Mitbestimmungstatbestand
  „technische Einrichtung, die zur Überwachung von Verhalten oder Leistung geeignet ist" greift,
  entscheidet sich nach dem Personalvertretungsrecht des Bundes oder des jeweiligen Landes — die
  Regelungen unterscheiden sich, und die einschlägige Norm hängt von der einführenden Behörde ab. Ich
  benenne die Frage; ihre Beantwortung gehört in die rechtliche Prüfung der Dienststelle. Insbesondere
  die Frage, ob die Fehlversuchsereignisse aus Befund 1 für sich genommen schon die Eignung zur
  Verhaltenskontrolle begründen, kann ich nicht abschließend beurteilen — ich halte sie für
  naheliegend.
- **Ob die Sub-Issues die Zusagen des ADR tatsächlich abbilden.** Ich habe #1541 stichprobenartig
  gelesen und dort die offene Frage „alle Konten oder nur lokale" gefunden (Befund 5); die übrigen
  zwölf Issues habe ich nicht geprüft. Der ADR ist die Grundlage — was daraus im Code wird, prüft das
  Review.
- **Den tatsächlichen Umfang des Beschlusses vom 10.09.2026** über die im ADR zitierten Punkte hinaus.
  Ich beurteile die Umsetzung, nicht den Beschluss.
- **Den Stand des „Auszugs für die Personalvertretung".** Nach meiner Durchsicht existiert er als
  Spezifikation, nicht als Funktion. Ob das für den geplanten Einführungszeitpunkt reicht, kann ich
  nicht beurteilen; dass er vor dem Einschalten der lokalen Verwaltung wenigstens als Dokument
  vorliegen muss, halte ich für zwingend.

---

## 6. Bedingungen für eine Zustimmung

Diese Punkte müssten in einer Dienstvereinbarung stehen — und die mit (P) markierten müssen zusätzlich
im Produkt so gebaut sein, dass die Regelung überprüfbar ist. Eine Zusage, die das Produkt nicht
abbildet, ist für uns eine Absichtserklärung.

1. **Kein Anmeldeprotokoll je Person.** Erfolgreiche Anmeldungen werden nicht einzeln aufgezeichnet —
   die Entscheidung des ADR wird als Regelung festgeschrieben und ist nicht durch Konfiguration
   umkehrbar (P). Einzige Ausnahme: das Notanker-Konto der Systemverwaltung (Befund 11).
2. **Einzelne Fehlversuche gehören nicht in das Nachweisprotokoll** (Befund 1). Im Protokoll steht
   allein die Kontosperre als Zustandsänderung. Einzelereignisse liegen im technischen Log mit einer
   Frist von höchstens 90 Tagen (P). Wird davon abgewichen, dann nur mit ausdrücklicher Änderung von
   `security-and-compliance.md`, eigener kürzerer Frist und technischer Sperre der Abfrage nach dieser
   Ereignisart.
3. **Kein Personenbezug im Klartext im Protokoll** (Befund 4): keine Namen, keine E-Mail-Adressen,
   keine IdP-Subjects, kein Freitext in `before`/`after` — automatisiert geprüft (P).
4. **Keine Anwesenheitsanzeige in der Verwaltungsoberfläche** (Befund 5): „letzte Aktivität" nur als
   Klasse, keine Sortierung nach dem exakten Wert, Kontenliste beschränkt auf lokale Konten, kein
   Export (P, mit Befund 14).
5. **Kein stiller Eingriff in ein Konto** (Abschnitt 4): Sperre, Entsperrung, Zurücksetzen, Ablauf und
   Übergabe werden der betroffenen Person mitgeteilt, mit Grund (P).
6. **Die Übergabe an eine Anbieteridentität verlangt vier Augen, einen dokumentierten Anlass und die
   Unterrichtung der betroffenen Person** (Befund 2). Der Bestätigungsdialog benennt, welche privaten
   Inhalte mitgehen (P).
7. **Zweckbindung des Anlagegrunds** (Befund 8): dienstlicher Anlass und Befristungsgrund,
   längenbegrenzt, für die betroffene Person einsehbar, keine Angaben zu Gesundheit,
   Beschäftigungsverhältnis, Leistung oder Disziplinarsachverhalten (P für Länge, Sichtbarkeit und
   Hilfetext; inhaltlich als Regelung).
8. **Aufbewahrung der Sitzungs- und Aktionsdaten** (Befund 9): harte Obergrenze der Token-Lebensdauer,
   Löschung spätestens sieben Tage nach Ablauf oder Widerruf, kein Auswertungsweg je Person außer der
   Selbstauskunft der betroffenen Person selbst (P).
9. **Der Auszug für die Personalvertretung ist vor dem Einschalten der lokalen Benutzerverwaltung
   vollständig** und wird jährlich vorgelegt; er führt alle neuen Ereignisarten und alle neuen
   personenbeziehbaren Felder mit Zweck und Frist (Befund 3).
10. **Systemverwalter bleiben ohne Inhaltszugriff.** Die Zusage „private Inhalte sind verbindlich
    unbeobachtet, auch gegenüber der Systemverwaltung" gilt unverändert; die neue
    Benutzerverwaltungsseite und der Übergabepfad ändern daran nichts (P, siehe Befund 2).
11. **Der Schalter der lokalen Verwaltung und der Schalter der Selbstregistrierung sind
    zustimmungspflichtig.** Ihr Einschalten ist bereits auditiert (Entscheidung 4) — das ist die
    richtige technische Grundlage; die Regelung ergänzt, dass der Personalrat vorher unterrichtet
    wird.
12. **Persönliche Verwalterkonten statt Sammelkonto** (Befund 11); das Seed-Konto wird nach der
    Einrichtung gesperrt und befristet, seine Benutzung ist nachvollziehbar (P).

---

### Schlussbemerkung

Der ADR ist an mehreren Stellen erkennbar mit unserer Perspektive geschrieben worden, und das ist
selten genug, um es zu sagen. Die Befunde oben sind deshalb überwiegend Präzisierungen, nicht
Gegenpositionen: Was hier fehlt, ist an den meisten Stellen nicht eine andere Entscheidung, sondern
eine ausgesprochene. Die Ausnahmen sind Befund 1 und Befund 2 — dort ist eine Entscheidung getroffen
worden, die eine bestehende Zusage berührt, ohne dass die Zusage angefasst wurde. Diese beiden sind
der Grund, warum das Urteil „mit Auflagen" und nicht „tragfähig" lautet.
