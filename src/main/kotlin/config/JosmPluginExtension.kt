package org.openstreetmap.josm.gradle.plugin.config

import groovy.lang.Closure
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.IvyArtifactRepository
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.plugins.BasePluginConvention
import org.gradle.api.tasks.util.PatternFilterable
import org.openstreetmap.josm.gradle.plugin.util.ARTIFACT_JOSM
import org.openstreetmap.josm.gradle.plugin.util.ARTIFACT_JOSM_UNITTEST
import org.openstreetmap.josm.gradle.plugin.util.GROUP_JMAPVIEWER
import org.openstreetmap.josm.gradle.plugin.util.GROUP_JOSM
import org.openstreetmap.josm.gradle.plugin.util.GROUP_JOSM_PLUGIN
import org.openstreetmap.josm.gradle.plugin.util.Urls
import org.openstreetmap.josm.gradle.plugin.util.josm
import java.io.File

/**
 * This extension is added to the project as `project.josm`
 *
 * @constructor instantiates the extension, takes project properties into account
 */
open class JosmPluginExtension(val project: Project) {
  var pluginName
    get() = project.convention.getPlugin(BasePluginConvention::class.java).archivesBaseName
    set(value) {
      project.convention.getPlugin(BasePluginConvention::class.java).archivesBaseName = value
    }

  /**
   * The version number of JOSM against which the plugin should be compiled.
   *
   * **Default value:** The value of the property `plugin.compile.version` or `null` if that property is not set.
   */
  var josmCompileVersion: String? = project.findProperty("plugin.compile.version")?.toString()

  /**
   * This is required, when you want to run the task `debugJosm`. Set this to the port
   * on which you want to listen for the debug output.
   *
   * **Default value:** `null`
   */
  var debugPort: Int? = null

  /**
   * Iff this is `true`, then after the build has finished, all skipped tasks are listed like this:
   * ```
   * Skipped tasks:
   * ⏭️  :taskA (UP-TO-DATE)
   * ⏭️  :taskB (NO-SOURCE)
   * ⏭️  :taskC (FROM-CACHE)
   * ```
   * The message is printed via [Project.getLogger], logging level is [org.gradle.api.logging.Logger.lifecycle].
   *
   * **Default value:** `true`
   * @since 0.5.1
   */
  var logSkippedTasks: Boolean = true

  /**
   * Iff this is `true`, the coverage numbers for instruction, branch and line coverage are logged for every tasks
   * with type [org.gradle.testing.jacoco.tasks.JacocoReport].
   *
   * The format is as follows:
   * ```
   * Instruction coverage: 25.0000 % (25 of 100)
   *      Branch coverage: 50.0000 % (5 of 10)
   *        Line coverage: 75.0000 % (3 of 4)
   * ```
   * The message is printed via [Project.getLogger], logging level is [org.gradle.api.logging.Logger.lifecycle].
   *
   * **Default value:** `true`
   * @since 0.5.1
   */
  var logJacocoCoverage: Boolean = true

  /**
   * Iff this is `true`, the number of seconds each task needed for execution is logged,
   * when execution of a task finishes.
   *
   * The line appended to the logging output of each task looks as follows:
   * ```
   *   🏁 Finished after 1.23 seconds.
   * ```
   * The message is printed via [Project.getLogger], logging level is [org.gradle.api.logging.Logger.lifecycle].
   *
   * **Default value:** `true`
   * @since 0.5.1
   */
  var logTaskDuration: Boolean = true

  /**
   * The directory in which the JOSM preferences for the JOSM instance used by `runJosm` and `debugJosm` are stored.
   *
   * **Default value:** `$buildDir/.josm/pref`
   * @since 0.4.0
   */
  var tmpJosmPrefDir = File(project.buildDir, ".josm/pref")

  /**
   * The directory in which the JOSM cache for the JOSM instance used by `runJosm` and `debugJosm` is stored.
   *
   * **Default value:** `$buildDir/.josm/cache` (for JOSM versions < 7841, `${josm.tmpJosmPrefDir}/cache` is used)
   * @since 0.4.0
   */
  var tmpJosmCacheDir = File(project.buildDir, ".josm/cache")

  /**
   * The directory in which the JOSM user data for the JOSM instance used by `runJosm` and `debugJosm` is stored.
   *
   * **Default value:** `$buildDir/.josm/userdata` (for JOSM versions < 7841,  `${josm.tmpJosmPrefDir}` is used)
   * @since 0.4.0
   */
  var tmpJosmUserdataDir = File(project.buildDir, ".josm/userdata")

