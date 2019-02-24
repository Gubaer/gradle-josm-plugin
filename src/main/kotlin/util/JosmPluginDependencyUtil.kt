package org.openstreetmap.josm.gradle.plugin.util

/**
 * Extends [Project] with methods specific to JOSM development.
 */

import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.IvyArtifactRepository
import org.gradle.api.plugins.Convention
import org.gradle.api.plugins.ExtensionContainer
import org.gradle.api.plugins.JavaPluginConvention
import org.openstreetmap.josm.gradle.plugin.config.JosmManifest
import org.openstreetmap.josm.gradle.plugin.config.JosmPluginExtension
import org.openstreetmap.josm.gradle.plugin.io.JosmPluginListParser
import java.io.IOException
import java.util.jar.Manifest
import java.util.zip.ZipFile
import kotlin.math.max

fun RepositoryHandler.josmPluginList(onlyForConfig: Configuration, dependency: Dependency): IvyArtifactRepository = this.ivy { repo ->
  repo.url = Urls.MainJosmWebsite.BASE.toURI()
  repo.patternLayout {
    it.artifact("[artifact]")
  }
  repo.content {
    it.onlyForConfigurations(onlyForConfig.name)
    it.includeModule(dependency.group ?: "", dependency.name)
  }
}

fun DependencyHandler.josmPluginList(withIcons: Boolean): ExternalModuleDependency =
  (this.create("$GROUP_METADATA:$ARTIFACT_PLUGIN_LIST:$VERSION_SNAPSHOT") as ExternalModuleDependency)
    .also { dep ->
      dep.isChanging = true
      dep.artifact {
        it.type = "txt"
        it.name = if (withIcons) {
          Urls.MainJosmWebsite.PATH_PLUGIN_LIST_WITH_ICONS
        } else {
          Urls.MainJosmWebsite.PATH_PLUGIN_LIST
        }
      }
    }

/**
 * @return a map with the virtual plugin names as key, the values are a list of pairs, where the first element
 * is the platform, the seconds element of the pairs is the name of the real plugin providing the virtual plugin.
 */
fun Project.getVirtualPlugins(): Map<String, List<Pair<String, String>>> = try {
  val parser = JosmPluginListParser(this, true)
  val result = parser
    .plugins
    .mapNotNull {
      it.manifestAtts["Plugin-Platform"]?.let { platform ->
        it.manifestAtts["Plugin-Provides"]?.let { provides ->
          Triple(provides, platform, if (it.pluginName.endsWith(".jar")) it.pluginName.substring(0 until it.pluginName.length - 4) else it.pluginName)
        }
      }
    }
    .groupBy({ it.first }, { Pair(it.second, it.third) })
  if (parser.errors.isNotEmpty()) {
    logger.warn("WARN: There were issues parsing the JOSM plugin list:\n * " + parser.errors.joinToString("\n * "))
  }

  result
} catch (e: IOException) {
  logger.warn("WARN: Virtual plugins cannot be resolved, since the plugin list can't be read from the web!")
  mapOf()
}

/**
 * Resolves the JOSM plugin names given as parameter, using the available repositories for this project.
 * Not only are the given plugin names resolved to Dependencies, but also all JOSM plugins on which these plugins depend.
 *
 * The resolution is aborted, if a dependency chain exceeds 10 plugins (plugin 1 depends on plugin 2 … depends on plugin 10). This limit can be changed by [JosmPluginExtension.maxPluginDependencyDepth]
 * @param [directlyRequiredPlugins] a [Set] of [String]s representing the names of JOSM plugins.
 *   These plugins (and their dependencies) will be resolved
 * @return a set of [Dependency] objects, including the requested plugins, plus all plugins required by the requested
 *   plugins
 */
