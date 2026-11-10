import { fieldWidth, fontSize } from '@/shared/theme/tokens'
import { useEffect, useMemo, useState } from 'react'
import Accordion from '@mui/material/Accordion'
import AccordionDetails from '@mui/material/AccordionDetails'
import AccordionSummary from '@mui/material/AccordionSummary'
import Box from '@mui/material/Box'
import Checkbox from '@mui/material/Checkbox'
import FormControlLabel from '@mui/material/FormControlLabel'
import FormHelperText from '@mui/material/FormHelperText'
import Radio from '@mui/material/Radio'
import RadioGroup from '@mui/material/RadioGroup'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import ValueRow from '@/shared/ui/ValueRow'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import type {
  AgentLimits,
  AgentPlanStep,
  AgentResponse,
  AgentUpdateRequest,
  LibraryListResponse,
} from '@/shared/api/types'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'
import ListSearch from '@/shared/ui/ListSearch'
import { getReadableLibraries } from '../api/agentApi'
import EditableRow from './EditableRow'
import {
  AGENT_TOOLS,
  INSTRUCTION_HELP,
  INSTRUCTION_MAX,
  INSTRUCTION_PLACEHOLDER,
  LIMIT_FIELDS,
  PLAN_STEP_OPTIONS,
  limitsSummary,
  planStepLabel,
  toolLabel,
  type LimitKey,
} from './agentTexts'

type Save = (change: Partial<AgentUpdateRequest>) => Promise<void>

const muted = { color: 'text.secondary' } as const

