import { useId, useState, type ReactNode } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import FormControlLabel from '@mui/material/FormControlLabel'
import FormHelperText from '@mui/material/FormHelperText'
import InputAdornment from '@mui/material/InputAdornment'
import Radio from '@mui/material/Radio'
import RadioGroup from '@mui/material/RadioGroup'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'
import { TextAction } from '@/shared/ui/DetailSection'
import { AssetTilePicker, type AssetPick } from '@/features/asset'
import type { AssetType } from '@/shared/api/types'
import {
  AGENT_TOOLS,
  INSTRUCTION_HELP,
  INSTRUCTION_PLACEHOLDER,
  LIMIT_FIELDS,
  NAME_PLACEHOLDER,
  PLAN_STEP_OPTIONS,
  countLabel,
} from '../agentTexts'
import type { AgentSetup } from '../hooks/useAgentSetup'
import type { AgentFileFormat, AgentOutputKind, AgentPlanStep } from '../types'

interface FieldProps {
  setup: AgentSetup
  idPrefix: string
}

/** Name, optional description and the instruction that adds to OPAA's own rules. */
export function BasicsFields({
  setup,
  idPrefix,
  instructionRows = 4,
}: FieldProps & { instructionRows?: number }) {
  const { config, patch, errors } = setup
  return (
    <>
      <Box>
        <FieldLabel htmlFor={`${idPrefix}-name`}>Name</FieldLabel>
        <TextField
          id={`${idPrefix}-name`}
          size="small"
          fullWidth
          value={config.name}
          placeholder={NAME_PLACEHOLDER}
          onChange={(e) => patch({ name: e.target.value })}
          error={Boolean(errors.name)}
          helperText={errors.name}
          slotProps={{ htmlInput: { maxLength: 120 } }}
        />
      </Box>
      <Box>
        <FieldLabel htmlFor={`${idPrefix}-description`}>Beschreibung (optional)</FieldLabel>
        <TextField
          id={`${idPrefix}-description`}
          size="small"
          fullWidth
          value={config.description}
          onChange={(e) => patch({ description: e.target.value })}
          slotProps={{ htmlInput: { maxLength: 300 } }}
        />
      </Box>
      <Box>
        <FieldLabel htmlFor={`${idPrefix}-unit`}>Verantwortliche Stelle</FieldLabel>
        <TextField
          id={`${idPrefix}-unit`}
          size="small"
          fullWidth
          value={config.responsibleUnit}
          placeholder="z. B. Referat 21"
          onChange={(e) => patch({ responsibleUnit: e.target.value })}
          error={Boolean(errors.responsibleUnit)}
          helperText={errors.responsibleUnit}
          slotProps={{ htmlInput: { maxLength: 120 } }}
        />
      </Box>
      <Box>
        <FieldLabel htmlFor={`${idPrefix}-instruction`}>Anweisung</FieldLabel>
        <TextField
          id={`${idPrefix}-instruction`}
          size="small"
          fullWidth
          multiline
          minRows={instructionRows}
          value={config.instruction}
          placeholder={INSTRUCTION_PLACEHOLDER}
          onChange={(e) => patch({ instruction: e.target.value })}
          error={Boolean(errors.instruction)}
          helperText={errors.instruction ?? INSTRUCTION_HELP}
          slotProps={{ htmlInput: { maxLength: 4000 } }}
        />
      </Box>
    </>
  )
}

/** The five tools as checkboxes with one line each. */
export function ToolsField({ setup, idPrefix }: FieldProps) {
  const { config, patch, errors } = setup
  const titleId = `${idPrefix}-tools-title`
  return (
    <Box role="group" aria-labelledby={titleId}>
      <FieldLabel id={titleId}>Werkzeuge</FieldLabel>
      <Box sx={{ display: 'flex', flexDirection: 'column' }}>
        {AGENT_TOOLS.map((tool) => {
          const checked = config.tools.includes(tool.id)
          const descriptionId = `${idPrefix}-tool-${tool.id}-description`
          return (
            <Box key={tool.id} sx={{ display: 'flex', alignItems: 'flex-start', py: 0.25 }}>
              <Checkbox
                size="small"
                checked={checked}
                onChange={() =>
                  patch({
                    tools: checked
                      ? config.tools.filter((id) => id !== tool.id)
                      : [...config.tools, tool.id],
                  })
                }
                slotProps={{
                  input: {
                    'aria-describedby': descriptionId,
                    'aria-label': tool.label,
                  },
                }}
                sx={{ mt: -0.5 }}
              />
              <Box sx={{ minWidth: 0 }}>
                <Typography sx={{ fontSize: 14, fontWeight: 500 }}>{tool.label}</Typography>
                <Typography id={descriptionId} sx={{ fontSize: 12.5, color: 'text.secondary' }}>
                  {tool.description}
                </Typography>
              </Box>
            </Box>
          )
        })}
      </Box>
      {errors.tools && <FormHelperText error>{errors.tools}</FormHelperText>}
    </Box>
  )
}

