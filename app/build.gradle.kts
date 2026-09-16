import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val signingProperties = Properties().apply {
    val file = rootProject.file("signing.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.opensolr.photos"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.opensolr.photos"
        minSdk = 26
        targetSdk = 36
        versionCode = 41
        versionName = "2.0.0"
    }

    signingConfigs {
        if (!signingProperties.isEmpty) {
            create("release") {
                storeFile = file(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
                // Every APK signature scheme, so each Android version verifies the release with the
                // scheme it trusts most: v1 (JAR) for the oldest, v2 and v3 for 7.0+ / 9.0+, v4 for
                // incremental installs on 11+. Fewer "unverified" prompts at install time.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    // No Google "dependency info" block in the APK: it is encrypted for Google alone, so nobody
    // else can verify what it says. IzzyOnDroid and F-Droid refuse APKs that carry it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (!signingProperties.isEmpty) {
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
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

val solrConfigAssets = layout.buildDirectory.dir("generated/solrConfigAssets")

val solrConfigZip = tasks.register<Zip>("solrConfigZip") {
    from(rootProject.file("solr/conf"))
    archiveFileName.set("opensolr-photos-conf.zip")
    destinationDirectory.set(solrConfigAssets)
}

android.sourceSets.getByName("main").assets.srcDir(solrConfigAssets)

tasks.configureEach {
    if (name != solrConfigZip.name && (name.contains("Assets") || name.contains("Lint", ignoreCase = true))) {
        dependsOn(solrConfigZip)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.exifinterface)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.osmdroid)
}
