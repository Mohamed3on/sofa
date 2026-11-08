---
name: IPTV (Fire TV)
description: A personal ten-foot TV app where the picture is the hero and all chrome floats over it as dark, blue-tinted material.
colors:
  ink: "#0B0E12"
  material: "#121820EB"
  raised: "#2C3744"
  focus-ring-soft: "#FFFFFFA6"
  tile: "#FFFFFF14"
  edge: "#FFFFFF1F"
  track: "#FFFFFF33"
  text-primary: "#F4F6F8"
  text-secondary: "#B4BCC6"
  text-tertiary: "#8790A0"
  accent: "#3D9BFF"
  live: "#FF5147"
  warning: "#FFB340"
  good: "#3DD68C"
  scrim-list: "#000000B3"
  scrim-modal: "#00000099"
typography:
  display-title:
    fontFamily: "Inter"
    fontSize: "36px"
    fontWeight: 600
    lineHeight: "42px"
    letterSpacing: "-0.02em"
  display:
    fontFamily: "Inter"
    fontSize: "30px"
    fontWeight: 600
    lineHeight: "36px"
    letterSpacing: "-0.02em"
  title:
    fontFamily: "Inter"
    fontSize: "22px"
    fontWeight: 600
    lineHeight: "28px"
    letterSpacing: "-0.015em"
  headline:
    fontFamily: "Inter"
    fontSize: "16px"
    fontWeight: 500
    lineHeight: "21px"
    letterSpacing: "-0.006em"
  body:
    fontFamily: "Inter"
    fontSize: "15px"
    fontWeight: 400
    lineHeight: "20px"
    letterSpacing: "0em"
  caption:
    fontFamily: "Inter"
    fontSize: "13px"
    fontWeight: 500
    lineHeight: "17px"
    letterSpacing: "0.01em"
  caption-tabular:
    fontFamily: "Inter"
    fontSize: "13px"
    fontWeight: 500
    lineHeight: "17px"
    letterSpacing: "0.01em"
    fontFeature: "tnum"
rounded:
  chip: "6px"
  row: "12px"
  panel: "20px"
spacing:
  xs: "4px"
  s: "8px"
  m: "12px"
  l: "16px"
  xl: "24px"
  xxl: "32px"
  xxxl: "48px"
  safe-x: "48px"
  safe-y: "27px"
components:
  action-button:
    textColor: "{colors.text-primary}"
    typography: "{typography.headline}"
    rounded: "{rounded.row}"
    padding: "12px 24px"
  action-button-focused:
    backgroundColor: "{colors.raised}"
    textColor: "{colors.text-primary}"
  page-button:
    backgroundColor: "{colors.material}"
    textColor: "{colors.text-primary}"
    typography: "{typography.headline}"
    rounded: "{rounded.row}"
    padding: "12px 24px"
  page-button-focused:
    backgroundColor: "{colors.text-primary}"
    textColor: "{colors.ink}"
  panel:
    backgroundColor: "{colors.material}"
    rounded: "{rounded.panel}"
    padding: "32px"
  list-row:
    textColor: "{colors.text-primary}"
    typography: "{typography.headline}"
    rounded: "{rounded.row}"
    padding: "8px"
  list-row-focused:
    backgroundColor: "{colors.raised}"
  poster-card:
    backgroundColor: "{colors.raised}"
    rounded: "{rounded.row}"
    width: "132px"
    height: "198px"
  wide-card:
    backgroundColor: "{colors.raised}"
    textColor: "{colors.text-primary}"
    typography: "{typography.caption}"
    rounded: "{rounded.row}"
    width: "184px"
    height: "104px"
  episode-still:
    backgroundColor: "{colors.raised}"
    rounded: "{rounded.row}"
    width: "128px"
    height: "72px"
  live-mark:
    backgroundColor: "#FF514729"
    textColor: "{colors.live}"
    typography: "{typography.caption}"
    rounded: "{rounded.chip}"
    padding: "1px 6px"
  progress-line:
    backgroundColor: "{colors.track}"
    textColor: "{colors.text-secondary}"
    height: "3px"
---

# Design System: IPTV (Fire TV)

## Overview

**Creative North Star: "The Picture Comes First"**

The video is the hero. Every piece of chrome is a dark, slightly blue-tinted translucent material that floats over the picture and darkens it only where text sits; when no video plays, the ground is a near-black ink. The library surfaces carry the same logic with titles bringing their own colour through full-bleed still backdrops held back by ink scrims. The app has no light theme and never had one: dark surfaces were designed as dark from the start.

