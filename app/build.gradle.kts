import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmEnvironment
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// Pins. No dynamic "+" versions and no snapshots.
// Plugin versions live in the root build.gradle.kts.
// Libraries below are resolved by auditPinnedDependencies.
// M02 packages Room 2.7.1. WorkManager brings an older Room; resolution forces 2.7.1.
// Navigation, biometric, datastore and appcompat stay off the APK.
private val composeBom = "2025.04.01"
private val activityCompose = "1.10.1"
private val appcompat = "1.7.0"
private val lifecycle = "2.8.7"
private val room = "2.7.1"
private val navigation = "2.8.9"
private val work = "2.10.5"
private val biometric = "1.1.0"
private val securityCrypto = "1.1.0"
private val datastore = "1.1.4"
private val serialization = "1.8.1"
private val coroutines = "1.10.1"
private val junitVersion = "4.13.2"
private val robolectric = "4.14.1"
private val testRunner = "1.6.2"
private val testExtJunit = "1.2.1"
private val kspApi = "2.1.20-1.0.32"
private val material3 = "1.3.2"

private val directPins = mapOf(
    "androidx.compose:compose-bom" to composeBom,
    "androidx.activity:activity-compose" to activityCompose,
    "androidx.appcompat:appcompat" to appcompat,
    "androidx.lifecycle:lifecycle-runtime-compose" to lifecycle,
    "androidx.lifecycle:lifecycle-viewmodel-compose" to lifecycle,
    "androidx.lifecycle:lifecycle-process" to lifecycle,
    "androidx.room:room-runtime" to room,
    "androidx.room:room-ktx" to room,
    "androidx.room:room-compiler" to room,
    "androidx.room:room-testing" to room,
    "androidx.navigation:navigation-compose" to navigation,
    "androidx.navigation:navigation-testing" to navigation,
    "androidx.work:work-runtime-ktx" to work,
    "androidx.work:work-testing" to work,
    "androidx.biometric:biometric" to biometric,
    "androidx.security:security-crypto" to securityCrypto,
    "androidx.datastore:datastore-preferences" to datastore,
    "org.jetbrains.kotlinx:kotlinx-serialization-json" to serialization,
    "org.jetbrains.kotlinx:kotlinx-coroutines-android" to coroutines,
    "org.jetbrains.kotlinx:kotlinx-coroutines-test" to coroutines,
    "junit:junit" to junitVersion,
    "org.robolectric:robolectric" to robolectric,
    "androidx.test:runner" to testRunner,
    "androidx.test.ext:junit" to testExtJunit,
    "com.google.devtools.ksp:symbol-processing-api" to kspApi,
)

android {
    namespace = "com.pixelplus.owner"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.pixelplus.owner"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Release signing stays off. Debug builds keep the local debug keystore only.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1"
            )
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
}

// Room writes the committed schema here. exportSchema is also set on OwnerDatabase.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

val pinnedAndroidAudit: Configuration = configurations.create("pinnedAndroidAudit") {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = true
    isVisible = false
    description = "Locked Android libraries. Resolved for the SDK audit only. Not packaged."
}

val pinnedJvmAudit: Configuration = configurations.create("pinnedJvmAudit") {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = true
    isVisible = false
    description = "Locked JVM libraries. Resolved for the SDK audit only. Not packaged."
}

