# Phase 1 UI inventory

The owner-supplied screenshots are visual references only. UI code, line icons and the chat mark are original. No reference image or provider branding is packaged.

| Reference pattern | Implementation |
| --- | --- |
| White chat, centered greeting, mark, menu and new-chat actions | `ChatScreen`, `DsAndroidApp`, original `AppIcon` and mark vector |
| Think/Search pills, attachment chooser, microphone hold | `ChatScreen`, `VoiceDictation`; camera/photo/document use system activities |
| Searchable drawer, dated history, selected conversation, more menu, profile footer | `ConversationDrawer`; Today, 30 Days, then localized month/year; rename and delete dialogs |
| Pale Settings background with white groups | `SettingsScreen`; Profile, Account and Data management |
| App language chooser | Centered English/Follow system radio dialog with Confirm uses AppCompat per-app locales; English resources are the available translation |
| Appearance radio choices and font size | Centered System/Light/Dark radio dialog and font preview dialog with Confirm; persisted scale applied through Compose density |
| Personalization screen/toggle | Secondary Personalization view with Back and helper text; collapse thinking is persisted and used by the assistant timeline |
| Audio language and Voice | Centered Use app language/English radio dialog; Voice bottom sheet shows a device-service card, Confirm and honest spoken-reply limitations |
| About, service, help, logout footer | App version, affiliation notice, provider/service explanation, local help and local account logout |
| Thinking, tool steps, details, answer and artifact cards | `AssistantTurn`; ordered steps, human action labels, raw arguments/results only in Details |
| Connection loss and restart recovery | Calm retry countdown, Retry now, Resume task and sign-in action, connected to ViewModel state |

## Review conditions

Source review covers resource references, state wiring, permission timing and settings persistence. Device rendering, speech-service behavior, locale recreation, dark mode and accessibility still require an authorized Android build/device session. No screenshot comparison is claimed as a rendered-app test.

Account sign-in is a configurable standard PKCE adapter. Its controls stay disabled until a provider-supported authorize URL, exchange URL and client ID are supplied. The UI does not promise that an undocumented DeepSeek account integration works. Data management is explicitly local; it does not invent remote deletion, quota, cloud sync or logout APIs.
