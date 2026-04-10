plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.sv21c.jsplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sv21c.jsplayer"
        minSdk = 26
        targetSdk = 36
        versionCode = 111
        versionName = "1.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    packaging {
        jniLibs {
            excludes += "**/libffmpegJNI.so" // GPL 회피를 위해 바이너리 제외
        }
        resources {
            excludes += "META-INF/beans.xml"
            excludes += "META-INF/license.txt"
            excludes += "META-INF/notice.txt"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // SMB/WebDAV 라이브러리 충돌 방지
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    
    // jupnp 라이브러리 (Maven Central 형식으로 변경)
    implementation("org.jupnp:org.jupnp:3.0.4")
    implementation("org.jupnp:org.jupnp.support:3.0.4")
    implementation("org.jupnp:org.jupnp.android:3.0.4")
    // jupnp는 servlet-api 등을 필요로 할 수 있습니다. (직접 선언)
    implementation("jakarta.xml.ws:jakarta.xml.ws-api:4.0.3")
    implementation("jakarta.xml.bind:jakarta.xml.bind-api:4.0.5")

    // JUPnP Android requires Jetty internally as Android doesn't have com.sun.net.httpserver
    implementation("org.eclipse.jetty:jetty-server:9.4.53.v20231009")
    implementation("org.eclipse.jetty:jetty-servlet:9.4.53.v20231009")
    implementation("org.eclipse.jetty:jetty-client:9.4.53.v20231009")

    // SLF4J for Android Logcat output
    implementation("org.slf4j:slf4j-api:2.0.7")
    implementation("org.slf4j:slf4j-simple:2.0.7")
    // Media3 (ExoPlayer) for Video Playback
    val media3_version = "1.5.0"
    implementation("androidx.media3:media3-exoplayer:$media3_version")
    implementation("androidx.media3:media3-ui:$media3_version")
    implementation("androidx.media3:media3-session:$media3_version")
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // ── SMB / WebDAV 지원 ─────────────────────────────────────────
    // SMB 클라이언트 (jCIFS-NG)
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")
    // WebDAV 클라이언트 (Sardine-Android by thegrizzlylabs)
    implementation("com.github.thegrizzlylabs:sardine-android:0.9")
    // OkHttp (Sardine 의존)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 암호화 저장 (EncryptedSharedPreferences)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // ── FTP / SFTP 지원 ──────────────────────────────────────────
    // FTP 클라이언트 (Apache Commons Net)
    implementation("commons-net:commons-net:3.11.1")
    // SFTP 클라이언트 (SSHJ)
    implementation("com.hierynomus:sshj:0.39.0")
    // Bouncy Castle (X25519 등 최신 암호 알고리즘 지원)
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}