pinnedJvmAudit.attributes {
    attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
    attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
    attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling::class.java, Bundling.EXTERNAL))
    attribute(
        TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
        objects.named(TargetJvmEnvironment::class.java, TargetJvmEnvironment.STANDARD_JVM),
    )
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:$composeBom"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:$activityCompose")
    implementation("androidx.security:security-crypto:$securityCrypto")
    implementation("androidx.work:work-runtime-ktx:$work")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serialization")
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:$junitVersion")
    testImplementation("org.robolectric:robolectric:$robolectric")

    add(pinnedAndroidAudit.name, platform("androidx.compose:compose-bom:$composeBom"))
    add(pinnedAndroidAudit.name, "androidx.compose.ui:ui")
    add(pinnedAndroidAudit.name, "androidx.compose.material3:material3")
    add(pinnedAndroidAudit.name, "androidx.compose.ui:ui-tooling")
    add(pinnedAndroidAudit.name, "androidx.compose.ui:ui-test-junit4")
    add(pinnedAndroidAudit.name, "androidx.compose.ui:ui-test-manifest")
    add(pinnedAndroidAudit.name, "androidx.activity:activity-compose:$activityCompose")
    add(pinnedAndroidAudit.name, "androidx.appcompat:appcompat:$appcompat")
    add(pinnedAndroidAudit.name, "androidx.lifecycle:lifecycle-runtime-compose:$lifecycle")
    add(pinnedAndroidAudit.name, "androidx.lifecycle:lifecycle-viewmodel-compose:$lifecycle")
    add(pinnedAndroidAudit.name, "androidx.lifecycle:lifecycle-process:$lifecycle")
    add(pinnedAndroidAudit.name, "androidx.room:room-runtime:$room")
    add(pinnedAndroidAudit.name, "androidx.room:room-ktx:$room")
    add(pinnedAndroidAudit.name, "androidx.room:room-testing:$room")
    add(pinnedAndroidAudit.name, "androidx.navigation:navigation-compose:$navigation")
    add(pinnedAndroidAudit.name, "androidx.navigation:navigation-testing:$navigation")
    add(pinnedAndroidAudit.name, "androidx.work:work-runtime-ktx:$work")
    add(pinnedAndroidAudit.name, "androidx.work:work-testing:$work")
    add(pinnedAndroidAudit.name, "androidx.biometric:biometric:$biometric")
    add(pinnedAndroidAudit.name, "androidx.security:security-crypto:$securityCrypto")
    add(pinnedAndroidAudit.name, "androidx.datastore:datastore-preferences:$datastore")
    add(pinnedAndroidAudit.name, "androidx.test:runner:$testRunner")
    add(pinnedAndroidAudit.name, "androidx.test.ext:junit:$testExtJunit")

    add(pinnedJvmAudit.name, "androidx.room:room-compiler:$room")
    add(pinnedJvmAudit.name, "org.jetbrains.kotlinx:kotlinx-serialization-json:$serialization")
    add(pinnedJvmAudit.name, "org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutines")
    add(pinnedJvmAudit.name, "org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutines")
    add(pinnedJvmAudit.name, "junit:junit:$junitVersion")
    add(pinnedJvmAudit.name, "org.robolectric:robolectric:$robolectric")
    add(pinnedJvmAudit.name, "com.google.devtools.ksp:symbol-processing-api:$kspApi")
}

configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "androidx.room") {
            useVersion(room)
            because("M02 keeps every Room artifact on 2.7.1")
        }
    }
}

afterEvaluate {
    val runtimeAttributes = configurations.getByName("debugRuntimeClasspath").attributes
    pinnedAndroidAudit.attributes {
        runtimeAttributes.keySet().forEach { key ->
            @Suppress("UNCHECKED_CAST")
            val typed = key as Attribute<Any>
            if (typed.name == "artifactType") return@forEach
            val value = runtimeAttributes.getAttribute(typed) ?: return@forEach
            attribute(typed, value)
        }
    }
}

