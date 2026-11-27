package app.openbridge.theme

import android.content.Context
import java.io.File

const val THEMES_DIR = "themes"
const val LEGACY_THEMES_DIR = "plugins/themes"

/** `filesDir/themes` — every imported theme lives in its own folder here. */
fun themesRoot(context: Context): File = File(context.filesDir, THEMES_DIR)

/** `filesDir/themes/<id>` — holds `theme.json` and, for `.obp`, the bundled font. */
fun themeDir(context: Context, id: String): File = File(themesRoot(context), id)