The system is built for a 960x540 dp canvas read from a sofa with a D-pad, on a Fire TV Stick 2nd gen whose small GPU shares memory with a 4K decode. Performance is part of the visual language: every surface is drawn in one pass, without offscreen layers, so focus moves and transitions hold frame time. Focus is depth, not colour: the focused thing lifts toward the viewer on a quick, non-bouncing spring and takes the lighter raised layer or a ring. One accent, a clear blue, marks what is playing, what is selected and where focus rings sit on artwork; everything else is neutral.

Type is Inter at a refined ten-foot scale (nothing below 13sp), copy is plain sentence case, and status words carry their meaning by colour on a tint of their own colour.

**Key Characteristics:**
- Dark-native translucent material over video; ink ground when nothing plays.
- Depth by tone, lift and scrim; no drop shadows anywhere.
- One accent (#3D9BFF), used sparingly; progress in lists is neutral.
- Focus springs (0.22s, no overshoot) with scale and ring, read only while drawing.
- One-pass drawing on weak hardware; no offscreen layers, the backdrop included.
- 5% overscan-safe inset (48 x 27 dp); chrome never sits closer to the edge.

## Colors

A cool blue-black neutral ramp with one clear blue accent and three status hues that only ever appear as meaning.

### Primary
- **Signal Blue** (accent): what is playing, the selected group, the text caret, the "Watched" word and the watched check disc, the "New episode" caption in Up next, the player's seek bar, the indeterminate activity segment, and the focus ring on posters, wide cards and multi-view tiles. Clears 3:1 on every surface it sits on.

### Neutral
- **Ink** (ink): the ground behind everything when no video plays, and the colour every library scrim is mixed from.
- **Night Glass** (material, 92% opaque): floating chrome over video: overlay columns, banner panels, quick panel, dialogs, a show's episode list.
- **Raised Slate** (raised): the focused row's lifted surface, and the placeholder fill behind every poster, card and still while art loads.
- **Soft White Ring** (focus-ring-soft): the 1.5dp ring on focused list rows, panel rows and action buttons.
- **Tile** (tile): faint lift behind channel logos and search keys.
- **Lit Edge** (edge): the 1dp hairline that edges material panels and unfocused page buttons.
- **Track** (track): progress and activity tracks.
- **Text Primary / Secondary / Tertiary** (text-primary, text-secondary, text-tertiary): names and headings; now/next, plot and meta lines; times, badges and hints. Each clears 4.5:1 on Night Glass.
- **List Scrim** (scrim-list): the flat tint under the channel columns. **Modal Scrim** (scrim-modal): dims everything behind a modal panel.

### Status
- **Live Red** (live): "LIVE" marks, errors that need attention, bad stream health. **Amber** (warning): weak picture or health. **Green** (good): a stream playing well, 1080p and up. Status colours appear as text on a 16% tint of the same colour, never as solid fills.
- **Score ramp** (`Palette.share`, `Palette.outOf10`): five steps at one lightness, acclaimed green to poor rose, as text on the numbers of IMDb's and Letterboxd's averages (Letterboxd's doubled, out of 10), the card chip's combined score and the owner's "You 9/10". Review scores only: never the tier chip, never the accent.

### Named Rules
**The One Accent Rule.** Signal Blue marks state (playing, selected, focused artwork, caret, watched); it never decorates. Progress lines in lists and cards are neutral (text-secondary on track) so the accent stays for focus; only the player's own seek bar fills in accent.

**The Dark-Native Rule.** Dark surfaces are designed deliberately as their own scale (ink, night glass, raised slate); never derive them by flipping a light scale. There is no light theme.

**The Tinted Status Rule.** A status word sits on a 16% tint of its own colour, so a list of them reads by colour without shouting.

## Typography

**Display Font:** Inter (bundled Regular, Medium, SemiBold; other scripts fall back to the system font)
**Body Font:** Inter
**Label/Mono Font:** Inter with tabular figures (`tnum`) where numbers change in place or line up

**Character:** One family, refined rather than shouty. Tracking tightens as size grows and opens slightly when small; leading is tight on large text and looser on small.

### Hierarchy
- **Display Title** (600, 36sp, 42sp): the title on Up next and on film and show title pages, where the title is the screen's subject. Only there.
- **Display** (600, 30sp, 36sp): the channel name in the banner, the library hero's focused title.
- **Title** (600, 22sp, 28sp): screen and section headings (details, search, errors); monograms on posters and stills without art.
- **Headline** (500, 16sp, 21sp): channel row names, row titles, button labels, episode titles, season headings.
- **Body** (400, 15sp, 20sp, text-secondary): now/next lines, schedule rows, meta lines, plot (as wide as the library hero, up to the Search button).
- **Caption** (500, 13sp, 17sp, +0.01em, text-tertiary): times, quality badges, key hints, cast and director lines.

### Named Rules
**The Sofa Floor Rule.** Nothing below 13sp; it is read from across a room.

**The Tabular Where Needed Rule.** Tabular figures only on numbers that tick or align (clock times, counts, buffer, bitrate). A font feature makes text layout several times slower on the stick, which a list of rows feels.

**The No Eyebrow Rule.** No small all-caps line above a heading. Headings stand alone; context goes in a caption line beside or below, in sentence case. All caps is reserved for short status marks (LIVE) and format names (4K, VPN).

## Layout

A fixed 960x540 dp canvas with a 5% overscan-safe inset (48dp horizontal, 27dp vertical); chrome never sits closer to the edge. Spacing comes from one step scale (4, 8, 12, 16, 24, 32, 48) and nothing ad hoc. Rhythm is tight inside a group (4dp between a title and its meta, 8dp inside a card) and opens between groups (16-24dp between hero, buttons and the next row).

Over live video, chrome takes the edges: the channel list slides in from the left over a flat scrim that covers its columns (to 91% of the width) and eases off past them; the banner rises from the bottom over a bottom-up scrim. In the library, text sits low-left at the safe inset in a column (760dp on Up next and a film page, so meta lines and plots rarely scroll; 420dp beside a show's episode panel) over a full-bleed backdrop, with the episode list on material to its right. Rows of cards scroll horizontally; lists fade into what they sit on at their top and bottom edges (edgeFade) rather than meeting a divider. Content blocks have fixed line counts where a change would shift focus targets, so buttons never move as a hero changes title.

## Elevation & Depth

No drop shadows exist in the system. Depth comes from three things: translucent material over the picture with a 1dp lit edge; gradient and flat scrims that darken the video only where chrome sits; and focus lift, where the focused element scales toward the viewer and takes the lightest layer (raised slate) or a ring. Bigger surfaces read heavier: more opaque, rounder. API 30 has no blur and nothing can blur a video SurfaceView, so the "glass" is tint, never blur.

### Named Rules
**The One Pass Rule.** Every surface draws in one pass: rounded fills instead of clips where possible, alpha applied per draw (ModulateAlpha) rather than through an offscreen copy, animated values read in the draw phase so focus moves repaint two items and recompose nothing. The library backdrop bakes its ink scrims into the still once (on the CPU, off the main thread), so its crossfade draws one or two pictures and no layer: the layered version cost the stick ~50 ms a frame.

**The No Stacked Glass Rule.** Never stack one translucent surface on another.

**The Flat Scrim Rule.** Behind full-height chrome over video, prefer a flat tint under the content and a gradient only where content never reaches; a full-screen gradient cost the stick four times the frame time of a flat tint.

**The Readable Over White Rule.** Every scrim is dense enough where text sits that body text clears 4.5:1 over a white sky: the library backdrop holds 94% to 86% ink across the text column (to 75% width) before falling off by 95%, plus a 70% ink band at the top behind Search.

## Shapes

Corners grow with surface size: 6dp on chips, keys and small marks; 12dp on rows, buttons, posters, cards and stills; 20dp on panels. A nested shape's radius is the outer radius minus its inset, and focus rings are drawn inset by half their stroke with the radius reduced to match. Progress tracks are fully rounded capsules 3dp tall. Marks are drawn, not typed: the watched mark is a white check stroked on a 22dp accent disc, the selection marker an accent capsule.

## Components

### Buttons
Quiet until focused; the press is felt on the same frame as the key.
- **Shape:** gently rounded (12dp).
- **Action button:** headline label, 24 x 12dp padding, no fill at rest; focus applies the focus lift (raised slate fill, soft white 1.5dp ring, 1.03 scale). OK presses it in to 0.96. Weight never changes with state.
- **Page button** (title pages and Up next): a Night Glass capsule 44dp tall (chips 36dp) with a 1dp lit edge at rest; focus fills it solid text-primary with ink text and scales it 1.06, so the one thing OK will press reads from across the room. Same 0.96 press. Only the primary action (Resume or Play, a drawn play mark before the label) is labelled at rest and focused on arrival; secondary actions (Start over, Versions, Trailer, Details, Episodes) and Movies' corner Search and Live TV are round drawn icons (`Glyph`, 20dp, 2dp round strokes) whose label slides out while focused, revealed in the layout phase.

### Chips and Badges
- **Watched line** (film and show pages): the cards' check disc at 18dp and a body line, "Watched 15 Dec 2024", "Watched twice · last 3 Nov", a show's "Last watched yesterday", plain "Watched" when no day is known. It shares the progress slot under the meta line (after the time left and its bar on a part-way rewatch), so the page gains no row for it.
- **Quality badges:** one quiet caption line joined with " · " ("4K · 50fps · Atmos"), no capsules, so they never compete with the name.
- **Live mark and info pills:** caption text in its status colour on a 16% tint of that colour, 6dp radius, 6 x 1dp padding. Drawn in one canvas from cached text layouts because rows show them by the dozen.
- **Picture tier:** the same pill, and the word alone on a card's corner: Reference in green, Great in text-primary, OK in text-secondary, Soft in amber. Never the accent: a tier is a quality, not a state.

### Cards / Containers
- **Panel (material):** Night Glass, 20dp radius, 1dp lit edge, 32dp padding for dialogs; modal panels sit over the modal scrim.
- **Poster card:** 2:3 at 132 x 198dp, 12dp radius, raised slate behind until art loads (150ms fade), the title's two-letter monogram in title type when there is none.
- **Wide card:** 16:9 at 184 x 104dp, a backdrop (else the poster), a bottom gradient to 90% black under a caption-size name line and a neutral progress line, 8dp inner padding.
- **Focus on artwork:** posters and wide cards scale 1.08 and take a 3dp Signal Blue ring inset by half its stroke. Artwork carries its own light, so a soft white ring would vanish on bright posters; the theme colour at 3:1 holds everywhere.

### Lists
- **Row:** headline name over body or caption lines, 8dp padding, 12dp radius. Focus lift: raised slate fill, 1.5dp soft white ring, 1.03 scale, on the 0.22s spring.
- **Episode row:** a 128 x 72dp still (else the episode number in title type) with a neutral progress line inside its bottom edge, then "N. Title" (generic "Episode N" titles dropped), minutes and an accent "Watched" in caption.
- **Edges:** lists fade into their ground over 24dp at the top and 48dp at the bottom (12/24dp inside an episode panel).
- **Match line and matchup:** a football match the scoreboards know reads as the match, not its broadcast slot, and one way everywhere: a state pill (the minute in Live Red, HT in amber, FT in text-tertiary, kick-off time in text-secondary, each on a 16% tint of itself), the home crest, "Man Utd 4–4 Aston Villa" in short names with only the score in tabular figures ("v" before kick-off), the away crest. No LIVE mark and no time bar; any other programme keeps both. Live now heads each such match with that line and its 🏆 competition caption, pinned on ink while its channels scroll under it; the channels below say only flag (Arabic is 🇪🇬), name and badges. The banner and info view replace the programme title with a matchup: 32dp crests flanking the names in title type, score over the state pill between them, the competition as a caption, and no guide text.
- **Competition logos:** a 🏆 competition the server has a logo for (ESPN's, dark-ground set) shows it in place of the 🏆, on its own square 24dp slot (the mark itself about 20dp) held before it loads, straight on the ground with no chip, then the name: in the groups column, Live now's captions and the matchup caption. One without a logo reads as before.

### Navigation
D-pad only. Panels enter from the edge they leave by (list from the left, banner from the bottom) by a third of their size on the 0.35s panel spring, staggered 15% for a second column. Zapping moves banner content in the direction of travel. Back always peels one layer. Held, Back switches between live and Movies & Shows (reopening the title page it was left on); Movies' top-right corner carries a Live TV page button with a drawn Live Red dot beside Search for the same trip. With Remove animations on, everything cross-fades over 160ms and nothing scales.

### Library Backdrop (signature)
A full-bleed title still decoded at 720p, crossfading over 400ms on an ink ground, under a dense left scrim, a bottom scrim, and a top band. On the rows page it dims a further 35% and an ink ground rises from the bottom to where the rows start, fading in over 72dp.

### Activity line
Indeterminate progress as a 30%-wide accent segment gliding along a 160 x 3dp track (1.1s linear); with reduced motion it stays put and breathes.

## Do's and Don'ts

### Do:
- **Do** pick spacing from the step scale (4 to 48dp) and keep chrome inside the 48 x 27dp safe inset.
- **Do** give focus as lift: raised slate plus the 1.5dp soft white ring on rows and panels, a 3dp accent ring on artwork, on the 0.22s no-overshoot spring.
- **Do** take a theme colour for any focus ring that must stand out, at 3:1 or better against what it sits on.
- **Do** draw every surface in one pass and read animated values in the draw phase.
- **Do** keep text over pictures at 4.5:1 with ink scrims dense where the text sits.
- **Do** draw marks (checks, carets, dots) as shapes in the theme colours.
- **Do** let missing art fall back to raised slate with a monogram or number, at the same size.

### Don't:
- **Don't** add eyebrow labels (a small all-caps line above a heading).
- **Don't** use drop shadows or blur; depth is tone, lift and scrim.
- **Don't** stack translucent surfaces, or add offscreen layers.
- **Don't** use the accent for decoration or for list progress.
- **Don't** set text below 13sp, or apply tabular figures to text that does not tick or align.
- **Don't** let motion overshoot or bounce; a remote carries no momentum.
- **Don't** derive dark surfaces by inverting a light scale.
- **Don't** use typed glyphs as icons.
