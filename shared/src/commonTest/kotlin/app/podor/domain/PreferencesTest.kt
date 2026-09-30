package app.podor.domain

import kotlin.test.*
import kotlinx.serialization.json.Json

class PreferencesTest {
    @Test
    fun legacyPreferencesReceiveSixDistinctAnimationShortcuts() {
        val restored =
            Json.decodeFromString<Preferences>("""{"language":"English"}""").withNewShortcuts()
        val expected =
            mapOf(
                ShortcutAction.AnimationTimeline to Shortcut("A", command = true, shift = true),
                ShortcutAction.PlayAnimation to Shortcut("P", alt = true),
                ShortcutAction.PreviousFrame to Shortcut("[", alt = true),
                ShortcutAction.NextFrame to Shortcut("]", alt = true),
                ShortcutAction.AddFrame to Shortcut("N", alt = true),
                ShortcutAction.OnionSkin to Shortcut("O", alt = true),
            )
        expected.forEach { (action, binding) -> assertEquals(binding, restored.shortcut(action)) }
        assertTrue(restored.valid())
        assertEquals(Language.English, restored.language)
        assertEquals(restored, restored.withNewShortcuts())
    }

    @Test
    fun legacyAltPBrushBindingMovesOnlyTheNewPlaybackShortcut() {
        val restored =
            Json.decodeFromString<Preferences>("""{"shortcuts":{"Brush":{"key":"P","alt":true}}}""")
                .withNewShortcuts()
        assertEquals(
            mapOf(
                ShortcutAction.Brush to Shortcut("P", alt = true),
                ShortcutAction.PlayAnimation to Shortcut("P", shift = true, alt = true),
            ),
            restored.shortcuts,
        )
        assertTrue(restored.valid())
        assertEquals(
            restored,
            restored.assign(ShortcutAction.Brush, Shortcut("P", alt = true)),
        )
        assertEquals(
            restored,
            Json.decodeFromString<Preferences>(Json.encodeToString(restored)).withNewShortcuts(),
        )
        assertFailsWith<IllegalArgumentException> {
            Preferences().assign(ShortcutAction.Brush, Shortcut("P", alt = true))
        }
    }

    @Test
    fun animationShortcutMigrationPreservesOlderBindingsEvenWhenFallbacksAreOccupied() {
        val old =
            Preferences(
                language = Language.English,
                shortcuts =
                    mapOf(
                        ShortcutAction.Brush to Shortcut("A", command = true, shift = true),
                        ShortcutAction.Eraser to Shortcut("P", alt = true),
                        ShortcutAction.Picker to Shortcut("[", alt = true),
                        ShortcutAction.Hand to Shortcut("]", alt = true),
                        ShortcutAction.Fit to Shortcut("N", alt = true),
                        ShortcutAction.Select to Shortcut("O", alt = true),
                        ShortcutAction.Fill to
                            Shortcut("A", command = true, shift = true, alt = true),
                        ShortcutAction.Gradient to Shortcut("P", shift = true, alt = true),
                    ),
            )
        val restored =
            Json.decodeFromString<Preferences>(Json.encodeToString(old)).withNewShortcuts()
        old.shortcuts.forEach { (action, binding) ->
            assertEquals(binding, restored.shortcut(action))
        }
        for (action in
            listOf(
                ShortcutAction.AnimationTimeline,
                ShortcutAction.PlayAnimation,
                ShortcutAction.PreviousFrame,
                ShortcutAction.NextFrame,
                ShortcutAction.AddFrame,
                ShortcutAction.OnionSkin,
            )) {
            assertNotEquals(action.default, restored.shortcut(action))
        }
        assertTrue(restored.valid())
        assertEquals(Language.English, restored.language)
        assertEquals(restored, restored.withNewShortcuts())
    }

    @Test
    fun savedCustomAnimationBindingsSurviveMigrationAndRejectReassignmentConflicts() {
        val customized =
            Preferences()
                .assign(ShortcutAction.AnimationTimeline, Shortcut("A", alt = true))
                .assign(ShortcutAction.PlayAnimation, Shortcut("P", command = true, shift = true))
                .assign(ShortcutAction.PreviousFrame, Shortcut("[", command = true, alt = true))
                .assign(ShortcutAction.NextFrame, Shortcut("]", command = true, alt = true))
                .assign(ShortcutAction.AddFrame, Shortcut("N", command = true, alt = true))
                .assign(ShortcutAction.OnionSkin, Shortcut("O", command = true, alt = true))
        val restored =
            Json.decodeFromString<Preferences>(Json.encodeToString(customized)).withNewShortcuts()
        assertEquals(customized, restored)
        assertTrue(restored.valid())
        assertFailsWith<IllegalArgumentException> {
            restored.assign(ShortcutAction.Brush, restored.shortcut(ShortcutAction.PlayAnimation))
        }
    }

