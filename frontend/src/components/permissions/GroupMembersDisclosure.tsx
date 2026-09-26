import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import type { GroupMemberDisclosureResponse } from '../../types/api'

/** Wie viele Namen eine Seite bringt — dieselbe Vorgabe wie im Dienst. */
const PAGE_SIZE = 50

interface GroupMembersDisclosureProps {
  /**
   * Wie die Gruppe in den aria-labels genannt wird. Der Aufrufer wählt ihn so, dass er für
   * eine namenlose Zeile nicht die rohe Kennung vorliest.
   */
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
 * <p>Was die Antwort zurückhält — geschützte Gruppe, kleine Gruppe — entscheidet der Dienst; hier
 * steht nur, wie es erklärt wird.
 */
export default function GroupMembersDisclosure({ groupLabel, load }: GroupMembersDisclosureProps) {
  const [disclosure, setDisclosure] = useState<GroupMemberDisclosureResponse | null>(null)
  const [isLoading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // Eine leere Folgeseite ist das Ende, auch wenn die Gesamtzahl noch hoeher steht: Zwischen zwei
  // Klicks kann die Gruppe schrumpfen, und sonst bliebe der Ausloeser dauerhaft klickbar, ohne je
  // etwas nachzuladen.
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

  if (!disclosure && !isLoading && !error) {
    return (
      <Link
        component="button"
        type="button"
        sx={{ alignSelf: 'flex-start', fontSize: 12 }}
        aria-label={`Mitglieder der Gruppe „${groupLabel}“ anzeigen`}
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
      ) : disclosure?.smallGroup ? (
        <Typography sx={{ fontSize: 12.5 }}>
          Kleine Gruppe — weder die Mitglieder noch ihre Zahl werden genannt. In einem Referat wäre
          eine Gruppe dieser Größe eine Person mit Namen.
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
          }}
        >
          Mitglieder ausblenden
        </Link>
      )}
    </Stack>
  )
}
