# Changelog

All notable changes to xApocalypse are documented here.

## [1.5.1] - 2026-07-21

### Added

- Added optional PlaceholderAPI integration through a bundled internal expansion. PlaceholderAPI remains a soft dependency, so xApocalypse still loads normally when it is absent.
- Added `%xapocalypse_bloodmoon_days_left%` for the whole number of in-game days until the next natural Blood Moon. It returns `Tonight` while a Blood Moon is active or due that day.
- Added `%xapocalypse_zombie_guts_duration%` for the active player's remaining Zombie Guts duration in whole seconds, returning `0` when inactive.
- Added `%xapocalypse_current_scent%` for the active player's live scent value rounded to the nearest whole number, returning `0` when no player context or scent is available.
- Added a configurable vanilla sound when a natural or forced Blood Moon begins. It is played to online players in enabled and lobby worlds.
- Added a configurable proximity sound after every successful Mutant spawn, including guaranteed Blood Moon, periodic Blood Moon, and admin-command spawns.
- Added sound controls for enablement, Bukkit sound name, volume, and pitch. Mutant sounds also support a configurable hearing radius.
- Added a dedicated PlaceholderAPI documentation page and updated the README and configuration reference for the new features.

### Configuration

- Added `bloodmoon.start-sound.enabled` (default `true`).
- Added `bloodmoon.start-sound.name` (default `ENTITY_WITHER_SPAWN`).
- Added `bloodmoon.start-sound.volume` (default `1.0`).
- Added `bloodmoon.start-sound.pitch` (default `0.7`).
- Added `mythicmobs.integration.spawn-sound.enabled` (default `true`).
- Added `mythicmobs.integration.spawn-sound.name` (default `ENTITY_ENDER_DRAGON_GROWL`).
- Added `mythicmobs.integration.spawn-sound.volume` (default `1.0`).
- Added `mythicmobs.integration.spawn-sound.pitch` (default `0.8`).
- Added `mythicmobs.integration.spawn-sound.radius` (default `48.0` blocks).
- Existing installations receive these missing keys through the automatic configuration synchronizer. Administrators do not need to delete or regenerate `config.yml`; existing settings and comments are preserved.
- Invalid configured sound names are skipped safely and reported in the server log instead of preventing the plugin from loading.

### Changed

- Added PlaceholderAPI 2.11.6 as a compile-time-only dependency and declared PlaceholderAPI in `plugin.yml` as a soft dependency.
- Blood Moon day calculations and Zombie Guts remaining-time access are now exposed as read-only manager APIs for integrations.
- Mutant spawn audio is emitted from the shared successful-spawn path, ensuring consistent behavior across every supported spawn source.
- Updated all current release badges, help text, installation examples, artifact names, and resource headers to version 1.5.1.

### Fixed

- Improved Minecraft attribute resolution across Paper 1.21 API variants. xApocalypse now tries modern registry keys, legacy namespaced keys, and reflective legacy attribute fields before reporting a missing attribute.

### Compatibility and upgrade notes

- Requires Java 21 and a server compatible with Bukkit/Paper API 1.21, matching previous releases.
- GriefPrevention, MythicMobs, and PlaceholderAPI integrations are all optional.
- PlaceholderAPI values requiring player state return `0` when parsed without a player context.
- Sound triggers use vanilla Bukkit sound names only; custom resource-pack sound keys are not supported in this release.