    @Test
    fun deletingACustomBrushKeepsPreferencesValidAndClearsItsFavorite() {
        val brush = BrushPreset(id = "custom-1", label = "Mine", hardness = 1f, opacity = 1f, size = 12f)
        val preferences =
            Preferences(brushes = listOf(brush), favoriteBrushes = setOf("custom-1", "ink"))
        assertTrue(preferences.valid())
        val updated =
            preferences.copy(
                brushes = preferences.brushes.filterNot { it.id == "custom-1" },
                favoriteBrushes = preferences.favoriteBrushes - "custom-1",
            )
        assertTrue(updated.valid())
        assertEquals(emptyList(), updated.brushes)
        assertEquals(setOf("ink"), updated.favoriteBrushes)
        assertEquals(setOf("ink"), updated.withAvailableBrushFavorites().favoriteBrushes)
    }

    @Test fun addingSmudgePreservesAnOlderSBinding() {
        val previous = Preferences(shortcuts = mapOf(ShortcutAction.Brush to Shortcut("S")))
        val restored = previous.withNewShortcuts()
        assertTrue(restored.valid())
        assertEquals(Shortcut("S"), restored.shortcut(ShortcutAction.Brush))
        assertNotEquals(Shortcut("S"), restored.shortcut(ShortcutAction.Smudge))
        assertEquals(Shortcut("S"), Preferences().shortcut(ShortcutAction.Smudge))
    }
    @Test
    fun addingClipboardActionsKeepsOlderBindingsAndResolvesAllNewConflicts() {
        val old = Preferences(shortcuts = mapOf(
            ShortcutAction.Brush to Shortcut("C", true),
            ShortcutAction.Eraser to Shortcut("C", true, true),
            ShortcutAction.Picker to Shortcut("X", true),
            ShortcutAction.Fill to Shortcut("V", true),
            ShortcutAction.Fit to Shortcut("C", command = true, alt = true),
        ), language = Language.English)
        val restored = Json.decodeFromString<Preferences>(Json.encodeToString(old)).withNewShortcuts()
        assertTrue(restored.valid())
        old.shortcuts.forEach { (action, key) -> assertEquals(key, restored.shortcut(action)) }
        assertEquals(Language.English, restored.language)
        assertEquals(restored, restored.withNewShortcuts())
        for (action in ClipboardAction.entries) {
            assertEquals(action.shortcut.default, Preferences().withNewShortcuts().shortcut(action.shortcut))
        }
        val assigned = Preferences().assign(ShortcutAction.Copy, Shortcut("P", command = true))
        assertEquals(assigned, assigned.withNewShortcuts())
    }

    @Test
    fun addingMoveToolPreservesOlderCustomShortcuts() {
        val old = Preferences(shortcuts = mapOf(
            ShortcutAction.Brush to Shortcut("V"),
            ShortcutAction.Eraser to Shortcut("V", shift = true),
            ShortcutAction.Picker to Shortcut("V", alt = true),
        ), language = Language.English)
        val restored = Json.decodeFromString<Preferences>(Json.encodeToString(old)).withNewShortcuts()
        assertTrue(restored.valid())
        old.shortcuts.forEach { (action, key) -> assertEquals(key, restored.shortcut(action)) }
        assertEquals(Language.English, restored.language)
        assertEquals(restored, restored.withNewShortcuts())
        assertEquals(Shortcut("V"), Preferences().withNewShortcuts().shortcut(ShortcutAction.MoveLayer))
        val assigned = Preferences().assign(ShortcutAction.MoveLayer, Shortcut("T", command = true, shift = true))
        assertEquals(assigned, assigned.withNewShortcuts())
    }

    @Test
    fun shortcutConflictsAreRejectedAndCustomKeysSurviveSerialization() {
        val original = Preferences()
        assertFailsWith<IllegalArgumentException> {
            original.assign(ShortcutAction.Brush, Shortcut("E"))
        }
        val changed =
            original
                .assign(ShortcutAction.Brush, Shortcut("P", shift = true))
                .copy(language = Language.English)
        val restored = Json.decodeFromString<Preferences>(Json.encodeToString(changed))
        assertTrue(restored.valid())
        assertEquals(Shortcut("P", shift = true), restored.shortcut(ShortcutAction.Brush))
        assertEquals(Language.English, restored.language)
    }

    @Test
    fun brushPackRejectsInvalidGeometryDuplicateIdsAndUnknownPayload() {
        val pack = BrushPack("artist.ink", "Artist ink", brushes = listOf(BrushPreset.Ink))
        assertEquals(pack, BrushPack.parse(Json.encodeToString(pack).encodeToByteArray()))
        for (invalid in
            listOf(
                pack.copy(version = 99),
                pack.copy(brushes = listOf(BrushPreset.Ink.copy(aspect = 0f))),
                pack.copy(brushes = listOf(BrushPreset.Ink.copy(stabilization = -0.1f))),
                pack.copy(brushes = listOf(BrushPreset.Ink.copy(stabilization = 1.1f))),
                pack.copy(brushes = listOf(BrushPreset.Ink, BrushPreset.Ink)),
            )) {
            assertFailsWith<IllegalArgumentException> {
                BrushPack.parse(Json.encodeToString(invalid).encodeToByteArray())
            }
        }
        assertFailsWith<IllegalArgumentException> {
            BrushPack.parse(
                """{"id":"x","name":"x","brushes":[],"script":"run"}""".encodeToByteArray()
            )
        }
        assertFalse(Preferences(brushes = listOf(BrushPreset.Ink)).valid())
        val custom = BrushPreset.Ink.copy(id = "custom-1")
        assertFalse(Preferences(brushes = listOf(custom, custom)).valid())
    }

