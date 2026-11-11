/** The one label of the mandatory reason on every query that is logged (guidelines 5.3). */
export const REASON_LABEL = 'Anlass'
export const REASON_HELP = 'Pflichtangabe. Der Anlass steht im Protokolleintrag dieses Abrufs.'

/** Why a period cannot be queried yet, or null; shown at the fields after a submit. */
export function periodProblem(from: string, to: string): { from?: string; to?: string } | null {
  const problem: { from?: string; to?: string } = {}
  if (from === '') problem.from = 'Bitte einen Beginn angeben.'
  if (to === '') problem.to = 'Bitte ein Ende angeben.'
  if (from !== '' && to !== '' && to <= from) problem.to = 'Das Ende muss nach dem Beginn liegen.'
  return problem.from || problem.to ? problem : null
}