/** Plan step as a row of three radios. */
export function PlanField({
  setup,
  idPrefix,
  withoutOff = false,
}: FieldProps & { withoutOff?: boolean }) {
  const { config, patch } = setup
  const titleId = `${idPrefix}-plan-title`
  return (
    <Box>
      <FieldLabel id={titleId}>Plan vor dem Start</FieldLabel>
      <RadioGroup
        row
        aria-labelledby={titleId}
        value={config.planStep}
        onChange={(e) => patch({ planStep: e.target.value as AgentPlanStep })}
      >
        {PLAN_STEP_OPTIONS.filter((option) => !withoutOff || option.value !== 'OFF').map(
          (option) => (
            <FormControlLabel
              key={option.value}
              value={option.value}
              control={<Radio size="small" />}
              label={option.label}
              sx={{ mr: 3 }}
            />
          ),
        )}
      </RadioGroup>
    </Box>
  )
}

export function ContradictionsField({ setup }: FieldProps) {
  const { config, patch } = setup
  return (
    <FormControlLabel
      control={
        <Switch
          checked={config.namesContradictions}
          onChange={(e) => patch({ namesContradictions: e.target.checked })}
        />
      }
      label="Widersprüche benennen"
    />
  )
}

/** The three limits; each may only stay at or below the installation's ceiling. */
export function LimitsField({ setup, idPrefix }: FieldProps) {
  const { config, patch, errors, systemLimits } = setup
  const titleId = `${idPrefix}-limits-title`
  return (
    <Box role="group" aria-labelledby={titleId}>
      <FieldLabel id={titleId}>Grenzen je Lauf</FieldLabel>
      <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 2 }}>
        {LIMIT_FIELDS.map((field) => (
          <TextField
            key={field.key}
            id={`${idPrefix}-limit-${field.key}`}
            size="small"
            type="number"
            value={Number.isNaN(config.limits[field.key]) ? '' : config.limits[field.key]}
            onChange={(e) =>
              patch({ limits: { ...config.limits, [field.key]: Number(e.target.value) } })
            }
            error={Boolean(errors[field.key])}
            helperText={errors[field.key]}
            sx={{ width: 168 }}
            slotProps={{
              htmlInput: {
                min: 1,
                max: systemLimits.max[field.key],
                'aria-label': `${field.label} (höchstens ${systemLimits.max[field.key]})`,
              },
              input: {
                endAdornment: <InputAdornment position="end">{field.unit}</InputAdornment>,
              },
            }}
          />
        ))}
      </Box>
      <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 1 }}>
        Höchstens {systemLimits.max.calls} Werkzeugaufrufe · {systemLimits.max.minutes} Minuten ·{' '}
        {systemLimits.max.pages} Seiten
      </Typography>
    </Box>
  )
}

/**
 * A choice of assets as one summary row with a text action; the catalog's tile choice opens in a
 * dialog (guidelines 5.11, Assets).
 */
