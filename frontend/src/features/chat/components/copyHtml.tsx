import { renderToStaticMarkup } from 'react-dom/server'
import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

/**
 * The `text/html` part of a copied answer: the Markdown rendered as HTML (raw HTML inside the
 * answer is not interpreted, as in the chat), followed by a rule and the note as a visible
 * footer. React escapes the note.
 */
export function answerAsHtml(markdown: string, note: string): string {
  return renderToStaticMarkup(
    <div>
      <Markdown remarkPlugins={[remarkGfm]}>{markdown}</Markdown>
      <hr />
      <p>
        <em>{note}</em>
      </p>
    </div>,
  )
}