  init {
    project.afterEvaluate {
      if (!useSeparateTmpJosmDirs()) {
        it.logger.warn("You are using a very old version of JOSM (< 7841) that doesn't have separate directories for cache and user data.")
        project.extensions.josm.tmpJosmCacheDir = File(project.extensions.josm.tmpJosmPrefDir, "cache")
        project.extensions.josm.tmpJosmUserdataDir = project.extensions.josm.tmpJosmPrefDir
        it.logger.warn("These settings are now overwritten as follows: tmpJosmCacheDir=${tmpJosmCacheDir.absolutePath} tmpJosmUserdataDir=${tmpJosmUserdataDir.absolutePath}")
      }
    }
  }

  /**
   * The directory where the default `preferences.xml` file is located, which will be used
   * when the directory defined in `tmpJosmHome` is empty.
   *
   * **Default value:** `$projectDir/config/josm`
   */
  var josmConfigDir: File = File("${project.projectDir}/config/josm")

  /**
   * When determining on which JOSM plugins this project depends, dependency chains are followed this number of steps.
   * This number is the termination criterion when recursively searching for JOSM plugins that are required through [JosmManifest.pluginDependencies].
   */
  var maxPluginDependencyDepth: Int = 10
    set(value) {
      require(value >= 0) {
        "For property `maxPluginDependencyDepth` only nonnegative integer values are allowed! You are trying to set it to $value."
      }
      field = value
    }

  /**
   * This determines how many JOSM versions are tried out before giving up when a JOSM version is specified that can't
   * be downloaded through [the download page](https://josm.openstreetmap.de/download).
   *
   * The only place where we'll pick one of the following version numbers and not the exact one,
   * is [JosmManifest.minJosmVersion]. The [JosmPluginExtension.josmCompileVersion] is always used as-is.
   *
   * Say you set [JosmManifest.minJosmVersion]=1234 and [josmVersionFuzziness]=20 ,
   * then we use the first version between and including 1234 and 1253 that can be downloaded will be used.
   *
   * **Default value:** 30
   * @since 0.6.1
   */
  var josmVersionFuzziness: Int = 30
    set(value) {
      require(value >= 0) {
        "For property `josmVersionFuzziness` only nonnegative integer values are allowed! You are trying to set it to $value."
      }
      field = value
    }

  /**
   * When packing the dependencies of the `packIntoJar` configuration into the distribution *.jar,
   * this closure is applied to the file tree. If you want to exclude certain files in your dependencies into
   * your release, you can modify this.
   * By default the `/META-INF/` directory of dependencies is discarded.
   *
   * **Default value:** `{ it.exclude("META-INF/&#42;&#42;/&#42;") }`
   * @see org.gradle.api.file.FileTree.matching(groovy.lang.Closure)
   * @see PatternFilterable
   */
  var packIntoJarFileFilter: (PatternFilterable) -> PatternFilterable = { it.exclude("META-INF/**/*") }

  /**
   * Set the [packIntoJarFileFilter] with a Groovy [Closure]
   */
  fun packIntoJarFileFilter(closure: Closure<PatternFilterable>) {
    packIntoJarFileFilter = { closure.call(it) }
  }

  /**
   * Repositories to which the artifacts can be published.
   * GitLab package repositories can be added via [org.openstreetmap.josm.gradle.plugin.gitlab.gitlabRepository]
   *
   * **Default value:** a Maven repository in `$buildDir/maven`
   * @since 0.6.2
   */
  var publishRepositories: (RepositoryHandler) -> Unit = {
    it.maven {
      it.url = project.uri("${project.buildDir}/maven")
      it.name = "buildDir"
    }
  }

