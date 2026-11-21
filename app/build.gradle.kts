plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ai.lily.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.lily.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (System.getenv("KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        jniLibs {
            // The terminal runs proot from the native library folder (apps may not run programs they unpack themselves).
            // Android only leaves real files there when libraries are extracted at install, so this must stay on.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.security.crypto)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    // Sidebar icons (Edit, Search, Build, Settings). Version comes from the Compose BOM.
    implementation("androidx.compose.material:material-icons-core")
    implementation(libs.androidx.activity.compose)

    // Remote workspaces. SFTP: the maintained JSch fork (modern algorithms, weak ones off by default). FTPS/FTP: Apache Commons Net.
    implementation("com.github.mwiede:jsch:0.2.20")
    implementation("commons-net:commons-net:3.11.1")
    // The terminal screen and the pseudo-terminal that runs the shell (Termux's libraries; see docs/terminal.md for the licence note).
    implementation("com.github.termux.termux-app:terminal-view:v0.118.0")
    implementation("com.github.termux.termux-app:terminal-emulator:v0.118.0")

    testImplementation(libs.junit)
    // Android's org.json is only a stub in unit tests; this real one lets the Turso request/response JSON be tested.
    testImplementation("org.json:json:20240303")
    // A real SQLite in memory, so the knowledge layer's migrations and queries are tested against actual SQL (tests only).
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.0")
}
