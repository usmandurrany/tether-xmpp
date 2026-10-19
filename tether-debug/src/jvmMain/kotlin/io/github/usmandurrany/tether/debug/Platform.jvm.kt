package io.github.usmandurrany.tether.debug

/** On Android the JVM target runs too; `android.os.Build` tells the two apart without an Android dependency. */
internal actual fun currentPlatform(): PlatformInfo = android() ?: PlatformInfo(
    platform = System.getProperty("os.name").orEmpty().lowercase().let {
        when {
            it.startsWith("linux") -> "linux"
            it.startsWith("mac") -> "macos"
            it.startsWith("windows") -> "windows"
            else -> it.ifEmpty { "jvm" }
        }
    },
    osVersion = "${System.getProperty("os.version")} (JVM ${System.getProperty("java.version")})",
    model = null, // desktops have no meaningful model; apps can set one, such as the host name

)

private fun android(): PlatformInfo? = try {
    val build = Class.forName("android.os.Build")
    val version = Class.forName("android.os.Build\$VERSION")
    val manufacturer = build.getField("MANUFACTURER").get(null) as String?
    val model = build.getField("MODEL").get(null) as String?
    val release = version.getField("RELEASE").get(null) as String?
    val sdk = version.getField("SDK_INT").getInt(null)
    PlatformInfo("android", "$release (API $sdk)", listOfNotNull(manufacturer, model).joinToString(" ").ifEmpty { null })
} catch (e: ReflectiveOperationException) {
    null
} catch (e: LinkageError) {
    null
}
