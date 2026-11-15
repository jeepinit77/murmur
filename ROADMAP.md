# Roadmap

Planned work for Murmur. Items move to the README's feature list once they ship.

Status: **Done** (on a branch, not released), **In progress**, **Planned**, **Idea** (not decided).

## In progress

### Smart web import — In progress (`smart-web-import` branch)
- Site handlers for common news sites
- Reading tables aloud, with optional on-device summaries (play build)
- Markdown and HTML file import
- Remaining: test on common news sites, then merge to `main`

## Sharing

### Ask before resuming a saved page — Done (`smart-web-import` branch)
When a shared web page is already in the library, ask what to do instead of always resuming the saved copy. Starting partway through an article you forgot you'd started can be confusing.
- A popup with two buttons and a checkbox:
  - **Resume**: continue the saved copy from where you left off
  - **Import again**: fetch the page again, replace the saved copy, and start from the beginning
  - **Don't ask again** (checkbox): remember the button chosen and stop showing the popup (changeable in Settings)
- Setting: **When sharing a saved page**: Ask (default), Resume, Import again
- Share → speak normally runs without opening the app, so the popup needs its own small dialog activity shown over the app you shared from.

### Quick Settings tile to read the clipboard — Planned
A **Read clipboard** tile in Quick Settings that reads whatever text you last copied, from any app.
- Main use: WhatsApp and other apps that have Copy but no Share for messages. Copy the text, pull down Quick Settings, tap the tile.
- Android only lets the app in front read the clipboard, so the tile opens a brief invisible screen that reads it, then starts reading the same way a share does (saved to the library, playback notification, no need to open the app).
- If the clipboard holds a link, import the page instead of reading the address aloud.
- If the clipboard is empty or isn't text, show a short message ("Nothing to read on the clipboard").
- The tile has to be added once from the Quick Settings edit screen. Android 13+ can offer to add it from inside the app, for example from Settings.

### WhatsApp messages — Researched, needs a decision
Make it easy to listen to a WhatsApp message or conversation.

**What WhatsApp allows on Android (as of November 2026; recheck before building):**
- **No Share button for text messages.** Long-pressing a message offers Copy and Forward, and Forward only sends to other WhatsApp chats. WhatsApp tested a bar for sending messages to other apps in a 2024 beta, but there's no sign it reached the regular app.
- **Selecting part of a message** is in beta on Android (2025) and already available on iPhone. Where it's available, Murmur's existing "Read aloud" option in the text selection menu should appear.
- **Export chat** (⋮ → More → Export chat) sends a whole conversation through the share sheet: a .txt file without media, or a .zip containing the .txt and attachments with media. Each line looks like `11/15/26, 9:41 AM - Name: message`.

**Options, easiest first:**
1. **Read clipboard.** The flow would be: long-press a message, tap Copy, then start Murmur reading with one tap. Possible ways to start it:
   - A **Quick Settings tile** ("Read clipboard") that opens a brief invisible screen, which is allowed to read the clipboard, then starts reading like a share does. Works from any app, not just WhatsApp.
   - A **launcher shortcut** (long-press the Murmur icon) that does the same.
   - In the Library, offer copied text the same way copied links are offered now.
2. **Pixel screen selection.** In Recents, choose Select, highlight the message and choose Share → Murmur. This already works where Pixel shows Share for selected text (reports on the latest Pixel updates are mixed). Nothing to build. Could be mentioned in a help or tips screen.
3. **Exported chats.** Exporting without media already reaches Murmur as a text file today, but it's read with every date, time and name. Planned improvements:
   - Recognize the WhatsApp format and read it as "Name: message", leaving out repeated timestamps
   - Skip lines like "<Media omitted>", "This message was deleted" and the end-to-end encryption notice
   - Start a new chapter for each day
   - Accept the .zip export (with media) and read the chat text inside it
4. **Reading incoming messages from notifications** (notification access). Now built as **Notification reading** (see below), off by default.

**Suggested:** option 1 (Quick Settings tile, now its own Planned item above) and option 3 (exported chats).

