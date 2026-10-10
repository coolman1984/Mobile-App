plugins {
    id("com.android.application") version "8.9.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.20" apply false
    // Locked for later Room processing. Not applied, so this milestone adds no processor.
    id("com.google.devtools.ksp") version "2.1.20-1.0.32" apply false
}
