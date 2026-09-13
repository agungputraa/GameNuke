# Game Nuke Official Website — V5

Premium static product website for Game Nuke Premium.

## Deploy
Upload the whole folder to the web root for `gamenukeofficial.com`. The site is static HTML/CSS/JS and works on GitHub Pages or standard cPanel hosting.

## Download flow (sponsor mode — default)
Configuration lives in `site-config.js`.

1. User clicks any Download button on `index.html`.
2. `download.html` opens immediately in a new tab.
3. The original `index.html` tab waits 2000 ms, then navigates to:
   `https://dulyhagglermounting.com/2082665`
4. On `download.html`, one click on Download starts the APK through the official `downloadUrl` from `version.json` using a hidden download frame.
5. `download.html` waits 2000 ms, then navigates to:
   `https://bmadss.com/get/?spot_id=2006837&cat=25&subid=808526990`

If the browser blocks the new tab from the first step, the current tab falls back to the real download page so the user is not left with a broken button.

## AdSense review mode
The requested sponsor redirect flow can conflict with Google AdSense site-behavior / pop-under / unwanted-redirect policies. Before submitting the domain for AdSense review, edit `site-config.js`:

```js
adsenseReviewMode: true
```

This disables both sponsor redirects while preserving the real download flow and hides the sponsor-flow disclosure text. Set it back to `false` only when you intentionally want the sponsor flow active and are not relying on AdSense compliance.

## Search / GEO / content structure
- `index.html`: primary product landing page + SoftwareApplication/WebSite/WebPage/FAQ structured data
- `guides.html`: original educational content on telemetry, thermal behavior, Shizuku, and network tools
- `about.html`: product purpose and scope
- `privacy.html`: website/app privacy information
- `terms.html`: terms and compatibility/performance disclaimers
- `contact.html`: official support channels
- `sitemap.xml`, `robots.txt`, `llms.txt`, `manifest.webmanifest`
- `download.html` intentionally uses `noindex,nofollow`

## Performance decisions
- No render-blocking Google Fonts
- No Font Awesome, Tailwind runtime, Alpine runtime, or particle CDN
- Local SVG symbol icons
- WebP product logo
- Deferred JavaScript
- Particle canvas starts during idle time, caps DPR/particle count, pauses off-screen, respects Save-Data and Reduced Motion
- Responsive breakpoints include a safer enterprise navigation switch at 1180px

## Release metadata
Update `version.json` for APK URL, version number, file size, and release information. `site-config.js` is the source of truth for sponsor behavior.
