import { useEffect, useState } from 'react'
import Accordion from '@mui/material/Accordion'
import AccordionDetails from '@mui/material/AccordionDetails'
import AccordionSummary from '@mui/material/AccordionSummary'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Divider from '@mui/material/Divider'
import FormControlLabel from '@mui/material/FormControlLabel'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import CreateGroupDialog from '../CreateGroupDialog'
import FieldLabel from '../wizard/FieldLabel'
import MetaBadge from '../MetaBadge'
import SectionHead from '../SectionHead'
import GroupStewardsSection from './GroupStewardsSection'
import PermissionTransferDialog from '../permissions/PermissionTransferDialog'
import UserPicker from './UserPicker'
import type { GroupListResponse, UserSummary } from '../../types/api'
import { confirmAction } from '../../stores/confirmStore'
import { useAuthStore } from '../../stores/authStore'
import { useGroupStore } from '../../stores/groupStore'
import { useMyCapabilities } from '../../hooks/useMyCapabilities'
import { capabilityMissingMessage } from '../../utils/labels'

function StewardedGroupCard({ group }: { group: GroupListResponse }) {
  const currentUserId = useAuthStore((s) => s.user?.id)
  const details = useGroupStore((s) => s.groupDetails[group.id])
  const loadGroupDetails = useGroupStore((s) => s.loadGroupDetails)
  const renameGroup = useGroupStore((s) => s.renameGroup)
  const deleteExistingGroup = useGroupStore((s) => s.deleteExistingGroup)
  const addMember = useGroupStore((s) => s.addMember)
  const removeMember = useGroupStore((s) => s.removeMember)
  const changeRelease = useGroupStore((s) => s.changeRelease)

  const [expanded, setExpanded] = useState(false)
  const [draft, setDraft] = useState<{ groupId: string | null; name: string; description: string }>(
    {
      groupId: null,
      name: '',
      description: '',
    },
  )
  const [selectedUser, setSelectedUser] = useState<UserSummary | null>(null)
  const [error, setError] = useState<string | null>(null)

  const name = draft.groupId === group.id ? draft.name : group.name
  const description = draft.groupId === group.id ? draft.description : (group.description ?? '')
  const members = details?.members ?? []
  const stewards = details?.stewards ?? group.stewards

  useEffect(() => {
    if (expanded && !details) {
      void loadGroupDetails(group.id)
    }
  }, [expanded, details, group.id, loadGroupDetails])

  async function run(action: () => Promise<void>, fallback: string) {
    setError(null)
    try {
      await action()
    } catch (err) {
      setError(err instanceof Error ? err.message : fallback)
    }
  }

  return (
    <Accordion expanded={expanded} onChange={(_event, isExpanded) => setExpanded(isExpanded)}>
      <AccordionSummary expandIcon={<ExpandMoreIcon />}>
        <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', flexGrow: 1 }}>
          <Typography sx={{ fontSize: 14.5, fontWeight: 600 }}>{group.name}</Typography>
          {group.protectedGroup && <MetaBadge>geschützt</MetaBadge>}
          <MetaBadge>{group.releasedForUse ? 'freigegeben' : 'nicht freigegeben'}</MetaBadge>
          <Typography sx={{ fontSize: 13, color: 'text.secondary', ml: 'auto', mr: 1 }}>
            {group.memberCount} {group.memberCount === 1 ? 'Mitglied' : 'Mitglieder'}
          </Typography>
        </Stack>
      </AccordionSummary>
      <AccordionDetails>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
            {error}
          </Alert>
        )}

        <Stack spacing={2} sx={{ mb: 2 }}>
          <Box>
            <FieldLabel htmlFor={`my-group-${group.id}-name`}>Name der Gruppe</FieldLabel>
            <TextField
              id={`my-group-${group.id}-name`}
              fullWidth
              value={name}
              onChange={(e) => setDraft({ groupId: group.id, name: e.target.value, description })}
              size="small"
            />
          </Box>
          <Box>
            <FieldLabel htmlFor={`my-group-${group.id}-description`}>Beschreibung</FieldLabel>
            <TextField
              id={`my-group-${group.id}-description`}
              fullWidth
              value={description}
              onChange={(e) => setDraft({ groupId: group.id, name, description: e.target.value })}
              multiline
              minRows={2}
              size="small"
            />
          </Box>
          <Stack direction="row" spacing={1}>
            <Button
              variant="contained"
              size="small"
              onClick={() =>
                void run(
                  () => renameGroup(group.id, name.trim(), description.trim()),
                  'Aktualisierung fehlgeschlagen',
                )
              }
            >
              Speichern
            </Button>
            <Button
              color="error"
              variant="outlined"
              size="small"
              onClick={async () => {
                const confirmed = await confirmAction({
                  question: `Gruppe "${group.name}" löschen?`,
                  consequence: 'Diese Aktion kann nicht rückgängig gemacht werden.',
                  confirmLabel: 'Löschen',
                  tone: 'danger',
                })
                if (!confirmed) return
                await run(() => deleteExistingGroup(group.id), 'Löschen fehlgeschlagen')
              }}
            >
              Gruppe löschen
            </Button>
          </Stack>
        </Stack>

        <Divider sx={{ mb: 2 }} />

        <SectionHead>Sichtbarkeit</SectionHead>
        <Stack spacing={0.5} sx={{ mb: 2 }}>
          <FormControlLabel
            control={
              <Switch
                checked={group.releasedForUse}
                onChange={(e) =>
                  void run(
                    () => changeRelease(group.id, e.target.checked),
                    'Die Freigabe konnte nicht geändert werden',
                  )
                }
              />
            }
            label="Zur Verwendung freigeben"
          />
          <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
            Erst freigegeben kann jemand anderes diese Gruppe berechtigen. Name und Herkunft werden
            damit für alle sichtbar, denen die Gruppe zur Auswahl steht.
          </Typography>
        </Stack>

        <Divider sx={{ mb: 2 }} />

        <GroupStewardsSection
          groupId={group.id}
          groupName={group.name}
          stewards={stewards}
          currentUserId={currentUserId}
        />

        <Divider sx={{ my: 2 }} />

        <SectionHead>Mitglieder</SectionHead>
        <Stack spacing={1}>
          {members.length === 0 && (
            <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
              Diese Gruppe hat noch keine Mitglieder.
            </Typography>
          )}
          {members.map((member) => (
            <Box
              key={member.userId}
              sx={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                gap: 1,
              }}
            >
              <Typography
                variant="body2"
                sx={member.displayName ? undefined : { fontFamily: 'monospace' }}
              >
                {member.displayName ?? member.userId}
              </Typography>
              <Button
                color="error"
                size="small"
                onClick={() =>
                  void run(
                    () => removeMember(group.id, member.userId),
                    'Entfernen des Mitglieds fehlgeschlagen',
                  )
                }
              >
                Entfernen
              </Button>
            </Box>
          ))}
          <Stack direction={{ xs: 'column', md: 'row' }} spacing={1} sx={{ pt: 1 }}>
            <UserPicker
              ariaLabel="Mitglied"
              placeholder="Person suchen …"
              value={selectedUser}
              onChange={setSelectedUser}
              excludedUserIds={members.map((member) => member.userId)}
            />
            <Button
              variant="contained"
              size="small"
              disabled={!selectedUser}
              onClick={async () => {
                if (!selectedUser) return
                await run(
                  () => addMember(group.id, selectedUser.id),
                  'Mitglied konnte nicht hinzugefügt werden',
                )
                setSelectedUser(null)
              }}
            >
              Mitglied hinzufügen
            </Button>
          </Stack>
        </Stack>
      </AccordionDetails>
    </Accordion>
  )
}

