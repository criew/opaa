import Paper from '@mui/material/Paper'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'

/**
 * What to set up at the provider and in the deployment before a provider works (ADR-0025,
 * Entscheidung 3 and 5; #1333): the shared redirect URI and origin, the CSP step with its frontend
 * restart, and the address allowlist - composed from this app's own origin, so the operator copies
 * exactly what this installation needs.
 */
export default function OidcProviderSetupInstructions() {
  const origin = window.location.origin
  return (
    <Paper variant="outlined" sx={{ p: 3 }} component="section" aria-labelledby="oidc-setup-title">
      <Typography id="oidc-setup-title" variant="h6" component="h2" gutterBottom>
        Einrichtung beim Anbieter und im Betrieb
      </Typography>
      <Stack spacing={1.5}>
        <Typography variant="body2">
          <strong>1. Öffentlicher Client beim Anbieter.</strong> Legen Sie einen Client ohne Secret
          mit Authorization Code Flow und PKCE an. Gültige Weiterleitungs-URI (für alle Anbieter
          dieselbe): <code data-testid="oidc-redirect-uri">{origin}/auth/callback</code>. Erlaubter
          Web-Origin und Abmelde-Weiterleitung: <code data-testid="oidc-origin">{origin}</code>.
        </Typography>
        <Typography variant="body2">
          <strong>2. Content-Security-Policy.</strong> Der Browser muss den Anbieter erreichen
          dürfen: Den Origin des Anbieters in <code>OPAA_CSP_CONNECT_SRC_EXTRA</code> eintragen und
          den Frontend-Container neu starten – die Richtlinie wird beim Start erzeugt.
        </Typography>
        <Typography variant="body2">
          <strong>3. Adressprüfung des Backends.</strong> Das Backend ruft Discovery-Dokument und
          JWK-Set des Anbieters selbst ab. Liegt der Anbieter in einem privaten Netz, muss sein Host
          in <code>OPAA_OIDC_TARGET_VALIDATION_ALLOWLIST</code> stehen; die Adressen des beim Start
          übernommenen Anbieters sind immer erlaubt.
        </Typography>
        <Typography variant="body2" color="text.secondary">
          Der Verbindungstest im Formular prüft Discovery-Dokument und JWK-Set vor dem Speichern.
          Ein Anbieter, dessen Schlüssel das Backend nicht abrufen kann, wird hier als „nicht
          erreichbar“ angezeigt und erscheint nicht auf der Anmeldeseite.
        </Typography>
      </Stack>
    </Paper>
  )
}
