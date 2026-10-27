# Patches to SillyTavern

Jiuguan ships the SillyTavern release unmodified except for the dated changes in this folder. `tools/prepare.sh`
applies every `*.patch` (in name order, `patch -p1`) to the exported release tree, and the bundle version gets a
`+p<hash>` suffix so installed apps re-extract when the patches change. Bug fixes are meant to be sent upstream.

| Patch | Modification date | Change | Reason / evidence |
|---|---|---|---|
| `0001-always-release-swipe-lock.patch` | 2026-10-18 | Wraps `swipe()` so the call that took the lock always releases it, and tolerates a message disappearing before swipe completion. | Without it, swipe → swipe back → `/regenerate` can leave `swipeState` locked and every later send silently returns until reload; reproduced by the e2e suite, especially in mobile WebViews. |
| `0002-link-jiuguan-corresponding-source.patch` | 2026-10-18 | Adds a visible Jiuguan source link to the LAN-accessible welcome panel. | Gives every user interacting with the modified network UI a direct AGPL corresponding-source offer. |
