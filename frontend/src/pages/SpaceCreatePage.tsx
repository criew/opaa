import { useEffect, useState, type ReactNode } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import IconButton from '@mui/material/IconButton'
import MenuItem from '@mui/material/MenuItem'
import Select from '@mui/material/Select'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import DeleteIcon from '@mui/icons-material/Delete'
import GroupsOutlinedIcon from '@mui/icons-material/GroupsOutlined'
import PersonOutlineIcon from '@mui/icons-material/PersonOutlined'
import { useLocation, useNavigate } from 'react-router'
import PageHeading from '../components/a11y/PageHeading'
import AssetTilePicker from '../components/assets/AssetTilePicker'
import type { AssetPick, SpaceCreateLocationState } from '../components/assets/assetPick'
import { assetTypeDefinition } from '../components/assets/assetTypeRegistry'
import SubjectFormRow from '../components/permissions/SubjectFormRow'
import SubjectPicker from '../components/permissions/SubjectPicker'
import {
  confirmExternalSubject,
  emptySubjectSelection,
  groupLabel,
  selectedSubjectId,
  type SubjectSelection,
} from '../components/permissions/subjectSelection'
import ChatAutoCleanupField from '../components/space/ChatAutoCleanupField'
import FieldLabel from '../components/wizard/FieldLabel'
import WizardStepBar from '../components/wizard/WizardStepBar'
import { confirmAction } from '../stores/confirmStore'
import { useAuthStore } from '../stores/authStore'
import { useSpaceStore } from '../stores/spaceStore'
import { useMyCapabilities } from '../hooks/useMyCapabilities'
import { getChatAutoCleanupPeriods } from '../services/spaceApi'
import { capabilityMissingMessage, spaceRoleLabel } from '../utils/labels'
import type { ChatAutoCleanupPeriods, PermissionSubjectType, SpaceRole } from '../types/api'

const STEPS = ['Grunddaten', 'Mitglieder', 'Inhalte', 'Zusammenfassung'] as const

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

/** One summary row; its value may be a list. */
function SummaryRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Box sx={{ display: 'flex', gap: 2 }}>
      <Typography
        component="span"
        sx={{ fontSize: 12.5, color: 'text.secondary', width: 120, flex: 'none', pt: 0.25 }}
      >
        {label}
      </Typography>
      <Box sx={{ fontSize: 13.5, minWidth: 0 }}>{children}</Box>
    </Box>
  )
}

/** One summary list item: a small symbol, while the text carries the meaning. */
function SummaryItem({ icon, children }: { icon: ReactNode; children: ReactNode }) {
  return (
    <Box component="li" sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
      <Box component="span" aria-hidden sx={{ display: 'inline-flex', color: 'text.secondary' }}>
        {icon}
      </Box>
      <Typography component="span" sx={{ fontSize: 13.5 }}>
        {children}
      </Typography>
    </Box>
  )
}

const summaryListSx = { listStyle: 'none', m: 0, p: 0, display: 'grid', gap: 0.5 } as const

