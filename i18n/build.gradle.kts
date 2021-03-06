import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.dokka.gradle.GradleSourceRootImpl
import org.openstreetmap.josm.gradle.plugin.Versions

plugins {
  `java-library`
  jacoco
}
apply(plugin = "kotlin")

dependencies {
  implementation(kotlin("stdlib-jdk8", Versions.kotlin))

  testImplementation("org.junit.jupiter", "junit-jupiter-api", Versions.junit)
  testRuntimeOnly("org.junit.jupiter", "junit-jupiter-engine", Versions.junit)
}

attachToRootProject(rootProject, project)

fun attachToRootProject(rootProj: Project, i18nProj: Project) {
  // Tasks of root project that depend on their counterparts in this subproject
  listOf("test", "compileKotlin", "jacocoTestReport").forEach {
    rootProj.tasks.getByName(it).dependsOn(i18nProj.tasks.getByName(it))
  }

  // Add to binary JAR of root project
  rootProj.tasks.withType(Jar::class).getByName(rootProj.sourceSets.main.get().jarTaskName) {
    dependsOn(i18nProj.tasks[i18nProj.sourceSets.main.get().compileJavaTaskName])
    doFirst {
      from(i18nProj.sourceSets.main.get().output)
    }
  }
  rootProj.sourceSets.main.get().compileClasspath += i18nProj.sourceSets.main.get().output

  // Add to sources JAR of root project
  rootProj.tasks.withType(Jar::class).getByName("publishPluginJar") {
    from(i18nProj.sourceSets.main.get().allSource)
  }

  // Include this subproject in JaCoCo report of root project
  rootProj.tasks.withType(JacocoReport::class).getByName("jacocoTestReport") {
    sourceSets(i18nProj.sourceSets.main.get())
    executionData(i18nProj.tasks.withType(JacocoReport::class)["jacocoTestReport"].executionData)
  }

  // Include this subproject in Dokka docs of root project
  rootProj.tasks.withType(DokkaTask::class).getByName("dokka") {
    configuration.sourceRoots.addAll(i18nProj.sourceSets.main.get().allSource.srcDirs.map { GradleSourceRootImpl().apply { path = it.path } })
  }
}