export function AssetChoiceRow({
  title,
  dialogTitle,
  assetType,
  picks,
  onChange,
  emptyText,
  error,
  unitOne,
  unitMany,
}: {
  title: string
  dialogTitle: string
  assetType: AssetType
  picks: AssetPick[]
  onChange: (picks: AssetPick[]) => void
  emptyText: string
  error?: string
  unitOne: string
  unitMany: string
}) {
  const [open, setOpen] = useState(false)
  const [draft, setDraft] = useState<AssetPick[]>(picks)
  const titleId = useId()
  const names = picks.map((pick) => pick.name)
  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 2 }}>
        <FieldLabel id={titleId}>{title}</FieldLabel>
        <Box sx={{ flex: 1 }} />
        <TextAction
          context={title}
          onClick={() => {
            setDraft(picks)
            setOpen(true)
          }}
        >
          {picks.length === 0 ? 'Auswählen' : 'Ändern'}
        </TextAction>
      </Box>
      <Typography
        aria-labelledby={titleId}
        sx={{
          fontSize: 14,
          color: picks.length === 0 ? 'text.secondary' : 'text.primary',
          overflowWrap: 'anywhere',
        }}
      >
        {picks.length === 0
          ? emptyText
          : names.length <= 3
            ? names.join(' · ')
            : `${countLabel(names.length, unitOne, unitMany)}: ${names.slice(0, 2).join(' · ')} …`}
      </Typography>
      {error && <FormHelperText error>{error}</FormHelperText>}
      <Dialog open={open} onClose={() => setOpen(false)} fullWidth maxWidth="md">
        <DialogTitle>{dialogTitle}</DialogTitle>
        <DialogContent>
          <AssetTilePicker
            types={[assetType]}
            value={draft}
            onChange={setDraft}
            aria-label={dialogTitle}
          />
        </DialogContent>
        <DialogActions>
          <Button variant="text" onClick={() => setOpen(false)}>
            Abbrechen
          </Button>
          <Button
            variant="contained"
            onClick={() => {
              onChange(draft)
              setOpen(false)
            }}
          >
            Übernehmen
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}

export function LibrariesField({ setup }: { setup: AgentSetup }) {
  return (
    <AssetChoiceRow
      title="Bibliotheken"
      dialogTitle="Bibliotheken auswählen"
      assetType="KNOWLEDGE_LIBRARY"
      picks={setup.libraryPicks}
      onChange={setup.setLibraries}
      emptyText="Keine – der Agent sucht in allem, was die aufrufende Person lesen darf."
      unitOne="Bibliothek"
      unitMany="Bibliotheken"
    />
  )
}

function templatePicks(setup: AgentSetup): AssetPick[] {
  return setup.config.templateIds.map((id) => {
    const template = setup.templates.find((candidate) => candidate.id === id)
    return {
      assetType: 'DOCUMENT_TEMPLATE',
      assetId: id,
      name: template?.name ?? id,
      description: template?.description ?? null,
    }
  })
}

/** Chat answer or a file after a template; the template and the format only for a file. */
export function OutputField({
  setup,
  idPrefix,
  children,
  kindFixed = false,
}: FieldProps & { children?: ReactNode; kindFixed?: boolean }) {
  const { config, patch, errors, templates } = setup
  const titleId = `${idPrefix}-output-title`
  const formatId = `${idPrefix}-format-title`
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
      {!kindFixed && (
        <Box>
          <FieldLabel id={titleId}>Ergebnis</FieldLabel>
          <RadioGroup
            row
            aria-labelledby={titleId}
            value={config.output}
            onChange={(e) => patch({ output: e.target.value as AgentOutputKind })}
          >
            <FormControlLabel
              value="CHAT"
              control={<Radio size="small" />}
              label="Antwort im Chat"
              sx={{ mr: 3 }}
            />
            <FormControlLabel
              value="FILE"
              control={<Radio size="small" />}
              label="Datei nach Vorlage"
              disabled={templates.length === 0}
            />
          </RadioGroup>
          {templates.length === 0 && (
            <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
              Für eine Datei braucht es eine Vorlage im Katalog.
            </Typography>
          )}
        </Box>
      )}
      {config.output === 'FILE' && (
        <>
          <AssetChoiceRow
            title="Vorlage"
            dialogTitle="Vorlage auswählen"
            assetType="DOCUMENT_TEMPLATE"
            picks={templatePicks(setup)}
            onChange={(picks) => patch({ templateIds: picks.map((pick) => pick.assetId) })}
            emptyText="Noch keine gewählt."
            error={errors.templateIds}
            unitOne="Vorlage"
            unitMany="Vorlagen"
          />
          <Box>
            <FieldLabel id={formatId}>Format</FieldLabel>
            <RadioGroup
              row
              aria-labelledby={formatId}
              value={config.fileFormat}
              onChange={(e) => patch({ fileFormat: e.target.value as AgentFileFormat })}
            >
              <FormControlLabel
                value="PDF"
                control={<Radio size="small" />}
                label="PDF"
                sx={{ mr: 3 }}
              />
              <FormControlLabel value="DOCX" control={<Radio size="small" />} label="Word" />
            </RadioGroup>
          </Box>
        </>
      )}
      {children}
    </Box>
  )
}
