import { flushSync } from 'react-dom'
import { formIssue, type SourceIssue } from '../../../utils/sourceIssue'
import { ISSUE_MARKER } from './sourceIssueContext'

/** The field whose helper text carries the issue's message, found via its `aria-describedby`. */
function fieldOfIssue(form: HTMLElement | null): HTMLElement | null {
  const helperId = form?.querySelector(`[${ISSUE_MARKER}]`)?.closest('[id]')?.id
  if (!form || !helperId) return null
  return form.querySelector<HTMLElement>(`[aria-describedby~="${helperId}"]`)
}

/**
 * Shows the issue of a failed save and moves the focus to it (accessibility 2.7): to the field the
 * issue names, otherwise to the message. An issue whose field the form does not show falls back to
 * the message, so no issue goes unseen.
 */
export function reportSourceIssue(
  issue: SourceIssue,
  show: (issue: SourceIssue) => void,
  form: HTMLElement | null,
  focusMessage: () => void,
): void {
  flushSync(() => show(issue))
  if (issue.field) {
    const field = fieldOfIssue(form)
    if (field) {
      field.focus()
      return
    }
    flushSync(() => show(formIssue(issue.message)))
  }
  focusMessage()
}
