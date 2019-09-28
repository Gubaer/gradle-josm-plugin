package org.openstreetmap.josm.gradle.plugin.task.gitlab

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UnstableDefault
import kotlinx.serialization.json.Json
import kotlinx.serialization.list
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.RevTag
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskExecutionException
import org.gradle.execution.commandline.TaskConfigurationException
import java.io.File
import java.lang.NullPointerException
import java.net.URL
import javax.inject.Inject
import javax.net.ssl.HttpsURLConnection
import kotlin.math.max

private const val GITLAB_URL = "https://gitlab.com"
private const val GITLAB_API_URL = "$GITLAB_URL/api/v4"

/**
 * Creates a Gitlab release from an already existing package in the Gitlab maven repository
 *
 * @param version a function that returns the version number that should be released (e.g. `1.2.3`)
 * @param names a list of package names (e.g. `org/openstreetmap/josm/gradle-josm-plugin`)
 */
@UnstableDefault
open class PublishGitlabPackageAsRelease @Inject constructor(
  var version: () -> Any,
  var names: List<String>
) : DefaultTask(), Runnable {

  var artifactExtension: String = ".jar"

  @TaskAction
  override fun run() {
    val projectId = System.getenv("CI_PROJECT_ID")?.toIntOrNull()
      ?: throw TaskConfigurationException(path, "Gitlab project ID is not set, use the environment variable CI_PROJECT_ID to set it!", NullPointerException())

    val projectPathSlug: String = System.getenv("CI_PROJECT_PATH")
      ?: throw TaskConfigurationException(path, "Gitlab project path slug not set, use the environment variable CI_PROJECT_PATH to set it!", NullPointerException())

    val version: String = this.version.invoke().toString()

    // Find git release
    val repo = FileRepositoryBuilder.create(File(project.rootProject.projectDir, "/.git"))
    val revTag: RevTag = Git(repo).tagList().call()
      .filter { it.name == "refs/tags/v$version" }
      .map { RevWalk(repo).parseTag(it.objectId) }
      .firstOrNull() ?: throw TaskExecutionException(this, GradleException("No git tag found for version v$version!"))

    logger.lifecycle("Found git tag ${revTag.tagName}.")

    // Find all matching packages available on GitLab
    val assetLinks = Json.nonstrict.parse(Package.serializer().list, URL("$GITLAB_API_URL/projects/$projectId/packages").openStream().bufferedReader().readText())
      .filter { version == it.version && names.contains(it.name) }
      .flatMap { packg ->
        Json.nonstrict.parse(PackageFile.serializer().list, URL("$GITLAB_API_URL/projects/$projectId/packages/${packg.id}/package_files").openStream().bufferedReader().readText())
          .filter { it.fileName.endsWith(artifactExtension) }
          .map { ReleaseAssetLink(it.fileName, "$GITLAB_URL/$projectPathSlug/-/package_files/${it.id}/download") }
      }
    logger.lifecycle("Found ${assetLinks.size} release assets on GitLab: ${assetLinks.map { it.name }.joinToString()}")

    // Create GitLab release
    (URL("$GITLAB_API_URL/projects/$projectId/releases").openConnection() as HttpsURLConnection).also { connection ->
      connection.requestMethod = "POST"
      connection.doOutput = true
      connection.doInput = true
      connection.setRequestProperty("Content-Type", "application/json")
      System.getenv("CI_JOB_TOKEN")?.also {
        connection.setRequestProperty("Job-Token", it)
      }
      System.getenv("PRIVATE_TOKEN")?.also {
        connection.setRequestProperty("Private-Token", it)
      }

      connection.outputStream.use {
        it.write(Json.stringify(Release.serializer(), Release(
          revTag.shortMessage,
          "v$version",
          revTag.fullMessage
            .substring(max(0, revTag.fullMessage.indexOf('\n') + 1))
            .replace("-----BEGIN PGP SIGNATURE-----", "\n```\n-----BEGIN PGP SIGNATURE-----")
            .replace("-----END PGP SIGNATURE-----", "-----END PGP SIGNATURE-----\n```")
            .trim(),
          ReleaseAssets(assetLinks)
        )).toByteArray())
      }
      require(connection.responseCode == 201) {
        "GitLab responded with: ${connection.responseCode} ${connection.responseMessage}"
      }
    }
  }

  @Serializable
  private data class Package(
    val id: Int,
    val name: String,
    val version: String?
  )

  @Serializable
  private data class PackageFile(
    val id: Int,
    @SerialName("package_id") val packageId: Int,
    @SerialName("file_name") val fileName: String
  )

  @Serializable
  private data class ReleaseAssetLink(
    val name: String,
    val url: String
  )

  @Serializable
  private data class ReleaseAssets(val links: List<ReleaseAssetLink>)

  @Serializable
  private data class Release(
    val name: String,
    @SerialName("tag_name") val tagName: String,
    val description: String,
    val assets: ReleaseAssets
  )
}