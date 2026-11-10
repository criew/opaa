import { useEffect, useState, type ReactNode } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import IconButton from '@mui/material/IconButton'
import MenuItem from '@mui/material/MenuItem'
import Select from '@mui/material/Select'
import Typography from '@mui/material/Typography'
import DeleteIcon from '@mui/icons-material/Delete'
import GroupsOutlinedIcon from '@mui/icons-material/GroupsOutlined'
import PersonOutlineIcon from '@mui/icons-material/PersonOutlined'
import { useLocation, useNavigate } from 'react-router'
import {
  AssetNameFields,
  AssetTilePicker,
  assetPickKey,
  useAssetCatalogEntry,
} from '@/features/asset'
import type { AssetPick, SpaceCreateLocationState } from '@/features/asset'
import { assetTypeDefinition } from '../../../shared/assetTypes'
import { SubjectFormRow } from '@/features/principal'
import { SubjectPicker } from '@/features/principal'
import {
  SUBJECT_CHOICE_MISSING,
  confirmExternalSubject,
  emptySubjectSelection,
  groupLabel,
  selectedSubjectId,
  useRequiredChoice,
  type SubjectSelection,
} from '@/features/principal'
import ChatAutoCleanupField from '../components/ChatAutoCleanupField'
import WizardLayout from '@/shared/ui/wizard/WizardLayout'
import WizardReview, { type WizardReviewEntry } from '@/shared/ui/wizard/WizardReview'
import { useFieldError } from '@/shared/ui/wizard/useFieldError'
import { STEP_MASTER_DATA, STEP_REVIEW } from '@/shared/ui/wizard/wizardSteps'
import { notify } from '@/shared/stores/notificationStore'
import { confirmAction } from '@/shared/stores/confirmStore'
import { useAuthStore } from '@/shared/session/authStore'
import { useSpaceStore } from '../stores/spaceStore'
import { useMyCapabilities } from '@/shared/session/useMyCapabilities'
import { getChatAutoCleanupPeriods } from '../api/spaceApi'
import { capabilityMissingMessage, spaceRoleLabel } from '@/shared/utils/labels'
import type { ChatAutoCleanupPeriods, PermissionSubjectType, SpaceRole } from '@/shared/api/types'

const STEPS = [STEP_MASTER_DATA, 'Mitglieder', 'Inhalte', STEP_REVIEW] as const
const STEP_MEMBERS = 1
const STEP_ASSETS = 2

export const NO_KNOWLEDGE_SUMMARY =
  'Kein Wissen zugeordnet — der Space durchsucht kein Wissen, bis Sie etwas zuordnen.'

const MEMBER_ROLES: SpaceRole[] = ['MEMBER', 'CURATOR', 'ADMIN']

interface PendingMember {
  subjectType: PermissionSubjectType
  subjectId: string
  label: string
  role: SpaceRole
}

function pendingKey(member: { subjectType: string; subjectId: string }): string {
  return `${member.subjectType}:${member.subjectId}`
}

/** One list item of the review: a small symbol, while the text carries the meaning. */
function SummaryItem({ icon, children }: { icon: ReactNode; children: ReactNode }) {
  return (
    <Box component="li" sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
      <Box component="span" aria-hidden sx={{ display: 'inline-flex', color: 'text.secondary' }}>
        {icon}
      </Box>
      <Typography component="span" variant="body2">
        {children}
      </Typography>
    </Box>
  )
}

const summaryListSx = { listStyle: 'none', m: 0, p: 0, display: 'grid', gap: 0.5 } as const

/**
 * The space creation wizard: Stammdaten, Mitglieder, Inhalte, Prüfen.
 * "Inhalte" associates assets of every type from the catalog's tile list and may be skipped; the
 * space and its associations are created in one call.
 */
