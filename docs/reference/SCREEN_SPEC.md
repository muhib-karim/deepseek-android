# Official DeepSeek Android app: screen specification (reference for v1.0.0)

What the official app (`com.deepseek.chat` 2.6.2, versionCode 282, see [SOURCE.json](SOURCE.json)) shows, screen by screen,
and how DeepSeek Android (Unofficial) reproduces it. This file describes publicly observable layout, colour, type,
spacing, icons and copy. No binary asset of the official app (logo, icons, fonts, images, animations) is copied into this
repository; every icon and the app mark are drawn in our own code.

## Sources and confidence

Every value carries a source tag:

| Tag | Source | Confidence |
|---|---|---|
| **[R]** | Resource table of the verified 2.6.2 base APK (string and colour resources read on 2026-10-20 with a local reader; nothing extracted into the repo) | Exact for copy and named colours |
| **[M]** | Measurements from the Play listing's promotional screenshots in [../ui-spec.md](../ui-spec.md) (phone inset normalised to 393 dp, about 2.14 px/dp, JPEG/PNG compression) | ±1-2 dp, ±1 sp, ±#03 per colour channel |
| **[E]** | Estimate from public screenshots and descriptions, not yet measured | To be replaced by values measured from the reference capture |

The reference workflow ([.github/workflows/reference.yml](../../.github/workflows/reference.yml)) installs the verified APK
on an emulator at the same viewport as our capture tests (1080 x 2200 px, 440 dpi = 392.7 dp wide, font scale 1.0) and
uploads the screens listed below as the `deepseek-official-reference` artifact. By default it installs the 2.6.2 arm64-v8a
build on an API 33 x86_64 Play image (ARM translation); the armeabi-v7a build and the pre-PairIP 1.4.1 build are selectable.
Signed-out screens need nothing else; signed-in screens need a dedicated test account in the
`DEEPSEEK_REFERENCE_EMAIL` / `DEEPSEEK_REFERENCE_PASSWORD` repository secrets.

**Status (2026-10-20):** the first dispatch (run 39436094511: API 30, armeabi-v7a build) installed the app but saved no
diagnostics. With diagnostics added, run 39440391796 showed why nothing was captured: from versionCode 110 on, the Play
builds wrap the app in Google's PairIP license check (`com.pairip.licensecheck.LicenseActivity`), which stops with "Check
that Google Play is enabled on your device" unless Play installed the app. The workflow now defaults to a Play system
image with Play recorded as the installer (`build=current`), and offers `build=pre-pairip`: the pinned 1.4.1 APK
(versionCode 107, same signer, no license check). Captures are labelled with the version they came from; screens taken
from 1.4.1 show an older interface and are compared with that in mind. Until a dispatch captures the screens, every [E]
value below is an estimate and no official capture is committed. When a run is reviewed, measured values replace [E]
here and sanitized captures go under [captures/](captures/).

## Global tokens

### Colour

| Token | Light | Dark | Used for |
|---|---|---|---|
| Accent | `#426EFE` [R] (named `primary`) | `#8FA8FF` [E] | Selected Think/Search, send button, links, primary buttons |
| Background | `#FFFFFF` [M] | `#161618` [E] | Chat, welcome, drawer list |
| Text | `#1A1A1A` [M] | `#ECECEF` [E] | Messages, titles |
| Secondary text | `#909398` [M] | `#9A9CA3` [E] | "Thought for N seconds", timestamps, section headers |
| Placeholder | `#B4B5B9` [M] | `#6E7178` [E] | Composer hint |
| User bubble | `#F3F7FF` [M] | `#26304A` [E] | User messages |
| Composer surface / border | `#FFFFFF` / `#E9EAED` [M] | `#222327` / `#34363C` [E] | Bottom composer |
| Selected pill | `#E8EEFF` fill, accent text [E] | `#2A3557` fill, accent text [E] | Think / Search on |
| Grouped background | `#F5F6F8` [E] | `#0F0F11` [E] | Settings page behind white groups |
| Group surface | `#FFFFFF` [E] | `#1E1F23` [E] | Settings groups, dialogs, bottom sheets |
| Divider | `#EDEEF0` [E] | `#2C2E33` [E] | Hairlines inside groups |
| Destructive | `#F2453D` [E] | `#FF6B63` [E] | Log out, Delete |

