package app.dsandroid.data

import app.dsandroid.core.backup.BackupSettings

// Keep this an explicit allowlist. AppSettings itself must never be exported.
fun AppSettings.backupSettings(language: String) = BackupSettings(
    appearance = appearance.name, fontScale = fontScale, collapseThinking = collapseThinking,
    suggestedFollowUps = suggestedFollowUps, audioLanguage = audioLanguage,
    appLanguage = language.substringBefore('-').takeIf { it in setOf("en", "bn") }.orEmpty(),
    memoryEnabled = memoryEnabled, memoryUpkeep = memoryUpkeep, customInstructions = customInstructions,
    disabledTools = disabledTools, searchBackend = searchBackend,
)

fun AppSettings.withBackupSettings(value: BackupSettings) = copy(
    appearance = Appearance.valueOf(value.appearance), fontScale = value.fontScale,
    collapseThinking = value.collapseThinking, suggestedFollowUps = value.suggestedFollowUps,
    audioLanguage = value.audioLanguage, memoryEnabled = value.memoryEnabled, memoryUpkeep = value.memoryUpkeep,
    customInstructions = value.customInstructions, disabledTools = value.disabledTools, searchBackend = value.searchBackend,
)
