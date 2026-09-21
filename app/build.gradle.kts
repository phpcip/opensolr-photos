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

    bundle {
        language {
            enableSplit = false
        }
    }

    defaultConfig {
        applicationId = "com.opensolr.photos"
        minSdk = 26
        targetSdk = 36
        versionCode = 80
        versionName = "3.5.3"
    }

    signingConfigs {
        if (!signingProperties.isEmpty) {
            create("release") {
                storeFile = file(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")

                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

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