export default function SpaceCreatePage() {
  const navigate = useNavigate()
  const location = useLocation()
  const preselect = (location.state as SpaceCreateLocationState | null)?.preselect
  const createNewSpace = useSpaceStore((s) => s.createNewSpace)
  const currentUserId = useAuthStore((s) => s.user?.id)

  const [activeStep, setActiveStep] = useState(0)
  // set by „Ändern“ on the review step: „Weiter“ then leads straight back to it
  const [returnToReview, setReturnToReview] = useState(false)
  const [name, setName] = useState('')
  const nameCheck = useFieldError('Name fehlt')
  const [description, setDescription] = useState('')
  const [chatAutoCleanup, setChatAutoCleanup] = useState(false)
  const [cleanupPeriods, setCleanupPeriods] = useState<ChatAutoCleanupPeriods | null>(null)
  const [pendingMembers, setPendingMembers] = useState<PendingMember[]>([])
  const [subject, setSubject] = useState<SubjectSelection>(emptySubjectSelection)
  const choice = useRequiredChoice(SUBJECT_CHOICE_MISSING)
  const [selectedRole, setSelectedRole] = useState<SpaceRole>('MEMBER')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // ADR-0036, Entscheidung 5: a missing Anlegerecht is explained, not hidden - „Anlegen“ stays
  // and leads to the reason. The backend refuses the same call regardless.
  const { isMissing } = useMyCapabilities()
  const mayNotCreate = isMissing('CREATE_SPACE')
  // Only what the creator may read is offered; the backend re-checks the same rule on creation.
  const [selectedAssets, setSelectedAssets] = useState<AssetPick[]>(preselect ? [preselect] : [])
  // Every choice here is a new association, which an open succession refuses (ADR-0036/6): a
  // handed-over asset with a frozen reach is let go, and the step "Inhalte" says why. Until its
  // catalog entry is in, the space is not created.
  const preselectEntry = useAssetCatalogEntry(
    preselect?.assetType ?? 'KNOWLEDGE_LIBRARY',
    preselect?.assetId ?? '',
  ).entry
  const preselectPending = Boolean(preselect) && preselectEntry === undefined
  const preselectFrozen = Boolean(preselect && preselectEntry?.succession)
  const [preselectDropped, setPreselectDropped] = useState(false)
  if (preselect && preselectFrozen && !preselectDropped) {
    setPreselectDropped(true)
    setSelectedAssets((current) =>
      current.filter((pick) => assetPickKey(pick) !== assetPickKey(preselect)),
    )
  }

  // The operator sets the periods; without an answer the switch names no numbers.
  useEffect(() => {
    let active = true
    getChatAutoCleanupPeriods()
      .then((periods) => {
        if (active) setCleanupPeriods(periods)
      })
      .catch(() => undefined)
    return () => {
      active = false
    }
  }, [])

  // The creator becomes the owner and ADMIN anyway; offering them would only be overruled.
  const excludedUserIds = [
    ...pendingMembers.filter((m) => m.subjectType === 'USER').map((m) => m.subjectId),
    ...(currentUserId ? [currentUserId] : []),
  ]
  const excludedGroupIds = pendingMembers
    .filter((m) => m.subjectType === 'GROUP')
    .map((m) => m.subjectId)

  const handleNoteMember = async () => {
    const subjectId = selectedSubjectId(subject)
    if (!choice.check(Boolean(subjectId))) return
    if (!subjectId || subject.type === 'ALL_ACCOUNTS') return
    if (!(await confirmExternalSubject(subject, 'add'))) return
    const label =
      subject.type === 'GROUP' && subject.group
        ? groupLabel(subject.group)
        : (subject.user?.displayName ?? subject.user?.email ?? subjectId)
    const subjectType: PermissionSubjectType = subject.type
    setPendingMembers((prev) => [...prev, { subjectType, subjectId, label, role: selectedRole }])
    setSubject(emptySubjectSelection)
  }

  const isDirty =
    name.trim() !== '' ||
    description.trim() !== '' ||
    pendingMembers.length > 0 ||
    selectedAssets.length > 0

  const handleCancel = async () => {
    if (isDirty) {
      const confirmed = await confirmAction({
        question: 'Eingaben verwerfen?',
        consequence: 'Der Assistent schließt, ohne etwas anzulegen; Ihre Eingaben gehen verloren.',
        confirmLabel: 'Verwerfen',
        tone: 'caution',
      })
      if (!confirmed) return
    }
    navigate('/spaces')
  }

  const handleNext = () => {
    if (activeStep === 0 && !nameCheck.check(name.trim() !== '')) return
    setActiveStep((s) => (returnToReview ? STEPS.length - 1 : s + 1))
    setReturnToReview(false)
  }

  /** „Ändern“ on the review step: to the step of the answer, and back after „Weiter“. */
  const changeAt = (step: number) => {
    setReturnToReview(true)
    setError(null)
    setActiveStep(step)
  }

  const handleCreate = async () => {
    // the catalog entry of the preselection decides whether it may be associated
    if (submitting || preselectPending) return
    setSubmitting(true)
    setError(null)
    try {
      const spaceId = await createNewSpace(
        name.trim(),
        description.trim(),
        selectedAssets.map(({ assetType, assetId }) => ({ assetType, assetId })),
        chatAutoCleanup,
        pendingMembers.map(({ subjectType, subjectId, role }) => ({
          subjectType,
          subjectId,
          role,
        })),
      )
      notify(`„${name.trim()}“ wurde angelegt.`, 'success')
      navigate(`/spaces/${spaceId}`)
    } catch (err) {
      // Space, members and assets are created together or not at all - nothing to clean up here.
      const reason = err instanceof Error ? err.message : null
      setError(
        `Der Space wurde nicht angelegt${reason ? `: ${reason}` : '.'} Prüfen Sie Mitglieder und Inhalte und versuchen Sie es erneut.`,
      )
      setSubmitting(false)
    }
  }

  const memberIcon = (member: PendingMember) =>
    member.subjectType === 'GROUP' ? (
      <GroupsOutlinedIcon fontSize="inherit" />
    ) : (
      <PersonOutlineIcon fontSize="inherit" />
    )

  const reviewEntries: WizardReviewEntry[] = [
    { label: 'Name', value: name.trim(), onChange: () => changeAt(0) },
    { label: 'Beschreibung', value: description.trim(), onChange: () => changeAt(0) },
    ...(chatAutoCleanup
      ? [
          {
            label: 'Chats',
            value: cleanupPeriods
              ? `Inaktive Chats werden nach ${cleanupPeriods.archiveAfterDays} Tagen archiviert und nach weiteren ${cleanupPeriods.deleteAfterDays} Tagen gelöscht.`
              : 'Inaktive Chats werden automatisch archiviert und später gelöscht.',
            onChange: () => changeAt(0),
          },
        ]
      : []),
    {
      label: 'Mitglieder',
      value:
        pendingMembers.length === 0 ? (
          'nur Sie'
        ) : (
          <Box component="ul" sx={summaryListSx}>
            {pendingMembers.map((member) => (
              <SummaryItem key={pendingKey(member)} icon={memberIcon(member)}>
                {member.label}
                {member.subjectType === 'GROUP' ? ' · Gruppe' : ''} · {spaceRoleLabel(member.role)}
              </SummaryItem>
            ))}
          </Box>
        ),
      onChange: () => changeAt(STEP_MEMBERS),
    },
    {
      label: 'Inhalte',
      value:
        selectedAssets.length === 0 ? (
          'keine'
        ) : (
          <Box component="ul" sx={summaryListSx}>
            {selectedAssets.map((pick) => {
              const definition = assetTypeDefinition(pick.assetType)
              const TypeIcon = definition?.Icon
              return (
                <SummaryItem
                  key={`${pick.assetType}:${pick.assetId}`}
                  icon={TypeIcon ? <TypeIcon fontSize="inherit" /> : null}
                >
                  {pick.name}
                  {definition ? ` · ${definition.title}` : ''}
                </SummaryItem>
              )
            })}
          </Box>
        ),
      onChange: () => changeAt(STEP_ASSETS),
    },
  ]

  return (
    <WizardLayout
      object="Neuer Space"
      steps={STEPS}
      activeStep={activeStep}
      failed={nameCheck.error !== null}
      createBlockedBy={mayNotCreate ? capabilityMissingMessage('CREATE_SPACE') : null}
      error={error}
      footerNote={
        activeStep === STEP_MEMBERS
          ? pendingMembers.length === 1
            ? '1 Mitglied vorgemerkt'
            : `${pendingMembers.length} Mitglieder vorgemerkt`
          : null
      }
      footerSeparated={activeStep !== STEPS.length - 1}
      submitting={submitting}
      createAnnouncement="Space wird angelegt"
      onCancel={() => void handleCancel()}
      onBack={() => {
        setReturnToReview(false)
        setActiveStep((s) => s - 1)
      }}
      onNext={handleNext}
      onCreate={() => void handleCreate()}
    >
      {activeStep === 0 && (
        <>
          <AssetNameFields
            idPrefix="space-create"
            name={name}
            onNameChange={(next) => {
              nameCheck.clear()
              setName(next)
            }}
            nameError={nameCheck.error}
            nameInputRef={nameCheck.ref}
            description={description}
            onDescriptionChange={setDescription}
            namePlaceholder="z. B. Widerspruchsstelle"
          />
          <ChatAutoCleanupField
            id="space-create-chat-auto-cleanup"
            checked={chatAutoCleanup}
            onChange={setChatAutoCleanup}
            archiveAfterDays={cleanupPeriods?.archiveAfterDays}
            deleteAfterDays={cleanupPeriods?.deleteAfterDays}
          />
        </>
      )}

      {activeStep === STEP_MEMBERS && (
        <>
          <Typography variant="body2" sx={{ color: 'text.secondary' }}>
            Mitglieder lassen sich auch später jederzeit in der Space-Verwaltung ergänzen — dieser
            Schritt ist optional.
          </Typography>
          <SubjectFormRow
            picker={
              <SubjectPicker
                value={subject}
                onChange={(next) => {
                  choice.clear()
                  setSubject(next)
                }}
                errorText={choice.errorText}
                inputRef={choice.inputRef}
                excludedUserIds={excludedUserIds}
                excludedGroupIds={excludedGroupIds}
              />
            }
            role={
              <Select
                size="small"
                fullWidth
                value={selectedRole}
                onChange={(e) => setSelectedRole(e.target.value as SpaceRole)}
                aria-label="Rolle des neuen Mitglieds"
              >
                {MEMBER_ROLES.map((role) => (
                  <MenuItem key={role} value={role}>
                    {spaceRoleLabel(role)}
                  </MenuItem>
                ))}
              </Select>
            }
            action={
              <Button variant="outlined" onClick={() => void handleNoteMember()}>
                Vormerken
              </Button>
            }
          />
          {pendingMembers.length > 0 && (
            <Box sx={{ border: 1, borderColor: 'divider', borderRadius: '10px' }}>
              {pendingMembers.map((member) => (
                <Box
                  key={pendingKey(member)}
                  sx={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 1.5,
                    px: 2,
                    py: 1.25,
                    '& + &': { borderTop: 1, borderColor: 'divider' },
                  }}
                >
                  {member.subjectType === 'GROUP' ? (
                    <GroupsOutlinedIcon fontSize="small" color="action" aria-hidden />
                  ) : (
                    <PersonOutlineIcon fontSize="small" color="action" aria-hidden />
                  )}
                  <Typography variant="body2" sx={{ flex: 1 }} noWrap>
                    {member.label}
                    {member.subjectType === 'GROUP' ? ' · Gruppe' : ''}
                  </Typography>
                  <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                    {spaceRoleLabel(member.role)}
                  </Typography>
                  <IconButton
                    size="small"
                    aria-label={`Vorgemerktes Mitglied ${member.label} entfernen`}
                    onClick={() =>
                      setPendingMembers((prev) =>
                        prev.filter((m) => pendingKey(m) !== pendingKey(member)),
                      )
                    }
                  >
                    <DeleteIcon fontSize="small" />
                  </IconButton>
                </Box>
              ))}
            </Box>
          )}
        </>
      )}

      {activeStep === STEP_ASSETS && (
        <>
          <Typography variant="body2" sx={{ color: 'text.secondary' }}>
            Ein Chat in diesem Space nutzt nur, was Sie hier zuordnen; die Zuordnung gewährt keinen
            zusätzlichen Zugriff. Dieser Schritt ist optional.
          </Typography>
          {preselect && preselectDropped && (
            <Alert severity="warning">
              „{preselect.name}“ ist nicht vorgewählt: Solange die Nachfolge offen ist, lässt es
              sich nicht neu zuordnen.
            </Alert>
          )}
          <AssetTilePicker
            value={selectedAssets}
            onChange={setSelectedAssets}
            aria-label="Inhalte für diesen Space"
          />
        </>
      )}

      {activeStep === STEPS.length - 1 && (
        <>
          <WizardReview entries={reviewEntries} />
          {!selectedAssets.some((pick) => pick.assetType === 'KNOWLEDGE_LIBRARY') && (
            <Alert severity="info">{NO_KNOWLEDGE_SUMMARY}</Alert>
          )}
        </>
      )}
    </WizardLayout>
  )
}
