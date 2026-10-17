# Feature matrix

What the big assistant apps (ChatGPT, Claude, Kimi) do on Android, and when DeepSeek Android (Unofficial) gets each one.
The goal: the official DeepSeek app only chats; this app lets DeepSeek *do* things — call tools mid-conversation and show the results inside the chat.

Legend: ✓ = the app has it · — = it doesn't · **Release** = the version of this app that ships it.
v0.1.0 = already released. Everything else is planned; a row moves to "done" only when it is in a tagged release.

## Core: the agent loop

| # | Feature | ChatGPT | Claude | Kimi | Release |
|---|---|---|---|---|---|
| 1 | Real function calling: the model calls tools, reads results, keeps going (multi-step) | ✓ | ✓ | ✓ | v0.2.0 |
| 2 | Tool-call cards inline in the chat (what ran, inputs, status, output; collapsible) | ✓ | ✓ | ✓ | v0.2.0 |
| 3 | Chat / Agent mode toggle (plain chat vs tool-using agent) | ✓ | ✓ | ✓ | v0.2.0 |
| 4 | Per-action confirm for anything that leaves the app or changes data | ✓ | ✓ | ✓ | v0.2.0 |
| 5 | Stop, retry, edit-and-resend, regenerate | ✓ | ✓ | ✓ | v0.2.0 |
| 6 | Thinking display (live reasoning, collapsible, time taken) | ✓ | ✓ | ✓ | v0.2.0 |

## Research and the web

| # | Feature | ChatGPT | Claude | Kimi | Release |
|---|---|---|---|---|---|
| 7 | Web search with inline numbered citations and a sources sheet | ✓ | ✓ | ✓ | v0.2.0 |
| 8 | Read a URL (fetch a page) with citation | ✓ | ✓ | ✓ | v0.2.0 |
| 9 | Deep research mode: plan → many searches → cited report | ✓ | ✓ | ✓ | v0.2.0 |
| 10 | Research report export (Markdown / HTML / PDF) | ✓ | ✓ | ✓ | v0.2.0 |

## Code, artifacts and outputs

| # | Feature | ChatGPT | Claude | Kimi | Release |
|---|---|---|---|---|---|
| 11 | Code interpreter in a sandbox, output shown in chat (stdout, tables) | ✓ | ✓ | ✓ | v0.2.0 |
| 12 | Charts from data, rendered in the chat | ✓ | ✓ | ✓ | v0.2.0 |
| 13 | Artifacts / canvas panel: live HTML, SVG, Mermaid, Markdown preview | ✓ | ✓ | ✓ | v0.2.0 |
| 14 | Edit an artifact and re-run it; version history | ✓ | ✓ | — | v0.2.0 |
| 15 | Downloadable generated files (.md .html .csv .txt .svg .json) via save/share | ✓ | ✓ | ✓ | v0.2.0 |
| 16 | Syntax-highlighted code blocks with copy | ✓ | ✓ | ✓ | v0.1.0 |
| 17 | Office files out: DOCX, XLSX, PPTX (slides) generation | ✓ | ✓ | ✓ | v0.3.0 |
| 18 | PDF generation | ✓ | ✓ | ✓ | v0.3.0 |

## Inputs

| # | Feature | ChatGPT | Claude | Kimi | Release |
|---|---|---|---|---|---|
| 19 | File input: PDF, Word, Excel, PowerPoint, text | ✓ | ✓ | ✓ | v0.1.0 |
| 20 | Image input (text extraction on-device; vision when the model supports it) | ✓ | ✓ | ✓ | v0.2.0 |
| 21 | Many files at once (batch analysis, ~50 files) | ✓ | ✓ | ✓ | v0.3.0 |
| 22 | Camera input (snap a photo or document) | ✓ | ✓ | ✓ | v0.3.0 |
| 23 | Share into the app from any other app (share target) | ✓ | ✓ | ✓ | v0.3.0 |
| 24 | Voice typing (press and hold) | ✓ | ✓ | ✓ | v0.1.0 |

## Memory, projects, personalisation

| # | Feature | ChatGPT | Claude | Kimi | Release |
|---|---|---|---|---|---|
| 25 | Memory: saved facts the user can see, edit and switch off | ✓ | ✓ | ✓ | v0.2.0 |
| 26 | Custom instructions (global) and per-chat system prompt | ✓ | ✓ | ✓ | v0.2.0 |
| 27 | Projects: folders with shared files and instructions | ✓ | ✓ | — | v0.2.0 |
| 28 | Temporary (no-history) chat | ✓ | ✓ | — | v0.2.0 |
| 29 | Saved assistants / personas | ✓ | ✓ | ✓ | v0.3.0 |
| 30 | Response styles (concise, explanatory, formal) | ✓ | ✓ | — | v0.3.0 |

