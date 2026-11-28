package app.openbridge.theme

import android.content.Context
import java.io.File

const val THEMES_DIR = "themes"
const val LEGACY_THEMES_DIR = "plugins/themes"

fun themesRoot(context: Context): File = File(context.filesDir, THEMES_DIR)

fun themeDir(context: Context, id: String): File = File(themesRoot(context), id)
