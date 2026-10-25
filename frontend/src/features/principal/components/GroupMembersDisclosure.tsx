import { useEffect, useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import type { GroupMemberDisclosureResponse } from '@/shared/api/types'

/** How many names a page brings — the same default as in the service. */
const PAGE_SIZE = 50

interface GroupMembersDisclosureProps {
  /**
   * How the group is named in the aria-labels. The caller chooses it so that it does not
   * read out the raw identifier for a nameless row.
   */
  groupLabel: string
  /** Loads a page; called only on explicit request, never while rendering. */
  load: (offset: number, limit: number) => Promise<GroupMemberDisclosureResponse>
  /**
   * The request has already been made (e.g. via a row menu): the first page loads at once, and
   * „Mitglieder ausblenden" reports via `onHide` instead of showing its own trigger.
   */
  requested?: boolean
  onHide?: () => void
}

/**
 * „Mitglieder anzeigen" on a group's row (ADR-0036 Entscheidung 9): whoever granted the group
 * a right on this object sees to whom. Loading happens **only on explicit
 * request** — a member list is a statement about persons and is not built as a by-product
 * of an overview.
 *
 * <p>What the response withholds — for a protected group — is decided by the service; here
 * is only how it is explained.
 */
export default function GroupMembersDisclosure({
  groupLabel,
  load,
  requested = false,
  onHide,
}: GroupMembersDisclosureProps) {
  const [disclosure, setDisclosure] = useState<GroupMemberDisclosureResponse | null>(null)
  const [isLoading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // An empty next page is the end, even when the total count is still higher: between two
  // clicks the group can shrink, and otherwise the trigger would stay clickable forever without
  // ever loading anything.
  const [reachedEnd, setReachedEnd] = useState(false)

  async function loadPage(offset: number) {
    setLoading(true)
    setError(null)
    try {
      const next = await load(offset, PAGE_SIZE)
      if (offset > 0 && next.members.length === 0) setReachedEnd(true)
      setDisclosure((current) =>
        current && offset > 0 ? { ...next, members: [...current.members, ...next.members] } : next,
      )
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Die Mitglieder konnten nicht geladen werden.')
    } finally {
      setLoading(false)
    }
  }

  // A ref, not state: StrictMode runs the effect twice, and every retrieval by the system role is
  // an audit event.
  const requestedLoadStarted = useRef(false)
  useEffect(() => {
    if (!requested || requestedLoadStarted.current) return
    requestedLoadStarted.current = true
    void loadPage(0)
    // eslint-disable-next-line react-hooks/exhaustive-deps -- once per mount, on the expressed wish
  }, [requested])

  if (!requested && !disclosure && !isLoading && !error) {
    return (
      <Link
        component="button"
        type="button"
        underline="hover"
        sx={{ alignSelf: 'flex-start', fontSize: 12.5 }}
        aria-label={`Mitglieder anzeigen: Gruppe „${groupLabel}“`}
        onClick={() => void loadPage(0)}
      >
        Mitglieder anzeigen
      </Link>
    )
  }

  const total = disclosure?.activeMemberCount ?? null
  const shown = disclosure?.members.length ?? 0
  const hasMore = total !== null && shown < total && !reachedEnd

  return (
    <Stack spacing={0.25} sx={{ mt: 0.5 }}>
      {error && <Alert severity="error">{error}</Alert>}
      {isLoading && !disclosure && (
        <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>
          Mitglieder werden geladen …
        </Typography>
      )}
      {disclosure?.protectedGroup ? (
        <Typography sx={{ fontSize: 12.5 }}>
          {disclosure.responsible.length > 0
            ? `Geschützte Gruppe — die Mitglieder werden nicht genannt. Verantwortlich: ${disclosure.responsible.join(', ')}`
            : 'Geschützte Gruppe — die Mitglieder werden nicht genannt. Auskunft gibt die Systemverwaltung.'}
        </Typography>
      ) : (
        disclosure && (
          <>
            <Typography variant="caption" sx={{ color: 'text.secondary' }}>
              {total === 0
                ? 'Diese Gruppe erreicht derzeit kein aktives Konto.'
                : `${shown} von ${total} aktiven Konten`}
            </Typography>
            <Stack component="ul" spacing={0.25} sx={{ m: 0, pl: 2.5 }}>
              {disclosure.members.map((member) => (
                <Typography component="li" key={member.userId} sx={{ fontSize: 13 }}>
                  {member.displayName ?? member.userId}
                </Typography>
              ))}
            </Stack>
            {hasMore && (
              <Link
                component="button"
                type="button"
                sx={{ alignSelf: 'flex-start', fontSize: 12 }}
                aria-label={`Weitere Mitglieder der Gruppe „${groupLabel}“ anzeigen`}
                onClick={() => void loadPage(shown)}
              >
                {isLoading ? 'Wird geladen …' : 'Weitere anzeigen'}
              </Link>
            )}
          </>
        )
      )}
      {(disclosure || error) && (
        <Link
          component="button"
          type="button"
          sx={{ alignSelf: 'flex-start', fontSize: 12 }}
          aria-label={`Mitglieder der Gruppe „${groupLabel}“ ausblenden`}
          onClick={() => {
            setDisclosure(null)
            setError(null)
            setReachedEnd(false)
            onHide?.()
          }}
        >
          Mitglieder ausblenden
        </Link>
      )}
    </Stack>
  )
}
