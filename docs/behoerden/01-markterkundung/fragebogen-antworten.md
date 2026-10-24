---
stand: 2026-10-24
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-23
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-23
---

# Antworten auf typische Fragebögen

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

**Gilt für:** Behörden, die in einer Markterkundung Fragebögen zu einem KI-Assistenten auswerten
oder selbst beantworten.

Fragebögen in der Markterkundung fragen meist dasselbe: was die Software kann, wie sie betrieben
wird und wie es um Datenschutz, Sicherheit, Barrierefreiheit und digitale Souveränität steht. Die
Antworten unten sind kurz und verweisen auf den Nachweis. Werte wie Fristen oder Datenflüsse stehen
nur in den Faktenblättern der jeweiligen Version.

## Was OPAA liefert

**Funktion**

| Frage | Antwort | Nachweis |
|---|---|---|
| Was leistet die Software? | beantwortet Fragen aus Dokumenten der Behörde mit Fundstellen | [Belege](../../handbuch/spaces/suche.md#7-belege-prüfen-und-fundstellen-bilden) |
| Welche Quellen und Formate? | Dateiablagen, Confluence, Objektspeicher, Feeds und weitere; Upload | [Konnektoren](../../handbuch/arbeitsmittel/wissensbibliotheken/konnektoren/README.md#3-die-konnektoren-im-überblick), [Formate](../../handbuch/arbeitsmittel/wissensbibliotheken/formate/README.md#zugelassene-formate) |
| Werden Rechte der Quelle übernommen? | nein; Leserechte werden in OPAA je Bibliothek vergeben und in jeder Suche geprüft | [Berechtigungen](../../handbuch/konten-und-rechte/berechtigungen.md#1-der-aufbau-in-vier-objekten) |
| Spracheingabe? | ja, mit eigenem Transkriptionsdienst | [Spracheingabe](../../handbuch/betrieb/modelle.md#spracheingabe-einschalten) |

**Betrieb und Schnittstellen**

| Frage | Antwort | Nachweis |
|---|---|---|
| Wo läuft die Software? | im Rechenzentrum der Behörde oder eines Dienstleisters, mit Docker Compose oder Kubernetes | [Bausteine](../../handbuch/betrieb/README.md#bausteine) |
| Welche Modelle? | jedes Modell hinter einer OpenAI-kompatiblen Schnittstelle, auch im eigenen Haus | [Modelle](../../handbuch/betrieb/modelle.md#llm-anbieter) |
| Betrieb ohne Internet? | ja, wenn Images und Modelle im Haus stehen; ein nächtlicher Lauf ohne Weg nach außen belegt Anmeldung, Aufnahme von Dokumenten in mehreren Formaten und Antwort mit Fundstelle und erkennt Namensauflösungen und Browser-Abrufe nach außen, Verbindungen an feste IP-Adressen nur über das Log | [Betrieb ohne Internet](../../handbuch/betrieb/netz-und-sicherheit.md#betrieb-ohne-internet), {{faktenblatt:datenfluesse}} |
| Ausfallsicherheit? | mehrere Backend-Instanzen; Update ohne Ausfall, soweit die neue Version es zulässt | [Mehrere Backend-Instanzen](../../handbuch/betrieb/mehrere-instanzen.md#update-ohne-ausfall) |
| Anmeldung? | OIDC-Identitätsanbieter oder lokale Konten | [Authentifizierung](../../handbuch/betrieb/authentifizierung.md#anmeldewege-im-betriebsmodus-oidc) |
| Anbindung anderer KI-Werkzeuge? | MCP-Server mit persönlichen Zugangstokens, abschaltbar | [Externe KI-Werkzeuge](../../handbuch/spaces/fremdzugaenge.md#8-was-ein-externes-ki-werkzeug-erreicht) |

**Datenschutz, Sicherheit, Barrierefreiheit**

| Frage | Antwort | Nachweis |
|---|---|---|
| Welche Daten, wie lange? | je Version beschrieben | {{faktenblatt:datenkatalog}}, {{faktenblatt:fristen}} |
| Wer sieht was über wen? | je Rolle beschrieben; Protokoll pseudonymisiert | {{faktenblatt:rollensicht}}, [Pseudonymisierung](../../handbuch/betrieb/nachvollziehbarkeit.md#pseudonymisierung-im-protokoll) |
| Vorlagen für den Datenschutz? | DSFA, VVT, TOM, Löschkonzept und weitere | [Datenschutz](../04-datenschutz/README.md) |
| Sicherheitsnachweise? | Härtung, SBOM, Provenance, Schwachstellenbehandlung, Meldeweg | [Sicherheitsnachweise](../06-informationssicherheit/sicherheitsnachweise.md) |
| Zertifizierung? | keine; ein Testat wie C5 erhält der Betreiber einer Cloud, nicht die Software | [C5](../06-informationssicherheit/c5-cloud-betrieb.md) |
| KI-Verordnung? | Einstufungshilfe für die Behörde | [Einstufung](../07-ki-verordnung/einstufungshilfe-ki-verordnung.md) |
| Barrierefreiheit? | Maßstab BITV 2.0, laufend automatisch geprüft; ein Audit einer veröffentlichten Version steht aus | [Prüfstand](../08-nutzung/erklaerung-barrierefreiheit.md#prüfstand) |

**Digitale Souveränität**

| Frage | Antwort | Nachweis |
|---|---|---|
| Lizenz und Quellcode? | AGPL-3.0, vollständiger Quellcode öffentlich | [Lizenz](lizenz.md) |
| Wo liegen die Daten? | in der eigenen PostgreSQL-Datenbank und Originalablage | [Originalablage](../../handbuch/betrieb/originalablage.md#die-drei-wege), {{faktenblatt:datenkatalog}} |
| Ist ein Anbieterwechsel möglich? | ja; Software, Daten und Handbuch stehen jedem Betreiber offen | [Handbuch](../../handbuch/README.md) |
| Fortbestand und Support? | siehe Projektstatus | [Projektstatus](projektstatus.md) |

## Aufgaben der Behörde

1. **Eigene Fragen zuordnen** und die Antworten mit der Version vergleichen, die geprüft wird.
2. **Nachweise öffnen,** vor allem bei Fragen, die später Muss-Anforderungen werden.
3. **Souveränität bewerten,** etwa nach dem Kriterienkatalog C3A des BSI
   ([Zuschlagskriterien](../03-vergabe/zuschlagskriterien.md#digitale-souveränität)).
4. **Lücken festhalten** für Teststellung und Leistungsbeschreibung.

## Gut zu wissen

- Fragen zu Reaktionszeiten, Preisen oder Verfügbarkeit beantwortet ein Dienstleister, nicht das
  Projekt.

## Zum Nachlesen

**Arbeitshilfen** für die Projektleitung:

- [Kriterienkatalog C3A](https://www.bsi.bund.de/DE/Themen/Unternehmen-und-Organisationen/Informationen-und-Empfehlungen/Empfehlungen-nach-Angriffszielen/Cloud-Computing/C3A/C3A_node.html)
  beim BSI: Kriterien für digitale Souveränität in der Beschaffung.
- [Erfüllungsmatrix](../03-vergabe/erfuellungsmatrix.md): Nachweise je Anforderung.

**Rechtsgrundlagen** für die Vergabestelle:

| Worum es geht | Fundstelle |
|---|---|
| Markterkundung oberhalb der EU-Schwellenwerte | § 28 [VgV](https://www.gesetze-im-internet.de/vgv_2016/__28.html) |
| Markterkundung unterhalb der EU-Schwellenwerte | § 20 UVgO bzw. Landesrecht; geltende Fassung zu prüfen |
