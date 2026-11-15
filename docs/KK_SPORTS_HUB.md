# Kable Killers TV — Sports Hub (development plan)

Base: signed, user-tested 1.1.3 staging revision `dd345242c9da29bbf3c9f3d4fc00879b010a6bfd`.
Branch: `feature/kk-sports-hub`. Do not change published v1.1.3 or store listing until a later release is explicitly approved.

## Product requirements
- TV-first electric-blue Kable Killers lightning branding; D-pad focus and Back navigation.
- USA-focused league filters: NFL, college football, NBA, MLB, NHL, UFC. No cricket or soccer.
- Sections: Live Now, Upcoming, Replays, Highlights.
- Clearly distinguish actual live availability from schedules and unavailable streams; never fabricate event data.
- Respect household-local profiles and existing add-on settings; do not leak one customer's profile to another.
- Integrate playback only through existing authorized providers/add-ons and existing player routing; no unlicensed or fabricated stream URLs.
- Loading, empty, offline and error states; responsive for onn and Fire TV devices.

## Implementation phases
1. Add standalone Sports Hub route and TV remote-friendly screen shell, with league tabs and section layout.
2. Add normalized sports-event model, provider adapters and cache; only display source-grounded events.
3. Wire event detail, live playback selection and replays/highlights from available lawful providers.
4. Regression tests for navigation, profile isolation, empty/offline states, and upgrade over 1.1.3.
5. Signed candidate, device testing and explicit approval before publication.

## Release safety
- Do not merge this branch into `kable-killers-branding` or change Kable Killers Store listing during development.
- Existing 1.1.3 APK and GitHub release remain immutable.