/** Area „Auftrag“: the instruction that complements OPAA's fixed rules. */
export function InstructionArea({
  agent,
  canEdit,
  save,
}: {
  agent: AgentResponse
  canEdit: boolean
  save: Save
}) {
  const [draft, setDraft] = useState(agent.instruction ?? '')
  const id = 'agent-instruction'
  if (agent.instruction === null) {
    return (
      <ValueRow
        title="Auftrag"
        value={
          <Typography sx={{ fontSize: fontSize.text, ...muted }}>
            Den Auftrag sieht nur, wer den Agenten selbst nutzen darf; Verwaltungsrechte genügen
            dafür nicht.
          </Typography>
        }
      />
    )
  }
  return (
    <EditableRow
      idPrefix={id}
      title="Auftrag"
      canEdit={canEdit}
      fieldLabels={{ instruction: 'Auftrag' }}
      onOpen={() => setDraft(agent.instruction ?? '')}
      onSave={() => save({ instruction: draft.trim() })}
      value={
        agent.instruction ? (
          <Typography
            sx={{ fontSize: fontSize.text, whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}
          >
            {agent.instruction}
          </Typography>
        ) : (
          <Typography sx={{ fontSize: fontSize.text, ...muted }}>
            Noch kein Auftrag. Der Agent arbeitet nur nach den festen Regeln von OPAA.
          </Typography>
        )
      }
      editor={(errors) => (
        <Box id={`${id}-instruction`}>
          <FieldLabel htmlFor={`${id}-field`}>Auftrag</FieldLabel>
          <TextField
            id={`${id}-field`}
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            placeholder={INSTRUCTION_PLACEHOLDER}
            multiline
            minRows={6}
            fullWidth
            error={Boolean(errors.instruction)}
            helperText={errors.instruction ?? INSTRUCTION_HELP}
            slotProps={{ htmlInput: { maxLength: INSTRUCTION_MAX } }}
          />
        </Box>
      )}
    />
  )
}

/** Above this many libraries the choice gets a filter field. */
const FILTER_FROM = 8

/** Area „Wissen und Werkzeuge“: the libraries a run may read and the tools it may call. */
export function KnowledgeAndToolsArea({
  agent,
  canEdit,
  save,
}: {
  agent: AgentResponse
  canEdit: boolean
  save: Save
}) {
  const [libraries, setLibraries] = useState<LibraryListResponse[] | null>(null)
  const [libraryError, setLibraryError] = useState<string | null>(null)
  const [libraryDraft, setLibraryDraft] = useState<string[]>([])
  const [toolDraft, setToolDraft] = useState<string[]>([])
  const [filter, setFilter] = useState('')

  useEffect(() => {
    if (!canEdit) return
    let active = true
    getReadableLibraries()
      .then((loaded) => active && setLibraries(loaded))
      .catch(() => active && setLibraryError('Die Bibliotheken konnten nicht geladen werden.'))
    return () => {
      active = false
    }
  }, [canEdit])

  const shown = useMemo(() => {
    const needle = filter.trim().toLocaleLowerCase('de-DE')
    const all = [...(libraries ?? [])].sort((a, b) => a.name.localeCompare(b.name, 'de'))
    return needle ? all.filter((l) => l.name.toLocaleLowerCase('de-DE').includes(needle)) : all
  }, [libraries, filter])

  const toggle = (list: string[], value: string) =>
    list.includes(value) ? list.filter((entry) => entry !== value) : [...list, value]

  const libraryValue =
    agent.libraries.length === 0 && agent.hiddenLibraryCount === 0 ? (
      <Typography sx={{ fontSize: fontSize.text }}>
        Alles, was die aufrufende Person lesen darf
      </Typography>
    ) : (
      <Box>
        {agent.libraries.length > 0 && (
          <Box component="ul" sx={{ m: 0, pl: 2.5, fontSize: fontSize.text }}>
            {agent.libraries.map((library) => (
              <li key={library.id}>{library.name}</li>
            ))}
          </Box>
        )}
        {agent.hiddenLibraryCount > 0 && (
          <Typography sx={{ fontSize: fontSize.meta, mt: 0.5, ...muted }}>
            {agent.hiddenLibraryCount === 1
              ? 'Dazu 1 Bibliothek, die Sie nicht lesen dürfen. Sie bleibt beim Speichern erhalten und wird für Sie nie gelesen.'
              : `Dazu ${agent.hiddenLibraryCount} Bibliotheken, die Sie nicht lesen dürfen. Sie bleiben beim Speichern erhalten und werden für Sie nie gelesen.`}
          </Typography>
        )}
      </Box>
    )

  return (
    <Box>
      <EditableRow
        idPrefix="agent-libraries"
        title="Wissen"
        canEdit={canEdit}
        fieldLabels={{ libraryIds: 'Wissen' }}
        onOpen={() => {
          setLibraryDraft(agent.libraries.map((library) => library.id))
          setFilter('')
        }}
        onSave={() => save({ libraryIds: libraryDraft })}
        value={libraryValue}
        editor={(errors) => (
          <Box id="agent-libraries-libraryIds" role="group" aria-labelledby="agent-libraries-label">
            <FieldLabel id="agent-libraries-label">Bibliotheken</FieldLabel>
            <Typography sx={{ fontSize: fontSize.meta, mb: 1, ...muted }}>
              Ohne Auswahl liest der Agent alles, was die aufrufende Person lesen darf. Eine Auswahl
              grenzt ein, erweitert aber nie: Wer eine Bibliothek nicht lesen darf, erhält auch über
              den Agenten nichts daraus.
            </Typography>
            {libraryError && <FormHelperText error>{libraryError}</FormHelperText>}
            {libraries === null && !libraryError && (
              <Typography sx={{ fontSize: fontSize.text, ...muted }}>
                Bibliotheken werden geladen …
              </Typography>
            )}
            {libraries !== null && libraries.length >= FILTER_FROM && (
              <Box sx={{ mb: 1, maxWidth: fieldWidth.medium }}>
                <ListSearch
                  value={filter}
                  onChange={setFilter}
                  placeholder="z. B. Vergabe"
                  label="Bibliotheken filtern"
                />
              </Box>
            )}
            {libraries !== null && libraries.length === 0 && (
              <Typography sx={{ fontSize: fontSize.text, ...muted }}>
                Sie dürfen noch keine Bibliothek lesen.
              </Typography>
            )}
            {libraries !== null && libraries.length > 0 && shown.length === 0 && (
              <Typography sx={{ fontSize: fontSize.text, ...muted }}>
                Keine Bibliothek passt zu „{filter.trim()}“.
              </Typography>
            )}
            <Box
              sx={{ display: 'flex', flexDirection: 'column', maxHeight: 320, overflowY: 'auto' }}
            >
              {shown.map((library) => (
                <FormControlLabel
                  key={library.id}
                  control={
                    <Checkbox
                      size="small"
                      checked={libraryDraft.includes(library.id)}
                      onChange={() => setLibraryDraft((list) => toggle(list, library.id))}
                    />
                  }
                  label={library.name}
                  sx={{
                    '& .MuiFormControlLabel-label': { overflowWrap: 'anywhere', hyphens: 'auto' },
                  }}
                />
              ))}
            </Box>
            {errors.libraryIds && <FormHelperText error>{errors.libraryIds}</FormHelperText>}
          </Box>
        )}
      />
      <EditableRow
        idPrefix="agent-tools"
        title="Werkzeuge"
        canEdit={canEdit}
        fieldLabels={{ toolNames: 'Werkzeuge' }}
        onOpen={() => setToolDraft(agent.toolNames)}
        onSave={async () => {
          if (toolDraft.length === 0) return { toolNames: 'Wählen Sie mindestens ein Werkzeug.' }
          await save({
            toolNames: AGENT_TOOLS.map((tool) => tool.name).filter((name) =>
              toolDraft.includes(name),
            ),
          })
        }}
        value={
          <Typography sx={{ fontSize: fontSize.text }}>
            {agent.toolNames.map(toolLabel).join(' · ')}
          </Typography>
        }
        editor={(errors) => (
          <Box id="agent-tools-toolNames" role="group" aria-labelledby="agent-tools-label">
            <FieldLabel id="agent-tools-label">Werkzeuge</FieldLabel>
            {AGENT_TOOLS.map((tool) => (
              <FormControlLabel
                key={tool.name}
                sx={{ display: 'flex', alignItems: 'flex-start', mb: 0.5 }}
                control={
                  <Checkbox
                    size="small"
                    checked={toolDraft.includes(tool.name)}
                    onChange={() => setToolDraft((list) => toggle(list, tool.name))}
                    sx={{ pt: 0.25 }}
                  />
                }
                label={
                  <Box>
                    <Typography sx={{ fontSize: fontSize.text }}>{tool.label}</Typography>
                    <Typography sx={{ fontSize: fontSize.meta, ...muted }}>
                      {tool.description}
                    </Typography>
                  </Box>
                }
              />
            ))}
            <Typography sx={{ fontSize: fontSize.meta, mt: 0.5, ...muted }}>
              Ein Werkzeug, das die Systemverwaltung abgeschaltet hat, bietet ein Lauf nicht an.
            </Typography>
            {errors.toolNames && <FormHelperText error>{errors.toolNames}</FormHelperText>}
          </Box>
        )}
      />
    </Box>
  )
}

/** Area „Ablauf“: the plan step, and the agent's own limits under „Erweitert“. */
export function ProcedureArea({
  agent,
  canEdit,
  save,
}: {
  agent: AgentResponse
  canEdit: boolean
  save: Save
}) {
  const [planDraft, setPlanDraft] = useState<AgentPlanStep>(agent.planStep)
  const [limitDraft, setLimitDraft] = useState<Record<LimitKey, string>>(limitStrings(agent.limits))
  const ownLimits = LIMIT_FIELDS.some((field) => agent.limits[field.key] != null)

  return (
    <Box>
      <EditableRow
        idPrefix="agent-plan"
        title="Plan"
        canEdit={canEdit}
        fieldLabels={{ planStep: 'Plan' }}
        onOpen={() => setPlanDraft(agent.planStep)}
        onSave={() => save({ planStep: planDraft })}
        value={
          <Typography sx={{ fontSize: fontSize.text }}>{planStepLabel(agent.planStep)}</Typography>
        }
        editor={() => (
          <Box id="agent-plan-planStep">
            <FieldLabel id="agent-plan-label">Plan</FieldLabel>
            <RadioGroup
              aria-labelledby="agent-plan-label"
              value={planDraft}
              onChange={(e) => setPlanDraft(e.target.value as AgentPlanStep)}
            >
              {PLAN_STEP_OPTIONS.map((option) => (
                <FormControlLabel
                  key={option.value}
                  value={option.value}
                  control={<Radio size="small" />}
                  label={
                    <Box>
                      <Typography sx={{ fontSize: fontSize.text }}>{option.label}</Typography>
                      <Typography sx={{ fontSize: fontSize.meta, ...muted }}>
                        {option.description}
                      </Typography>
                    </Box>
                  }
                  sx={{ alignItems: 'flex-start', mb: 0.5, '& .MuiRadio-root': { pt: 0.25 } }}
                />
              ))}
            </RadioGroup>
          </Box>
        )}
      />
      <Accordion
        defaultExpanded={ownLimits}
        disableGutters
        elevation={0}
        sx={{ mt: 2, '&::before': { display: 'none' } }}
        slotProps={{ heading: { component: 'h2' } }}
      >
        <AccordionSummary
          expandIcon={<ExpandMoreIcon />}
          aria-controls="agent-advanced-content"
          id="agent-advanced-header"
          sx={{ px: 0 }}
        >
          <Typography component="span" sx={{ fontSize: fontSize.title, fontWeight: 600 }}>
            Erweitert
          </Typography>
        </AccordionSummary>
        <AccordionDetails id="agent-advanced-content" sx={{ px: 0 }}>
          <EditableRow
            idPrefix="agent-limits"
            title="Grenzen"
            canEdit={canEdit}
            fieldLabels={Object.fromEntries(
              LIMIT_FIELDS.map((field) => [`limits.${field.key}`, field.label]),
            )}
            onOpen={() => setLimitDraft(limitStrings(agent.limits))}
            onSave={async () => {
              const parsed = parseLimits(limitDraft)
              if ('errors' in parsed) return parsed.errors
              await save({ limits: parsed.limits })
            }}
            value={
              <Box>
                <Typography sx={{ fontSize: fontSize.text }}>
                  {limitsSummary(agent.limits)}
                </Typography>
                <Typography sx={{ fontSize: fontSize.meta, ...muted }}>
                  Ein leeres Feld übernimmt die Grenze der Installation; ein Lauf nimmt immer den
                  kleineren Wert.
                </Typography>
              </Box>
            }
            editor={(errors) => (
              <Box
                sx={{
                  display: 'grid',
                  gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' },
                  gap: 2,
                }}
              >
                {LIMIT_FIELDS.map((field) => (
                  <Box key={field.key} id={`agent-limits-limits.${field.key}`}>
                    <FieldLabel htmlFor={`agent-limit-${field.key}`}>
                      {`${field.label} (${field.unit})`}
                    </FieldLabel>
                    <TextField
                      id={`agent-limit-${field.key}`}
                      size="small"
                      value={limitDraft[field.key]}
                      onChange={(e) =>
                        setLimitDraft((draft) => ({ ...draft, [field.key]: e.target.value }))
                      }
                      placeholder="wie Installation"
                      fullWidth
                      error={Boolean(errors[`limits.${field.key}`])}
                      helperText={errors[`limits.${field.key}`]}
                      slotProps={{ htmlInput: { inputMode: 'numeric' } }}
                    />
                  </Box>
                ))}
              </Box>
            )}
          />
        </AccordionDetails>
      </Accordion>
    </Box>
  )
}

function limitStrings(limits: AgentLimits): Record<LimitKey, string> {
  return {
    maxToolCalls: limits.maxToolCalls?.toString() ?? '',
    maxSearchCalls: limits.maxSearchCalls?.toString() ?? '',
    maxDurationSeconds: limits.maxDurationSeconds?.toString() ?? '',
    maxCollectedChars: limits.maxCollectedChars?.toString() ?? '',
  }
}

function parseLimits(
  draft: Record<LimitKey, string>,
): { limits: AgentLimits } | { errors: Record<string, string> } {
  const limits: AgentLimits = {}
  const errors: Record<string, string> = {}
  for (const field of LIMIT_FIELDS) {
    const raw = draft[field.key].trim()
    if (raw === '') {
      limits[field.key] = null
      continue
    }
    const value = Number(raw)
    if (!Number.isInteger(value) || value < 1) {
      errors[`limits.${field.key}`] = 'Eine ganze Zahl ab 1 oder leer.'
    } else {
      limits[field.key] = value
    }
  }
  return Object.keys(errors).length > 0 ? { errors } : { limits }
}
