# Game Nuke V6 — UI/UX & Download Flow Fix

## Download flow
- `index.html`: Download opens `download.html` in a new tab. The original tab keeps the requested 2-second sponsor timer to `https://dulyhagglermounting.com/2082665`.
- `download.html`: APK is opened directly from the user's click gesture in a dedicated browser download context. This replaces the hidden iframe approach that could be ignored/blocked by browsers and GitHub's delivery path.
- Only after the APK context starts successfully does `download.html` schedule the requested 2-second sponsor redirect to `https://bmadss.com/get/?spot_id=2006837&cat=25&subid=808526990`.
- Popup-blocker fallback prioritizes the APK and skips the sponsor redirect if a separate download context cannot be created.

## Mobile UI/UX
- Mobile header is now a full-width app bar instead of a centered floating nav card.
- Mobile hero is left-aligned with clearer hierarchy and thumb-friendly full-width CTAs.
- Device mockup remains secondary to the product message and does not collide with the copy.
- Hamburger menu redesigned as a structured full-screen product navigation with numbered sections, release information, primary actions, and utility links.
- Footer redesigned into release CTA, brand statement, product/help navigation, current build card, and legal row.
- Extra styling is isolated in `style-v6.css` and loaded after the existing design layers.

## QA completed
- JavaScript syntax check: `app.js`, `download.js`.
- Duplicate HTML IDs: none in `index.html` or `download.html`.
- One H1 per page.
- Visual responsive checks at 320, 390, 430, 820, and 1440 px: no horizontal overflow.
- Mobile hero alignment verified left at <=820 px.
- Download JS unit test verified the official GitHub APK URL is the first download target.
- Sponsor timer unit test verified 2000 ms on the download page.
- Index flow unit test verified new-tab `download.html` and 2000 ms original-tab sponsor timer.