Launcher and splash background are white [R]. Dynamic colour is not used.

### Type

System sans-serif (Roboto); the base APK bundles no font [R]. Sizes at font scale 1.0:

| Role | Size / line height | Weight | Source |
|---|---|---|---|
| Top bar title | 16 sp / 22 sp | Medium | [M] (~14-16 sp) |
| Message body (user and assistant) | 15 sp / 24 sp | Regular | [M] (~14 sp at the promo scale) |
| Markdown H1 / H2 / H3 | 20 / 18 / 16 sp | Bold | [E] |
| Composer hint and input | 15 sp | Regular | [M] (13-14 sp) |
| Pill label (Think, Search) | 13 sp | Medium | [M] |
| Thinking header ("Thought for 52 seconds") | 13 sp | Regular | [M] |
| Drawer row | 15 sp | Regular | [E] |
| Drawer group header (Today, 7 Days) | 12 sp | Medium, secondary colour | [E] |
| Settings row / section header | 15 sp / 12 sp | Regular / Medium | [E] |
| Welcome title | 24 sp | Bold | [E] |

### Spacing, size and shape

| Element | Value | Source |
|---|---|---|
| Horizontal page margin | 16 dp | [M] |
| Top bar height | 56 dp, icons 24 dp in 48 dp touch targets | [M] icon placement, [E] height |
| User bubble | radius 18 dp (12 dp measured on an older build), padding 12 x 10 dp, max 80 % width, right margin 16 dp | [M] / [E] |
| Assistant message | no bubble, full width | [M] |
| Composer | radius 22 dp, 16 dp bottom and side margin, two rows (input, then pills and actions), 1 dp border, soft shadow | [M] |
| Pills | 32 dp high, radius 16 dp, 6 dp gap, 16 dp icon | [M] (27 dp measured; 32 dp keeps the touch target) |
| Composer round buttons | 32 dp circles in 48 dp targets: plus (attach), microphone / send / stop | [M] |
| Attachment tray | three equal tiles (Camera, Photo, File), radius 14 dp, 64 dp minimum height | [M] |
| Drawer | 86 % of width, max 320 dp; selected chat is a light pill with a "..." button | [E] |
| Settings groups | radius 12 dp, 16 dp outside margin, 52 dp rows, 12 dp between groups | [E] |
| Dialogs | radius 20 dp, title, radio list, full-width blue "Confirm" | [M] owner screenshots, [E] sizes |

### Icons

Line icons with round caps, 1.8 px stroke on a 24 px grid, drawn in [../../app/src/main/kotlin/app/dsandroid/ui/AppIcon.kt](../../app/src/main/kotlin/app/dsandroid/ui/AppIcon.kt):
menu (two lines, the lower one shorter), new chat (circle with plus), attach (circle with plus), think (atom-like
spark), search (globe), microphone, send (up arrow in a filled accent circle), stop (filled square), copy (two sheets),
regenerate (circular arrow), share (box with arrow), more ("..."), camera, photo, file, chevron. The official whale logo
is **not** recreated; our original chat-bubble-and-spark mark takes its place.

## Screens

Screen IDs match `e2e/reference/driver.py`, our capture tests and the release E2E journey, so captures can be paired.

