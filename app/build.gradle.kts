import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// keystore/ is private (not in git). Without it, builds fall back to the debug key.
val keyFile = rootProject.file("keystore/key.properties")
val keyProps = Properties().apply { if (keyFile.exists()) keyFile.inputStream().use { load(it) } }
val hasKey = keyFile.exists()

android {
    namespace = "com.iconforge.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.iconforge.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 17
        versionName = "1.10.1"
    }

    // The app's own signing key (keystore/). Not the key that signs exported
    // icon packs — that one is generated on each phone in the Android Keystore.
    signingConfigs {
        if (hasKey) create("iconforge") {
            storeFile = rootProject.file("keystore/" + keyProps.getProperty("storeFile"))
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            if (hasKey) signingConfig = signingConfigs.getByName("iconforge")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasKey) signingConfigs.getByName("iconforge") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    // Builds a full Material You colour scheme from one seed colour (the colour picker).
    implementation("com.materialkolor:material-kolor:4.1.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    // Signs the icon-pack APKs the app generates (pure Java, works on-device).
    implementation("com.android.tools.build:apksig:9.3.1")

    testImplementation("junit:junit:4.13.2")
}
