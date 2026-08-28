# Contributing

### General Rules

Instructions
- If you are implementing a feature, please let me know (by opening an issue) so you don't waste time
  on a feature I don't want to include
- Include photos to demonstrate your code works
  - Not needed if your code is short (<10 lines) or common sense is proof enough

Translations
- Translatons should be made through weblate (https://hosted.weblate.org/projects/modern-apps/)
  - Translations may not even be included in a PR that adds new strings due to the way weblate is set up.
- AI is not allowed for translations, and neither is any translation software like Google Translate

Genral Policies
- AI code is allowed generally if you have demonstrated that your code works

Styling rules
- All icons should have a function declared for them in Icons.kt
  - Do not use Icon() or painterResource anywhere

Store metadata
- Every app module needs a `metadata_data/<module-key>.md`, where the module key is the Gradle
  path with `/` replaced by `-` (so `games/voxels` is `games-voxels.md`)
- The format is strict, and `./gradlew checkMetadata` enforces it:
  - Line 1 is the short description, at most 80 characters - Play and F-Droid cap it there
  - Line 2 is blank
  - Line 3 is exactly `Features:`
  - Then one or more `- ` bullets, and nothing else
  - Then a blank line, then a single line saying what the app needs the network for, exactly
    one of `100% offline`, `Requires internet`,
    `Internet required only for initial asset downloads`, or
    `Internet only used for: <feature(s)>`
- Use `Internet only used for:` only when the app still mostly works without a connection
- Claims here must match the code - the connectivity line in particular is checked against the
  INTERNET permission and the hosts the app actually contacts, including ones merged in from
  library modules
