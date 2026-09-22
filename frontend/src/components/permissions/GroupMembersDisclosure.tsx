import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import type { GroupMemberDisclosureResponse } from '../../types/api'

/** Wie viele Namen eine Seite bringt — dieselbe Vorgabe wie im Dienst. */
const PAGE_SIZE = 50

interface GroupMembersDisclosureProps {
  /** Wie die Zeile heißt, um die es geht — steht in Beschriftung und aria-label. */
  groupLabel: string
  /** Lädt eine Seite; erst auf ausdrücklichen Wunsch aufgerufen, nie beim Rendern. */
  load: (offset: number, limit: number) => Promise<GroupMemberDisclosureResponse>
}

/**
 * „Mitglieder anzeigen" an der Zeile einer Gruppe (#1880, ADR-0036 Entscheidung 9): Wer der Gruppe
 * an diesem Objekt ein Recht eingeräumt hat, sieht, an wen. Geladen wird **erst auf ausdrücklichen
 * Wunsch** — eine Mitgliederliste ist eine Aussage über Personen und entsteht nicht als Beiwerk
 * einer Übersicht.
 *
 * <p>Bei einer geschützten Gruppe kommt keine Liste, sondern die Ansprechstelle; die Antwort des
 * Dienstes trägt dafür keine Namen der Mitglieder und keine Größe.
 */
export default function GroupMembersDisclosure({ groupLabel, load }: GroupMembersDisclosureProps) {
  const [disclosure, setDisclosure] = useState<GroupMemberDisclosureResponse | null>(null)
  const [isLoading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function loadPage(offset: number) {
    setLoading(true)
    setError(null)
    try {
      const next = await load(offset, PAGE_SIZE)
      setDisclosure((current) =>
        current && offset > 0 ? { ...next, members: [...current.members, ...next.members] } : next,
      )
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Die Mitglieder konnten nicht geladen werden.')
    } finally {
      setLoading(false)
    }
  }

  if (!disclosure && !isLoading && !error) {
    return (
      <Link
        component="button"
        type="button"
        sx={{ alignSelf: 'flex-start', fontSize: 12 }}
        aria-label={`Mitglieder von ${groupLabel} anzeigen`}
        onClick={() => void loadPage(0)}
      >
        Mitglieder anzeigen
      </Link>
    )
  }

  const total = disclosure?.activeMemberCount ?? null
  const shown = disclosure?.members.length ?? 0
  const hasMore = total !== null && shown < total

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
            ? `Geschützte Gruppe — die Mitglieder werden nicht genannt. Ansprechstelle: ${disclosure.responsible.join(', ')}`
            : 'Geschützte Gruppe — die Mitglieder werden nicht genannt. Eine Ansprechstelle ist noch nicht benannt.'}
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
                aria-label={`Weitere Mitglieder von ${groupLabel} anzeigen`}
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
          aria-label={`Mitglieder von ${groupLabel} ausblenden`}
          onClick={() => {
            setDisclosure(null)
            setError(null)
          }}
        >
          Mitglieder ausblenden
        </Link>
      )}
    </Stack>
  )
}
