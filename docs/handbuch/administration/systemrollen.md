# Systemrollen vergeben

**Ziel:** Jede Person hat die passende Rolle für die ganze Installation.
{: .kopf }

**Wer:** Systemverwaltung; die Rolle *Revision* bestätigt eine Person der Revision
{: .kopf }

Eine Systemrolle gilt für die ganze Installation. Was ein Konto an einer Bibliothek oder in einem
Space darf, regeln die Rollen dort.

| Rolle | Darf |
|---|---|
| Nutzer | arbeiten und pflegen, soweit ihm Rollen gegeben sind |
| Systemverwaltung | alles unter „Administration“; verwaltet jedes Arbeitsmittel, **liest aber keinen Inhalt** ohne eigenes Leserecht |
| Revision | Nachweisprotokoll, Stichtagsauskunft, Verbindungsprotokoll und Rollenanträge lesen; sonst nichts |

1. Lokales Konto: „Bearbeiten“, Rolle wählen. Konto eines Identitätsanbieters: Zeilenmenü „Rolle
   ändern …“. Führt der Anbieter die Rollen über einen Claim, ist der Eintrag gesperrt.
2. Liegt Ihre Anmeldung länger zurück, bestätigen Sie sie erneut.

Systemverwalterkonten sind persönlich, je Person eines. Ein Haus, das eine „Bibliotheksverwaltung“
will, legt eine Gruppe an und gibt ihr [Anlegerechte](anlegerechte.md) und Rollen; neue Systemrollen
gibt es nicht.

## Die Rolle Revision

Damit die Systemverwaltung nicht ihr eigenes Handeln prüft, wird aus ihrer Wahl nur ein **Antrag**:
Die Liste zeigt „Revision beantragt“, alle Personen der Revision werden benachrichtigt. Eine von
ihnen bestätigt oder lehnt unter „Revision → Rollenanträge“ ab.

- **Die erste Person der Revision** ist ein lokales Konto unter der Adresse, die der Betrieb als
  `OPAA_AUTH_FIRST_AUDITOR_EMAIL` setzt. Die Systemverwaltung legt es an und wählt die Rolle; den
  Link zum Passwort schreibt OPAA ins Server-Log, der Betrieb übergibt ihn.
- **Zugangsdaten** eines Revisionskontos laufen nie über die Systemverwaltung: Den Link zum Passwort
  zeigt OPAA der bestätigenden Person, die ihn persönlich übergibt. Passwort erzeugen, Rücksetzlink,
  neue Adresse und Übergabe an einen Anbieter sind an solchen Konten gesperrt.
- **Passwort vergessen?** Die Systemverwaltung entzieht die Rolle und beantragt sie neu; eine andere
  Person der Revision bestätigt.

Jede Vergabe und jeder Entzug der Revision, jede Änderung an Mailversand, Mailvorlagen oder den
Anmeldedaten eines Identitätsanbieters meldet OPAA allen Personen der Revision.

**Gut zu wissen:**

- Dem letzten anmeldefähigen Systemverwalter lässt sich die Rolle nicht entziehen.
- Wird ein lokales Konto zur Systemverwaltung, enden seine laufenden Sitzungen; die Person meldet
  sich neu an.
- Eine Systemrolle hat keine Rechtehistorie; ihre Änderungen stehen im Nachweisprotokoll.
