# Google Play listing

Everything the Play Console asks for, kept with the code so it can be reviewed
and regenerated.

```
listings/ar, listings/en-US   title (30), short description (80), full description (4000)
graphics/icon-512.png         app icon, 512 x 512
graphics/feature-1024x500.png feature graphic
graphics/phone/<locale>/      four phone screenshots, 1080 x 1920 (9:16)
whatsnew/                     release notes per locale
```

The screenshots come from `tools/store/shot-android.html` (`?scene=home|results|detail|privacy&lang=ar|en`),
rendered at 1290 x 2293 and scaled to 1080 x 1920. Play rejects screenshots
longer than twice their width, which is why these are 9:16 and not the taller
iPhone shape.

`tools/android/play-listing.py` puts all of this on the store page in every
language (texts, icon, feature graphic, screenshots, and release notes on a
track's current release); `tools/android/play-upload.py` sends a bundle to a
track, which `.github/workflows/android.yml` does on every Android change.

Privacy policy: https://soor.3li.info/privacy.html
Package: `com.eworldq8.soor`, the same identifier as the iOS app.
