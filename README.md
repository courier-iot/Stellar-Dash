# Stellar Dash

A fast, reaction-based space platformer for Android by **Courier, of Stellarite**.

Your runner never stops. Read the terrain, react to warnings, and survive as long as you can.

## Modes

- **Endless** — run as far as you can. Speed keeps climbing.
- **Trainer** — an endless loop of reaction training:
  1. **Test (25 levels):** calibrates your reactions. New hazards are introduced one pair at a time.
  2. **Training (100 levels):** generated from your results. Your weakest hazards show up more, and each
     hazard's difficulty adapts on its own (pass → harder, fail → easier).
  3. **Retest (25 levels):** same structure as the test, so the results compare fairly.
  4. Back to Training, then Retest again, forever. Each loop is a new **Stage**.
  Dying restarts the level. Every attempt counts as data.
- **Expert Trainer** — the same loop with ~10% more speed, much denser hazards, extra mines,
  and **3 lives**. Lose them all and you go back to the last checkpoint (every 5 levels).
- **Profile** — a reflex map: one node per test/retest that climbs as your rating improves,
  plus per-skill scores, warning response time (ms), gap/spike timing (early/late),
  and short-hop tap length.

## Controls

Pick a layout in **Settings → Controls**:

| | Half-screen (default) | Buttons |
|---|---|---|
| Jump | Tap/hold the right half | JUMP button (bottom-right) |
| Slide | Hold the left half | SLIDE button (bottom-left) |
| Dash | Swipe right anywhere (swipe on the jump side = jump-dash) | DASH button |

- Hold jump for a full jump, quick tap for a short hop. Jump again in mid-air to double jump.
- Slide while airborne = fast-fall slam. One air-dash per jump; dash cooldown ~0.9 s.
- Dashing only makes you safe against **phase walls**. Spikes, mines and lasers still hurt.
- Back button or the pause icon pauses.

## Hazards (32, introduced two per test level)

| Group | Hazards |
|---|---|
| Reaction | Pop-up spikes, closing doors, meteors, collapsing floors, fake-outs |
| Decisions | Decoys, signals (red ▲ jump / blue ▼ slide), mystery gates, split paths, mirror runs (Expert only) |
| Timing & rhythm | Gaps, spikes, mines, laser fences, stepping stones, bounce pads, phase walls, rhythm runs |
| Jump control | Step-ups, double gaps, short hops, air-dash gaps, moving platforms, wind |
| Slide & dash | Low barriers, slide tunnels, cracked floors, chasers, combos |
| Focus & memory | Blackouts, distraction flashes, speed surges |

Settings also has sound, vibration, and reset buttons for Trainer / Expert progress (tap twice).

## Install

Download the latest `StellarDash-vX.Y.Z.apk` from **Releases** and open it on your phone
(allow "install unknown apps" for your browser or file manager if asked). Android 8.0+.

## Building

GitHub Actions builds everything; you don't need Android Studio.

| What you do | What you get |
|---|---|
| Push to `main` | A debug APK under **Actions → the run → Artifacts → StellarDash-debug** |
| Push a tag like `v1.0.0` | A **signed** release APK attached to a new GitHub Release |

The version comes from the tag: `v1.2.3` becomes versionName `1.2.3` and versionCode `10203`,
so every new tag is a proper update for auto-updaters.

### Signing (one-time setup)

Add these under **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | Your `.jks` keystore, base64-encoded |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias |
| `KEY_PASSWORD` | Key password |

You can reuse the keystore from your other Stellarite apps. To base64 it on Windows:
`certutil -encodehex -f release.jks release.b64 0x40000001`, then paste the contents of `release.b64`.
On macOS/Linux: `base64 -w0 release.jks` (macOS: `base64 -i release.jks`).

**Keep the same keystore forever** — Android refuses updates signed with a different key.

### Releasing

```
git tag v1.0.0
git push origin v1.0.0
```

Or create the release on the GitHub website with a new tag `v1.0.0`.
