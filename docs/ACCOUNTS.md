# Accounts (v1.0.0)

**Settings → Accounts** lists every DeepSeek account and API-key profile kept on the phone. Each row shows
its label, sign-in type, balance (where a documented endpoint returns one) and when that figure was last
read. One tap on a row makes it the account in use.

## The rule: manual switching only

**The app never switches, rotates or falls back between accounts.** Not on a quota error, not on a rate
limit, not on a failed request, not when another account has more balance, and not in the background.
Only a tap on an account in Settings → Accounts changes the account in use.

- On a quota or rate error the app shows the plain-English error and a **Switch account** button. The
  button only opens the list; nothing changes until the user taps an account there.
- Removing (or logging out of) the account in use leaves **no** account selected. The app does not pick
  another one.
- Adding an account never switches away from the account in use. The one exception is signing in while
  no account is in use at all (the first sign-in, or after the last one was removed): that account
  becomes the one in use, because there is nothing to switch away from.
- A session that expires stays selected and is marked signed out; signing in again restores it.

How it is enforced:

| Where | What |
| --- | --- |
| `core/accounts/AccountRegistry.kt` | The account list and the active id. `switchTo` (called only from a tap) is the only way an existing account becomes active; `remove` never selects another; `add` activates only a DeepSeek sign-in when no account is active. |
| `core/accounts/AccountRegistry.kt` (`AccountFailurePolicy`) | Every failed request goes through `handle`, which keeps the active account, marks it signed out on a sign-in failure, and offers "Switch account" for quota and rate errors. |
| `AccountRegistryTest.aQuotaErrorNeverChangesTheActiveAccount` | Unit test: for every failure kind, from each starting account, the active account is unchanged; a quota error still offers the list. |
| `AccountRegistryTest.randomFailureSequencesNeverSelectAnotherAccount` | Unit test: 1,000 random failures in a row never select another account. |
| `AccountManagerTest` (emulator) | A mocked rate error (account mode) and a mocked HTTP 402 quota error (API-key client) show "Switch account" and leave the account in use unchanged; adding two API-key profiles does not switch; a tap does. |

## Kinds of account

| Kind | How it is added | Credential | Balance |
| --- | --- | --- | --- |
| DeepSeek account (web sign-in) | **Add DeepSeek account** opens DeepSeek's sign-in page signed out (cookies and site storage are cleared first, so the account in use is not captured again). | The web session token, encrypted. | **Not available.** DeepSeek's web chat has no documented balance or quota endpoint, and the session data has no such field. |
| API-key profile | **Add API-key profile** with a label and a DeepSeek API key. The key saved under Settings → API-key mode is also listed, as the profile "API key". | The API key, encrypted. | `GET https://api.deepseek.com/user/balance` (DeepSeek API docs: `is_available`, `balance_infos[]` with currency and string amounts), read with that profile's key only when the user taps **Refresh** or **Refresh balances**. |

Each row shows the result of the last read and when it happened ("Last read today 14:05"), or why it
failed. Balances are never read automatically.

## What each account keeps

Every account has its own encrypted credential (Android Keystore AES-GCM master key through
EncryptedSharedPreferences, as before) and its own chat history:

| | First DeepSeek account (and installs from before v0.7.0) | Other DeepSeek accounts | API-key profiles |
| --- | --- | --- | --- |
| Credential | `deepseek_web_session`, key `session` | `deepseek_web_session`, key `session_<id>` | `dsandroid_settings`, account list (shared with the API-key client's own picker) |
| Chats | `deepseek-web-chats.json` | `deepseek-web-chats-<id>.json` | `conversations-<id>/` (the Settings key and older chats: `conversations/`) |
| Agent files | `workspace/account/<chat>` | `workspace/accounts/<id>/<chat>` | the API-key client's workspace |
| Artifact versions | `workbench/default/` | `workbench/<id>/` | — |

Switching to a DeepSeek account clears the in-app browser's DeepSeek cookies and site storage (they
belong to the previous sign-in) and then uses only that account's own token. Switching to an API-key
profile writes that profile's key into the API settings, as the API-key client's own account picker does,
and the chat screen becomes the API-key client for that profile. **Switch account** in its header opens
the same list.

Logging out of, or removing, a DeepSeek account deletes its session, chats, agent files and artifact
versions from the phone (the DeepSeek account itself is not changed). Removing an API-key profile deletes
the saved key.

## Signing in with Google (v0.7.1)

Google does not allow its sign-in inside an app's embedded browser, so a DeepSeek account that uses Google
signs in through the phone's browser. On the welcome screen tap **Continue with Google** (or **Sign in with browser**):

1. **Copy the helper.** In Chrome, bookmark any page, edit that bookmark, name it `dsandroid` and paste the
   helper as its address.
2. **Open DeepSeek in your browser** and sign in with Google.
3. In the address bar type `dsandroid` and tap the bookmark. Tap **Open in DeepSeek Android** at the bottom of
   the page, then **Sign in** in the app.

If the browser does not switch back, tap **Copy session instead** on the page and use **Paste session** in the
app. Either way the session is checked and stored like a normal sign-in (encrypted, per account).

Safety rules: the helper runs only on chat.deepseek.com and reads only `localStorage.userToken`; it sends
nothing over the network. The hand-off is an intent addressed to this package, carried as an extra rather than
in the link. The app takes it only within 30 minutes of starting a browser sign-in from the app and asks before
signing in; anything else is ignored with a notice.

## The sign-in gate (v1.0.0)

Signed out, the app shows only its welcome screen: **Sign in with browser** (the default route), **Continue with
Google** (the same browser route), **Log in with phone or email** (DeepSeek's own sign-in page inside the app) and
**Paste session**. There is no drawer, chat history, composer, file panel, tool or editor until DeepSeek confirms a
session.

- **Every session is checked first.** A session from the sign-in page, the browser hand-off or Paste session unlocks the
  app only after `GET /api/v0/users/current` answers successfully. A rejected session (HTTP 401 or codes
  40001-40003) is refused and not offered again; an unreachable or unreadable answer leaves the app locked with a
  notice; an account that still owes its date of birth stays locked until it confirms it on chat.deepseek.com.
- **Stored sessions are re-checked** in the background on start and when you switch to the account. An explicit
  rejection locks the app on the welcome screen; being offline does not (the next request decides).
- **Two layers.** The shell composes nothing but the welcome screen, the sign-in page and the account list while
  signed out, and the view model refuses chat, file, tool and code actions without a session.
- **Shared content and shortcuts wait.** Text or files shared into the app and launcher shortcuts are held (the
  welcome screen says so) and used after sign-in.
- **API keys are separate and explicit.** Under **More sign-in options**, **Use a DeepSeek API key** checks the key
  with `GET /user/balance` before saving it and opening the API-key client. The app never switches between the web
  account and an API key by itself.
- Logging out or an expired session returns to the welcome screen.

## Not included

- No balance or quota figure for web sign-ins (no documented endpoint).
- No sync of the account list between devices, no server-side sign-out of removed accounts.
- The list holds at most 20 accounts.