## Tools and integrations

| # | Feature | ChatGPT | Claude | Kimi | Release |
|---|---|---|---|---|---|
| 31 | Custom tools (MCP-style): add remote HTTPS tool servers, each call confirmed | ✓ | ✓ | — | v0.2.0 |
| 32 | Built-in tool settings: turn each tool on/off | ✓ | ✓ | ✓ | v0.2.0 |
| 33 | Scheduled tasks / reminders run by the assistant | ✓ | — | — | v0.3.0 |

## Voice

| # | Feature | ChatGPT | Claude | Kimi | Release |
|---|---|---|---|---|---|
| 34 | Voice mode: hands-free spoken conversation | ✓ | ✓ | ✓ | v0.3.0 |
| 35 | Read aloud with sentence highlighting | ✓ | ✓ | ✓ | v0.3.0 |

## Phone actions (every action behind a confirm)

| # | Feature | ChatGPT | Claude | Kimi | Release |
|---|---|---|---|---|---|
| 36 | Draft SMS / email, add calendar events, set alarms, open maps | ✓ | — | — | v0.3.0 |
| 37 | Screen awareness (opt-in screenshot of the current screen) | ✓ | — | — | v0.3.0 |
| 38 | Floating assistant bubble / quick launch | ✓ | — | — | v0.3.0 |
| 39 | Home-screen widget | ✓ | ✓ | — | v0.3.0 |

## Conversations and sharing

| # | Feature | ChatGPT | Claude | Kimi | Release |
|---|---|---|---|---|---|
| 40 | Saved history, search, rename, delete | ✓ | ✓ | ✓ | v0.1.0 |
| 41 | Pin chats | ✓ | ✓ | ✓ | v0.2.0 |
| 42 | Share / export a conversation (Markdown, HTML, PDF, share sheet) | ✓ | ✓ | ✓ | v0.2.0 |

## Account and app

| # | Feature | ChatGPT | Claude | Kimi | Release |
|---|---|---|---|---|---|
| 43 | Sign in with your own account (system browser) or API key | ✓ | ✓ | ✓ | v0.1.0 |
| 44 | Model / mode picker (fast vs reasoning) | ✓ | ✓ | ✓ | v0.1.0 |
| 45 | Several accounts listed, switched manually only (never automatic) | ✓ | ✓ | — | v0.3.0 |
| 46 | Language toggle; Bangla interface | ✓ | ✓ | ✓ | v0.2.0 |
| 47 | Text size and display controls; dark/light | ✓ | ✓ | ✓ | v0.2.0 |

## Agent extras

| # | Feature | ChatGPT | Claude | Kimi | Release |
|---|---|---|---|---|---|
| 48 | Browser agent: the model opens and reads pages in an in-app browser for you, every click/submit confirmed | ✓ | ✓ | ✓ | v0.3.0 |
| 49 | Connectors to your own accounts (e.g. cloud drive, mail), read-only first, signed in by you | ✓ | ✓ | — | v0.3.0 |
| 50 | Multi-file website / slide-deck builder in the artifacts panel, export as a zip | ✓ | ✓ | ✓ | v0.3.0 |
| 51 | Study mode: step-by-step tutoring instead of straight answers | ✓ | ✓ | — | v0.3.0 |
| 52 | Suggested follow-up questions under an answer | ✓ | — | ✓ | v0.3.0 |
| 53 | Branch a conversation from any message into a new chat | ✓ | ✓ | — | v0.3.0 |
| 54 | Library: every generated file, artifact and image from all chats in one place | ✓ | ✓ | ✓ | v0.3.0 |
| 55 | Parallel sub-agents for big tasks: one plan, several agents at once, progress shown per agent | ✓ | — | ✓ | v0.3.0 |
| 56 | Memory upkeep: suggested memory updates drawn from past chats, each approved by the user | ✓ | ✓ | ✓ | v0.3.0 |
| 57 | Share the camera or screen into voice mode (vision when the model supports it) | ✓ | — | — | v0.3.0 |

## Not planned

- Image and video generation: DeepSeek has no image model. Drawings come out as SVG/HTML artifacts instead (row 13).
- Automatic account rotation: never (terms-of-service and ban risk).
- Group chats with other people: needs a shared server, and this app is client-only.

## Totals

| Release | Features |
|---|---|
| v0.1.0 (released) | 6 |
| v0.2.0 | 26 |
| v0.3.0 | 25 |
| **All** | **57** |
