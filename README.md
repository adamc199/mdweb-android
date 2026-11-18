# MD Web for Android

A reader for the web of Markdown pages, for Android phones, by Adam Curry.

It follows the open format Dave Winer published with [BonBon](https://github.com/scripting/bonBon) ([the charter](https://github.com/scripting/bonBon/blob/main/docs/charter.md)), so the pages and sites that work in BonBon work here. It is a separate app written from that charter, not BonBon's code.

**[Download the APK](https://github.com/adamc199/mdweb-android/releases/latest)** (Android 10 or newer). Open the downloaded file on your phone to install it; Android asks once to allow installs from your browser or file app.

## What it does

- Reads Markdown pages by address. It asks servers for `text/markdown` and shows Markdown pages; HTML inside a page is thrown away and no page code runs. Links to HTML pages open in the phone's browser.
- Front matter (`title`, `author`, `pubDate`, ...) and `{{page.title}}`-style values; pictures (PNG, JPEG, GIF, WebP) judged by their content; emoji shortcodes like `:smile:`.
- Sites: the ‹ › arrows step to the page before and after (`urlPrev` / `urlNext`, else the site's `.well-known/toc.opml`); **📅** shows a site's calendar (the `calendarMonth` / `calendarDay` lines of its table of contents); one word in the address box searches the site (`bonbon.json` `urlSearch`); pages update by themselves on sites that offer it (`urlUpdateSocket`); a link to a site's HTML version opens its Markdown copy (`urlMirrorOf`).
- **River of news:** an orange **RSS** button shows when a page or its site has a feed (front matter, `bonbon.json` `urlFeed`, feed links, or an `rss.xml` in the site's folders); one tap subscribes. The **📰 River** tab lists the newest items from all your feeds, each with **Copy link** (for reposting), **Open** and **Unsubscribe**. RSS 2.0, RSS 1.0 and Atom; read every 15 minutes while the app is open.
- Opens Markdown files from the phone (Downloads, Files, chat apps) and links shared to it (long-press a link in any browser → Share → MD Web).
- Five reading styles (Reader, Newsprint, Classic, Mono, Book) plus your own stylesheet by address; tabs; bookmarks with editing; history; find in page; view source.

Places to start: [dave.writersweb.org](https://dave.writersweb.org/), [Scripting News](https://dave.writersweb.org/scripting/), [adam.curry.com/md](https://adam.curry.com/md/).

## Building

Plain Java, one activity, no Gradle. Needs JDK 17+ and the Android SDK (platform 35, build-tools 35).

```
MDWEB_KEYSTORE=/path/to/your.jks MDWEB_KEYPASS=your-password ./build.sh
```

It makes `MDWeb.apk`. If the keystore does not exist yet, `build.sh` creates one with that password. Keep the same keystore for every update, or Android refuses to install an update over the old version. No keys or passwords are in this repository.

## Credits

- [markdown-it](https://github.com/markdown-it/markdown-it) 14.1.0 and [markdown-it-emoji](https://github.com/markdown-it/markdown-it-emoji) 2.0.2 (both MIT licensed), in `assets/`.
- The Markdown-web format: Dave Winer's BonBon charter.
