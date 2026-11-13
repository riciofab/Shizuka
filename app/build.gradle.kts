import java.util.Properties

plugins {
    id("com.android.application")
}

// No dependencies on purpose: framework Views only, to keep RAM and APK size minimal.
android {
    namespace = "dev.plume"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.plume"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "1.5.0"
        // What's new: only this version's section of the changelog ships, one entry per line, ** marks kept for bold.
        // A ### heading ships as a line that starts with #. A fix release (1.7.1) ships its x.y section after its own,
        // so people who update from 1.6 still see what 1.7 brought.
        // Two changelogs, so the notes follow the TV's language: CHANGELOG.md is French (whats_new_fr), CHANGELOG.en.md
        // is English (whats_new, which every other language reads). MainActivity.whatsNew() picks one.
        val minor = versionName!!.split('.').take(2).joinToString(".")
        fun whatsNew(file: String, name: String) {
            val changelog = rootProject.file(file).readText().replace("\r", "")
            val section = { v: String -> changelog.substringAfter("\n## $v\n", "").substringBefore("\n## ") }
            val notes = (section(versionName!!) + if (minor != versionName) "\n" + section(minor) else "")
                .lines().filter { it.startsWith("- ") || it.startsWith("### ") }
                .map { if (it.startsWith("### ")) "#" + it.removePrefix("### ") else it.removePrefix("- ") }
            require(notes.isNotEmpty()) { "$file has no entries under \"## $versionName\"" }
            resValue("string", name, notes.joinToString("\\n") { it.replace("'", "\\'").replace("\"", "\\\"") })
        }
        whatsNew("CHANGELOG.en.md", "whats_new")
        whatsNew("CHANGELOG.md", "whats_new_fr")
    }

    buildFeatures {
        resValues = true
    }

    // CI fails on new lint errors only; the ones already here are listed in lint-baseline.xml
    // (./gradlew updateLintBaseline rewrites it). Translations have their own check, so lag there isn't an error.
    lint {
        baseline = file("lint-baseline.xml")
        disable += setOf("MissingTranslation", "ExtraTranslation")
    }

    // Release key lives outside the repo; keystore.properties (git-ignored) points at it. Without that file
    // (a fresh clone) release builds fall back to the debug key, so anyone can still build and sideload.
    val keys = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
        }
        if (keys != null) create("release") {
            storeFile = file(keys.getProperty("storeFile"))
            storePassword = keys.getProperty("storePassword")
            keyAlias = keys.getProperty("keyAlias")
            keyPassword = keys.getProperty("keyPassword")
        }
    }

    // Kotlin's own metadata files (reflection and tooling data) aren't used at run time.
    packaging {
        resources {
            excludes += setOf("kotlin/**", "**/*.kotlin_builtins", "META-INF/*.kotlin_module", "META-INF/*.version", "DebugProbesKt.bin")
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildTypes {
        release {
            vcsInfo.include = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
