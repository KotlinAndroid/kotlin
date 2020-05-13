description = "Kotlin STM Compiler Plugin"

plugins {
    kotlin("jvm")
    id("jps-compatible")
}

repositories {
    maven { setUrl("https://dl.bintray.com/ololoshechkin/kotlinx-stm-runtime") }
    maven { setUrl("https://kotlin.bintray.com/kotlin-dev") }
}

dependencies {
    compileOnly(intellijCoreDep()) { includeJars("intellij-core", "asm-all", rootProject = rootProject) }

    compileOnly(project(":compiler:plugin-api"))
    compileOnly(project(":compiler:frontend"))
    compileOnly(project(":compiler:backend"))
    compileOnly(project(":compiler:ir.backend.common"))
    compileOnly(project(":compiler:ir.psi2ir"))
    compileOnly(project(":js:js.frontend"))
    compileOnly(project(":js:js.translator"))

    runtime(kotlinStdlib())

    testCompile(projectTests(":compiler:tests-common"))
    testCompile(commonDep("junit:junit"))
    testCompile("org.jetbrains.kotlinx:kotlinx-stm-runtime:0.0.1-tmp5")

    testRuntimeOnly(intellijCoreDep()) { includeJars("intellij-core") }

    Platform[192].orHigher {
        testRuntimeOnly(intellijDep()) { includeJars("platform-concurrency") }
    }
}

sourceSets {
    "main" { projectDefault() }
    "test" { projectDefault() }
}

runtimeJar()
sourcesJar()
javadocJar()
testsJar()

projectTest(parallel = true) {
    workingDir = rootDir
}

apply(from = "$rootDir/gradle/kotlinPluginPublication.gradle.kts")
