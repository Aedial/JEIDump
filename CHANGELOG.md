# Changelog

All notable changes to this project will be documented in this file.

The format is based on Keep a Changelog and this project adheres to Semantic Versioning.

- Keep a Changelog: https://keepachangelog.com/en/1.1.0/
- Semantic Versioning: https://semver.org/spec/v2.0.0.html


## [1.2.1] - 2025-05-07
### Added
- Add a gradle task to automatically generate the locale manifest from the bundled lang files.

### Changed
- Make background splitting disabled by default as it can easily cause out-of-memory crashes on larger modpacks, and update the tooltip to warn about this. It can still be enabled for users who want to save disk space and have the resources to handle it (or disabled enough JEI categories for it to not crash).


## [1.2.0] - 2025-05-06
### Added
- Expose Botania mana costs and Thermal Expansion RF bars through reusable virtual ingredient types, with one shared ingredient id per resource type, recipe-specific tooltip text staying on the exported hotspot, and correct produce-vs-consume indexing for the exported ingredient pages. Any integration can expose arbitrary virtual ingredients via integration/, which the frontend will handle generically.
- Allow tooltip query zones to be defined from JSON files under `config/jeidump/integrations/<modid>/`, so non-slot JEI hints can be configured for any loaded mod without adding new Java integrations.

### Fixed
- Make recipe grids size columns from each category's actual image width, which prevents wide recipe cards from bleeding into adjacent columns while keeping ultra-wide screens from over-packing narrow recipes.


## [1.1.0] - 2025-05-05
### Added
- Add a "last updated" timestamp to the footer, showing when the dump was generated.
- Add I18n support for the website and a language selector to switch between available translations. The command feedback messages are now internationalized as well.
- Deduplicate shared recipe backgrounds per category in exported dumps, and layer the shared background below per-recipe foreground PNGs on the website when it reduces the total image size.
- Export arbitrary JEI ingredient types, using JEI's registered ingredient helpers and renderers instead of hardcoded item and fluid handling.

### Fixed
- Fix recipe hotspots reusing the first-seen tooltip for shared ingredient ids, so different stack amounts of the same fluid now show their own correct value.


## [1.0.1] - 2025-05-04
### Fixed
- Fix github link in footer.


## [1.0.0] - 2025-05-04
### Added
- Initial release of JEI Dump.
  - Exports JEI recipes into a static site with HTML, CSS, JS, and PNG assets.
  - Supports vanilla and all JEI plugins.
  - Usage instructions included in the README.