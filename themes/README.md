# OpenBridge themes

A theme is a JSON file (`.json`) or a ZIP package (`.obp`) imported from
**Settings → General → Themes**.

## Files inside an `.obp`

```
theme.json      required, the theme itself
font.ttf        optional, referenced by typography.fontFile
```

`manifest.json` may be used instead of `theme.json`.

## Schema (v2)

```json
{
  "id": "material3",
  "name": "Material 3",
  "author": "OpenBridge",
  "version": "1.0",
  "shapes": {
    "cardRadiusDp": 20,
    "buttonRadiusDp": 20,
    "badgeRadiusDp": 8
  },
  "components": {
    "buttonStyle": "filled",
    "switchStyle": "pill",
    "navBarStyle": "pill",
    "cardBorderWidthDp": 0,
    "cardElevationDp": 1
  },
  "typography": {
    "font": "sans",
    "fontFile": null,
    "uppercaseLabels": false,
    "letterSpacingSp": 0.0
  },
  "dark": {
    "background": "#101014",
    "surface": "#1C1B1F",
    "surfaceElevated": "#2B2930",
    "border": "#49454F",
    "accent": "#D0BCFF",
    "textPrimary": "#E6E1E5",
    "textSecondary": "#CAC4D0"
  },
  "light": {
    "background": "#FEF7FF",
    "surface": "#FFFFFF",
    "surfaceElevated": "#F3EDF7",
    "border": "#CAC4D0",
    "accent": "#6750A4",
    "textPrimary": "#1D1B20",
    "textSecondary": "#49454F"
  }
}
```

### Values

| Field | Values |
| --- | --- |
| `shapes.cardRadiusDp` | `0`–`28` |
| `shapes.buttonRadiusDp` | `0`–`50` (`50` = pill) |
| `shapes.badgeRadiusDp` | `0`–`28` |
| `components.buttonStyle` | `filled`, `outlined`, `tonal` |
| `components.switchStyle` | `flat`, `pill`, `ios` |
| `components.navBarStyle` | `top_line`, `pill`, `floating`, `minimal` |
| `components.cardBorderWidthDp` | `0`–`3` |
| `components.cardElevationDp` | `0`–`8` |
| `typography.font` | `mono`, `sans`, `serif`, `custom` |
| `typography.uppercaseLabels` | `true` / `false` |
| `typography.letterSpacingSp` | `0.0`–`4.0` |

## Custom fonts

Set `"font": "custom"` and `"fontFile": "font.ttf"` (or `.otf`), then put that
file next to `theme.json` inside the `.obp`. On import the app copies both into
`filesDir/themes/<id>/`. If the font is missing or cannot be read, the app falls
back to the platform font instead of crashing.

## Old schema

Files with the v1 fields (`controlsStyle`, `cornerRadiusDp`, `borderWidthDp`,
`font`) still import: they are converted into the granular schema automatically.
