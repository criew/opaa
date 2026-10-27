import { expect, test, type Locator, type Page } from "@playwright/test";
import {
  acceptConfirmDialogs,
  bootstrapAdmin,
  configureSmtp,
  enableLocalAccounts,
  signInSuccessfully,
} from "../../fixtures/localAuth";

/**
 * The keyboard focus indicator as the browser computes it (guidelines 4.4, accessibility.md 2.4):
 * an opaque line of at least 2px whose colour reaches 3:1 against the surface behind the focused
 * element, for a button, a link and an input field, each reached with the Tab key so the browser's
 * own `:focus-visible` heuristic decides, in both colour schemes.
 *
 * The sign-in page with the local form switched on carries all three: the primary button sits on an
 * accent surface, the link and the fields on the page ground.
 */

const UI_CONTRAST_MINIMUM = 3;
const MIN_RING_WIDTH_PX = 2;
const MAX_TAB_PRESSES = 30;

type Rgba = [number, number, number, number];

function parseColor(value: string): Rgba {
  const match = /rgba?\(([^)]+)\)/.exec(value);
  if (!match) throw new Error(`not an rgb colour: ${value}`);
  const parts = match[1].split(",").map((part) => Number.parseFloat(part));
  return [parts[0], parts[1], parts[2], parts[3] ?? 1];
}

function luminance([r, g, b]: Rgba): number {
  const channel = (v: number) => {
    const c = v / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
}

/** Contrast of a possibly translucent colour painted over an opaque ground. */
function contrastOver(color: Rgba, ground: Rgba): number {
  const a = color[3];
  const painted: Rgba = [0, 1, 2].map((i) => color[i] * a + ground[i] * (1 - a)) as unknown as Rgba;
  const [hi, lo] = [luminance(painted), luminance(ground)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

/** Presses Tab until the target holds the focus, so focus arrives the way a keyboard user moves it. */
async function tabTo(page: Page, target: Locator): Promise<void> {
  for (let presses = 0; presses < MAX_TAB_PRESSES; presses++) {
    const focused = await target.evaluate((el) => {
      // The suite compiles without the DOM library; this runs in the browser.
      const browser = globalThis as unknown as { document: { activeElement: unknown } };
      return el === browser.document.activeElement;
    });
    if (focused) return;
    await page.keyboard.press("Tab");
  }
  throw new Error(`Tab never reached ${target.toString()}`);
}

interface Indicator {
  focusVisible: boolean;
  outlineStyle: string;
  outlineWidth: number;
  outlineColor: string;
  ground: string;
}

/**
 * Reads the computed ring of `ringHost` while `focused` holds the focus, and the first opaque
 * background behind the ring host - the surface the ring is painted against.
 */
async function indicatorOf(focused: Locator, ringHost: Locator): Promise<Indicator> {
  const focusVisible = await focused.evaluate((el) => el.matches(":focus-visible"));
  const ring = await ringHost.evaluate((el) => {
    // The suite compiles without the DOM library; this runs in the browser.
    const browser = globalThis as unknown as {
      getComputedStyle(node: typeof el): {
        outlineStyle: string;
        outlineWidth: string;
        outlineColor: string;
        backgroundColor: string;
      };
    };
    const style = browser.getComputedStyle(el);
    let ground = "rgb(255, 255, 255)";
    for (let node = el.parentElement; node; node = node.parentElement) {
      const background = browser.getComputedStyle(node).backgroundColor;
      const alphaMatch = /rgba\([^)]*,\s*([\d.]+)\)/.exec(background);
      if (background !== "transparent" && (!alphaMatch || Number(alphaMatch[1]) === 1)) {
        ground = background;
        break;
      }
    }
    return {
      outlineStyle: style.outlineStyle,
      outlineWidth: Number.parseFloat(style.outlineWidth),
      outlineColor: style.outlineColor,
      ground,
    };
  });
  return { focusVisible, ...ring };
}

function expectVisibleRing(indicator: Indicator, what: string): void {
  expect.soft(indicator.focusVisible, `${what}: :focus-visible`).toBe(true);
  expect.soft(indicator.outlineStyle, `${what}: outline style`).toBe("solid");
  expect.soft(indicator.outlineWidth, `${what}: outline width`).toBeGreaterThanOrEqual(
    MIN_RING_WIDTH_PX,
  );
  const color = parseColor(indicator.outlineColor);
  expect.soft(color[3], `${what}: ring colour ${indicator.outlineColor} is opaque`).toBe(1);
  const ratio = contrastOver(color, parseColor(indicator.ground));
  expect.soft(
    ratio,
    `${what}: ring ${indicator.outlineColor} against ${indicator.ground}`,
  ).toBeGreaterThanOrEqual(UI_CONTRAST_MINIMUM);
}

test.describe("Fokusring nach Tastaturnavigation", () => {
  test.beforeAll(async ({ browser }) => {
    const context = await browser.newContext();
    const admin = await context.newPage();
    await acceptConfirmDialogs(admin);
    await signInSuccessfully(admin, bootstrapAdmin.email, bootstrapAdmin.password, {
      route: "/login/system",
    });
    // The mail server enables the "Passwort vergessen?" link on the sign-in page.
    await configureSmtp(admin);
    await enableLocalAccounts(admin);
    await context.close();
  });

  for (const scheme of ["light", "dark"] as const) {
    test(`Eingabefeld, Schaltfläche und Link auf der Anmeldeseite (${scheme})`, async ({
      page,
    }) => {
      // The scheme is set before loading: switching on the rendered page leaves a mixed palette.
      await page.emulateMedia({ colorScheme: scheme });
      await page.goto("/login");
      const email = page.getByLabel("E-Mail-Adresse");
      await expect(email).toBeVisible();

      await tabTo(page, email);
      const field = email.locator("xpath=ancestor::*[contains(@class,'MuiOutlinedInput-root')][1]");
      expectVisibleRing(await indicatorOf(email, field), "Eingabefeld");

      const button = page.getByRole("button", { name: "Anmelden" });
      await tabTo(page, button);
      expectVisibleRing(await indicatorOf(button, button), "Schaltfläche");

      const link = page.getByRole("link", { name: "Passwort vergessen?" });
      await tabTo(page, link);
      expectVisibleRing(await indicatorOf(link, link), "Link");
    });
  }
});