    @Test
    fun stabilizationSurvivesBrushPackAndPreferencesRoundTrips() {
        val brush =
            BrushPreset.Ink.copy(id = "custom-1", stabilization = 0.65f, followDirection = true)
        val preferences = Preferences(brushes = listOf(brush))
        assertEquals(
            preferences,
            Json.decodeFromString<Preferences>(Json.encodeToString(preferences)),
        )
        val pack = BrushPack("artist.liner", "Liner", brushes = listOf(brush))
        assertEquals(pack, BrushPack.parse(Json.encodeToString(pack).encodeToByteArray()))
        assertFalse(brush.copy(stabilization = Float.NaN).valid())
        assertFalse(brush.copy(stabilization = Float.POSITIVE_INFINITY).valid())
        val legacy =
            Json.decodeFromString<BrushPreset>(
                """{"id":"old","label":"Old brush","hardness":1.0,"opacity":1.0,"size":12.0}"""
            )
        assertFalse(legacy.followDirection)
    }

    @Test
    fun customCanvasValidatesAreaDimensionsAndMissingInput() {
        assertTrue(validCanvasSize(4096, 4096))
        assertTrue(validCanvasSize(8192, 2048))
        assertFalse(validCanvasSize(8192, 8192))
        assertFalse(validCanvasSize(0, 100))
        assertFalse(validCanvasSize(null, 100))
        assertFalse(validCanvasSize(Int.MAX_VALUE, 2))
    }

    @Test
    fun bundledBrushesHaveDistinctAndValidParameters() {
        assertEquals(24, BrushPreset.entries.size)
        assertTrue(BrushPreset.entries.all { it.valid() })
        assertEquals(24, BrushPreset.entries.map { it.id }.distinct().size)
        assertTrue(BrushPreset.entries.any { it.tip == BrushTip.Flat })
        assertTrue(BrushPreset.entries.any { it.tip == BrushTip.Leaf })
        assertTrue(BrushPreset.entries.any { it.tip == BrushTip.Comb })
        assertTrue(BrushPreset.entries.any { it.grain > 0f })
        assertTrue(BrushPreset.entries.any { it.paper > 0f })
        assertTrue(BrushPreset.entries.any { it.mix > 0f })
        assertTrue(BrushPreset.entries.any { it.stabilization > 0f })
        assertEquals(7, BrushPreset.entries.count { it.followDirection })
        assertTrue(BrushPreset.entries.any { it.opacityPressure == 1f && it.sizePressure < 1f })
    }

    @Test
    fun pressureSettingsSurviveSavingAndOlderBrushesKeepTheirResponse() {
        val legacy = BrushPack.parse("""{"id":"old","name":"Old","brushes":[{"id":"custom-1","label":"Ink","hardness":1,"opacity":1,"size":12}]}""".encodeToByteArray())
        val original = legacy.brushes.single()
        assertEquals(0f, original.pressureCurve)
        assertEquals(1f, original.sizePressure)
        assertEquals(0f, original.opacityPressure)
        val brush = original.copy(pressureCurve = -0.6f, sizePressure = 0.3f, opacityPressure = 0.8f)
        val pack = legacy.copy(brushes = listOf(brush))
        assertEquals(pack, BrushPack.parse(Json.encodeToString(pack).encodeToByteArray()))
        val preferences = Preferences(brushes = pack.brushes)
        assertTrue(preferences.valid())
        assertEquals(preferences, Json.decodeFromString<Preferences>(Json.encodeToString(preferences)))
    }

    @Test
    fun invalidPressureSettingsAreRejectedBeforeImport() {
        val json = Json { allowSpecialFloatingPointValues = true }
        for (value in listOf(-1.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val invalid = listOf(
                BrushPreset.Ink.copy(pressureCurve = value),
                BrushPreset.Ink.copy(sizePressure = value),
                BrushPreset.Ink.copy(opacityPressure = value),
            )
            invalid.forEach { brush ->
                assertFalse(brush.valid())
                val pack = BrushPack("invalid", "Invalid", brushes = listOf(brush))
                assertFailsWith<IllegalArgumentException> {
                    BrushPack.parse(json.encodeToString(pack).encodeToByteArray())
                }
            }
        }
        assertFalse(BrushPreset.Ink.copy(sizePressure = -0.01f).valid())
        assertFalse(BrushPreset.Ink.copy(opacityPressure = -0.01f).valid())
    }
}
