import { useEffect, useState } from 'react'
import { Link as RouterLink, Navigate, useNavigate, useParams } from 'react-router'
import Accordion from '@mui/material/Accordion'
import AccordionDetails from '@mui/material/AccordionDetails'
import AccordionSummary from '@mui/material/AccordionSummary'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import ArrowBackIcon from '@mui/icons-material/ArrowBack'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import type {
  AssetRole,
  AssetVisibility,
  PromptLibraryResponse,
  PromptResponse,
} from '../types/api'
import { usePromptLibraryStore } from '../stores/promptLibraryStore'
import { confirmAction } from '../stores/confirmStore'
import { assetRoleLabel, promptVariableTypeLabel } from '../utils/labels'
import { fontFamily } from '../theme/tokens'
import { PROMPT_LIBRARY_TABS, promptLibraryRoute, type PromptLibraryTab } from '../routes'
import PageHeading from '../components/a11y/PageHeading'
import PageSection from '../components/PageSection'
import AreaTabs from '../components/AreaTabs'
import MetaBadge from '../components/MetaBadge'
import FieldLabel from '../components/wizard/FieldLabel'
import SuccessionStateNote from '../components/succession/SuccessionStateNote'
import AssetDistributionSection from '../components/assets/AssetDistributionSection'
import PromptEditorDialog from '../components/prompts/PromptEditorDialog'
import PromptTextHighlight from '../components/prompts/PromptTextHighlight'

const ROLE_ORDER: AssetRole[] = ['VIEWER', 'EDITOR', 'MANAGER', 'OWNER']

function holds(role: AssetRole | undefined, minimum: AssetRole): boolean {
  return role !== undefined && ROLE_ORDER.indexOf(role) >= ROLE_ORDER.indexOf(minimum)
}

const tabs: Array<{ value: PromptLibraryTab; label: string }> = [
  { value: 'prompts', label: 'Prompts' },
  { value: 'settings', label: 'Verwaltung' },
]

function isPromptLibraryTab(value: string | undefined): value is PromptLibraryTab {
  return PROMPT_LIBRARY_TABS.some((tab) => tab === value)
}

function variablesSummary(prompt: PromptResponse): string {
  const count = prompt.variables.length
  if (count === 0) return 'ohne Variablen'
  const required = prompt.variables.filter((variable) => variable.required).length
  const base = count === 1 ? '1 Variable' : `${count} Variablen`
  return required > 0 ? `${base}, davon ${required} Pflicht` : base
}

