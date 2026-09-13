import type { ReactNode } from 'react'
import Box from '@mui/material/Box'
import IconButton from '@mui/material/IconButton'
import Stack from '@mui/material/Stack'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import { alpha } from '@mui/material/styles'
import ContentCopyOutlinedIcon from '@mui/icons-material/ContentCopyOutlined'
import DnsOutlinedIcon from '@mui/icons-material/DnsOutlined'
import ShieldOutlinedIcon from '@mui/icons-material/ShieldOutlined'
import VpnKeyOutlinedIcon from '@mui/icons-material/VpnKeyOutlined'
import { copyToClipboard } from '../../utils/clipboard'
import { fontFamily, radius } from '../../theme/tokens'
import SectionHead from '../SectionHead'
import ProviderStateDot from './providers/ProviderStateDot'
import { PROVIDER_STATE_LABEL, type ProviderState } from './oidcProviderState'

const eyebrowSx = {
  fontFamily: fontFamily.mono,
  fontSize: 10,
  fontWeight: 500,
  letterSpacing: '0.08em',
  textTransform: 'uppercase',
  color: 'text.secondary',
} as const

const codeSx = {
  fontFamily: fontFamily.mono,
  fontSize: 12,
  px: 0.75,
  py: 0.25,
  borderRadius: `${radius.xs}px`,
  bgcolor: (t: { palette: { text: { primary: string } } }) => alpha(t.palette.text.primary, 0.05),
} as const

interface CopyableValueProps {
  label: string
  value: string
  testId: string
}

/** A value the operator carries over to the provider - readable, and copied with one click. */
function CopyableValue({ label, value, testId }: CopyableValueProps) {
  return (
    <Box sx={{ minWidth: 0 }}>
      <Typography sx={{ fontSize: 11.5, color: 'text.secondary' }}>{label}</Typography>
      <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center', minWidth: 0, mt: 0.25 }}>
        <Box
          component="code"
          data-testid={testId}
          sx={{
            ...codeSx,
            color: 'text.primary',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
            minWidth: 0,
          }}
        >
          {value}
        </Box>
        <Tooltip title="Kopieren">
          <IconButton
            size="small"
            aria-label={`${label} kopieren`}
            onClick={() => void copyToClipboard(value, label)}
          >
            <ContentCopyOutlinedIcon sx={{ fontSize: 14 }} />
          </IconButton>
        </Tooltip>
      </Stack>
    </Box>
  )
}

interface StepProps {
  number: number
  icon: ReactNode
  title: string
  children: ReactNode
}

function Step({ number, icon, title, children }: StepProps) {
  return (
    <Box
      component="li"
      // Ein Schritt einer Anleitung ist ein Listeneintrag, kein Kasten (#1608): Die Trennlinie
      // unten scheidet ihn vom nächsten, das Symbol links führt das Auge.
      sx={{
        listStyle: 'none',
        borderBottom: 1,
        borderColor: 'divider',
        py: 2,
        '&:last-of-type': { borderBottom: 0 },
        display: 'flex',
        flexDirection: 'column',
        gap: 1.5,
        minWidth: 0,
      }}
    >
      <Stack direction="row" spacing={1.25} sx={{ alignItems: 'center' }}>
        <Box
          aria-hidden="true"
          sx={{
            display: 'inline-flex',
            alignItems: 'center',
            justifyContent: 'center',
            flex: 'none',
            width: 32,
            height: 32,
            borderRadius: `${radius.sm}px`,
            color: 'primary.main',
            bgcolor: (t) => alpha(t.palette.primary.main, 0.1),
            border: 1,
            borderColor: (t) => alpha(t.palette.primary.main, 0.4),
            '& svg': { fontSize: 18 },
          }}
        >
          {icon}
        </Box>
        <Box sx={{ minWidth: 0 }}>
          <Typography component="span" sx={{ ...eyebrowSx, display: 'block' }}>
            Schritt {number}
          </Typography>
          <Typography component="h3" sx={{ fontSize: 14, fontWeight: 600, lineHeight: 1.25, m: 0 }}>
            {title}
          </Typography>
        </Box>
      </Stack>
      <Box sx={{ fontSize: 13, color: 'text.secondary', lineHeight: 1.5, '& code': codeSx }}>
        {children}
      </Box>
    </Box>
  )
}

const STATE_EXPLANATION: Record<ProviderState, string> = {
  reachable: 'Das Backend hat die Schlüssel des Anbieters geladen; Anmeldungen funktionieren.',
  unreachable:
    'Die Schlüssel sind nicht abrufbar. Der Anbieter bleibt auf der Anmeldeseite, Anmeldungen ' +
    'schlagen fehl, bis die Verbindung steht – das Backend versucht es bei der nächsten ' +
    'Anmeldung erneut.',
  disabled:
    'Von der Systemverwaltung abgeschaltet: nicht auf der Anmeldeseite, Konten bleiben erhalten.',
}

