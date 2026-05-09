# JEI Dump

A client-side Minecraft 1.12.2 mod that exports every JEI recipe (vanilla + every JEI plugin) into a self-contained static site (HTML + CSS + JS + PNG assets) that you can open in any browser.

## Usage

1. Drop the built jar into your `mods/` folder along with JEI 4.16+.
2. Launch the game and load any world (single-player is fine).
3. Open the JEI overlay once so the runtime is fully populated, then press `T` and run:
   ```
   /dumpjei              # writes to <gameDir>/jeidump/<UTC-timestamp>/
   /dumpjei myfolder     # writes to <gameDir>/jeidump/myfolder/
   ```
4. The game may lag while it renders (should be unplayable for most people at default speed).

When done, open `<gameDir>/jeidump/<folder>/index.html`.

## Output layout

```
index.html
assets/style.css
assets/app.js
data/manifest.json
data/locales/<locale>/index.json
data/locales/<locale>/categories/<sanitized_uid>/recipe_N.png
data/locales/<locale>/ingredients/<kind>/<dedup_id>.png
```

The frontend is dependency-free: no lunr, no React, no build step. The "fuzzy" search is a lowercase substring + subsequence scorer that handles tens of thousands of entries in the browser without trouble.

## Configurable Tooltip Zones

Configured non-slot tooltip zones can be defined for any loaded mod under `config/jeidump/integrations/<modid>/**/*.json`.

See [thermalexpansion-tooltip-zones.example.json](https://github.com/Aedial/JEIDump/blob/main/docs/thermalexpansion-tooltip-zones.example.json) for a complete example file and [configured-tooltip-zones.schema.json](https://github.com/Aedial/JEIDump/blob/main/docs/configured-tooltip-zones.schema.json) for the JSON Schema.

Each JSON file may contain either a single object, an array of objects, or an object with a `zones` array. Each zone supports:
- `wrapperClass`: optional wrapper class name. This is matched with `isInstance`, so base wrapper classes work. Either this or `categoryUid` is required to select the relevant recipes.
- `categoryUid`: optional JEI category uid. Either this or `wrapperClass` is required to select the relevant recipes.
- `x`, `y`, `width`, `height`: required rectangle coordinates in JEI recipe-local pixels. Use the texture in the relevant mod's JEI plugin as a reference for the coordinate system.

JEI Dump reads every JSON file under `integrations/<modid>/` recursively, even if that mod has no built-in Java integration. The configured rectangle is only the exported hotspot; the tooltip text itself still comes from the mod by calling the recipe wrapper at the configured sample point during the dump.

The example file includes a `$schema` entry so editors can validate and autocomplete the document while you are authoring it.

## TODO
- [x] Internationalize the command feedback messages (currently hardcoded in English).
- [x] Add a "last updated" timestamp to the footer.
- [x] Add dispatch to translated pages, so the frontend can load images in the appropriate language, if said dump is available. Add language selector to the UI to switch between them and add I18n support for the rest of the UI text.
- [ ] Add dispatch to version, so the frontend can show the recipe in different versions if multiple dumps are available.
- [x] Separate the background texture (shared by category) from the content (items, fluids, text, etc), to reduce dump size and caching overhead.
- [x] Add support for other types than items and fluids, such Gases/Essentia/Mana/etc, using JEI's generic ingredient helpers and renderers.


## Build

To build, run:
```
./gradlew build
```

The jar will be available in `build/libs/`.
