// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import android.database.Cursor

internal const val HOME_CARD_OPACITY_KEY = "home_card_opacity"
internal const val DEFAULT_HOME_CARD_OPACITY = 55

internal fun SharedPreferences.homeCardOpacity(): Int =
    runCatching { getInt(HOME_CARD_OPACITY_KEY, DEFAULT_HOME_CARD_OPACITY) }
        .getOrDefault(DEFAULT_HOME_CARD_OPACITY).coerceIn(0, 100)

internal fun SharedPreferences.Editor.putHomeCardOpacity(opacity: Int): SharedPreferences.Editor =
    putInt(HOME_CARD_OPACITY_KEY, opacity.coerceIn(0, 100))

internal fun Cursor.homeCardOpacity(): Int = getColumnIndex(HOME_CARD_OPACITY_KEY).takeIf { it >= 0 }
    ?.let { getInt(it).coerceIn(0, 100) } ?: DEFAULT_HOME_CARD_OPACITY
