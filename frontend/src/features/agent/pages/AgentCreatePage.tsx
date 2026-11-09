import { useRef, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import PageHeading from '@/shared/ui/a11y/PageHeading'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'
import { AssetOwnerFields } from '@/features/asset'
import { useMyGroups } from '@/features/principal'
import { useMyCapabilities } from '@/shared/session/useMyCapabilities'
import { capabilityMissingMessage } from '@/shared/utils/labels'
import { confirmAction } from '@/shared/stores/confirmStore'
import { CATALOG_ROUTE, agentRoute } from '@/shared/routes'
import { contentWidth } from '@/shared/theme/tokens'
import { apiFieldErrors } from '@/shared/api/apiErrorDetails'
import type { AssetOwnerType, GroupListResponse } from '@/shared/api/types'
import { useAgentStore } from '../stores/agentStore'
import ErrorSummary, { type SummaryEntry } from '@/shared/ui/feedback/ErrorSummary'
import { fieldMessages } from '../components/fieldMessages'
import { NAME_MAX, NAME_PLACEHOLDER, PURPOSE_HELP, PURPOSE_MAX } from '../components/agentTexts'

const ID = 'agent-create'

/**
 * Creating an agent: name, purpose and owner, nothing else. The agent starts empty - no
 * instruction, no library, searching and reading as tools - and is set up on its detail page.
 * Without the right the page explains that and offers no form.
 */
export default function AgentCreatePage() {
  const navigate = useNavigate()
  const create = useAgentStore((s) => s.createAgent)
  const { isMissing } = useMyCapabilities()
  const myGroups = useMyGroups()
  const summaryRef = useRef<HTMLDivElement>(null)

  const [name, setName] = useState('')
  const [purpose, setPurpose] = useState('')
  const [ownerType, setOwnerType] = useState<AssetOwnerType>('USER')
  const [selectedGroup, setSelectedGroup] = useState<GroupListResponse | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<'name' | 'ownerId', string>>>({})
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  if (isMissing('CREATE_AGENT')) {
    return (
      <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, maxWidth: contentWidth.notice }}>
        <PageHeading title="Neuer Agent" gutterBottom />
        <Alert severity="info">{capabilityMissingMessage('CREATE_AGENT')}</Alert>
      </Box>
    )
  }

  const isDirty = name.trim() !== '' || purpose.trim() !== ''

  async function handleCancel() {
    if (isDirty) {
      const confirmed = await confirmAction({
        question: 'Eingaben verwerfen?',
        confirmLabel: 'Verwerfen',
        tone: 'caution',
      })
      if (!confirmed) return
    }
    navigate(CATALOG_ROUTE)
  }

  function showErrors(errors: Partial<Record<'name' | 'ownerId', string>>) {
    setFieldErrors(errors)
    // The summary renders with this state; it takes the focus once it is there.
    requestAnimationFrame(() => summaryRef.current?.focus())
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const errors: Partial<Record<'name' | 'ownerId', string>> = {}
    if (name.trim() === '') errors.name = 'Geben Sie dem Agenten einen Namen.'
    if (ownerType === 'GROUP' && !selectedGroup) {
      errors.ownerId = 'Wählen Sie die Gruppe, der der Agent gehören soll.'
    }
    if (Object.keys(errors).length > 0) {
      showErrors(errors)
      return
    }
    setFieldErrors({})
    setError(null)
    setSubmitting(true)
    try {
      const id = await create({
        name: name.trim(),
        description: purpose.trim() || null,
        ownerType,
        ownerId: ownerType === 'GROUP' ? (selectedGroup?.id ?? null) : null,
      })
      navigate(agentRoute(id))
    } catch (err) {
      const fromServer = fieldMessages(apiFieldErrors(err))
      if (fromServer.name || fromServer.ownerId) {
        showErrors({ name: fromServer.name, ownerId: fromServer.ownerId })
      } else {
        setError(err instanceof Error ? err.message : 'Der Agent konnte nicht angelegt werden.')
      }
      setSubmitting(false)
    }
  }

  const summary: SummaryEntry[] = []
  if (fieldErrors.name) {
    summary.push({
      text: `Name: ${fieldErrors.name}`,
      onJump: () => document.getElementById(`${ID}-name`)?.focus(),
    })
  }
  if (fieldErrors.ownerId) {
    summary.push({ text: `Eigentümer: ${fieldErrors.ownerId}`, onJump: () => undefined })
  }

  return (
    <Box sx={{ flexGrow: 1, overflowY: 'auto', p: { xs: 2.5, md: 5 } }}>
      <Box component="form" noValidate onSubmit={handleSubmit} sx={{ maxWidth: 640 }}>
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mb: 0.5 }}>
          Neuer Katalogeintrag
        </Typography>
        <PageHeading title="Neuer Agent" />
        <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mt: 1, mb: 3 }}>
          Der Agent startet leer: ohne Auftrag, mit allem Wissen, das die aufrufende Person lesen
          darf, und mit den Werkzeugen Suchen und Lesen. Auftrag, Wissen und Ablauf richten Sie
          danach auf seiner Seite ein.
        </Typography>

        <ErrorSummary ref={summaryRef} entries={summary} idPrefix={ID} />
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}

        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2.5 }}>
          <Box>
            <FieldLabel htmlFor={`${ID}-name`}>Name</FieldLabel>
            <TextField
              id={`${ID}-name`}
              size="small"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder={NAME_PLACEHOLDER}
              fullWidth
              required
              error={Boolean(fieldErrors.name)}
              helperText={fieldErrors.name}
              slotProps={{ htmlInput: { maxLength: NAME_MAX } }}
            />
          </Box>
          <Box>
            <FieldLabel htmlFor={`${ID}-purpose`}>Zweck (optional)</FieldLabel>
            <TextField
              id={`${ID}-purpose`}
              size="small"
              value={purpose}
              onChange={(e) => setPurpose(e.target.value)}
              multiline
              minRows={2}
              fullWidth
              helperText={PURPOSE_HELP}
              slotProps={{ htmlInput: { maxLength: PURPOSE_MAX } }}
            />
          </Box>
          <AssetOwnerFields
            idPrefix={ID}
            assetType="AGENT"
            ownerType={ownerType}
            onOwnerTypeChange={setOwnerType}
            myGroups={myGroups}
            selectedGroup={selectedGroup}
            onSelectedGroupChange={setSelectedGroup}
          />
        </Box>

        <Box
          sx={{
            display: 'flex',
            gap: 1.5,
            mt: 4,
            pt: 2,
            borderTop: 1,
            borderColor: 'divider',
          }}
        >
          <Button variant="text" onClick={() => void handleCancel()} disabled={submitting}>
            Abbrechen
          </Button>
          <Box sx={{ flex: 1 }} />
          <Button type="submit" variant="contained" disabled={submitting}>
            {submitting ? 'Wird angelegt …' : 'Agent anlegen'}
          </Button>
        </Box>
      </Box>
    </Box>
  )
}