### Notification reading — Built, needs testing on a phone
Settings → Notifications → Notification reading. Off by default; needs notification access.
- Apps appear in a list after they send a notification. Only checked apps are read; for other apps, only the package name is stored.
- Murmur posts its own notification for each chat (or each notification from other apps) with Play and Dismiss. Messages that arrive close together are grouped ("Mom · 3 messages"), and the full message text is read, not just what's visible in the notification.
- Playing pauses what's being read, reads the notification, then resumes at the same sentence. Notification text is kept in memory only and never saved to the library.
- Auto-play (off by default), set per app. Each app has its own conditions: only with headphones or Bluetooth, respect Do Not Disturb, and a Screen choice (on, off, any time).
- Murmur's notifications use normal importance, so they appear with other notifications rather than under Silent, but without their own sound or vibration.
- A one-time card on the home screen suggests it once the library has items.
- To check: whether Android allows auto-play to start the playback service from the background (there's a fallback that reads in place), and how WhatsApp's notifications look in practice.

## Reading

### URLs in text — Planned
Choose how web addresses written in the text are read.
- Setting: **Read web addresses**, with options:
  - **Say "link to [site]"** (default): reads `https://www.example.com/a/b` as "link to example dot com"
  - **Skip**: leaves addresses out completely
- Today every address is already read as "link to [site]" ([SpeechText.kt](core/text/src/main/kotlin/app/murmur/text/SpeechText.kt)), with no setting. This item adds the setting and the Skip option.
- Link text in web articles (the words you'd tap on) is read as normal text either way.

### Change table and code reading for the current article — Planned
Switch how the open article handles tables while listening, without changing the default in Settings.
- **Tables**: Skip, Read row by row, Summarize with AI (when the phone supports it)
- **Code blocks**: Skip, Say "A code block appears here.", Read line by line (same options as Settings)
- In the reader's More options menu, or as a control on the player card. Only shown when the article has tables or code blocks.
- The choice is saved for that article. Settings stays the default for new imports.
- Today these modes are applied when the article is imported, and the original page isn't kept. Changing them means re-extracting the text, so this needs one of:
  - keeping the original HTML for each document, so it can be re-extracted offline, or
  - keeping every version of each table (skipped, rows, summary) in the document and choosing at playback time
- Playback should stay at the same place in the article after switching.

## Playback

### Speed slider from the notification — Planned
Tapping the Speed button in the media notification opens a speed slider instead of moving to the next speed.
- Today the button steps through 1.0×, 1.2×, 1.5×, 1.8× and 2.0×, then back to 1.0× ([MediaButtons.kt](app/src/main/java/app/murmur/playback/MediaButtons.kt)). Reaching a slower speed means going all the way around.
- The slider would match the reader's: 0.5–3×, in small steps, showing the current speed.
- A notification can't hold a slider itself, so the button opens a small dialog over whatever app is in front, like the "Already in your library" popup.
- Things to check:
  - Media3 notification buttons send a command to the playback service rather than opening a screen, and Android limits starting screens from the background. The button may need a custom notification layout or a different way to open the dialog.
  - On the lock screen the dialog would need the phone unlocked first. Keeping the current stepping there may be simpler.

## Importing

### Word documents (.docx) — Planned
Open and share Word documents. Today .docx files can't be picked from the Add menu or shared to Murmur.
- Read the text, headings (as chapters), lists and tables from the document. A .docx file is a zip of XML files, so this can use the existing XML and zip handling without adding a large library like Apache POI.
- Add the Word file type to the file picker and to the share and "Open with" targets.
- Older .doc files (before Word 2007) use a different binary format and aren't included.

## Library

### Mark as done, with a swipe — Planned
Mark items in the library as done by swiping them, or from the item's menu.
- **Swipe** an item in the library list to mark it as done. Swiping a done item marks it as not done.
- **Item menu:** add "Mark as done" (or "Mark as not done") to the item's More options menu, for anyone who doesn't use the swipe.
- An **Undo** button appears briefly in a message at the bottom of the screen after swiping.
- A done item shows a check mark, and its progress bar is hidden.
- Optionally, mark an item as done automatically when it's read to the end.
- To decide:
  - Whether done items stay in the list, move to the bottom, or are hidden behind a "Show done" filter
  - Whether swiping the other way does something too, such as delete (with Undo)

## Appearance

### Theme settings — Planned
Choose how the app looks instead of always following the system.
- Today the app follows the system's light or dark mode, and on Android 12+ always uses colors from the wallpaper. On older phones it uses Murmur's own blue and gold. There are no settings for any of this ([Theme.kt](app/src/main/java/app/murmur/ui/Theme.kt)).
- A new **Appearance** section in Settings:
  - **Theme**: System default, Light, Dark
  - **Use wallpaper colors** (Android 12+, on by default): turn off to use Murmur's own colors
  - **Black background in dark theme**: pure black instead of dark gray, which saves battery on OLED screens
- To decide:
  - Reader-only color themes, such as sepia, separate from the app theme
  - Whether text size and font (listed under Ideas) belong here too

## Search

Search has three scopes, built in this order.

### 1. Find in article — Done
Search inside the document that's open in the reader.
- Search icon in the reader's top bar opens a find bar
- Every match is highlighted; the current match is shown in a stronger color
- Previous and next arrows (and the keyboard's Search key) move between matches, with a "3/12" count
- Ignores case, accents and curly quotes ("cafe" finds "Café")

### 2. Library search, titles only — Done
Search titles, authors and site names across the library.
- Search icon on the Library screen, with a "Titles only" filter
- Instant, since it only uses the library index

### 3. Library search, full text — Done
Search the text of every saved document.
- "Full text" filter (the default), alongside "Titles only"
- Each result shows a snippet of the first match and the number of matches
- Opening a result opens the reader with the find bar at the first match

### Search follow-ups — Planned
- **Search index**: full-text search reads each document from disk, with a memory cache. Move to a SQLite FTS index if large libraries (hundreds of books) make it slow.
- **Read from match**: a "Read from here" action on the current match.
- **Filter results by type** (web, PDF, EPUB, text).

## Ideas

Not planned yet. These need a decision before they move up.

### Library
- Sort (date added, title, progress, length)
- Select several items to delete
- Tags or folders
- Export and import the library for backup or moving to a new phone

### Reader
- Text size and font settings
- Bookmarks, with a list per document
- Copy or share a sentence or paragraph

### Playback
- Per-document speed and voice
- Pronunciation dictionary
- Continue to the next item in the queue automatically

### Other
- RSS feeds that save new items to the library
- Home screen widget
- Browse the library from Android Auto
- Export an article as an audio file
