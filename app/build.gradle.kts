import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Lê CLAUDE_API_KEY e CLAUDE_MODEL do local.properties (que NÃO vai pro git).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localProp(name: String, default: String = ""): String =
    localProps.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() } ?: default

// Escapa pra virar um literal String válido em Java/Kotlin no BuildConfig.
fun String.asBuildConfigString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "dev.breno.jarvis"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.breno.jarvis"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "CLAUDE_API_KEY", localProp("CLAUDE_API_KEY").asBuildConfigString())
        buildConfigField(
            "String", "CLAUDE_MODEL",
            localProp("CLAUDE_MODEL", "claude-sonnet-5-5").asBuildConfigString(),
        )
        // Agente do PC (Fase 2): ex. http://192.168.0.10:8765 e o token do pc-agent/config.json
        buildConfigField("String", "PC_AGENT_URL", localProp("PC_AGENT_URL").asBuildConfigString())
        buildConfigField("String", "PC_AGENT_TOKEN", localProp("PC_AGENT_TOKEN").asBuildConfigString())
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // O org.json do Android vira stub nos testes JVM; este é o real.
    testImplementation(libs.json)
}
