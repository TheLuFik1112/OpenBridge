package app.openbridge.ui

import androidx.compose.runtime.compositionLocalOf
import app.openbridge.text.AppStrings
import app.openbridge.text.RuStrings

/** Строки интерфейса. Словарь живёт в [app.openbridge.text], здесь только доступ из Compose. */
val LocalStrings = compositionLocalOf<AppStrings> { RuStrings }