/**
 * The space creation wizard (#594, mockup 1b): Grunddaten, Mitglieder, Inhalte, Zusammenfassung.
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
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [chatAutoCleanup, setChatAutoCleanup] = useState(false)
  const [cleanupPeriods, setCleanupPeriods] = useState<ChatAutoCleanupPeriods | null>(null)
  const [pendingMembers, setPendingMembers] = useState<PendingMember[]>([])
  const [subject, setSubject] = useState<SubjectSelection>(emptySubjectSelection)
  const [selectedRole, setSelectedRole] = useState<SpaceRole>('MEMBER')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // ADR-0036, Entscheidung 5: a missing Anlegerecht is explained, not hidden - the button stays
  // visible and says why it cannot be used. The backend refuses the same call regardless.
  const { isMissing } = useMyCapabilities()
  const mayNotCreate = isMissing('CREATE_SPACE')
  // Only what the creator may read is offered; the backend re-checks the same rule on creation.
  const [selectedAssets, setSelectedAssets] = useState<AssetPick[]>(preselect ? [preselect] : [])

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
    if (!subjectId || subject.type === 'ALL_ACCOUNTS') return
    if (!(await confirmExternalSubject(subject))) return
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
        question: 'Eingaben verwerfen und den Assistenten verlassen?',
        confirmLabel: 'Verwerfen',
        tone: 'caution',
      })
      if (!confirmed) return
    }
    navigate('/spaces')
  }

  const handleCreate = async () => {
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

  return (
    <Box sx={{ flexGrow: 1, overflowY: 'auto', p: { xs: 2.5, md: 5 } }}>
      <Box sx={{ maxWidth: 640 }}>
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mb: 0.5 }}>
          Neuer Space
        </Typography>
        <PageHeading title="Neuer Space" visuallyHidden />
        <Typography component="div" sx={{ fontSize: 26, fontWeight: 600, mb: 3 }} aria-hidden>
          {STEPS[activeStep]}
        </Typography>
        <WizardStepBar steps={STEPS} active={activeStep} />

        {mayNotCreate && (
          <Alert severity="info" sx={{ mb: 2 }} id="space-create-capability-hint">
            {capabilityMissingMessage('CREATE_SPACE')}
          </Alert>
        )}

        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}

        {activeStep === 0 && (
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2.5 }}>
            <Box>
              <FieldLabel htmlFor="space-create-name">Name</FieldLabel>
              <TextField
                id="space-create-name"
                size="small"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="z. B. Widerspruchsstelle"
                fullWidth
              />
            </Box>
            <Box>
              <FieldLabel htmlFor="space-create-description">Beschreibung (optional)</FieldLabel>
              <TextField
                id="space-create-description"
                size="small"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                multiline
                minRows={2}
                fullWidth
              />
            </Box>
            <ChatAutoCleanupField
              id="space-create-chat-auto-cleanup"
              checked={chatAutoCleanup}
              onChange={setChatAutoCleanup}
              archiveAfterDays={cleanupPeriods?.archiveAfterDays}
              deleteAfterDays={cleanupPeriods?.deleteAfterDays}
            />
          </Box>
        )}

        {activeStep === 1 && (
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
              Mitglieder lassen sich auch später jederzeit in der Space-Verwaltung ergänzen — dieser
              Schritt ist optional. Gruppen geben ihre Rolle an alle ihre Mitglieder weiter.
            </Typography>
            <SubjectFormRow
              picker={
                <SubjectPicker
                  value={subject}
                  onChange={setSubject}
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
                <Button
                  variant="outlined"
                  disabled={!selectedSubjectId(subject)}
                  onClick={() => void handleNoteMember()}
                >
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
                    <Typography sx={{ fontSize: 13.5, flex: 1 }} noWrap>
                      {member.label}
                      {member.subjectType === 'GROUP' ? ' · Gruppe' : ''}
                    </Typography>
                    <Typography sx={{ fontSize: 11.5, color: 'text.secondary' }}>
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
                      <DeleteIcon sx={{ fontSize: 16 }} />
                    </IconButton>
                  </Box>
                ))}
              </Box>
            )}
          </Box>
        )}

        {activeStep === 2 && (
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
            <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
              Ein Chat in diesem Space nutzt nur, was Sie hier zuordnen. Zur Auswahl steht, was Sie
              selbst lesen dürfen. Die Zuordnung gewährt niemandem zusätzlichen Zugriff und lässt
              sich später in den Einstellungen des Space ändern — dieser Schritt ist optional.
            </Typography>
            <AssetTilePicker
              value={selectedAssets}
              onChange={setSelectedAssets}
              aria-label="Inhalte für diesen Space"
            />
          </Box>
        )}

        {activeStep === 3 && (
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
            <SummaryRow label="Name">{name.trim()}</SummaryRow>
            <SummaryRow label="Beschreibung">{description.trim() || '–'}</SummaryRow>
            <SummaryRow label="Mitglieder">
              {pendingMembers.length === 0 ? (
                'nur Sie'
              ) : (
                <Box component="ul" sx={summaryListSx}>
                  {pendingMembers.map((member) => (
                    <SummaryItem
                      key={pendingKey(member)}
                      icon={
                        member.subjectType === 'GROUP' ? (
                          <GroupsOutlinedIcon sx={{ fontSize: 16 }} />
                        ) : (
                          <PersonOutlineIcon sx={{ fontSize: 16 }} />
                        )
                      }
                    >
                      {member.label}
                      {member.subjectType === 'GROUP' ? ' · Gruppe' : ''} ·{' '}
                      {spaceRoleLabel(member.role)}
                    </SummaryItem>
                  ))}
                </Box>
              )}
            </SummaryRow>
            <SummaryRow label="Inhalte">
              {selectedAssets.length === 0 ? (
                'keine'
              ) : (
                <Box component="ul" sx={summaryListSx}>
                  {selectedAssets.map((pick) => {
                    const definition = assetTypeDefinition(pick.assetType)
                    const TypeIcon = definition?.Icon
                    return (
                      <SummaryItem
                        key={`${pick.assetType}:${pick.assetId}`}
                        icon={TypeIcon ? <TypeIcon sx={{ fontSize: 16 }} /> : null}
                      >
                        {pick.name}
                        {definition ? ` · ${definition.title}` : ''}
                      </SummaryItem>
                    )
                  })}
                </Box>
              )}
            </SummaryRow>
            {chatAutoCleanup && (
              <SummaryRow label="Chats">
                {cleanupPeriods
                  ? `Inaktive Chats werden nach ${cleanupPeriods.archiveAfterDays} Tagen archiviert und nach weiteren ${cleanupPeriods.deleteAfterDays} Tagen gelöscht.`
                  : 'Inaktive Chats werden automatisch archiviert und später gelöscht.'}
              </SummaryRow>
            )}
            {!selectedAssets.some((pick) => pick.assetType === 'KNOWLEDGE_LIBRARY') && (
              <Alert severity="info">{NO_KNOWLEDGE_SUMMARY}</Alert>
            )}
          </Box>
        )}

        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
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
          {activeStep === 1 && (
            <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>
              {pendingMembers.length === 1
                ? '1 Mitglied vorgemerkt'
                : `${pendingMembers.length} Mitglieder vorgemerkt`}
            </Typography>
          )}
          {activeStep > 0 && (
            <Button
              variant="outlined"
              onClick={() => setActiveStep((s) => s - 1)}
              disabled={submitting}
            >
              Zurück
            </Button>
          )}
          {activeStep < STEPS.length - 1 ? (
            <Button
              variant="contained"
              onClick={() => setActiveStep((s) => s + 1)}
              disabled={name.trim() === ''}
            >
              Weiter
            </Button>
          ) : (
            <Button
              variant="contained"
              onClick={() => void handleCreate()}
              disabled={submitting || mayNotCreate || name.trim() === ''}
              aria-describedby={mayNotCreate ? 'space-create-capability-hint' : undefined}
            >
              {submitting ? 'Wird angelegt …' : 'Space anlegen'}
            </Button>
          )}
        </Box>
      </Box>
    </Box>
  )
}
