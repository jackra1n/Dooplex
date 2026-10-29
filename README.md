# Dooplex

A fast and modern Plex client made for Zidoo media players. It is developed and tested on a Zidoo
Z9X 8K, and may also run on other Android TV devices (Android 11 or newer).

## Why Dooplex

Zidoo's own media browser is sluggish and wants your files over SMB or NFS, even when a Plex server
already has them organized. Plex clients fix the library but not the playback: their players
transcode or drop quality, or their interface is a chore with a remote. Dooplex takes the best of
both:

- **Zidoo's native player.** Dooplex browses Plex and hands the file to Zidoo's own player, so
  Dolby Vision, HDR and lossless audio passthrough work just like they do on the box.
- **A fast client with a good interface.** Built for the remote and tuned to stay smooth on the box:
  posters load quickly, scrolling keeps up, and everything is a few clicks away.

![Dooplex Home with the demo library](docs/screenshots/home.png)

![Dooplex Movies with the demo library](docs/screenshots/movies.png)

## Installation

1. Download the APK from the [latest release](https://github.com/jackra1n/Dooplex/releases/latest).
2. Install it on the Zidoo, either way works:
   - Open `http://<zidoo-ip>:18888` in a browser on the same network and upload the APK. The page
     may report an error even though the install worked.
   - Copy it to a USB drive and open it in the Zidoo's file manager.
3. Open Dooplex, scan the QR code or enter the code at [plex.tv/link](https://plex.tv/link), then
   pick your server.

Playback goes to Zidoo's own player automatically; there is nothing to set up.

## Building

```sh
mise run build       # app/build/outputs/apk/release/dooplex.apk
mise run upload      # build and install on a Zidoo (ZIDOO_URL in mise.local.toml)
mise run screenshot  # docs/screenshots/home.png from the demo library on the TV emulator
```

## Thanks

Dooplex is heavily inspired by [plx-native](https://github.com/GLinnik21/plx-native), both in its
focus on performance and in its interface. Thanks for the inspiration and its amazing work.

## License

[GPL-3.0](LICENSE)