/** The prompts of one library; editors add, change and delete them here. */
function PromptsArea({
  library,
  canEditPrompts,
}: {
  library: PromptLibraryResponse
  canEditPrompts: boolean
}) {
  const prompts = usePromptLibraryStore((s) => s.promptsByLibrary[library.id])
  const promptsError = usePromptLibraryStore((s) => s.promptsErrorByLibrary[library.id])
  const loadPrompts = usePromptLibraryStore((s) => s.loadPrompts)
  const removePrompt = usePromptLibraryStore((s) => s.removePrompt)
  // `undefined` = closed, `null` = a new prompt, otherwise the prompt being changed.
  const [editing, setEditing] = useState<PromptResponse | null | undefined>(undefined)
  const [actionError, setActionError] = useState<string | null>(null)

  useEffect(() => {
    void loadPrompts(library.id)
  }, [library.id, loadPrompts])

  async function handleDelete(prompt: PromptResponse) {
    const confirmed = await confirmAction({
      question: `Prompt „${prompt.title}“ löschen?`,
      consequence: `Der Befehl /${prompt.name} steht danach nicht mehr zur Verfügung.`,
      confirmLabel: 'Löschen',
      tone: 'danger',
    })
    if (!confirmed) return
    setActionError(null)
    try {
      await removePrompt(library.id, prompt.id)
    } catch (err) {
      setActionError(err instanceof Error ? err.message : 'Löschen fehlgeschlagen')
    }
  }

  const mayCreate = canEditPrompts && !promptsError

  return (
    <PageSection
      title="Prompts"
      description="Benannte Anweisungen dieser Prompt-Bibliothek. Der Befehl ist der Name, unter dem ein Prompt im Chat aufgerufen wird."
      action={
        mayCreate ? (
          <Button variant="contained" size="small" onClick={() => setEditing(null)}>
            Neuer Prompt
          </Button>
        ) : undefined
      }
    >
      {actionError && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setActionError(null)}>
          {actionError}
        </Alert>
      )}
      {promptsError ? (
        <Alert severity="warning">{promptsError}</Alert>
      ) : prompts === undefined ? (
        <Typography sx={{ color: 'text.secondary' }}>Prompts werden geladen …</Typography>
      ) : prompts.length === 0 ? (
        <Typography sx={{ color: 'text.secondary' }}>
          {canEditPrompts
            ? 'Diese Prompt-Bibliothek enthält noch keine Prompts. Legen Sie den ersten mit „Neuer Prompt“ an.'
            : 'Diese Prompt-Bibliothek enthält noch keine Prompts.'}
        </Typography>
      ) : (
        <Box>
          {prompts.map((prompt) => (
            // The summary is the prompt's heading (MUI's heading slot); the title inside is a span.
            <Accordion key={prompt.id} slotProps={{ heading: { component: 'h3' } }}>
              <AccordionSummary
                expandIcon={<ExpandMoreIcon />}
                aria-controls={`prompt-${prompt.id}-content`}
                id={`prompt-${prompt.id}-header`}
              >
                <Box sx={{ minWidth: 0 }}>
                  <Stack
                    direction="row"
                    spacing={1.5}
                    sx={{ alignItems: 'baseline', flexWrap: 'wrap' }}
                  >
                    <Typography component="span" sx={{ fontSize: 14.5, fontWeight: 600 }}>
                      {prompt.title}
                    </Typography>
                    <Typography
                      component="span"
                      sx={{ fontFamily: fontFamily.mono, fontSize: 12, color: 'primary.main' }}
                    >
                      /{prompt.name}
                    </Typography>
                  </Stack>
                  <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
                    {[prompt.description, variablesSummary(prompt)].filter(Boolean).join(' · ')}
                  </Typography>
                </Box>
              </AccordionSummary>
              <AccordionDetails id={`prompt-${prompt.id}-content`}>
                <Stack spacing={1.5}>
                  <PromptTextHighlight text={prompt.text} label={`Text von ${prompt.title}`} />
                  {prompt.variables.length > 0 && (
                    <Box component="ul" sx={{ m: 0, pl: 2.5, fontSize: 12.5 }}>
                      {prompt.variables.map((variable) => (
                        <li key={variable.name}>
                          <Box component="span" sx={{ fontFamily: fontFamily.mono }}>
                            {`{{${variable.name}}}`}
                          </Box>{' '}
                          — {variable.label} · {promptVariableTypeLabel(variable.type)}
                          {variable.required ? ' · Pflicht' : ''}
                          {variable.defaultValue ? ` · Vorbelegung „${variable.defaultValue}“` : ''}
                        </li>
                      ))}
                    </Box>
                  )}
                  {canEditPrompts && (
                    <Stack direction="row" spacing={1}>
                      <Button
                        size="small"
                        variant="outlined"
                        onClick={() => setEditing(prompt)}
                        aria-label={`Prompt ${prompt.title} bearbeiten`}
                      >
                        Bearbeiten
                      </Button>
                      <Button
                        size="small"
                        color="error"
                        onClick={() => void handleDelete(prompt)}
                        aria-label={`Prompt ${prompt.title} löschen`}
                      >
                        Löschen
                      </Button>
                    </Stack>
                  )}
                </Stack>
              </AccordionDetails>
            </Accordion>
          ))}
        </Box>
      )}
      {editing !== undefined && (
        <PromptEditorDialog
          // A fresh dialog per prompt: its draft state starts from the prompt it edits.
          key={editing?.id ?? 'new'}
          open
          promptLibraryId={library.id}
          prompt={editing}
          onClose={() => setEditing(undefined)}
        />
      )}
    </PageSection>
  )
}