### intro: first-run notice [R copy]
Bottom sheet over the launch screen: "Welcome to DeepSeek", two rows ("Official Free App: Experience the latest DeepSeek
models with cross-device history sync."; "DeepSeek can make mistakes: Content is AI-generated..."), checkbox text
"I confirm that I have read and agree to DeepSeek's Terms of Use and Privacy Policy", button "Agree".
**Ours:** not reproduced as a gate. The welcome screen carries the same "DeepSeek can make mistakes" notice and a link to
DeepSeek's Terms of Use and Privacy Policy; "Official Free App" is never claimed.

### login: sign-in [R copy]
White page, logo and wordmark at the top, field "Email / +86 Phone number", then either "Send code" (code login) or a
"Password" field ("Log in with password" / "Continue with Password"), primary full-width "Log in", outlined
"Continue with Google", consent checkbox, "Forgot password?". Validation copy: "Please enter your email address or
+86 phone number.", "Password must be between 8 and 50 characters."
**Ours (WelcomeScreen):** same order and button styles: mark and "DeepSeek Android" wordmark, "Welcome", then
**Sign in with browser** (primary, the default route), **Continue with Google** (browser; Google refuses sign-in inside
embedded browsers), **Log in with phone or email** (DeepSeek's own sign-in page inside the app), and **Paste session**.
No chat, drawer or composer exists until a session is validated with DeepSeek (`GET /api/v0/users/current`).
Tags: `welcome-screen`, `welcome-browser`, `welcome-google`, `welcome-phone-email`, `welcome-paste`.

### login-password, forgot-password, login-google
Variants of the sign-in page. **Ours:** the in-app page is DeepSeek's own web sign-in (password, code and reset live
there); Google always goes through the phone's browser.

### chat-empty: new chat [M]
Top bar: menu icon left ("Open sidebar" [R]), title centred ("New chat" [R]; the model mode "Instant" / "Expert" [R]
appears as a picker), new-chat icon right ("New Chat" [R]). Centre: logo above a greeting. Bottom: composer with hint
"Type a message or hold to speak" [R] ("Type a message" [R] without voice), pills **Think** [R] and **Search** [R],
right side plus (attach, "Attach files" [R]) and microphone; the microphone becomes send when there is text and stop
while a reply streams. The resources also hold "AI-generated response, for reference only." [R]; where it is shown is to
be confirmed by the capture.
**Ours:** same layout; greeting "How can I help you?"; an **Agent** pill joins Think and Search (agent extension,
off by default) and the top bar's right side keeps the new-chat icon. Tags: `account-composer`, `agent-toggle`.

### think-on, search-on [M]
Selected pill: light-blue fill, accent icon and label; unselected: outlined with a hairline border. Search with an
attached file is refused: "Please remove the file before searching" [R]. **Ours:** same; Search asks to confirm before
sending (the query leaves the device).

### attach-sheet [M]
Bottom sheet with three tiles: Camera, Photo, File (labelled "Document" on older builds). **Ours:** Camera, Photo, File.

### model-picker [R]
"Instant: Instant responses for daily conversations" and "Expert: For complex problems, busy at peak times".
**Ours:** not available on the web route (the app does not choose DeepSeek's server model); Think selects reasoning.

### chat-populated, thinking, thinking-expanded [M]
User message: right-aligned light-blue bubble. Assistant: plain text on the page with Markdown (bold headings, tables,
code cards with "Copy" and a language label, inline maths). Thinking: grey "Thinking" while streaming, then
"Thought for N seconds" with a chevron; expanded text is grey, smaller, with a thin vertical line on its left. Search:
"Searching the web", then "Read N web pages" with numbered citations [R]. Below the last reply: copy, regenerate,
like, dislike and share icons [R labels: Copy, Regenerate, Like, Dislike, Share].
**Ours:** same; agent tool steps appear inside the same grey timeline ("Created report.pdf"), files as cards with
Open, Save and Share (agent extension).

### message-menu [R]
Long-press menu: Copy, Edit (user messages), Regenerate, Select text, Share, Report. **Ours:** Copy, Edit, Regenerate,
Select text, Share, Export PDF (no Report: there is no reporting channel for an unofficial client).

### share, share-image [R]
Share opens a turn picker ("%d turns selected"), then "Create Image" (a branded card with "AI-generated, for reference
only") or "Copy Link" (a server-side shared link). **Ours:** Share sends the conversation as text or Markdown, or
exports a PDF; there are no server-side shared links in the web contract we use, so "Copy Link" is not offered.

### drawer, drawer-item-menu [R copy, E layout]
Search field "Search chat content..." [R], "New chat" [R], chats grouped "Pinned", "Today", "Yesterday", "7 Days",
"30 Days" then months [R]; empty state "No chat yet" / "Your chat with DeepSeek will be displayed here." [R]. Long-press:
Rename ("Rename Chat" dialog), Pin / Unpin, Delete ("Once deleted, this chat can't be recovered") [R]. Bottom: avatar,
account name and a "..." button that opens Settings.
**Ours:** same groups, search, rename and delete (pinning is local); bottom row shows the account label and opens
Settings. Extra rows below the list: **Files**, **Plugins & tools**, **Skills** (agent extensions).

### settings, settings-more [R copy, E layout]
Title "Settings" [R]. Groups on a light-grey page [R section names]: **Profile** (Account settings, Data controls),
**App** (Language, Appearance, Font size, Personalization), **Audio** (Main language), **About** (Check for updates,
Service agreement), then Help & Feedback / Contact us, **Log out** (red), footer "AI-generated, for reference only.
Use legally." [R].
**Ours:** same groups and order; **Advanced** holds the API-key client; **Agent** (Plugins & tools, Skills) is an added
group; About states the unofficial status; "Check for updates" opens the project's releases page.

### settings-appearance, settings-language, settings-font-size [R]
Appearance dialog: System, Light, Dark with "Confirm". Language: System and the available languages. Font size: a
preview ("Preview font size", "You can adjust the font size by dragging the slider below.") and a slider.
**Ours:** same three dialogs; Language offers System and English for the account screens.

### profile: account settings [R]
Email or phone number, Google / Apple / WeChat binding rows, "Log out of all devices", "Delete account".
**Ours:** the signed-in account's label and masked email or phone as DeepSeek returns them, the session route
(web sign-in or API key), **Log out** of this app, and a link to manage the account on chat.deepseek.com. No in-app
"Log out of all devices" or "Delete account": those are server actions this client does not perform.

### data-controls [R]
"Improve the model for everyone" switch, "Delete all chats" ("Once deleted, all chats will be cleared and cannot be
recovered"). **Ours:** "Delete all chats" (deletes them on DeepSeek and on this phone after confirmation) and a note that
local history stays on this phone; no training switch (this client cannot control training).

### personalization [R]
Response settings and read-aloud options. **Ours:** collapse thinking automatically, text size.

## Intentional differences (all screens)

1. **Identity:** app name "DeepSeek Android", "Unofficial. Not affiliated with or endorsed by DeepSeek." in About and on
   the welcome screen; client headers name DeepSeek-Android-Unofficial in API mode. Never "Official".
2. **Art:** original mark and icons; no whale, no wordmark artwork, no copied animation.
3. **Sign-in:** browser route first; Google never inside an embedded browser; Paste session as a fallback.
4. **Agent extensions:** Agent pill, tool steps, file cards, Files panel, Plugins & tools, Skills, artifacts panel and
   editor. They appear only when used or from the drawer, so the default chat matches the reference.
5. **Not offered:** voice calls, model mode picker on the web route, server-side share links, account deletion,
   report, WeChat / Apple binding, training switch, like / dislike (there is no feedback channel for an unofficial
   client).
6. **Contrast:** the accent is `#3D63F5` (white text on the official `#426EFE` is about 4.3:1) and secondary text is
   `#6E7178` (the measured `#909398` is about 3.2:1 on white), so text keeps 4.5:1. The dark palette is ours until the
   reference capture measures the official one.

## Comparing captures

Our capture tests (`app/src/androidTest/.../OfficialScreenshotsTest.kt`) and the release E2E journey save screens with
the same IDs and viewport. Pair `light-chat-empty.png` (official) with ours, mask only the documented differences (mark,
wordmark, Agent pill, dynamic text), and measure regions (top bar, composer, bubble, drawer rows) instead of whole-image
pixel equality. Tolerances come from repeated captures of the same build, never from loosening thresholds until a build
passes.
