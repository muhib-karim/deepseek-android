# Research notes (Oct 2026)

What we looked at before building, what we take from each, and under what terms.
Rule: code is only reused from permissively licensed projects (MIT/Apache-2.0) with attribution;
everything else is ideas only. Nothing in this repo is copied from GPL/AGPL or unlicensed projects.

| Project | Licence | What it is | What we learn from it |
|---|---|---|---|
| [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) | MIT | DeepSeek's own open-source agent harness (TypeScript, plugins) | The reference for the protocol: browser sign-in with PKCE and a loopback `/oauth/callback`, the `x-dsh-auth-token` header, honest per-client identity headers, `login_source`, sign-out, and a guard layer in front of tools. We re-implement the ideas in Kotlin and credit it in NOTICE. |
| Official DeepSeek app (`com.deepseek.chat`, Play Store screenshots) | proprietary | DeepSeek's own Android chat app | Look and flow only: white canvas, centered title, drawer, rounded composer with Think / Search chips, + attach sheet (Camera / Photo / Document), light-blue user bubbles, assistant text without a bubble, "Thinking" then "Thought for N seconds" with a grey step timeline. No assets or logo used. |
| [ylh440104/dsh-android](https://github.com/ylh440104/dsh-android) | none | Unofficial Chinese-language Android port of the harness | Ideas only (no licence, so no code): tool cards with "Details", collapsible thinking cards, building Office files by writing OOXML and zipping. Things we deliberately do differently: confirm gate before risky tools, internet-only permissions, honest client identity, one account, resumable runs after network loss. |
| [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub) | AGPL-3.0 | Popular multi-provider Android LLM chat app (Compose) | Ideas only: provider/model management, reasoning display, assistant presets. |
| [Vali-98/ChatterUI](https://github.com/Vali-98/ChatterUI) | AGPL-3.0 | Mobile LLM front end (local + API) | Ideas only: streaming UX on low-end phones, message editing and regeneration. |
| [Taewan-P/gpt_mobile](https://github.com/Taewan-P/gpt_mobile) | GPL-3.0 | Android chat app, several LLMs at once | Ideas only: Material 3 chat layout, per-provider settings screens. |
| [chatboxai/chatbox](https://github.com/chatboxai/chatbox) | GPL-3.0 | Cross-platform AI client | Ideas only: artifact previews, file attachments as context. |
| [aallam/openai-kotlin](https://github.com/aallam/openai-kotlin) | MIT | Kotlin OpenAI client | Usable, but we keep our own small OkHttp client so DeepSeek fields (`reasoning_content`) and account headers stay under our control. |
| [mikepenz/multiplatform-markdown-renderer](https://github.com/mikepenz/multiplatform-markdown-renderer) | Apache-2.0 | Compose Markdown renderer | Candidate replacement for our own renderer if tables/LaTeX need more; v0.1 ships a tested in-house parser. |
| [jeziellago/compose-markdown](https://github.com/jeziellago/compose-markdown) | MIT | Markwon-based Compose Markdown | Same as above. |
| [lllucccian/Deekseep](https://github.com/lllucccian/Deekseep) | GPL-3.0 | LSPosed module patching the official app | Not used. Hooking the official app is out of scope. |
| wzystal/AiChatHub, wized2/D-Harness | none | Small DeepSeek-style demos | Not used (no licence). |

## Decisions that follow
- Sign-in follows the harness's documented flow and identifies as `DeepSeek-Android-Unofficial`; no reuse of the
  official clients' identity, no private web-chat endpoints, no account pooling or rotation.
- API key and any OpenAI-compatible endpoint are first-class, so the app is useful even if account sign-in is not
  available to third-party clients.
- The richer-than-official experience (visible tool steps, real files, previews, uploads, search) is the point of the app.
