An app store that verifies every install and tells you how far it can vouch for it

Features:
- Three built-in sources: this project's own apps, F-Droid, and Google Play
- Every download is signature-checked before the system installer ever sees it
- A security tier per app, saying plainly who could push you a malicious update
- F-Droid listings restricted to builds F-Droid's server independently reproduced
- Play installs pinned to a publisher source stamp, which survives Play re-signing
- Anonymous Play access — no Google account, no login
- Apps targeting an outdated Android API are refused, not just hidden
- Material You design with dynamic theming

New sources cannot be added. Nothing above could be promised for a repo you added
yourself, so the store does not offer the option.
