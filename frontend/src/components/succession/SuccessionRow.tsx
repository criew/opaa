import { useId, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import visuallyHidden from '@mui/utils/visuallyHidden'
import AutoStoriesOutlinedIcon from '@mui/icons-material/AutoStoriesOutlined'
import GroupsOutlinedIcon from '@mui/icons-material/GroupsOutlined'
import WorkspacesOutlinedIcon from '@mui/icons-material/WorkspacesOutlined'
import { Link as RouterLink } from 'react-router'
import type { SuccessionEntryResponse, SuccessionKind } from '../../types/api'
import { radius } from '../../theme/tokens'
import MetaBadge from '../MetaBadge'
import PermissionTransferDialog from '../permissions/PermissionTransferDialog'
import { assetTypeDefinition, assetTypeLabel } from '../assets/assetTypeRegistry'
import { addresseeOf, nextStepOf, problemOf, sinceLabel } from './successionPresentation'

function objectHref(entry: SuccessionEntryResponse): string | null {
  switch (entry.objectType) {
    case 'ASSET':
      return assetTypeDefinition(entry.assetType)?.detailRoute(entry.objectId) ?? null
    case 'SPACE':
      return `/spaces/${entry.objectId}`
    default:
      return null
  }
}

function objectTypeLabel(entry: SuccessionEntryResponse): string {
  switch (entry.objectType) {
    case 'ASSET':
      return assetTypeLabel(entry.assetType ?? undefined)
    case 'SPACE':
      return 'Space'
    default:
      return 'Gruppe'
  }
}

const ICONS = {
  ASSET: AutoStoriesOutlinedIcon,
  SPACE: WorkspacesOutlinedIcon,
  GROUP: GroupsOutlinedIcon,
}

/**
 * One entry: what it is, what the problem is, and the one way out. The entry point is always the
 * object (ADR-0036, Entscheidung 6; Personalrat E1) - a former owner is text, never a key.
 */
export default function SuccessionRow({
  kind,
  entry,
  onChanged,
}: {
  kind: SuccessionKind
  entry: SuccessionEntryResponse
  onChanged: () => void
}) {
  const titleId = useId()
  const [transferOpen, setTransferOpen] = useState(false)
  const [handoverOpen, setHandoverOpen] = useState(false)
  const step = nextStepOf(kind, entry)
  const href = objectHref(entry)
  const Icon = ICONS[entry.objectType]
  const hints = entry.membershipHints ?? []
  const details = [
    `Zuständig: ${addresseeOf(entry)}`,
    entry.ownerHint ? `bisher: ${entry.ownerHint}` : null,
    // Bestandsinformation as a hint where to look - never an activity evaluation (Personalrat E4).
    hints.length > 0 ? `Anhaltspunkt für die Nachfolge: ${hints.join(', ')}` : null,
  ].filter(Boolean)

  return (
    <Box
      component="li"
      aria-labelledby={titleId}
      sx={{
        display: 'grid',
        gridTemplateColumns: { xs: 'auto 1fr', md: 'auto 1fr auto' },
        columnGap: 2,
        rowGap: 1,
        alignItems: 'start',
        px: { xs: 0.5, md: 1 },
        py: 2.25,
        '& + &': { borderTop: 1, borderColor: 'divider' },
      }}
    >
      <Box
        aria-hidden="true"
        sx={{
          width: 36,
          height: 36,
          borderRadius: `${radius.md}px`,
          display: 'grid',
          placeItems: 'center',
          color: 'text.secondary',
          bgcolor: 'action.hover',
        }}
      >
        <Icon sx={{ fontSize: 20 }} />
      </Box>

      <Box sx={{ minWidth: 0 }}>
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
          <Typography id={titleId} component="h2" sx={{ fontSize: 15, fontWeight: 600 }}>
            {href ? (
              <Link component={RouterLink} to={href}>
                {entry.objectName}
              </Link>
            ) : (
              entry.objectName
            )}
          </Typography>
          <MetaBadge>{objectTypeLabel(entry)}</MetaBadge>
        </Stack>
        <Typography sx={{ fontSize: 14, mt: 0.5 }}>{problemOf(kind, entry)}</Typography>
        <Stack
          direction="row"
          spacing={1}
          sx={{ alignItems: 'center', flexWrap: 'wrap', mt: 0.5, rowGap: 0.5 }}
        >
          <Typography component="span" sx={{ fontSize: 12.5, color: 'text.secondary' }}>
            {sinceLabel(entry)}
          </Typography>
          {entry.highlighted && <MetaBadge accent>lange offen</MetaBadge>}
          {entry.highlighted && (
            <Box component="span" sx={visuallyHidden}>
              – länger offen als die eingestellte Zeit
            </Box>
          )}
        </Stack>
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.25 }}>
          {details.join(' · ')}
        </Typography>
      </Box>

      <Stack
        spacing={0.5}
        sx={{
          gridColumn: { xs: '2', md: '3' },
          alignItems: { xs: 'flex-start', md: 'flex-end' },
        }}
      >
        {step === 'HANDOVER' && (
          <Button size="small" variant="outlined" onClick={() => setHandoverOpen(true)}>
            Nachfolge bestimmen
          </Button>
        )}
        {step === 'GROUP_TRANSFER' && (
          <Button size="small" variant="outlined" onClick={() => setTransferOpen(true)}>
            Rechte übergeben
          </Button>
        )}
        {step === 'GROUP_ADMIN' && (
          <Button size="small" variant="outlined" component={RouterLink} to="/admin/groups">
            Zur Gruppenverwaltung
          </Button>
        )}
      </Stack>

      {transferOpen && (
        <PermissionTransferDialog
          open
          onClose={() => setTransferOpen(false)}
          source={{ type: 'GROUP', id: entry.objectId, name: entry.objectName }}
          targetKinds={['GROUP']}
          scopes={['OWNERSHIP', 'ASSET_GRANTS', 'SPACE_MEMBERSHIPS', 'CAPABILITIES']}
          intro={`Eigentum und Rechte von „${entry.objectName}“ gehen an die gewählte Gruppe. Diese Gruppe sollte aktive Mitglieder haben, damit die Rechte wieder jemanden erreichen.`}
          onTransferred={onChanged}
          labels={{ title: 'Rechte übergeben', targetGroup: 'Neue Gruppe' }}
        />
      )}

      {handoverOpen && (
        <PermissionTransferDialog
          open
          onClose={() => setHandoverOpen(false)}
          sourceKinds={['USER']}
          targetKinds={['USER']}
          scopes={['OWNERSHIP', 'STEWARDSHIP']}
          intro={`Eigentum und Verantwortung des Kontos, dem „${entry.objectName}“ gehört, gehen an eine Nachfolge. Wählen Sie zuerst das bisherige Konto, dann die Nachfolge. Die Rechte einzelner Personen gehen nicht mit über.`}
          onTransferred={onChanged}
          labels={{
            title: 'Nachfolge bestimmen',
            source: 'Bisheriges Konto',
            targetUser: 'Nachfolge',
          }}
        />
      )}
    </Box>
  )
}
