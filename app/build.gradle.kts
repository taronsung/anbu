import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 서명 정보는 git에 올리지 않는 signing/ 폴더에서 읽는다.
val signingProps = Properties().apply {
    val f = rootProject.file("signing/signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

object PleosSdk {
    const val VEHICLE = "2.3.2"
    const val NAVI_HELPER = "3.0.5"
    const val TTS = "3.3.1"
    const val STT = "3.3.1"
    const val FUSED_LOCATION = "1.1.3"
}

android {
    namespace = "app.anbu.car"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.anbu.car"
        minSdk = 28
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"

        // 가족 화면 데모 릴레이 주소. 에뮬레이터에서 본 호스트 PC가 기본값이다.
        buildConfigField("String", "DEFAULT_RELAY_URL", "\"http://10.0.2.2:8787\"")
        buildConfigField("String", "PLEOS_VEHICLE_VERSION", "\"${PleosSdk.VEHICLE}\"")
        buildConfigField("String", "PLEOS_NAVI_VERSION", "\"${PleosSdk.NAVI_HELPER}\"")
        buildConfigField("String", "PLEOS_TTS_VERSION", "\"${PleosSdk.TTS}\"")
        buildConfigField("String", "PLEOS_STT_VERSION", "\"${PleosSdk.STT}\"")
        buildConfigField("String", "PLEOS_FUSED_VERSION", "\"${PleosSdk.FUSED_LOCATION}\"")

        // 차량(arm64)과 에뮬레이터(Mac은 arm64, Windows·Linux는 x86_64)에서 쓰는 ABI만 담는다.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        if (signingProps.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
                // 서명 키 교체(lineage)를 지원하는 v3 서명까지 붙인다. minSdk 28 요건과 같은 이유다.
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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
    // AAOS Car API (속도, 기어, 주행 규제). 플랫폼이 제공하는 선택 라이브러리다.
    useLibrary("android.car")

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:${PleosSdk.VEHICLE}")
    implementation("ai.pleos.playground:NaviHelper:${PleosSdk.NAVI_HELPER}")
    implementation("ai.pleos.playground:TextToSpeech:${PleosSdk.TTS}")
    implementation("ai.pleos.playground:SpeechToText:${PleosSdk.STT}")
    implementation("ai.pleos.playground:FusedLocation:${PleosSdk.FUSED_LOCATION}")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.json:json:20240303")
}

// 개발 보조: 컴파일 클래스패스에 들어온 Pleos SDK 좌표를 출력한다.
tasks.register("printPleosSdk") {
    val cfg = configurations.named("releaseRuntimeClasspath")
    doLast {
        cfg.get().resolvedConfiguration.resolvedArtifacts
            .filter { it.moduleVersion.id.group.startsWith("ai.pleos") }
            .forEach { println("${it.moduleVersion.id} -> ${it.file}") }
    }
}
