# Official DeepSeek Android UI reference (v2.6.2, 2026-10-18)

Source: Google Play listing `com.deepseek.chat`, the 10 current promotional PNGs, and resource inspection of the 2.6.2 APK fetched from APKPure (not distributed in this repository). The screenshots are promotional compositions: measure the phone inset, not their ocean background. Do not copy proprietary drawable/font assets into this repository. The reconstructed UI is an unofficial client and must not present itself as the official app.

## Measured baseline (light, portrait)
- Reference screenshot: listing's “Focus & tough, deeper analysis” phone inset at 1080 × 1920 source, phone interior x≈120–960 (840 px). Width normalized to 393 dp gives scale ≈2.14 px/dp. Status bar y≈560–614 (25 dp), top toolbar y≈614–686 (34 dp), message area white #FFFFFF.
- Top toolbar: hamburger at 24 dp from left, centered conversation title ~14 sp medium, circular new-chat icon 22–24 dp at right; horizontal margin ~16 dp. Empty state centers the logo over “How can I help you?” (centered text ~16 sp medium); use a fresh drawn mark, not the official logo asset.
- User prompt bubble: pale blue #F3F7FF (sampling promotional image; allow ±#03 per channel due to compression), ~12 dp corner radius, ~12 dp vertical and horizontal inner padding, right-aligned with 16 dp right outer margin, max ~80% width; black #1A1A1A 14 sp regular.
- Assistant response: no bubble; white canvas, black ~14 sp body text and bold headings. “Thought for 52 seconds” is small gray #909398 text with chevron. Markdown and math render inline; code in rounded lightly tinted cards with copy action.
- Bottom composer: x≈164–912 on source → 350 dp width at reference width, y≈1578–1732 → 72 dp high; white surface, subtle #E9EAED border/shadow, radius ≈22 dp, bottom 16 dp; placeholder “Type a message or hold to speak” (#B4B5B9, 13–14 sp). Pills at bottom: blue “Think” (icon and blue #426EFE label), outlined “Search” globe, ~27 dp high. Circle plus 28 dp and audio action 28 dp on right.
- Attachment tray (listing empty-state screenshot): horizontally preview thumbnails above a three-cell row “Camera”, “Photo”, “Document”, each with a dark pictogram and light gray rounded tile. Dark theme not shown in Play listing; derive an equivalent palette, then mark it as app interpretation rather than exact verified colors.

## APK resource facts
- `res/values/colors.xml` has named `primary` = `#FF426EFE`. The launcher and splash background are white. No bundled `res/font` in base APK; use Android default sans-serif/Roboto (not a copied font).
- `res/values/strings.xml`: `chat_input_placeholder_voice` = “Type a message or hold to speak”, `chat_input_placeholder_chat` = “Type a message”, `control_title_new_chat` = “New Chat”. The app's many dimensions are programmatic and are **not** reconstructible from resource XML; dp values above are measured estimates from raster marketing images, not a pixel-perfect guarantee.

## UI implementation targets
- 393 dp reference viewport; edge-to-edge status/navigation bars matching surface; chat, empty state, drawer/history, composer, attachment tray, settings, login, markdown/code examples in both light and dark.
- Use Material icons or original vector shapes. Do not bundle extracted APK assets.
- Accessibility: min 48 dp touch targets, meaningful labels, respect system text scaling.
