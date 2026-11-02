# Die Einführung vorbereiten

> **Entwurf** — gegen den Code geprüft, vom Maintainer noch nicht abgenommen.

> **Wer darf das:** die Systemverwaltung, zusammen mit Datenschutz und Personalvertretung. Ab Werk
> ist alles, was Personen betrifft, entweder aus oder auf den engsten Wert gestellt.

Diese Liste gehört vor den ersten Tag mit echten Personen, nicht danach.

## Klären

- [ ] **Personalvertretung beteiligen.** Sie bekommt einen Testzugang und die Auskunft über die
      Datenerhebung ([Nachvollziehbarkeit](../betrieb/nachvollziehbarkeit.md#auskunft-über-die-datenerhebung-einer-installation)).
- [ ] **Externe Modellanbieter** mit dem Datenschutz klären, bevor ein solches Modell aktiv wird
      ([Modelle verwalten](modelle.md)).
- [ ] **Spracheingabe** und **externe KI-Werkzeuge** erst nach dieser Klärung einschalten
      ([Modelle verwalten](modelle.md#reranking-und-spracheingabe),
      [Externe KI-Werkzeuge zulassen](externe-ki-werkzeuge.md)).

## Einstellen

- [ ] **Anlegerechte** bewusst bestätigen oder ändern; „Stand jetzt“ zeigt den Werkszustand
      ([Anlegerechte vergeben](anlegerechte.md)).
- [ ] **„Interne Gruppen anlegen“** einer benannten Gruppe geben, nicht allen Konten.
- [ ] **Identitätsanbieter** anbinden, danach lokale Konten abschalten und die Selbstregistrierung
      aus lassen ([Anmeldung einstellen](anmeldung-einstellen.md)).
- [ ] **Fristen** prüfen ([Datenschutz einstellen](datenschutz.md)).
- [ ] **Zugänge** anlegen und freigeben ([Zugänge einrichten](zugaenge.md)).
- [ ] **Die erste Person der Revision** einrichten ([Systemrollen vergeben](systemrollen.md#die-rolle-revision)).

## Die zwei Schutzgrößen des Betriebs

**Mindestgruppengröße** und **Plausibilitätsschwelle** stellt der Betrieb über Umgebungsvariablen
ein. Eine Änderung wirkt erst mit dem Neustart und **steht nicht im Nachweisprotokoll**. Behandeln
Sie sie deshalb wie eine Änderung an der Dienstvereinbarung: vorher absprechen, nachher
dokumentieren. Alle anderen Fristen und Schalter sind Verwaltungseinstellungen und stehen mit jeder
Änderung im Protokoll.

**Gut zu wissen:**

- Eine fertige Einstellung lässt sich mit [Einstellungen übertragen](einstellungen-uebertragen.md)
  aus einer Testinstallation übernehmen.
- Was die Revision prüfen kann, steht unter [Prüfen](README.md#prüfen).