tasks.register("auditPinnedDependencies") {
    group = "verification"
    description = "Resolve locked pins and fail if an AAR needs a compile SDK above 35."
    doLast {
        val problems = mutableListOf<String>()
        val lines = mutableListOf<String>()
        if (project.gradle.gradleVersion != "8.11.1") {
            problems += "Gradle ${project.gradle.gradleVersion} is not the locked 8.11.1"
        }
        if (JavaVersion.current() != JavaVersion.VERSION_17) {
            problems += "JDK ${JavaVersion.current()} is not 17"
        }
        val androidExt = project.extensions.getByType(com.android.build.api.dsl.ApplicationExtension::class.java)
        if (androidExt.compileSdk != 35) problems += "compileSdk is ${androidExt.compileSdk}, expected 35"
        if (androidExt.buildToolsVersion != "35.0.0") {
            problems += "buildToolsVersion is ${androidExt.buildToolsVersion}, expected 35.0.0"
        }
        if (androidExt.defaultConfig.minSdk != 26) problems += "minSdk drifted from 26"
        if (androidExt.defaultConfig.targetSdk != 35) problems += "targetSdk drifted from 35"
        if (androidExt.defaultConfig.applicationId != "com.pixelplus.owner") {
            problems += "applicationId drifted"
        }
        val release = androidExt.buildTypes.getByName("release")
        if (release.signingConfig != null) problems += "release signing must stay off"
        lines += "gradle=${project.gradle.gradleVersion} jdk=${JavaVersion.current()}"
        lines += "compileSdk=${androidExt.compileSdk} buildTools=${androidExt.buildToolsVersion} minSdk=${androidExt.defaultConfig.minSdk} targetSdk=${androidExt.defaultConfig.targetSdk}"
        lines += "releaseSigning=${if (release.signingConfig == null) "off" else "SET"}"

        val selected = linkedMapOf<String, String>()
        listOf(pinnedAndroidAudit, pinnedJvmAudit).forEach { configuration ->
            lines += ""
            lines += "[${configuration.name}]"
            configuration.incoming.resolutionResult.root.dependencies.forEach { dependency ->
                if (dependency is org.gradle.api.artifacts.result.ResolvedDependencyResult) {
                    val module = dependency.selected.id
                    if (module is org.gradle.api.artifacts.component.ModuleComponentIdentifier) {
                        val key = "${module.group}:${module.module}"
                        selected[key] = module.version
                        lines += "direct $key:${module.version}"
                    }
                } else {
                    problems += "${configuration.name} has an unresolved dependency: $dependency"
                }
            }
            var maxMinCompile = 0
            var aarCount = 0
            configuration.resolvedConfiguration.resolvedArtifacts.forEach { artifact ->
                val id = artifact.moduleVersion.id
                val notation = "${id.group}:${id.name}:${id.version}"
                if (id.version.contains('+') || id.version.contains("SNAPSHOT", ignoreCase = true)) {
                    problems += "$notation uses a floating version"
                }
                val file = artifact.file
                if (file.extension == "aar") {
                    aarCount += 1
                    val (compileSdk, extension) = readAarCompileSdk(file)
                    lines += "aar $notation minCompileSdk=${compileSdk ?: "legacy"} extension=${extension ?: "-"}"
                    if (compileSdk != null) {
                        if (compileSdk > maxMinCompile) maxMinCompile = compileSdk
                        if (compileSdk > 35) problems += "$notation minCompileSdk=$compileSdk exceeds 35"
                        if (extension != null && extension > 0) {
                            problems += "$notation requires compile SDK extension $extension"
                        }
                    }
                } else {
                    lines += "artifact $notation (${file.extension})"
                }
            }
            lines += "aars=$aarCount maxMinCompileSdk=$maxMinCompile"
        }

        directPins.forEach { (module, version) ->
            val got = selected[module]
            if (got != version) problems += "$module resolved ${got ?: "missing"}, pin is $version"
        }
        val resolvedMaterial = selected["androidx.compose.material3:material3"]
        if (resolvedMaterial != material3) {
            problems += "material3 resolved ${resolvedMaterial ?: "missing"}, BOM pin is $material3"
        }
        lines += ""
        lines += if (problems.isEmpty()) "result=ok" else "result=failed"
        problems.forEach { lines += "problem: $it" }

        val reportDir = project.layout.buildDirectory.dir("reports/dependency-sdk-audit").get().asFile
        reportDir.mkdirs()
        val report = reportDir.resolve("audit.txt")
        report.writeText(lines.joinToString("\n") + "\n")
        logger.lifecycle("Dependency SDK audit: ${report.absolutePath}")
        if (problems.isNotEmpty()) {
            throw org.gradle.api.GradleException(problems.joinToString("\n"))
        }
    }
}

