// Ported from upstream app/build.gradle.kts at commit da782a8.
import java.time.Duration
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
}

val signingDirectory = System.getenv("ATOMIC_SIGNING_DIR")?.let(::File)
    ?: rootProject.file(".signing")
val signingPropertiesFile = signingDirectory.resolve("signing.properties")
require(signingPropertiesFile.isFile) { "Project signing.properties missing; use tools/build.sh." }
val signingProperties = Properties().apply {
    signingPropertiesFile.inputStream().use(::load)
}
fun signingValue(name: String): String = requireNotNull(signingProperties.getProperty(name)) {
    "Project signing setting $name is missing."
}.also { require(it.isNotBlank()) { "Project signing setting $name is empty." } }

val appLabel = providers.gradleProperty("atomic.appLabel").orElse("Atomic Bridge")
val launcherLabel = providers.gradleProperty("atomic.launcherLabel").orElse("Bridge")
val appVersionCode = providers.gradleProperty("atomic.versionCode").orElse("8").get().let { value ->
    requireNotNull(value.toIntOrNull()) { "atomic.versionCode must be an integer." }
}

android {
    namespace = "tech.atomiclabs.link"
    compileSdk = 37

    defaultConfig {
        applicationId = "tech.atomiclabs.link"
        minSdk = 34
        targetSdk = 36
        versionCode = appVersionCode
        versionName = "1.0.0"
        resValue("string", "app_name", appLabel.get())
        resValue("string", "launcher_name", launcherLabel.get())
        resValue("string", "notification_listener_description_static", "Lets ${appLabel.get()} read notifications on this device.")
    }

    signingConfigs {
        create("project") {
            storeFile = file(signingValue("storeFile")).also {
                require(it.isFile) { "Project signing keystore is missing." }
            }
            storePassword = signingValue("storePassword")
            keyAlias = signingValue("keyAlias")
            keyPassword = signingValue("keyPassword")
            storeType = "JKS"
        }
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("project")
        }
        release {
            signingConfig = signingConfigs.getByName("project")
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        resValues = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.withType<Test>().configureEach {
    timeout.set(Duration.ofMinutes(10))
    maxParallelForks = 2
    systemProperty("java.io.tmpdir", System.getProperty("java.io.tmpdir"))
    // Preloading MockK's existing instrumentation avoids sandbox-restricted runtime attachment.
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        val instrumentation = requireNotNull(classpath.files.singleOrNull {
            it.name.startsWith("byte-buddy-agent-") && it.extension == "jar"
        }) { "MockK's transitive Byte Buddy instrumentation JAR is missing or ambiguous." }
        listOf("-javaagent:${instrumentation.absolutePath}")
    })
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.core.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
