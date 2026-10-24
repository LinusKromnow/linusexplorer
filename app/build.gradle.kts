import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.linuskromnow.linusexplorer"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.linuskromnow.linusexplorer"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // scrypt keeps its table off-heap; desktop JVMs cap that unless told otherwise.
        unitTests.all { it.jvmArgs("-XX:MaxDirectMemorySize=2g") }
    }

    packaging {
        resources {
            // bcprov, bcpkix and bcutil each ship the same license files; keep one copy.
            pickFirsts += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md")
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    // F-Droid rejects the encrypted dependency metadata blob AGP embeds by default.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zip4j)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.bouncycastle)
    implementation(libs.bouncycastle.pkix) // sshj needs it; pinned to match bcprov
    implementation(libs.sshj)
    implementation(libs.commons.net)
    implementation(libs.smbj)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment) // FragmentActivity for BiometricPrompt; newer than biometric's own
    // Thumbnails. Deliberately no coil-network-*: the app has no network access.
    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mina.sshd.core) // in-process SFTP server for network tests
    testImplementation(libs.mina.sshd.sftp)
    testImplementation(libs.ftpserver.core) // in-process FTP/FTPS server for network tests
}
