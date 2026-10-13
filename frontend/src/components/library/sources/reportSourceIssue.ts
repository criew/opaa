import { flushSync } from 'react-dom'
import { formIssue, type SourceIssue } from '../../../utils/sourceIssue'

/**
 * Shows the issue of a failed save and moves the focus to it (accessibility 2.7): to the first
 * invalid field in `form` when the issue names a field, otherwise to the message. An issue whose
 * field the form does not show falls back to the message, so no issue goes unseen.
 */
export function reportSourceIssue(
  issue: SourceIssue,
  show: (issue: SourceIssue) => void,
  form: HTMLElement | null,
  focusMessage: () => void,
): void {
  flushSync(() => show(issue))
  if (issue.field) {
    const field = form?.querySelector<HTMLElement>('[aria-invalid="true"]')
    if (field) {
      field.focus()
      return
    }
    flushSync(() => show(formIssue(issue.message)))
  }
  focusMessage()
}
