# Steady Read

Steady Read scrolls the page you're reading at a slow, steady pace, so you don't have to keep swiping. It's an Android accessibility service for people who find repeated swiping painful or difficult.

You start and stop it from a notification, by touching the screen, or — on phones with a proximity sensor — by covering the top of the phone. Touching the screen always stops it.

It's free, with no ads and no accounts.

## What it deliberately cannot do

The point of Steady Read is that its limits are **structural and checkable**, not promises. It:

- **cannot read what's on your screen** — the accessibility service declares `canRetrieveWindowContent="false"`, and the capability the system grants it is gesture dispatch alone, so screen content isn't available to it;
- **cannot use the network** — it declares no `INTERNET` permission, and strips it from any dependency that tries to merge one in;
- **only performs gestures** — its accessibility capability is gesture dispatch and nothing more (`capabilities=32`);
- **accepts no configuration from outside the app** — no component in a release build reads any value out of an incoming intent;
- **requests only** `POST_NOTIFICATIONS` (for the control notification) and `VIBRATE` (a short tap acknowledgement).

You don't have to take that on faith. `tools/declaration-ratchet.sh` reads a built release APK and checks these from the artifact itself — the declared permissions, the exported components, and the accessibility declaration. (The gesture-only capability is a value the system computes at runtime, not something stored in the APK, so the ratchet checks the declared attributes that yield it rather than the number itself.) The assertions and how to run them are in that file.

## Licence

GPL-3.0. The app's whole pitch is that its properties are verifiable; copyleft is what stops a closed fork from carrying the same pitch with none of them.

## Status

This is one person's app. At the time of writing it has been built and used by its developer, and by no one else. It's published as a record of an approach — an auto-scroll reading aid built to be checkable — not as a finished or widely-used product.
