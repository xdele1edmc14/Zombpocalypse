# 🎨 Localization (messages.yml)

Every piece of player-facing text in xApocalypse lives in **`messages.yml`** and is fully editable and translatable. The message system supports **both** modern MiniMessage tags and classic legacy color codes, automatically detecting which you've used.

---

## Formatting: MiniMessage + Legacy

You can write messages in either style — even mix them across different keys:

| Style | Examples |
|-------|----------|
| **MiniMessage** | `<gradient:red:blue>Text</gradient>`, `<rainbow>Text</rainbow>`, `<#FF5555>Hex</#FF5555>`, `<bold>`, `<hover>`, `<click>` |
| **Legacy** | `&a`, `&b`, `&l`, `&n`, `&4`, `§c`, … |

A message is parsed as **MiniMessage** if it contains `<` together with `gradient`, `rainbow`, `hover`, `click`, or `#`; otherwise it's treated as legacy `&`/`§` codes. If MiniMessage parsing ever fails, it gracefully falls back to legacy parsing.

---

## Placeholders

Arguments are substituted with `{0}`, `{1}`, etc. For example:

```yaml
player-not-found: "&cPlayer not found: {0}"
reload-error: "&cError reloading: {0}"
```

The Blood Moon boss-bar title is configured in **`config.yml`** (not `messages.yml`) and uses the `%time%` placeholder:

```yaml
bloodmoon:
  bossbar-title: "&4&l☠ BLOOD MOON ☠ &cRemaining: %time%"
```

---

## What You Can Customize

The file is organized into sections. Highlights:

| Section | Controls |
|---------|----------|
| `prefix`, `no-permission`, `reload-success`, `reload-error`, `player-not-found` | General/system messages |
| `commands.help.*` | The `/xa help` screen lines |
| `commands.item.*`, `commands.unknown` | Command feedback |
| `bloodmoon.*` | Blood Moon broadcasts (natural rise + force-start) |
| `immunity.*` | Zombie Guts item name, lore, and immunity messages |

---

## Reloading

After editing `messages.yml`, apply changes live with:

```
/xa reload
```

No restart needed. The message cache is cleared on reload.

---

## Update-Safe Defaults

The plugin registers the bundled `messages.yml` (inside the jar) as a **fallback default source**. This means:

- If a plugin update adds a new message key that your on-disk `messages.yml` doesn't have yet, it still resolves to the bundled default instead of showing `Missing message: <path>`.
- You **don't** need to delete your `messages.yml` after updating the plugin — your customizations are preserved and only genuinely missing keys fall back to defaults.

> If you ever *do* see `&cMissing message: some.path` in-game, that key is absent from **both** your file and the jar — usually a typo in a key you renamed.

<p align="center"><i>← Back to <a href="Home.md">Wiki Home</a></i></p>
