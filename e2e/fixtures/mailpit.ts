import { expect } from "@playwright/test";

/**
 * Reader for the mail catcher of the local-auth target (ADR-0033, #1543).
 *
 * The stack's mailpit (docker-compose.yml, profile "mail") is what a house's SMTP server stands in
 * for: the backend delivers to it over the Compose network, and the scenarios read the invitation,
 * reset and confirmation links back out of its HTTP API. Only two of its endpoints are used -
 * `GET /api/v1/messages` for the list and `GET /api/v1/message/{id}` for one message's bodies.
 *
 * Every wait is a poll with a deadline rather than a fixed sleep: the delivery of the two
 * self-service flows runs on the backend's own mail thread (ADR-0033, Entscheidung 10), so the
 * HTTP response a scenario just saw says nothing about whether the mail has arrived yet.
 */

const baseUrl = process.env.E2E_MAILPIT_BASE_URL ?? "http://localhost:18025";

interface MailpitSummary {
  ID: string;
  Subject: string;
  To: { Address: string; Name: string }[];
  Created: string;
}

interface MailpitMessage {
  ID: string;
  Subject: string;
  To: { Address: string; Name: string }[];
  Text: string;
  HTML: string;
}

/** One delivered mail, reduced to what a scenario asserts on. */
export interface DeliveredMail {
  id: string;
  subject: string;
  recipients: string[];
  text: string;
  html: string;
}

async function getJson<T>(path: string): Promise<T> {
  const response = await fetch(`${baseUrl}${path}`);
  if (!response.ok) {
    throw new Error(`Mailpit ${path} antwortete ${response.status} ${response.statusText}`);
  }
  return (await response.json()) as T;
}

/** Deletes every message, so a scenario can assert on "the mail" instead of "the newest mail". */
export async function clearMailbox(): Promise<void> {
  const response = await fetch(`${baseUrl}/api/v1/messages`, { method: "DELETE" });
  if (!response.ok) {
    throw new Error(
      `Postfach konnte nicht geleert werden: ${response.status} ${response.statusText}`,
    );
  }
}

/**
 * Waits until a mail to `recipient` has arrived and returns it with its bodies.
 *
 * @param recipient the address as the account carries it - compared case-insensitively, the way the
 *   backend normalises it for lookup (ADR-0033, Entscheidung 1)
 */
export async function waitForMail(
  recipient: string,
  { timeoutMs = 20_000 }: { timeoutMs?: number } = {},
): Promise<DeliveredMail> {
  const wanted = recipient.toLowerCase();
  const deadline = Date.now() + timeoutMs;
  let seen: string[] = [];
  while (Date.now() < deadline) {
    const list = await getJson<{ messages: MailpitSummary[] }>("/api/v1/messages?limit=50");
    seen = list.messages.flatMap((message) => message.To.map((to) => to.Address));
    const match = list.messages.find((message) =>
      message.To.some((to) => to.Address.toLowerCase() === wanted),
    );
    if (match) {
      const full = await getJson<MailpitMessage>(`/api/v1/message/${match.ID}`);
      return {
        id: full.ID,
        subject: full.Subject,
        recipients: full.To.map((to) => to.Address),
        text: full.Text,
        html: full.HTML,
      };
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error(
    `Innerhalb von ${timeoutMs} ms kam keine E-Mail an "${recipient}" an. ` +
      `Im Postfach liegen: ${seen.length === 0 ? "keine Nachrichten" : seen.join(", ")}.`,
  );
}

/** How many messages the mailbox currently holds - for asserting that a flow sent *nothing*. */
export async function mailCount(): Promise<number> {
  const list = await getJson<{ messages: MailpitSummary[] }>("/api/v1/messages?limit=50");
  return list.messages.length;
}

/**
 * The one link out of a mail's plain-text body.
 *
 * The templates put exactly one absolute URL of this installation into the text (ADR-0033,
 * Entscheidung 10: OPAA_PUBLIC_BASE_URL is its base), and that URL is what the person clicks. The
 * assertion is part of the extraction on purpose: a template that silently stops carrying its link
 * must fail here, not three steps later with "element not found".
 */
export function linkIn(mail: DeliveredMail): string {
  const matches = mail.text.match(/https?:\/\/\S+/g) ?? [];
  const links = matches.map((link) => link.replace(/[).,;]+$/, ""));
  expect(
    links,
    `Die Textfassung der Mail "${mail.subject}" enthält keinen Link:\n${mail.text}`,
  ).not.toHaveLength(0);
  return links[0];
}

/** The path plus query of the link in a mail, ready for `page.goto()` against the suite's baseURL. */
export function linkPathIn(mail: DeliveredMail): string {
  const url = new URL(linkIn(mail));
  return `${url.pathname}${url.search}`;
}
