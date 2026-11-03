<h1 align="center">Chatrix.One for Android</h1>

<p align="center">A friendly, private XMPP messenger for <a href="https://chatrix.one">chatrix.one</a> and any other Jabber/XMPP server.</p>

<p align="center">
  <a href="https://github.com/MrEddX/Chatrix.One-android/releases">Download the latest release</a>
</p>

Chatrix.One is a fork of [Conversations](https://codeberg.org/iNPUTmice/Conversations), the
well-known XMPP client by Daniel Gultsch. It keeps everything that makes Conversations reliable
(end-to-end encryption, calls, file sharing, group chats, low battery use) and adds its own look
and faster ways to work with messages.

It works with every standards-compliant XMPP server; `chatrix.one` is only the default suggested
when you create a new account.

## What is different from Conversations

**Message gestures**

* Swipe left on a message to quote it, swipe right to react with your quick emoji
  (👍 by default, a second swipe removes it), double-tap to copy the text.
* Every gesture is configurable in *Settings → Interface → Message gestures*: quote, react, copy,
  edit your own message or do nothing.
* The quick reaction emoji can be any emoji, chosen from the full emoji picker.

**Message menu**

* Long-press opens a compact, rounded menu with a reaction row
  (👍 👎 ❤️ 😂 😮 😢 🙏) on top, plus buttons for the full emoji picker and for typing an emoji
  with the keyboard. All original menu entries are still there.

**Look and feel**

* Chatrix.One design: green accent colours, IBM Plex Sans font, dark app icon, rounded
  Material 3 dialogs everywhere (including settings).
* Chat backgrounds: a subtle glow, plain, or your own picture, globally or per chat
  (*More options → Chat background*).
* Voice messages are shown as waveforms.

**Notifications**

* Own connection status icon (a ring with a dot when online, a crossed ring when offline) that
  is clearly different from the new-message icon.
* *Settings → Notifications → Connection status notification* explains the permanent notification
  and opens the Android page to hide it with one tap. Messages keep arriving and you still get a
  warning when the connection fails.

**Privacy and support**

* Crash reports are never sent automatically. After a crash the app asks, and you can send the
  report via XMPP or by email to the Chatrix.One team, or switch the question off.
* Optional "new version available" notice for installs from the website, see below.

**Installation**

* Own package name (`one.chatrix.app`) and signing key, so Chatrix.One installs next to
  Conversations. To move your chats, use *Settings → Backup* in Conversations and restore the
  backup in Chatrix.One.

## Privacy

Chatrix.One contains no trackers, no analytics and no advertising, and uses no Google services.

The only network request the app makes on its own, apart from your XMPP server, is the optional
update check: once a day it downloads the public file `https://chatrix.eu/download/version.json`
and, if a newer version exists, shows a notification that opens the
[releases page](https://github.com/MrEddX/Chatrix.One-android/releases). Nothing is downloaded or
installed automatically and no identifiers are sent. The check is skipped when the app was
installed from F-Droid or another app store, when Tor is enabled, and when you switch it off in
*Settings → Privacy → Check for updates*. Builds made with `-PchatrixUpdateCheck=false` do not
contain it at all.

## Reporting problems

Please report problems with Chatrix.One here on GitHub or to `admin@chatrix.one`
(email or XMPP), **not** to the Conversations developers.

## Building

Requirements: JDK 25 and the Android SDK.

```sh
./gradlew assembleConversationsFreeRelease
```

Signed release builds read their keystore from a `signing.properties` file in the project root,
which must never be committed:

```
keystore=/path/to/keystore.jks
keystore.password=…
keystore.alias=…
```

Without it, Gradle produces unsigned APKs in `build/outputs/apk/`.

## Versions

Versions follow the Conversations release they are based on, plus a Chatrix.One suffix:
`2.21.0-cx1-1` is the first Chatrix.One release based on Conversations 2.21.0, `2.21.0-cx1-2` the
next one on the same base. New Conversations releases are merged regularly.

## Credits and license

Chatrix.One is based on [Conversations](https://conversations.im) by Daniel Gultsch and its many
contributors; most of the code is theirs. For general questions about how the app works
(encryption, push, battery, servers) the
[Conversations README and FAQ](https://codeberg.org/iNPUTmice/Conversations/src/branch/master/README.md)
applies to Chatrix.One as well.

Like Conversations, Chatrix.One is free software licensed under the
[GNU General Public License v3](LICENSE).
