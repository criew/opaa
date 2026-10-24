import type { ReactNode } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import Markdown from 'react-markdown'
import type { Components } from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { isAllowedLinkTarget } from '@/shared/ui/markdown/linkPolicy'

/**
 * The `text/html` part of a copied answer: the Markdown rendered as HTML (raw HTML inside the
 * answer is not interpreted, as in the chat), followed by a rule and the note as a visible
 * footer. React escapes the note. The pasted text honours the chat's link policy: an image is
 * never carried along as an image (its address could carry data out when the target program
 * fetches it), a link is clickable only for the own origin and the allowed link targets, and
 * footnotes keep neither the screen-reader heading nor the jump links of the default rendering.
 */
export function answerAsHtml(
  markdown: string,
  note: string,
  allowedOrigins: readonly string[],
  pageHref: string = window.location.href,
): string {
  return renderToStaticMarkup(
    <div>
      <Markdown
        remarkPlugins={[remarkGfm]}
        components={componentsFor(allowedOrigins, pageHref)}
        remarkRehypeOptions={{
          footnoteLabel: 'Quellen',
          footnoteLabelProperties: {},
          footnoteBackContent: '',
        }}
      >
        {markdown}
      </Markdown>
      <hr />
      <p>
        <em>{note}</em>
      </p>
    </div>,
  )
}

function componentsFor(allowedOrigins: readonly string[], pageHref: string): Components {
  return {
    a: ({ href, children }) => {
      // Footnote marks and their back links point inside the chat page; pasted elsewhere they
      // would lead nowhere, so they stay plain text.
      if (href === undefined || href.startsWith('#')) return <>{children}</>
      if (isAllowedLinkTarget(href, pageHref, allowedOrigins)) return <a href={href}>{children}</a>
      return linkAsText(children, href)
    },
    img: ({ src, alt }) => (
      <span>
        {`[Bild: ${alt?.trim() || 'ohne Beschreibung'}]`}
        {typeof src === 'string' && src !== '' ? ` (${src})` : null}
      </span>
    ),
  }
}

function linkAsText(children: ReactNode, href: string): ReactNode {
  const text = typeof children === 'string' ? children : null
  return (
    <span>
      {text === href ? null : children}
      {text === href ? href : ` (${href})`}
    </span>
  )
}