/**
 * „Meine Gruppen" (#1814, ADR-0036 Entscheidung 4): die internen Gruppen, für die das eigene Konto
 * verantwortlich ist — mit dem sichtbaren Ausgang „Verantwortung abgeben". Anlegen setzt das
 * Anlegerecht „Interne Gruppen anlegen" voraus; fehlt es, erklärt die Seite das, statt die
 * Schaltfläche zu verstecken.
 */
export default function MyGroupsSection() {
  const groups = useGroupStore((s) => s.groups)
  const isLoading = useGroupStore((s) => s.isLoading)
  const error = useGroupStore((s) => s.error)
  const loadGroups = useGroupStore((s) => s.loadGroups)
  const currentUser = useAuthStore((s) => s.user)
  const { isMissing } = useMyCapabilities()
  const [createDialogOpen, setCreateDialogOpen] = useState(false)
  const [handoverOpen, setHandoverOpen] = useState(false)

  const missingCapability = isMissing('CREATE_INTERNAL_GROUP')

  useEffect(() => {
    void loadGroups('STEWARDED')
  }, [loadGroups])

  return (
    <Box>
      <Stack
        direction={{ xs: 'column', md: 'row' }}
        spacing={1}
        sx={{ alignItems: { md: 'center' }, justifyContent: 'space-between', mb: 2 }}
      >
        <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
          Gruppen, für die Sie verantwortlich sind. Sie pflegen deren Mitglieder, geben sie zur
          Verwendung frei und geben die Verantwortung ab, wenn Sie die Aufgabe wechseln.
        </Typography>
        <Stack direction="row" spacing={1}>
          {/* Verantwortung abgeben ist ein eigener Schritt (ADR-0036, Entscheidung 4/10): kein
              Automatismus, sondern ein sichtbarer Ausgang beim Aufgabenwechsel. */}
          <Button variant="outlined" disabled={!currentUser} onClick={() => setHandoverOpen(true)}>
            Verantwortung und Eigentum abgeben
          </Button>
          <Button
            variant="contained"
            disabled={missingCapability}
            onClick={() => setCreateDialogOpen(true)}
          >
            Neue Gruppe
          </Button>
        </Stack>
      </Stack>

      {missingCapability && (
        <Alert severity="info" sx={{ mb: 2 }}>
          {capabilityMissingMessage('CREATE_INTERNAL_GROUP')}
        </Alert>
      )}

      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}

      {isLoading ? (
        <Typography sx={{ color: 'text.secondary' }}>Gruppen werden geladen …</Typography>
      ) : groups.length === 0 ? (
        <Typography sx={{ color: 'text.secondary' }}>
          Sie sind für keine Gruppe verantwortlich.
        </Typography>
      ) : (
        <Stack spacing={1}>
          {groups.map((group) => (
            <StewardedGroupCard key={group.id} group={group} />
          ))}
        </Stack>
      )}

      <CreateGroupDialog
        open={createDialogOpen}
        onClose={() => setCreateDialogOpen(false)}
        onCreated={() => setCreateDialogOpen(false)}
      />

      {handoverOpen && currentUser && (
        <PermissionTransferDialog
          open
          onClose={() => setHandoverOpen(false)}
          source={{
            type: 'USER',
            id: currentUser.id,
            name: currentUser.displayName ?? 'Ihr Konto',
          }}
          targetKinds={['USER']}
          scopes={['STEWARDSHIP', 'OWNERSHIP']}
          intro="Die Verantwortung für Ihre internen Gruppen und Ihr Eigentum an Objekten gehen an die gewählte Person. Ihre eigenen Berechtigungen und Space-Mitgliedschaften bleiben unberührt — sie sind weder übertragbar noch Teil der Vorschau."
          onTransferred={() => void loadGroups('STEWARDED')}
        />
      )}
    </Box>
  )
}