/**
 * What to set up at the provider and in the deployment before a provider works (ADR-0025,
 * Entscheidung 3 and 5; #1333): the shared redirect URI and origin, the CSP step with its frontend
 * restart, and the address allowlist - composed from this app's own origin, so the operator copies
 * exactly what this installation needs. Followed by the legend of the states the list above shows.
 */
export default function OidcProviderSetupInstructions() {
  const origin = window.location.origin
  return (
    <Box component="section" aria-labelledby="oidc-setup-title">
      <SectionHead id="oidc-setup-title">Einrichtung beim Anbieter und im Betrieb</SectionHead>
      <Typography sx={{ fontSize: 13, color: 'text.secondary', mb: 2 }}>
        Drei Schritte, bevor ein Anbieter Anmeldungen annimmt. Die Werte gelten für diese
        Installation.
      </Typography>
      <Box
        component="ol"
        sx={{
          m: 0,
          p: 0,
          display: 'grid',
          gridTemplateColumns: { xs: '1fr', md: 'repeat(3, minmax(0, 1fr))' },
          gap: 1.5,
        }}
      >
        <Step number={1} icon={<VpnKeyOutlinedIcon />} title="Öffentlicher Client beim Anbieter">
          Client ohne Secret, Authorization Code Flow mit PKCE. Beide Werte sind für alle Anbieter
          dieselben.
          <Stack spacing={1.25} sx={{ mt: 1.5 }}>
            <CopyableValue
              label="Weiterleitungs-URI"
              value={`${origin}/auth/callback`}
              testId="oidc-redirect-uri"
            />
            <CopyableValue
              label="Web-Origin und Abmelde-Weiterleitung"
              value={origin}
              testId="oidc-origin"
            />
          </Stack>
        </Step>
        <Step number={2} icon={<ShieldOutlinedIcon />} title="Content-Security-Policy">
          Der Browser muss den Anbieter erreichen dürfen: den Origin des Anbieters in{' '}
          <code>OPAA_CSP_CONNECT_SRC_EXTRA</code> eintragen und den Frontend-Container neu starten –
          die Richtlinie wird beim Start erzeugt.
        </Step>
        <Step number={3} icon={<DnsOutlinedIcon />} title="Adressprüfung des Backends">
          Das Backend ruft Discovery-Dokument und JWK-Set selbst ab. Liegt der Anbieter in einem
          privaten Netz, muss sein Host in <code>OPAA_OIDC_TARGET_VALIDATION_ALLOWLIST</code>{' '}
          stehen; die Adressen des beim Start übernommenen Anbieters sind immer erlaubt.
        </Step>
      </Box>

      <Box
        component="section"
        aria-labelledby="oidc-state-legend-title"
        sx={{ mt: 3, pt: 2, borderTop: 1, borderColor: 'divider' }}
      >
        <Typography id="oidc-state-legend-title" component="h3" sx={{ ...eyebrowSx, mb: 1.5 }}>
          Status verstehen
        </Typography>
        <Box
          component="dl"
          sx={{
            m: 0,
            display: 'grid',
            gridTemplateColumns: { xs: '1fr', md: 'repeat(3, minmax(0, 1fr))' },
            columnGap: 3,
            rowGap: 1.5,
          }}
        >
          {(['reachable', 'unreachable', 'disabled'] as const).map((state) => (
            <Box key={state}>
              <Stack component="dt" direction="row" spacing={0.75} sx={{ alignItems: 'center' }}>
                <ProviderStateDot state={state} />
                <Typography component="span" sx={{ fontSize: 13, fontWeight: 500 }}>
                  {PROVIDER_STATE_LABEL[state]}
                </Typography>
              </Stack>
              <Typography
                component="dd"
                sx={{ m: 0, mt: 0.5, fontSize: 12.5, color: 'text.secondary', lineHeight: 1.5 }}
              >
                {STATE_EXPLANATION[state]}
              </Typography>
            </Box>
          ))}
        </Box>
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary', lineHeight: 1.5, mt: 2 }}>
          Der Verbindungstest im Formular prüft Discovery-Dokument und JWK-Set vor dem Speichern.
          Ist die Issuer-URI vom Backend aus nicht erreichbar, prüft er das JWK-Set über die
          Backend-seitige Adresse und sagt das.
        </Typography>
      </Box>
    </Box>
  )
}
