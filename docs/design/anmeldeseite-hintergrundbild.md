# Hintergrundbild der Anmeldeseite

Das mitgelieferte Motiv der Markenfläche auf der Anmeldeseite (`frontend/src/features/auth/assets/login-backdrop.webp`), seine Herkunft und der Weg von der Erzeugung bis zum Kontrastnachweis. Eine Installation kann es über das Branding durch ein eigenes Bild ersetzen ([Handbuch: Branding](../handbuch/betrieb/branding.md)).

## Motiv

Der Lesesaal eines Verwaltungsgebäudes, leicht erhöht gesehen: hohe, geordnete Regale mit Aktenordnern rechts und an der Rückwand, ein langer leerer Lesetisch, ein hohes Rundbogenfenster mit Morgenlicht, dahinter die Silhouette eines Rathauses. Aus den Ordnern steigen feine blaue Lichtfäden auf und verbinden sich über dem Tisch zu einer geordneten Konstellation kleiner Dokumentkarten, jede mit einer leuchtenden Fußnotenmarke. Das linke Drittel ist eine ruhige, dunkle Wand.

Was das Bild von OPAA erzählt ([Vision](../VISION.md)):

- **Wissen, das im Haus bleibt**: Alles spielt innerhalb der Mauern; die Akten sind die Quelle.
- **Belegbarkeit**: Die Karten tragen Fußnotenmarken, die Fäden führen zurück in die Regale, nicht ins Leere.
- **Verwaltung**: Aktenordner, Lesesaal, Rathaus im Fenster; keine Science-Fiction, keine Roboter.
- **Aufbruch**: Morgenlicht, ohne Pathos.
- **Platz für die Marke**: Das linke Drittel bleibt frei; dort stehen Logo und Claim.

Keine Personen, kein Text, keine Logos, keine Bildschirme. Die Farben sind die des Navy-Schemas (`navyRoles` in `frontend/src/shared/theme/tokens.ts`): Flächen `#012142`, `#00152D`, `#02305E`, Akzent `#1292EE`, `#61B5F6`, Aufhellung bis `#C6E3FC`, ein zurückgenommenes warmes Gold `#F5B83D` nur am Fenster.

## Erzeugung

Erzeugt am 25.10.2026 über Higgsfield mit dem Modell GPT Image 2.5 (Variante Flare, Qualität high, 2048 × 2048, Seitenverhältnis 1:1). Das Bild wurde für OPAA generiert und ist Teil des Projekts; es enthält keine erkennbaren Personen, Marken oder fremden Werke.

Prompt (englisch, so wie er an das Modell ging):

```text
Editorial illustration for a software login screen, square format, 2K. Interior of a bright, modern
archive and reading room of a German public administration building, seen from a slight elevation:
tall, perfectly ordered shelves with document folders and bound files along the right side and the
back wall; a tall arched window at the far end lets in soft dawn light in pale warm gold (#F5B83D) at
low intensity. From the folders, fine luminous threads of clear blue (#1292EE, #61B5F6) rise and
converge above a long empty reading table into an ordered constellation of small floating document
cards linked by thin lines, each card carrying a tiny glowing footnote marker: knowledge becoming
searchable and provable. Everything stays inside the building's walls. The left third of the image is
calm, almost empty deep navy (#012142) with a soft gradient, reserved for text. Deep navy palette
(#012142, #00152D, #02305E); highlights never brighter than pale blue (#C6E3FC) except a restrained
warm glow at the window. Flat vector shapes with soft atmospheric light, subtle grain, cinematic
depth, calm and dignified. No people, no text, no letters, no logos, no robots, no screens, no user
interface, no lens flare, no sparkles.
```

Dieselbe Sitzung erzeugte als Alternative ein Rathaus, dessen Fassade aus vernetzten Dokumentkarten besteht, vor einem Morgenhimmel (gleiches Modell; Prompt analog mit „the silhouette of a classic German town hall with a clock tower, flanked by a modern glass annex, drawn entirely from a fine network of small glowing document cards connected by thin blue threads“). Es liegt nicht im Repository.

