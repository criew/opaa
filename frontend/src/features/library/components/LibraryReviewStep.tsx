import WizardReview from '@/shared/ui/wizard/WizardReview'
import { SummaryValue } from './setup/SummaryRow'
import type { RowSummary } from './sourceCore/setupTypes'

export interface ReviewEntry {
  label: string
  value: RowSummary
  /** Absent for what cannot be changed from here. */
  onChange?: () => void
}

/**
 * The step „Prüfen“ before the library is created: every answer in the rows of every assistant,
 * its value in the form of the setup's summaries, each with „Ändern“, which leads to the answer
 * and back here.
 */
export default function LibraryReviewStep({ entries }: { entries: ReviewEntry[] }) {
  return (
    <WizardReview
      entries={entries.map((entry) => ({
        label: entry.label,
        value: <SummaryValue summary={entry.value || null} />,
        onChange: entry.onChange,
      }))}
    />
  )
}
