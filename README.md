# TapInvaders

The sequel to TapMeteors: the galaxy's least appreciated **space sweeper**
clocks in for a second shift, and this time the mess **marches in formation**.
A synthwave neon vector take on the 1978 arcade invasion for the **RayNeo X3
Pro** AR glasses — OpenGL ES 3.0, additive lines on black (transparent on the
waveguide), rendered side-by-side per eye.

## Three shifts, one title screen

Swipe on the title to choose, tap to clock in:

- **CLASSIC** — the 1978 shift, by the book. Mono-green rack of 55, four
  bunkers that erode cell by cell (your own shots chew them too), one bolt in
  the air at a time, the red cruiser crossing the top for a mystery bonus,
  and the four-note march bass that quickens as the rack thins. No power-ups,
  no commentary. Purity.
- **REMIX** — the color and the commentary switched on: hue-cycling rack,
  particle bursts, the sweeper's deadpan voice lines, and **power-ups that
  drop from dying invaders** — Rapid Fire, Triple Shot, Shield, Piercing
  Bolts, Time Warp — caught with the cannon, ten seconds each.
  From **wave 6** the rack learns Galaga manners: front-row invaders peel
  off in a loop and **strafe the cannon** — weaving, firing aimed shots,
  body-checking — then swing home to march again. Pick one off mid-dive
  for **double points** (and better power-up odds). And from wave 2 the
  imperial cruiser arrives **armed**, a different weapon every wave,
  cycling and hastening: SCATTERBOMB (fans of falling fire), TRACTOR BEAM
  (drags the cannon toward its shadow), NECRO RAY (resurrects the fallen
  rack, slot by slot — kill it quick), MAGNET MAW (bends and swallows
  bolts; only piercing shots fly true), MINELAYER (drifting proximity
  mines, shootable for +25), COLUMN LANCE (telegraphs your column, then
  burns it — bunkers included).
- **FPS 3D** — stand on the firing line, behind the stick of a full 3D
  wireframe interceptor riding the bottom of your view — raised spine, swept
  wings, tip fins, twin flickering engine pods — that **banks into every
  strafe** and muzzle-flashes off the nose. The formation is a glowing wall
  leaning in over you, and its fire comes *at* you as spinning fireballs
  that **grow as they close** — big enough to shoot down (+10), urgent
  enough that a proximity blip quickens and rises the nearer the closest
  one gets. No bunkers up here. Crosshair HUD.

## Controls — two gestures, no settings menu

| Gesture | Action |
|---|---|
| **Hold + drag** (horizontal) | Move while your finger is on the pad — the cannon **stops the instant you lift**. Drag back through center to reverse without lifting. |
| **Tap** | Fire. Also selects on the title and restarts after game over. |
| Swipe (vertical) | Title: choose mode. Game over: back to mode select. |

## The game

- 5×11 rack, classic scoring: 30 / 20 / 10 by row, cruiser 50–300.
- The **march is a beat**: one lateral step per bass note, tempo rising as
  the rack empties — the invaders animate in step with it.
- Rack reaching your line ends the shift immediately, as tradition demands.
- 3 lives, +1 every 5,000 points. Per-mode high scores and best waves persist.
- Each wave starts a step lower and angrier.

## The sweeper's voice

REMIX carries the full commentary (FPS gets the sparse cut; CLASSIC stays
silent): clock-in grumbles, wave-clear non-celebrations, **10 random variants**
of complaint when the imperial cruiser shows up, death soliloquies, and a
high-score line of total indifference. Speech plays louder than the effects,
which duck further while he's talking.

Voice clips are pre-generated **fish.audio S2.1 Pro**
([free developer API](https://fish.audio/blog/s2-1-pro-free-api/)) audio using
the same player voice model as TapMeteors
([`1864d40339ae4dbabf832f844c8d1d6f`](https://fish.audio/app/m/1864d40339ae4dbabf832f844c8d1d6f/)).
Most lines ship in the APK already; a few sequel-specific ones fall back to a
one-time baked Android-TTS render until regenerated:

```bash
export FISH_API_KEY=...   # free at fish.audio
python3 tools/generate_tts.py    # fills the missing clips, skips existing
./gradlew assembleDebug
```

## Sound

All effects synthesized at first launch, zero audio binaries: the four
descending **march bass notes**, cannon fire, bit-crushed invader pops,
bunker thuds, the cruiser's fast **warble loop**, its shoot-down sweep,
warp-in shimmer, wave fanfares, 1UP jingle, power-up chimes, spawn, dirge,
and high-score arpeggio.

## Build & install

```bash
cd ~/Projects/TapInvaders
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

JDK 17, AGP 8.7.3, Kotlin 2.0.21, compileSdk 35 / minSdk 29, zero
dependencies, zero vendor AARs. Binocular SBS auto-enables on RayNeo hardware
(detected by manufacturer identity, never `Build.MODEL` — it reports
`ARGF20`). Audio playback and SoundPool calls run off the render thread, and
no speech is ever synthesized at run time — lessons the predecessor learned
the hard way.
