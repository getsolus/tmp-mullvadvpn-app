import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask

plugins {
    id(Dependencies.Plugin.dependencyCheckId) version Versions.Plugin.dependencyCheck apply false
    id(Dependencies.Plugin.gradleVersionsId) version Versions.Plugin.gradleVersions
    id(Dependencies.Plugin.ktfmtId) version Versions.Plugin.ktfmt apply false
    id(Dependencies.Plugin.detektId) version Versions.Plugin.detekt
}

buildscript {
    repositories {
        google()
        mavenCentral()
        maven(Repositories.GradlePlugins)
        gradlePluginPortal()
    }

    dependencies {
        classpath(Dependencies.Plugin.android)
        classpath(Dependencies.Plugin.playPublisher)
        classpath(Dependencies.Plugin.kotlin)
        classpath(Dependencies.Plugin.dependencyCheck)

        // Required for Gradle metadata verification to work properly, see:
        // https://github.com/gradle/gradle/issues/19228
        classpath(Dependencies.Plugin.aaptLinux)
        classpath(Dependencies.Plugin.aaptOsx)
        classpath(Dependencies.Plugin.aaptWindows)
    }
}

val baselineFile = file("$rootDir/config/baseline.xml")
val configFile = files("$rootDir/config/detekt.yml")

val projectSource = file(projectDir)
val detektExcludedPaths = listOf(
        "**/build/**",
        "**/mullvad_daemon/management_interface/**",
)

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(configFile)
    source.setFrom(projectSource)
    baseline = baselineFile
    parallel = true
    ignoreFailures = false
    autoCorrect = true
}

tasks.withType<Detekt>().configureEach {
    // Ignore generated files from the build directory, e.g files created by ksp.
    exclude(detektExcludedPaths)
}

tasks.withType<DetektCreateBaselineTask>().configureEach {
    // Ignore generated files from the build directory, e.g files created by ksp.
    exclude(detektExcludedPaths)
}

allprojects {
    apply(plugin = Dependencies.Plugin.dependencyCheckId)
    apply(plugin = Dependencies.Plugin.ktfmtId)

    repositories {
        google()
        mavenCentral()
    }

    configure<org.owasp.dependencycheck.gradle.extension.DependencyCheckExtension> {
        failBuildOnCVSS = 0F // All severity levels
        suppressionFile = "${rootProject.projectDir}/config/dependency-check-suppression.xml"
    }

    configure<com.ncorti.ktfmt.gradle.KtfmtExtension> {
        kotlinLangStyle()
        maxWidth.set(100)
        removeUnusedImports.set(true)
    }
}

tasks.withType<DependencyUpdatesTask> {
    gradleReleaseChannel = "current"
    rejectVersionIf { candidate.version.isNonStableVersion() }
}

tasks.register("clean", Delete::class) { delete(rootProject.buildDir) }
