package io.github.usmandurrany.tether.debug

import kotlin.experimental.ExperimentalNativeApi

@OptIn(ExperimentalNativeApi::class)
internal actual fun currentPlatform(): PlatformInfo = PlatformInfo(
    platform = when (Platform.osFamily) {
        OsFamily.LINUX -> "linux"
        OsFamily.MACOSX -> "macos"
        OsFamily.IOS -> "ios"
        OsFamily.WINDOWS -> "windows"
        else -> Platform.osFamily.name.lowercase()
    },
    osVersion = null,
    model = null,
)