  /**
   * The repositories that are added to the repository list.
   *
   * **Default value (in this order):**
   * 1. Nexus repo for JOSM releases: [https://josm.openstreetmap.de/nexus/content/repositories/releases/](https://josm.openstreetmap.de/nexus/content/repositories/releases/) (as [MavenArtifactRepository])
   * 2. Download page for JOSM releases and snapshots: [https://josm.openstreetmap.de/download/](https://josm.openstreetmap.de/download/) (as custom [IvyArtifactRepository], the `Archiv` subdirectory is also included)
   * 3. Nexus repo for JOSM snapshots: [https://josm.openstreetmap.de/nexus/content/repositories/snapshots/](https://josm.openstreetmap.de/nexus/content/repositories/snapshots/) (as [MavenArtifactRepository])
   * 4. Directory in SVN repo where JOSM plugins are published: [https://svn.openstreetmap.org/applications/editors/josm/dist/](https://svn.openstreetmap.org/applications/editors/josm/dist/) (as custom [IvyArtifactRepository])
   * 5. GitLab Maven repository containing some plugins that are neither in SVN nor in the Nexus repository: [https://gitlab.com/api/v4/groups/JOSM/-/packages/maven](https://gitlab.com/api/v4/groups/JOSM/-/packages/maven) (as [MavenArtifactRepository])
   *
   * @see RepositoryHandler
   */
  var repositories: (RepositoryHandler) -> Unit = { rh ->
    rh.maven { repo ->
      repo.url = Urls.MainJosmWebsite.NEXUS_REPO_RELEASES.toURI()
      repo.content {
        it.includeGroup(GROUP_JOSM)
        it.includeGroup(GROUP_JMAPVIEWER)
      }
    }
    rh.ivy { repo ->
      repo.url = Urls.MainJosmWebsite.DOWNLOADS.toURI()
      repo.content {
        it.includeModule(GROUP_JOSM, ARTIFACT_JOSM)
        it.includeModule(GROUP_JOSM, ARTIFACT_JOSM_UNITTEST)
      }
      repo.patternLayout {
        it.artifact("[artifact].jar")
        it.artifact("[artifact]-[revision].jar")
        it.artifact("[artifact]-snapshot-[revision].jar")
        it.artifact("Archiv/[artifact]-snapshot-[revision].jar")
      }
      repo.metadataSources {
        it.artifact()
      }
    }
    rh.maven { repo ->
      repo.url = Urls.MainJosmWebsite.NEXUS_REPO_SNAPSHOTS.toURI()
      repo.content {
        it.includeGroup(GROUP_JOSM)
        it.includeGroup(GROUP_JOSM_PLUGIN)
      }
    }
    rh.ivy { repo ->
      repo.url = Urls.JOSM_PLUGIN_DIST.toURI()
      repo.content {
        it.includeGroup(GROUP_JOSM_PLUGIN)
      }
      repo.patternLayout {
        it.artifact("[artifact].jar")
      }
      repo.metadataSources {
        it.artifact()
      }
    }
    rh.maven { repo ->
      repo.url = Urls.GITLAB_JOSM_PLUGINS_REPO.toURI()
      repo.content {
        it.includeGroup(GROUP_JOSM_PLUGIN)
      }
    }
  }

  /**
   * Set the [repositories] with a Groovy [Closure] (replaces any previous setting).
   */
  fun repositories(closure: Closure<RepositoryHandler>) {
    repositories = { closure.call(it) }
  }

  /**
   * Configuration options for GitHub releases
   *
   * @since 0.5.3
   */
  val github: GithubConfig = GithubConfig(project)

  /**
   * Configure the field [JosmPluginExtension.github] using a Groovy [Closure].
   *
   * @since 0.5.3
   */
  fun github(c: Closure<GithubConfig>): Any = project.configure(github, c)

  /**
   * Configure the field [JosmPluginExtension.github] using an [Action].
   *
   * @since 0.5.3
   */
  fun github(a: Action<GithubConfig>) = a.execute(github)

  /**
   * Configuration options for GitLab repositories for your project
   *
   * @since 0.6.2
   */
  val gitlab: GitlabConfig = GitlabConfig(project)

  /**
   * Configure the field [JosmPluginExtension.gitlab] using a Groovy [Closure]
   *
   * @since 0.6.2
   */
  fun gitlab(c: Closure<GitlabConfig>): Any = project.configure(gitlab, c)

  /**
   * Configure the field [JosmPluginExtension.gitlab] using an [Action]
   *
   * @since 0.6.2
   */
  fun gitlab(a: Action<GitlabConfig>) = a.execute(gitlab)

  /**
   * Configuration options for i18n
   */
  val i18n: I18nConfig = I18nConfig(project)

  /**
   * Configure the field [JosmPluginExtension.i18n] using a Groovy [Closure].
   */
  fun i18n(c: Closure<I18nConfig>) {
    project.configure(i18n, c)
  }

  /**
   * Configure the field [JosmPluginExtension.i18n] using an [Action].
   */
  fun i18n(a: Action<I18nConfig>) {
    a.execute(i18n)
  }

  /**
   * The manifest for the JOSM plugin
   */
  val manifest: JosmManifest = JosmManifest(project)

  /**
   * Configure the field [manifest] using a Groovy [Closure].
   */
  fun manifest(c: Closure<JosmManifest>) {
    project.configure(manifest, c)
  }

  /**
   * Configure the field [manifest] using an [Action].
   */
  fun manifest(a: Action<JosmManifest>) {
    a.execute(manifest)
  }

  /**
   * Determines if the leading 'v' character is trimmed from the beginning of [Project.getVersion]
   *
   * **Default value:** `true` (before `0.6.0` this was `false` by default)
   * @since 0.5.2
   */
  var versionWithoutLeadingV = true

  /**
   * @return true if a JOSM version of 7841 or later is used that can be configured to use separate directories for cache, preferences and userdata
   */
  fun useSeparateTmpJosmDirs(): Boolean = josmCompileVersion?.toIntOrNull()?.let { it >= 7841 } ?: true
}