/** Master data, release and deletion - the area for MANAGER and above. */
function SettingsArea({ library }: { library: PromptLibraryResponse }) {
  const navigate = useNavigate()
  const updateLibrary = usePromptLibraryStore((s) => s.updateLibrary)
  const deleteLibrary = usePromptLibraryStore((s) => s.deleteLibrary)
  const [draft, setDraft] = useState<{ name: string; description: string } | null>(null)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const name = draft?.name ?? library.name
  const description = draft?.description ?? library.description ?? ''

  // The PUT replaces name, description and reach as a whole; each form sends its own fields and
  // the saved values of the other.
  async function save(fields: {
    name: string
    description: string | null | undefined
    visibility: AssetVisibility
    listed: boolean
  }) {
    await updateLibrary(library.id, {
      name: fields.name.trim(),
      description: fields.description?.trim() || null,
      visibility: fields.visibility,
      listed: fields.listed,
    })
  }

  async function handleSave() {
    setError(null)
    setSaving(true)
    try {
      await save({ name, description, visibility: library.visibility, listed: library.listed })
      setDraft(null)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Speichern fehlgeschlagen')
    } finally {
      setSaving(false)
    }
  }

  async function handleDelete() {
    const confirmed = await confirmAction({
      question: `Prompt-Bibliothek „${library.name}“ löschen?`,
      consequence:
        'Das entfernt auch alle Prompts, Rechte und Space-Zuordnungen dieser Prompt-Bibliothek. Diese Aktion kann nicht rückgängig gemacht werden.',
      confirmLabel: 'Löschen',
      tone: 'danger',
    })
    if (!confirmed) return
    setError(null)
    try {
      await deleteLibrary(library.id)
      navigate('/prompts')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Löschen fehlgeschlagen')
    }
  }

  return (
    <Stack>
      <PageSection title="Stammdaten" description="Name und Beschreibung dieser Prompt-Bibliothek.">
        <Stack spacing={2}>
          {error && (
            <Alert severity="error" onClose={() => setError(null)}>
              {error}
            </Alert>
          )}
          <Box>
            <FieldLabel htmlFor="prompt-library-detail-name">Name der Prompt-Bibliothek</FieldLabel>
            <TextField
              id="prompt-library-detail-name"
              size="small"
              fullWidth
              value={name}
              onChange={(e) => setDraft({ name: e.target.value, description })}
              slotProps={{ htmlInput: { maxLength: 255 } }}
            />
          </Box>
          <Box>
            <FieldLabel htmlFor="prompt-library-detail-description">Beschreibung</FieldLabel>
            <TextField
              id="prompt-library-detail-description"
              size="small"
              fullWidth
              multiline
              minRows={2}
              value={description}
              onChange={(e) => setDraft({ name, description: e.target.value })}
              slotProps={{ htmlInput: { maxLength: 2000 } }}
            />
          </Box>
          <Stack direction="row" spacing={1}>
            <Button
              variant="contained"
              size="small"
              onClick={() => void handleSave()}
              disabled={saving || !name.trim()}
            >
              {saving ? 'Wird gespeichert …' : 'Speichern'}
            </Button>
          </Stack>
        </Stack>
      </PageSection>

      <AssetDistributionSection
        assetType="PROMPT_LIBRARY"
        assetId={library.id}
        assetName={library.name}
        visibility={library.visibility}
        listed={library.listed}
        onSave={(visibility, listed) =>
          save({ name: library.name, description: library.description, visibility, listed })
        }
      />

      {library.myRole === 'OWNER' && (
        <PageSection
          title="Prompt-Bibliothek löschen"
          description="Entfernt die Prompt-Bibliothek unwiderruflich — einschließlich aller Prompts."
        >
          <Button color="error" variant="outlined" size="small" onClick={() => void handleDelete()}>
            Prompt-Bibliothek löschen
          </Button>
        </PageSection>
      )}
    </Stack>
  )
}