tasks.register("auditDebugApkSecrets") {
    group = "verification"
    description = "Fail if the debug APK contains a secret, a release signer, or a library this milestone must not ship."
    doLast {
        val apk = project.layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        if (!apk.isFile) throw org.gradle.api.GradleException("Debug APK is missing: ${apk.absolutePath}")
        val problems = mutableListOf<String>()
        val forbiddenBytes = listOf(
            "-----BEGIN PRIVATE KEY-----",
            "-----BEGIN RSA PRIVATE KEY-----",
            "-----BEGIN EC PRIVATE KEY-----",
            "-----BEGIN OPENSSH PRIVATE KEY-----",
            "firebaseio.com",
            "google-services.json",
            "Landroidx/navigation/",
            "Landroidx/biometric/",
            "Landroidx/datastore/",
            "Landroidx/appcompat/",
        ).map { it.toByteArray(Charsets.US_ASCII) }
        ZipFile(apk).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || entry.size > 40_000_000) continue
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val latin = String(bytes, Charsets.ISO_8859_1)
                forbiddenBytes.forEach { needle ->
                    if (indexOf(bytes, needle) >= 0) {
                        val label = String(needle, Charsets.US_ASCII)
                        problems += "${entry.name} contains $label"
                    }
                }
                SECRET_VALUE.forEach { pattern ->
                    if (pattern.containsMatchIn(latin)) {
                        problems += "${entry.name} matches ${pattern.pattern}"
                    }
                }
            }
        }
        val sdk = project.extensions.getByType(com.android.build.gradle.AppExtension::class.java).sdkDirectory
        val apksigner = File(sdk, "build-tools/35.0.0/apksigner")
        val aapt = File(sdk, "build-tools/35.0.0/aapt")
        if (!apksigner.isFile || !aapt.isFile) {
            problems += "build-tools 35.0.0 apksigner/aapt missing under ${sdk.absolutePath}"
        } else {
            val signer = ProcessBuilder(apksigner.absolutePath, "verify", "--print-certs", apk.absolutePath)
                .redirectErrorStream(true)
                .start()
            val signerText = signer.inputStream.bufferedReader().readText()
            if (signer.waitFor() != 0) problems += "apksigner could not verify the APK"
            val names = Regex("certificate DN: (.+)").findAll(signerText).map { it.groupValues[1] }.toList()
            if (names.isEmpty()) problems += "APK has no signer certificate"
            names.forEach { name ->
                if (!name.contains("CN=Android Debug")) {
                    problems += "APK signer is not the debug certificate: $name"
                }
            }
            val dump = ProcessBuilder(aapt.absolutePath, "dump", "permissions", apk.absolutePath)
                .redirectErrorStream(true)
                .start()
            val dumpText = dump.inputStream.bufferedReader().readText()
            if (dump.waitFor() != 0) problems += "aapt could not read APK permissions"
            val permissions = Regex("uses-permission: name='([^']+)'")
                .findAll(dumpText)
                .map { it.groupValues[1] }
                .toSet()
            // WorkManager already merges the extra four, plus its exported-receiver permission.
            // Anything else means a new library changed the app.
            val allowed = setOf(
                "android.permission.INTERNET",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.WAKE_LOCK",
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.RECEIVE_BOOT_COMPLETED",
                "android.permission.FOREGROUND_SERVICE",
                "com.pixelplus.owner.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            )
            if (permissions != allowed) {
                problems += "Merged APK permissions are $permissions"
            }
        }
        val manifest = project.file("src/main/AndroidManifest.xml").readText()
        val declared = Regex("""uses-permission[^>]*android:name="([^"]+)"""")
            .findAll(manifest)
            .map { it.groupValues[1] }
            .toSet()
        val owned = setOf(
            "android.permission.INTERNET",
            "android.permission.POST_NOTIFICATIONS",
        )
        if (declared != owned) problems += "Source manifest permissions are $declared"
        if (problems.isNotEmpty()) {
            throw org.gradle.api.GradleException(problems.joinToString("\n"))
        }
        logger.lifecycle("Debug APK secret audit passed (${apk.length()} bytes)")
    }
}

tasks.configureEach {
    if (name == "preBuild") {
        dependsOn("auditPinnedDependencies")
    }
    if (name == "assembleDebug") {
        finalizedBy("auditDebugApkSecrets")
    }
}

private val SECRET_VALUE = listOf(
    Regex("AIza[0-9A-Za-z_\\-]{20,}"),
    Regex("sk_live_[0-9A-Za-z]{8,}"),
    Regex("sk_test_[0-9A-Za-z]{8,}"),
    Regex("AKIA[0-9A-Z]{16}"),
    Regex("ghp_[0-9A-Za-z]{20,}"),
    Regex("github_pat_[0-9A-Za-z_]{20,}"),
    Regex("xox[baprs]-[0-9A-Za-z-]{10,}"),
)

private fun readAarCompileSdk(aar: File): Pair<Int?, Int?> {
    ZipFile(aar).use { zip ->
        val entry = zip.getEntry("META-INF/com/android/build/gradle/aar-metadata.properties")
            ?: return null to null
        val props = Properties()
        zip.getInputStream(entry).use { props.load(it) }
        return props.getProperty("minCompileSdk")?.toIntOrNull() to
            props.getProperty("minCompileSdkExtension")?.toIntOrNull()
    }
}

private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
    if (needle.isEmpty() || haystack.size < needle.size) return -1
    val last = haystack.size - needle.size
    for (start in 0..last) {
        var matched = true
        for (offset in needle.indices) {
            if (haystack[start + offset] != needle[offset]) {
                matched = false
                break
            }
        }
        if (matched) return start
    }
    return -1
}

