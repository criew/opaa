## Zusammenfassung

Kurze Beschreibung der Änderungen.

## Zugehörige Issues

Closes #

## Art der Änderung

- [ ] Bugfix
- [ ] Neues Feature
- [ ] Dokumentation
- [ ] Refactoring
- [ ] CI/Build

## Wirkung auf Daten und Behördenunterlagen

Zutreffendes ankreuzen. Ist mindestens ein Punkt außer „Keine Wirkung“ angekreuzt, bekommt der PR das Label `data-impact` und erscheint in den Release-Notizen unter „Datenrelevante Änderungen“. Der Check „Datenwirkung“ schlägt fehl, wenn das Label dann fehlt oder wenn der PR Dateien aus `.github/data-impact-paths.txt` ändert, ohne Label und ohne angekreuztes „Keine Wirkung“ (`docs/releases.md`, Abschnitt „Datenwirkung kennzeichnen“).

- [ ] Neue oder geänderte personenbezogene Daten oder Protokollereignisse
- [ ] Neue Sichtbarkeit: Daten werden für weitere Rollen oder Personen sichtbar
- [ ] Neue Auswertung oder neuer Export, auch von bereits gespeicherten Daten
- [ ] Geänderte Aufbewahrungs- oder Löschfrist
- [ ] Neuer externer Datenfluss (neuer Dienst, neues Ziel ausgehender Verbindungen)
- [ ] Geänderte Voreinstellung, geändertes Standardmodell oder geänderter System-Prompt
- [ ] Geänderte KI-Einstufung (Zweck oder Einsatzbereich im Sinne der KI-Verordnung)
- [ ] Keine Wirkung auf Daten, Sichtbarkeit, Auswertung, Datenflüsse oder Voreinstellungen

**Satz für die Release-Notizen** (Alltagssprache, bei „Keine Wirkung“ entfällt er):

> Beispiel: Administratoren sehen im Protokoll jetzt auch, wer eine Bibliothek gelöscht hat.

## Checkliste

- [ ] Tests bestehen lokal
- [ ] Bei Bugfix: Reproduktionsnachweis erbracht — der Test schlägt auf dem fehlerhaften Stand fehl und besteht mit dem Fix (rote Fehlermeldung unten einfügen)
- [ ] Dokumentation aktualisiert (falls zutreffend)
- [ ] Handbuch (`docs/handbuch/`) nachgezogen oder ausdrücklich nicht betroffen (bei Änderungen an sichtbarem Verhalten oder Konfiguration)
- [ ] Helm-Chart (`deploy/helm/opaa`) und Compose-Setup nachgezogen oder ausdrücklich nicht betroffen (bei Änderungen an Konfiguration oder Laufzeitverhalten, ADR-0042)
- [ ] Barrierefreiheit nach `docs/design/accessibility.md` geprüft (bei UI-Änderungen; sonst entfällt)
- [ ] Keine Secrets oder Anmeldeinformationen committet
- [ ] Commit-Nachrichten folgen Conventional Commits
- [ ] CLA unterzeichnet (Erstbeitragende: Unterzeichnungskommentar unten posten, oder wurde bereits unterzeichnet)

## KI-Agenten-Offenlegung

- [ ] Dieser PR wurde von einem Menschen verfasst
- [ ] Dieser PR wurde von einem KI-Agenten verfasst (angeben: _______)
- [ ] Dieser PR wurde von Mensch + KI-Agent gemeinsam verfasst