Warum quadratisch: Die Markenfläche ist die linke Hälfte des Bildschirms ab Breakpoint `md`, also je nach Monitor zwischen etwa 0,9 und 1,2 breit zu hoch. Ein quadratisches Bild verliert bei `background-size: cover` an keiner dieser Flächen mehr als wenige Prozent am Rand.

## Aufbereitung

Ziel: eine Datei unter 300 kB, deren hellste Stelle bekannt und begrenzt ist. Die Spitzlichter werden sanft gekappt, weil der Kontrastnachweis nur das Maximum begrenzt; mit gekappten Spitzlichtern darf der Schleier leichter sein, und die Mitteltöne des Bildes kommen deutlicher durch als mit vollen Spitzlichtern unter einem dichten Schleier.

```bash
# 2048 px -> 1600 px, Spitzlichter oberhalb von 50 % sanft auf höchstens 165/255 gedrückt
ffmpeg -i quelle.png \
  -vf "scale=1600:1600:flags=lanczos,curves=all='0/0 0.5/0.5 0.75/0.564 1/0.647',format=rgb24" \
  login-backdrop.png

# WebP, verlustbehaftet; -sharp_yuv mindert Farbsäume der Farbunterabtastung
cwebp -q 82 -m 6 -sharp_yuv login-backdrop.png -o login-backdrop.webp
```

Ergebnis: 1600 × 1600, 120 948 Byte. Die Farbunterabtastung des WebP-Encoders erzeugt an Kanten einzelne Pixel, die heller sind als jedes Pixel der PNG-Vorlage; deshalb wird nicht die Vorlage gemessen, sondern die ausgelieferte Datei.

## Kontrastnachweis

Über dem Bild liegt ein Schleier aus `navyRoles.bg1` mit der Deckkraft `LOGIN_BACKDROP_DEFAULT_SCRIM_OPACITY` (0,56) in `AuthLayout.tsx`; hochgeladene Bilder behalten den festen Worst-Case-Schleier von 0,82, weil ihre Helligkeit unbekannt ist. Produktname (Weiß) und Claim (Weiß mit 78 % Deckkraft) müssen über jedem Pixel des Bildes mindestens 4,5:1 erreichen ([accessibility.md](./accessibility.md)).

`frontend/scripts/measure-login-backdrop.mjs` dekodiert die Datei mit ffmpeg, legt den Schleier rechnerisch über jedes der 2 560 000 Pixel (gleiche Formel wie `compositeOver` in `frontend/src/shared/utils/contrast.ts`) und meldet das Pixel mit dem geringsten Kontrast sowie die SHA-256-Prüfsumme der Datei:

```bash
cd frontend && node scripts/measure-login-backdrop.mjs src/features/auth/assets/login-backdrop.webp 0.56
```

| Messwert | Wert |
|---|---|
| Hellstes Pixel (zugleich ungünstigstes für Name und Claim) | `#ADB295` |
| Kontrast Produktname über diesem Pixel | 6,52:1 |
| Kontrast Claim über diesem Pixel | 4,72:1 |
| SHA-256 der Datei | `c7b2110edaeaf0dcf6872af1f221374986366c4ca7050b92f4021ac8da180496` |

`AuthLayout.contrast.test.ts` bindet Prüfsumme, Worst-Case-Pixel und die Konstanten des Skripts (Grundfarbe, Claim-Deckkraft) aneinander und rechnet den Kontrast nach, zusätzlich mit einer Toleranz von 3 % Aufhellung für Dekoder-Unterschiede. Wer das Bild ersetzt, führt das Skript erneut aus und zieht die Werte im Test nach; bis dahin schlägt der Test an der Prüfsumme fehl.

Warum nicht ohne Schleier: Bei voller Sichtbarkeit müsste das hellste Pixel unter etwa `#5C5C5C` liegen, damit der Claim 4,5:1 hält; das Bild wäre dann kein Bild mehr. Der Schleier von 0,56 zeigt 44 % des Motivs, gegenüber 18 % unter dem Worst-Case-Schleier.