fun Project.getAllRequiredJosmPlugins(directlyRequiredPlugins: Collection<String>): Set<Dependency> =
  if (directlyRequiredPlugins.isNullOrEmpty()) {
    logger.info("No other JOSM plugins required by this plugin.")

    setOf()
  } else {
    logger.lifecycle("Resolving required JOSM plugins…")
    val result = getAllRequiredJosmPlugins(0, mutableSetOf(), directlyRequiredPlugins.toSet())
    logger.lifecycle(" → {} JOSM {} required: {}", result.size, if (result.size == 1) "plugin is" else "plugins are", result.map { it.name }.sorted().joinToString())

    result
  }

private fun Project.getAllRequiredJosmPlugins(recursionDepth: Int, alreadyResolvedPlugins: MutableSet<String>, directlyRequiredPlugins: Set<String>): Set<Dependency> {
  val realRecursionDepth = max(0, recursionDepth)
  if (realRecursionDepth >= extensions.josm.maxPluginDependencyDepth) {
    throw GradleException(
      "Dependency tree of required JOSM plugins is too deep (>= %d steps). Aborting resolution of required JOSM plugins."
        .format(extensions.josm.maxPluginDependencyDepth)
    )
  }

  val virtualPlugins = getVirtualPlugins()

  val indentation = "  ".repeat(maxOf(0, recursionDepth))
  val result = HashSet<Dependency>()
  for (pluginName in directlyRequiredPlugins) {
    if (alreadyResolvedPlugins.contains(pluginName)) {
      logger.info("{}* {} (see above for dependencies)", indentation, pluginName)
    } else if (virtualPlugins.containsKey(pluginName)) {
      val suitableImplementation = virtualPlugins.getValue(pluginName).firstOrNull {
        when (it.first.toUpperCase()) {
          JosmManifest.Platform.UNIXOID.toString() -> Os.isFamily(Os.FAMILY_UNIX)
          JosmManifest.Platform.OSX.toString() -> Os.isFamily(Os.FAMILY_MAC)
          JosmManifest.Platform.WINDOWS.toString() -> Os.isFamily(Os.FAMILY_WINDOWS) || Os.isFamily(Os.FAMILY_9X) || Os.isFamily(Os.FAMILY_NT)
          else -> false
        }
      }

      if (suitableImplementation == null) {
        logger.warn("WARN: No suitable implementation found for virtual JOSM plugin $pluginName!")
      } else {
        alreadyResolvedPlugins.add(pluginName)
        logger.info("{}* {} (virtual): provided by {}", indentation, pluginName, virtualPlugins.getValue(pluginName).joinToString { "${it.second} for ${it.first}" })
        result.addAll(getAllRequiredJosmPlugins(realRecursionDepth + 1, alreadyResolvedPlugins, setOf(suitableImplementation.second)))
      }
    } else {
      val dep = dependencies.createJosmPlugin(pluginName)
      val resolvedFiles = configurations.detachedConfiguration(dep).files
      alreadyResolvedPlugins.add(pluginName)
      for (file in resolvedFiles) {
        logger.info("{}* {}", indentation, pluginName)
        ZipFile(file).use {
          val entries = it.entries()
          while (entries.hasMoreElements()) {
            val zipEntry = entries.nextElement()
            if ("META-INF/MANIFEST.MF" == zipEntry.name) {
              val requirements = Manifest(it.getInputStream(zipEntry)).mainAttributes.getValue("Plugin-Requires")
                ?.split(";")
                ?.map { it.trim() }
                ?.toSet()
                ?: setOf()
              result.addAll(getAllRequiredJosmPlugins(
                realRecursionDepth + 1,
                alreadyResolvedPlugins,
                requirements
              ))
            }
          }
        }
      }
      result.add(dep)
    }
  }
  return result
}

/**
 * Access method for the `project.josm{}` extension.
 * @return the [JosmPluginExtension] for this project.
 */
val ExtensionContainer.josm : JosmPluginExtension
  get() = getByType(JosmPluginExtension::class.java)

/**
 * Convenience method to access the Java plugin convention.
 * @return the [JavaPluginConvention] of the project
 */
val Convention.java : JavaPluginConvention
  get() = getPlugin(JavaPluginConvention::class.java)