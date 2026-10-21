# TASKS — v1.2.0 "Agent-first" (versionCode 12)

Branch feature/v1.2.0 from main ff9fae2 (v1.1.0). Frozen spec lives outside the repo; this is the progress checklist.
Status: TODO / WIP / DONE (commit) / CI (run id green).

| # | Item | Status |
|---|------|--------|
| 1 | Agent ON by default on both routes (+ `agent_mode_set_by_user` migration), Agent chip + hint in composer | DONE (f8e2ffb) |
| 2 | API key as a front door (Welcome primary button, "API mode" wording, Settings > Accounts "Add API key") | DONE (2810ac7) |
| 3 | Collapsible tool-step cards (icon, human title, status, args/result) + file cards with Open/Share/Save, both routes | DONE (0357a7b) |
| 4 | Model routing on the API route (deepseek-chat with tools, reasoner only without) + ModelSelector tests | DONE (4dd92f4, f8e2ffb) |
| 5 | Step counter "Step k of up to N", Settings > Agent max steps (default 8, 1-20), Stop keeps steps, tool errors to model | DONE (4dd92f4, f8e2ffb) |
| 6 | First-run "What Agent can do" card with 4 example prompts filling the composer | DONE (dc commit "Empty chat") |
| 7 | File input feeds the model on both routes; PDF text on the API route | DONE: existing PdfBox extraction on API route verified in code; no new code |
| 8 | No regressions; tests asserting agent OFF updated | WIP: tests updated, CI pending |
| 9 | Tests: ModelSelector, AgentLoop error/max-steps/stop, stream delta parser, instrumented API-key agent journey | DONE (written), CI pending |
| 10 | README lead + quick start + privacy + screenshots, RELEASE_NOTES_120, CHANGELOG, TOOLS.md | DONE (a1dff22); screenshots pending CI artifacts |
| 11 | versionCode 12 / versionName 1.2.0 | DONE (a1dff22) |

## CI record
Owner direction 2026-10-21: wrap up, fix only what CI needs. Draft PR #32.