/**
 * One prompt library: its prompts for every reader, and for MANAGER and above the management
 * area with master data, the shared release section and deletion. Each area is a route of its own
 * (`/prompts/:id`, `/prompts/:id/settings`), so a link lands in the right one.
 */
export default function PromptLibraryDetailPage() {
  const { promptLibraryId, tab } = useParams()
  const library = usePromptLibraryStore((s) =>
    promptLibraryId ? s.details[promptLibraryId] : undefined,
  )
  const storeError = usePromptLibraryStore((s) => s.error)
  const loadLibrary = usePromptLibraryStore((s) => s.loadLibrary)

  useEffect(() => {
    if (promptLibraryId) void loadLibrary(promptLibraryId)
  }, [promptLibraryId, loadLibrary])

  if (!promptLibraryId) return <Navigate to="/prompts" replace />

  if (!library) {
    return (
      <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 } }}>
        {storeError ? (
          <Alert severity="error">{storeError}</Alert>
        ) : (
          <Typography sx={{ color: 'text.secondary' }}>Prompt-Bibliothek wird geladen …</Typography>
        )}
      </Box>
    )
  }

  const canManage = holds(library.myRole, 'MANAGER')
  const canEditPrompts = holds(library.myRole, 'EDITOR')
  const activeTab: PromptLibraryTab = tab === undefined ? 'prompts' : (tab as PromptLibraryTab)
  if (tab !== undefined && (!isPromptLibraryTab(tab) || tab === 'prompts' || !canManage)) {
    return <Navigate to={promptLibraryRoute(promptLibraryId)} replace />
  }

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <Box sx={{ maxWidth: 880 }}>
        <Link
          component={RouterLink}
          to="/prompts"
          underline="hover"
          sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5, mb: 2, fontSize: 13 }}
        >
          <ArrowBackIcon fontSize="small" />
          Zurück zur Übersicht
        </Link>

        <Box component="header" sx={{ borderBottom: 1, borderColor: 'divider', pb: 2.5, mb: 3 }}>
          <Typography
            sx={{
              fontFamily: fontFamily.mono,
              fontSize: 10,
              fontWeight: 500,
              letterSpacing: '0.08em',
              color: 'primary.main',
              mb: 0.5,
            }}
          >
            PROMPT-BIBLIOTHEK
          </Typography>
          <Stack
            direction="row"
            spacing={1.5}
            sx={{ alignItems: 'center', flexWrap: 'wrap', mb: 0.5 }}
          >
            <PageHeading title={library.name} />
            <MetaBadge accent>{assetRoleLabel(library.myRole)}</MetaBadge>
          </Stack>
          {library.description && (
            <Typography sx={{ fontSize: 13.5, color: 'text.secondary', maxWidth: 640 }}>
              {library.description}
            </Typography>
          )}
          <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.5 }}>
            {[
              library.ownerName ? `Eigentum: ${library.ownerName}` : null,
              library.promptCount === 1 ? '1 Prompt' : `${library.promptCount} Prompts`,
            ]
              .filter(Boolean)
              .join(' · ')}
          </Typography>
          {/* ADR-0036, Entscheidung 6: state and addressee for every reader - no date, no former
              owner, no reason. */}
          <Box sx={{ maxWidth: 640 }}>
            <SuccessionStateNote succession={library.succession} />
          </Box>
        </Box>

        {canManage ? (
          <AreaTabs
            tabs={tabs}
            value={activeTab}
            href={(value) => promptLibraryRoute(promptLibraryId, value)}
            label="Bereiche der Prompt-Bibliothek"
            idPrefix="prompt-library"
          >
            {(value) =>
              value === 'settings' ? (
                <SettingsArea library={library} />
              ) : (
                <PromptsArea library={library} canEditPrompts={canEditPrompts} />
              )
            }
          </AreaTabs>
        ) : (
          <PromptsArea library={library} canEditPrompts={canEditPrompts} />
        )}
      </Box>
    </Box>
  )
}
