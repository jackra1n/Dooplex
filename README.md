# Dooplex

A fast and modern Plex client made for Zidoo media players. It browses your Plex library and plays
through Zidoo's own player, so every format and audio passthrough the box supports keeps working.
It may also run on other Android TV devices (Android 11 or newer).

## Building

```sh
mise run build    # app/build/outputs/apk/release/dooplex-movies.apk
mise run upload   # build and install on a Zidoo (set ZIDOO_URL to yours)
```

## Thanks

Thanks to [plx-native](https://github.com/GLinnik21/plx-native) for the inspiration and its amazing
work.
