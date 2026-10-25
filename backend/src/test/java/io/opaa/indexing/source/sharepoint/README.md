# Stufe 3: SharePoint-Konnektor gegen einen echten Mandanten

`SharePointTenantIntegrationTest` prüft den Konnektor gegen einen echten Microsoft-365-Mandanten und
belegt die als unsicher markierten Annahmen aus ADR-0047, vor allem: Mit `Sites.Selected` arbeitet
der Konnektor ohne mandantenweite Leserechte. Die Suite läuft **nur lokal beim Maintainer**. Sie hat
keinen CI-Eintrag, kein Label und kein Secret. Sie liest nur: Sie legt nichts an, ändert nichts und
löscht nichts.

## Einmalige Einrichtung

1. **App registrieren:** Im Entra Admin Center unter „App-Registrierungen“ → „Neue Registrierung“,
   nur Konten dieses Verzeichnisses, ohne Umleitungs-URI.
2. **Berechtigung:** „API-Berechtigungen“ → „Microsoft Graph“ → „Anwendungsberechtigungen“ →
   `Sites.Selected`, danach „Administratorzustimmung erteilen“.
3. **Geheimnis:** „Zertifikate & Geheimnisse“ → „Neuer geheimer Clientschlüssel“.
4. **Site freigeben:** Eine Testsite mit mindestens einer Dokumentbibliothek und ein paar Dateien
   für die App mit der Rolle `read` freigeben, etwa per Graph Explorer mit einem
   Administratorkonto: `POST https://graph.microsoft.com/v1.0/sites/{site-id}/permissions` mit
   `{"roles": ["read"], "grantedToIdentities": [{"application": {"id": "<Client-ID>",
   "displayName": "OPAA-Test"}}]}`.
5. **Zugangsdatei außerhalb des Repositorys anlegen**, etwa `C:\opaa-local\sharepoint.env`. Der Test
   bricht ab, wenn die Datei im Repository liegt:

   ```
   OPAA_SP_TENANT_ID=<Mandanten-ID oder contoso.onmicrosoft.com>
   OPAA_SP_CLIENT_ID=<Client-ID der App>
   OPAA_SP_CLIENT_SECRET=<Wert des Geheimnisses>
   OPAA_SP_SITE_URL=https://contoso.sharepoint.com/sites/opaa-test
   # optional: eine Site, die NICHT für die App freigegeben ist
   OPAA_SP_FOREIGN_SITE_URL=https://contoso.sharepoint.com/sites/andere
   ```

## Start

```bash
# aus backend/
OPAA_SP_IT_ENV_FILE=C:/opaa-local/sharepoint.env ./gradlew sharePointIntegrationTest
```

Ohne `OPAA_SP_IT_ENV_FILE` überspringt die Task die Suite.

## Was der Lauf prüft

- Anmeldung über den Zugang mit Client-Credentials am Token-Endpunkt des Mandanten. Das Token
  trägt genau die Rolle `Sites.Selected`; jede weitere Anwendungsberechtigung lässt jeden Test
  scheitern, weil die übrigen Befunde nur ohne mandantenweite Rechte etwas belegen.
- Die freigegebene Site wird über ihre Adresse aufgelöst, ihre Dokumentbibliotheken und Ordner
  werden aufgelistet; der Verbindungstest erreicht jede Bibliothek.
- Die Site-Suche nennt bei `Sites.Selected` die fehlende Berechtigung `Sites.Read.All`, statt zu
  scheitern.
- Eine nicht freigegebene Site wird mit dem Hinweis auf die Freigabe für `Sites.Selected`
  abgewiesen: `OPAA_SP_FOREIGN_SITE_URL`, ohne Angabe die Stammsite des Mandanten.
- Ein Vollabgleich aller Bibliotheken der Site mit der Größengrenze des Konnektors lädt jede Datei
  ohne Abweisung, ein anschließender Änderungslauf läuft ohne Fehler durch; danach sind alle
  Änderungsströme lesbar.
- Das Original jeder aufgenommenen Datei kommt über die Download-Weiterleitung.
- Die Testsite sollte Office-Dateien und PDFs enthalten und darf nicht die Stammsite des Mandanten
  sein, wenn `OPAA_SP_FOREIGN_SITE_URL` fehlt.

Client-Geheimnis und Zugriffstoken erscheinen weder in Logs noch in Reports oder Fehlermeldungen.
Der Test prüft die mitgeschnittenen Logzeilen am Ende jeder Methode.
