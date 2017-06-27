# Gradle plugin for developing JOSM plugins

## Getting started
### Prerequisites
Naturally this plugin only makes sense, if you build your JOSM plugin with Gradle.

### Setup

Add the following to the beginning of your `build.gradle` file:

```gradle
buildscript {
  repository {
    maven {
      url "https://floscher.github.io/gradle-josm-plugin/maven"
    }
  }
  dependencies {
    classpath 'org.openstreetmap.josm:gradle-plugin:0.1.0'
  }
}
apply plugin: 'org.openstreetmap.josm.gradle.plugin'
```

### Configuration

You'll need to add JOSM as a dependency:
```gradle
dependencies {
  implementation(name: 'josm', version: '12345')
}
```
Instead of `12345`, please use any (up-to-date) version of JOSM available [for download as *.jar](https://josm.openstreetmap.de/download).

Then also add the following:
```gradle
josm {
  jarName 'MyAwesomePluginName.jar'
}
```
This tells us, how the resulting \*.jar file will be called in the plugins directory.

There are more configuration options you can set in that josm{} block, see [the documentation](https://floscher.github.io/gradle-josm-plugin/groovydoc/org/openstreetmap/josm/gradle/plugin/JosmPluginExtension.html#prop_detail) for all available options.

If your JOSM plugin requires other JOSM plugins, simply add them to your dependencies:
```gradle
dependencies {
  requiredPlugin(name: 'NameOfTheAwesomePluginIDependOn'){changing=true}
}
```

### Usage

The main point of using this plugin is, that it allows you to easily fire up a JOSM instance with the current state of your JOSM plugin.

Simply run `./gradlew runJosm` and Gradle does the following for you:
* compiles your JOSM plugin into a \*.jar file
* creates a separate JOSM home directory (`$projectDir/build/.josm/`) in order not to interfere with other preexisting JOSM installations on your system
* puts the JOSM plugins you require into the plugins directory of the separate JOSM home directory
* puts your JOSM plugin also into the plugins directory
* starts the specific JOSM version, which you told Gradle you want to implement and compile against

By default the separate JOSM home directory is kept between separate executions of the `runJosm` task. If you want to regenerate it, execute the `cleanJosm` task.

For external debugging (e.g. using Eclipse), you can use the task `debugJosm`.

## Projects using this Gradle plugin
* [JOSM/Mapillary](https://github.com/JOSM/Mapillary)
