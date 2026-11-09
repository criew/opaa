# Virenprüfung einrichten

**Ziel:** Jede Datei, die eine Bibliothek oder ein Chat aufnimmt, geht vorher an den Virenscanner des Hauses.
{: .kopf }

**Wer:** Systemverwaltung, „Administration → Virenprüfung“
{: .kopf }

OPAA bringt keinen Virenscanner mit. Es bindet einen clamd-Dienst (ClamAV) an, den der Betrieb
bereitstellt, so wie ein Modell oder die Texterkennung.

1. Unter „Host des Virenscanners“ den Namen oder die Adresse des Dienstes eintragen, ohne Schema,
   etwa `clamav.amt.example`; „Port“ ist üblich `3310`.
2. „Verbindung testen“ fragt den Dienst nach seiner Version, ohne eine Datei zu senden, auch vor dem
   Speichern.
3. Unter „Wenn der Scanner eine Datei nicht prüfen kann“ wählen: „Ablehnen“ (ab Werk) oder
   „Ungeprüft aufnehmen“.
4. „Jede eingehende Datei prüfen“ einschalten und „Speichern“.

Die Änderung wirkt ab der nächsten Datei auf allen Instanzen, ohne Neustart, und steht mit altem und
neuem Wert im [Nachweisprotokoll](nachweisprotokoll-lesen.md). Ausgeschaltet bleiben die Werte
gespeichert.

**Gut zu wissen:**

- Derselbe Scanner lässt sich schon im optionalen Schritt „Virenprüfung“ der
  [Installation](../betrieb/installation.md#schritte) eintragen, dort nur mit Port `3310`.
- Befund, Verhalten bei Ausfall und die Grenzen des Scanners stehen unter
  [Virenprüfung der aufgenommenen Dateien](../betrieb/netz-und-sicherheit.md#virenprüfung-der-aufgenommenen-dateien).
- Im Kubernetes-Betrieb muss eine NetworkPolicy dem Backend den Weg zum Scanner öffnen.
