import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import SmartToyOutlinedIcon from '@mui/icons-material/SmartToyOutlined'
import NoteAddOutlinedIcon from '@mui/icons-material/NoteAddOutlined'
import ChoiceTileGroup from '@/shared/ui/choice/ChoiceTileGroup'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'
import WizardStepBar from '@/shared/ui/wizard/WizardStepBar'
import { getAgents } from '../api/agentApi'
import {
  defaultAgentConfig,
  startingPointConfig,
  useAgentSetup,
  type AgentField,
} from '../hooks/useAgentSetup'
import {
  BasicsFields,
  LibrariesField,
  OutputField,
  PlanField,
  ToolsField,
} from '../components/AgentFields'
import {
  AdvancedSettings,
  AgentPageFrame,
  CancelButton,
  SaveButton,
} from '../components/AgentPageFrame'
import { AgentSummaryRows, type SummaryArea } from '../components/AgentSummary'
import type { AgentResponse } from '../types'

const STEPS = ['Auftrag', 'Wissen', 'Ablauf', 'Ergebnis', 'Prüfen'] as const
const STEP_TITLES = [
  'Was soll der Agent tun?',
  'Worauf greift er zurück?',
  'Wie geht er vor?',
  'Was liefert er?',
  'Prüfen',
] as const
const STEP_FIELDS: AgentField[][] = [
  ['name', 'responsibleUnit', 'instruction'],
  [],
  ['tools', 'calls', 'minutes', 'pages'],
  ['templateIds'],
  [],
]
const AREA_STEP: Record<SummaryArea, number> = {
  auftrag: 0,
  stelle: 0,
  wissen: 1,
  werkzeuge: 1,
  ablauf: 2,
  ergebnis: 3,
}

/**
 * Variant B: a guided wizard with one question per step; a new agent may start from a delivered
 * one. Editing opens on the review step, whose „Ändern“ jumps back.
 */
export default function WizardVariant({ agentId }: { agentId?: string }) {
  const setup = useAgentSetup(agentId)
  const navigate = useNavigate()
  const editing = agentId !== undefined
  const [step, setStep] = useState(editing ? STEPS.length - 1 : 0)
  const [returnToReview, setReturnToReview] = useState(false)
  const [startingPoints, setStartingPoints] = useState<AgentResponse[]>([])
  const [start, setStart] = useState('blank')
  const idPrefix = 'agent-wizard'
  const last = STEPS.length - 1

  useEffect(() => {
    if (editing) return
    void getAgents()
      .then((agents) => setStartingPoints(agents.filter((agent) => agent.builtIn)))
      .catch(() => setStartingPoints([]))
  }, [editing])

  const chooseStart = (value: string) => {
    setStart(value)
    const source = startingPoints.find((agent) => agent.id === value)
    const base = source ? startingPointConfig(source) : defaultAgentConfig(setup.systemLimits)
    void setup.replace({
      ...base,
      name: setup.config.name,
      description: setup.config.description,
      instruction: source ? base.instruction : setup.config.instruction,
    })
  }

  const next = () => {
    if (!setup.check(STEP_FIELDS[step])) return
    setStep(returnToReview ? last : step + 1)
    setReturnToReview(false)
  }

  return (
    <AgentPageFrame
      setup={setup}
      eyebrow={editing ? (setup.agent?.name ?? 'Agent') : 'Neuer Agent'}
      title={STEP_TITLES[step]}
      footer={
        <>
          <CancelButton setup={setup} to={editing ? `/agents/${agentId}` : undefined} />
          <Box sx={{ flex: 1 }} />
          {step > 0 && (
            <Button variant="outlined" onClick={() => setStep(step - 1)} disabled={setup.saving}>
              Zurück
            </Button>
          )}
          {step < last ? (
            <Button variant="contained" onClick={next}>
              {returnToReview ? 'Zurück zur Prüfung' : 'Weiter'}
            </Button>
          ) : (
            <SaveButton
              setup={setup}
              label={editing ? 'Speichern' : 'Agent anlegen'}
              onSaved={(id) => navigate(`/agents/${id}`)}
            />
          )}
        </>
      }
    >
      <WizardStepBar steps={STEPS} active={step} />
      <Box
        component="form"
        noValidate
        onSubmit={(e) => e.preventDefault()}
        sx={{ display: 'flex', flexDirection: 'column', gap: 2.5, mt: 1 }}
      >
        {step === 0 && (
          <>
            {!editing && startingPoints.length > 0 && (
              <Box>
                <FieldLabel id={`${idPrefix}-start`}>Ausgangspunkt</FieldLabel>
                <ChoiceTileGroup<string>
                  aria-labelledby={`${idPrefix}-start`}
                  value={start}
                  onChange={chooseStart}
                  tiles={[
                    {
                      value: 'blank',
                      label: 'Leer',
                      description: 'Suchen und Lesen, Antwort im Chat.',
                      icon: <NoteAddOutlinedIcon sx={{ fontSize: 22 }} />,
                    },
                    ...startingPoints.map((agent) => ({
                      value: agent.id,
                      label: `Wie „${agent.name}“`,
                      description: 'Übernimmt Anweisung, Werkzeuge und Ablauf.',
                      icon: <SmartToyOutlinedIcon sx={{ fontSize: 22 }} />,
                    })),
                  ]}
                />
              </Box>
            )}
            <BasicsFields setup={setup} idPrefix={idPrefix} />
          </>
        )}
        {step === 1 && (
          <>
            <LibrariesField setup={setup} />
            <ToolsField setup={setup} idPrefix={idPrefix} />
          </>
        )}
        {step === 2 && (
          <>
            <PlanField setup={setup} idPrefix={idPrefix} />
            <AdvancedSettings setup={setup} idPrefix={idPrefix} />
          </>
        )}
        {step === 3 && <OutputField setup={setup} idPrefix={idPrefix} />}
        {step === 4 && (
          <AgentSummaryRows
            setup={setup}
            onChange={(area) => {
              setReturnToReview(true)
              setStep(AREA_STEP[area])
            }}
          />
        )}
      </Box>
    </AgentPageFrame>
  )
}
