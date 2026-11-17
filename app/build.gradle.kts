import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}
android {
    // Keep Gradle's Android toolchain aligned with the NDK used to build MNN in CI.
    ndkVersion = "27.2.12479018"
    namespace = "com.seankew.localai"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.seankew.localai"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildTypes {
        getByName("release") {
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
// 导出 Room schema（JSON）：为以后的 AutoMigration / 迁移测试保留每个版本的表结构历史。
// 不使用 Room Gradle 插件：插件会按构建变体写出 schemas/debug/... 与 schemas/release/... 两份，
// 路径随变体变化，不利于 CI 守卫；这里固定输出到 app/schemas/<数据库类全名>/<版本>.json。
class RoomSchemaArgProvider(
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val schemaDir: File,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf("room.schemaLocation=${schemaDir.path}")
}

ksp {
    // @InputDirectory 要求目录必须存在，首次构建前先创建
    arg(RoomSchemaArgProvider(File(projectDir, "schemas").also { it.mkdirs() }))
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // ViewModel / lifecycleScope / ComponentActivity（viewModels()、Activity Result API）
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // 本地长时记忆（Room + KSP）
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    // JVM unit tests for pure Kotlin logic
    testImplementation("junit:junit:4.13.2")
}
