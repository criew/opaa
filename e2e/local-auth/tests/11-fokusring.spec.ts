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
/** The outline of a field is the ring's stand-in and holds the text threshold (guidelines 4.4). */
const FIELD_OUTLINE_CONTRAST_MINIMUM = 4.5;
const MIN_RING_WIDTH_PX = 2;
const MIN_RING_OFFSET_PX = 2;
const FIELD_OUTLINE_WIDTH_PX = 2;
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
  outlineOffset: number;
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
        outlineOffset: string;
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
      outlineOffset: Number.parseFloat(style.outlineOffset),
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

interface FieldFrame {
  focusVisible: boolean;
  rootOutlineStyle: string;
  frameWidth: number;
  frameColor: string;
  fieldSurface: string;
  ground: string;
}

/**
 * Reads how an outlined field shows its focus: the ring the root would draw (none expected, it
 * would cross the floating label), and the width and colour of the field's own outline.
 */
async function fieldFrameOf(input: Locator): Promise<FieldFrame> {
  const focusVisible = await input.evaluate((el) => el.matches(":focus-visible"));
  const frame = await input.evaluate((el) => {
    // The suite compiles without the DOM library; this runs in the browser.
    const browser = globalThis as unknown as {
      getComputedStyle(node: unknown): {
        outlineStyle: string;
        borderTopWidth: string;
        borderTopColor: string;
        backgroundColor: string;
      };
    };
    const root = el.closest(".MuiOutlinedInput-root");
    const outline = root?.querySelector(".MuiOutlinedInput-notchedOutline");
    if (!root || !outline) throw new Error("not an outlined field");
    const rootStyle = browser.getComputedStyle(root);
    const outlineStyle = browser.getComputedStyle(outline);
    let ground = "rgb(255, 255, 255)";
    for (let node = root.parentElement; node; node = node.parentElement) {
      const background = browser.getComputedStyle(node).backgroundColor;
      const alphaMatch = /rgba\([^)]*,\s*([\d.]+)\)/.exec(background);
      if (background !== "transparent" && (!alphaMatch || Number(alphaMatch[1]) === 1)) {
        ground = background;
        break;
      }
    }
    return {
      rootOutlineStyle: rootStyle.outlineStyle,
      frameWidth: Number.parseFloat(outlineStyle.borderTopWidth),
      frameColor: outlineStyle.borderTopColor,
      fieldSurface: rootStyle.backgroundColor,
      ground,
    };
  });
  return { focusVisible, ...frame };
}

function expectFieldFrame(frame: FieldFrame, what: string): void {
  expect.soft(frame.focusVisible, `${what}: :focus-visible`).toBe(true);
  expect.soft(frame.rootOutlineStyle, `${what}: ring around the field`).toBe("none");
  expect.soft(frame.frameWidth, `${what}: outline width`).toBe(FIELD_OUTLINE_WIDTH_PX);
  const color = parseColor(frame.frameColor);
  expect.soft(color[3], `${what}: outline colour ${frame.frameColor} is opaque`).toBe(1);
  for (const surface of [frame.fieldSurface, frame.ground]) {
    expect
      .soft(
        contrastOver(color, parseColor(surface)),
        `${what}: outline ${frame.frameColor} against ${surface}`,
      )
      .toBeGreaterThanOrEqual(FIELD_OUTLINE_CONTRAST_MINIMUM);
  }
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
      expectFieldFrame(await fieldFrameOf(email), "Eingabefeld");

      const button = page.getByRole("button", { name: "Anmelden" });
      await tabTo(page, button);
      const buttonRing = await indicatorOf(button, button);
      expectVisibleRing(buttonRing, "Schaltfläche");
      // The gap keeps the accent ring off the accent surface of the primary button.
      expect
        .soft(buttonRing.outlineOffset, "Schaltfläche: outline offset")
        .toBeGreaterThanOrEqual(MIN_RING_OFFSET_PX);

      const link = page.getByRole("link", { name: "Passwort vergessen?" });
      await tabTo(page, link);
      expectVisibleRing(await indicatorOf(link, link), "Link");
    });

    test(`Feld mit schwebendem Label in der Verwaltung (${scheme})`, async ({ page }) => {
      await page.emulateMedia({ colorScheme: scheme });
      await signInSuccessfully(page, bootstrapAdmin.email, bootstrapAdmin.password);
      await page.goto("/admin/branding");
      const name = page.getByLabel("Produktname", { exact: true });
      await expect(name).toBeVisible({ timeout: 20_000 });

      await tabTo(page, name);
      const label = page.locator("label", { hasText: "Produktname" });
      // The label floats on the field's top edge: a ring around the field would cross it.
      await expect(label).toHaveAttribute("data-shrink", "true");
      expectFieldFrame(await fieldFrameOf(name), "Feld mit schwebendem Label");
    });
  }
});
