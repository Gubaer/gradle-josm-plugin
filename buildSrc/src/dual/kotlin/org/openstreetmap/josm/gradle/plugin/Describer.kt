package org.openstreetmap.josm.gradle.plugin

import java.io.IOException

/**
 * Can describe the current state of a project directory with an identifier (e.g. version number or commit hash).
 */
interface Describer {
  /**
   * @param [dirty] iff set to true, the return value gets appended with "-dirty" if there are changes
   *   to the repository that are not recorded in the current revision of the VCS.
   * @param [trimLeading] iff set to true, the leading character (`r` for SVN, `v` for git) is not included in the returned version number
   * @return an identifier of the current version of the repository
   */
  @Throws(IOException::class)
  fun describe(dirty: Boolean = true, trimLeading: Boolean = false): String
}
