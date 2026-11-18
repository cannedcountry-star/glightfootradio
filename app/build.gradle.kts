plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 署名鍵は GitHub Actions の Secrets から渡す。無いときは debug 鍵で署名する (インストールはできる)
val signingKeystore: String? = System.getenv("SIGNING_KEYSTORE")

android {
    namespace = "com.cannedcountrystar.glightfootradio"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cannedcountrystar.glightfootradio"
        minSdk = 24
        targetSdk = 34
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0-dev"
    }

    signingConfigs {
        create("release") {
            if (signingKeystore != null) {
                storeFile = file(signingKeystore)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (signingKeystore != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3") // 配信が HLS (.m3u8) に変わっても再生できるように
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.core:core-ktx:1.13.1")
}
