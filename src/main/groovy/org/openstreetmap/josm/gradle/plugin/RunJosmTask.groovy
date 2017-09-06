package org.openstreetmap.josm.gradle.plugin

import org.gradle.api.tasks.JavaExec

/**
 * A task that can execute a JOSM instance. Both the {@code runJosm} task and the {@code debugJosm} task extend this type of task.
 */
class RunJosmTask extends JavaExec {
  def String extraInformation = ''
  public RunJosmTask() {
    group 'JOSM'
    main 'org.openstreetmap.josm.gui.MainApplication'
    args (project.hasProperty('josmArgs') ? project.josmArgs.split('\\\\') : [])
    shouldRunAfter project.tasks.cleanJosm

    dependsOn project.tasks.updateJosmPlugins
    project.gradle.projectsEvaluated {
      systemProperties['josm.home'] = project.josm.tmpJosmHome
      classpath = project.sourceSets.main.runtimeClasspath

      doFirst {
        println "Running version ${project.version} of ${project.name}"
        println "\nUsing JOSM version " + project.josm.josmCompileVersion

        println '\nThese system properties are set:'
        for (def entry : systemProperties.entrySet()) {
          println entry.key + " = " + entry.value
        }

        if (args.size() <= 0) {
          println '\nNo command line arguments are passed to JOSM.\nIf you want to pass arguments to JOSM add \'-PjosmArgs="arg0\\\\arg1\\\\arg2\\\\..."\' when starting Gradle from the commandline (separate the arguments with double-backslashes).'
        } else {
          println '\nPassing these arguments to JOSM:'
          println args.join('\n')
        }

        print extraInformation

        println '\nOutput of JOSM starts with the line after the three equality signs\n==='
      }
    }
  }
}
