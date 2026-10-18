# Read-only OAuth connectors

Settings → Connectors includes a generic connector for APIs with a search GET
endpoint and an item-read GET endpoint. It sends requests directly to the configured
provider. There is no relay, client secret or dependency on a bundled provider account.

Use a provider that supports a **public native client**, authorization-code OAuth2,
S256 PKCE and an IPv4 loopback redirect with a dynamic port. The callback path is
`/oauth/callback`; the app binds only to `127.0.0.1` and checks random OAuth state.
Sign-in opens the system browser so credentials stay on the provider's page.

1. Register your own public native client with the provider. Enable only the API
   permissions needed for search and read. Providers that require a confidential
   client secret or a fixed callback port are not supported by this adapter.
2. Enter the provider's documented HTTPS authorization and token endpoints, your
   public client ID, and its read-only OAuth scopes. No credentials belong in URLs.
3. Enter the HTTPS search endpoint and its query parameter (usually `q`). A search
   adds one URL-encoded query value. Static query strings are not supported.
4. Enter the read endpoint with `{id}` as one complete path segment. For example,
   `https://api.example.org/files/{id}`. The search and read endpoints must have
   the same scheme, host and port. Item IDs are URL-encoded; responses are returned
   as bounded JSON/text for the model to interpret.
5. Continue in the browser and approve the provider's read-only scopes. In Agent
   mode, request a search; the app then shows the exact initial destination and
   asks to allow search/read for that connector **for this chat**.

The generic transport implements both GET operations; it is not a provider stub.
Compatibility depends on the provider's public-client contract. APIs requiring
POST-based searches, custom request bodies, cookies, binary downloads or additional
headers need a separate adapter. Do not point a GET endpoint at an API that changes
data on GET. The app cannot infer a custom provider's semantics from its URL.

Tokens are encrypted using the existing Keystore-backed store. Neither DeepSeek
credentials nor chat history are sent to the connector; it receives only the
approved chat's search queries or item IDs. The model receives returned text, so
review which account and scopes you connect. Returned data is untrusted, and may
contain misleading instructions. Subsequent calls in the same chat share the
read-only grant; another chat or branch needs its own grant. Grants are not
restored after process death. Disconnect removes the local token and grants;
revoke the application's access on the provider's website for server-side revocation.

Access-token refresh is not automatic. Use **Sign in again** after expiry. Requests
refuse redirects and proxies, check public DNS addresses, and cap responses at
1 MiB with a 30-second timeout; at most 40,000 response characters reach the model.
No provider-backed sign-in was exercised locally; CI and a compatible provider's
device flow must be validated before release.
