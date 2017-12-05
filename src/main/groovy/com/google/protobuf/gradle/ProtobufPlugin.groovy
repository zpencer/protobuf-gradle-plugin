/*
 * Original work copyright (c) 2015, Alex Antonov. All rights reserved.
 * Modified work copyright (c) 2015, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.google.protobuf.gradle

import com.google.common.collect.ImmutableList

import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.file.FileCollection
import org.gradle.api.internal.file.FileResolver
import org.gradle.api.logging.LogLevel
import org.gradle.api.plugins.AppliedPlugin
import org.gradle.api.tasks.SourceSet

import javax.inject.Inject

/**
 * The main class for the protobuf plugin.
 */
class ProtobufPlugin implements Plugin<Project> {
    // any one of these plugins should be sufficient to proceed with applying this plugin
    private static final List<String> PREREQ_PLUGIN_OPTIONS = [
            'java',
            'com.android.application',
            'com.android.feature',
            'com.android.library',
            'android',
            'android-library',
    ]

    private static final String userLanguageProperty = 'protobuf_gradle_plugin_additional_languages'
    private static final List<String> SUPPORTED_LANGUAGES = [
        'java',
        'kotlin'
    ]

    private static List<String> getLanguages(Project project) {
      List<String> additionalLanguages = []
      if (project.hasProperty(userLanguageProperty)) {
        additionalLanguages = (List<String>) project.property(userLanguageProperty)
        project.logger.log(
            LogLevel.WARN,
            "protobuf plugin is now using additional unsupported languages: " + additionalLanguages)
      }
      return SUPPORTED_LANGUAGES + additionalLanguages
    }

    private final FileResolver fileResolver
    private Project project
    private boolean wasApplied = false

    @Inject
    public ProtobufPlugin(FileResolver fileResolver) {
      this.fileResolver = fileResolver
    }

    void apply(final Project project) {
        this.project = project
        // At least one of the prerequisite plugins must by applied before this plugin can be applied, so
        // we will use the PluginManager.withPlugin() callback mechanism to delay applying this plugin until
        // after that has been achieved. If project evaluation completes before one of the prerequisite plugins
        // has been applied then we will assume that none of prerequisite plugins were specified and we will
        // throw an Exception to alert the user of this configuration issue.
        Action<? super AppliedPlugin> applyWithPrerequisitePlugin = { prerequisitePlugin ->
          if (wasApplied) {
            project.logger.warn('The com.google.protobuf plugin was already applied to the project: ' + project.path
                + ' and will not be applied again after plugin: ' + prerequisitePlugin.id)

          } else {
            wasApplied = true

            doApply()
          }
        }

        PREREQ_PLUGIN_OPTIONS.each { pluginName ->
          project.pluginManager.withPlugin(pluginName, applyWithPrerequisitePlugin)
        }

        project.afterEvaluate {
          if (!wasApplied) {
            throw new GradleException('The com.google.protobuf plugin could not be applied during project evaluation.'
                + ' The Java plugin or one of the Android plugins must be applied to the project first.')
          }
        }
    }

    private void doApply() {
        // Provides the osdetector extension
        project.apply([plugin:'com.google.osdetector'])

        project.convention.plugins.protobuf = new ProtobufConvention(project, fileResolver)

        addSourceSetExtensions()
        getSourceSets().all { sourceSet ->
          createConfiguration(sourceSet.name)
        }
        project.afterEvaluate {
          // The Android variants are only available at this point.
          addProtoTasks()
          project.protobuf.runTaskConfigClosures()
          // Disallow user configuration outside the config closures, because
          // next in linkGenerateProtoTasksToJavaCompile() we add generated,
          // outputs to the inputs of javaCompile tasks, and any new codegen
          // plugin output added after this point won't be added to javaCompile
          // tasks.
          project.protobuf.generateProtoTasks.all()*.doneConfig()
          linkGenerateProtoTasksToJavaCompile()
          // protoc and codegen plugin configuration may change through the protobuf{}
          // block. Only at this point the configuration has been finalized.
          project.protobuf.tools.registerTaskDependencies(project.protobuf.getGenerateProtoTasks().all())
        }
    }

    /**
     * Creates a configuration if necessary for a source set so that the build
     * author can configure dependencies for it.
     */
    private createConfiguration(String sourceSetName) {
      String configName = Utils.getConfigName(sourceSetName, 'protobuf')
      if (project.configurations.findByName(configName) == null) {
        project.configurations.create(configName) {
          visible = false
          transitive = true
          extendsFrom = []
        }
      }
    }

    /**
     * Adds the proto extension to all SourceSets, e.g., it creates
     * sourceSets.main.proto and sourceSets.test.proto.
     */
    private addSourceSetExtensions() {
      getSourceSets().all {  sourceSet ->
        sourceSet.extensions.create('proto', ProtobufSourceDirectorySet, sourceSet.name, fileResolver)
      }
    }

    /**
     * Returns the sourceSets container of a Java or an Android project.
     */
    private Object getSourceSets() {
      if (Utils.isAndroidProject(project)) {
        return project.android.sourceSets
      }
      return project.sourceSets
    }

    private Object getNonTestVariants() {
      return project.android.hasProperty('libraryVariants') ?
          project.android.libraryVariants : project.android.applicationVariants
    }

    /**
     * Adds Protobuf-related tasks to the project.
     */
    private addProtoTasks() {
      if (Utils.isAndroidProject(project)) {
        getNonTestVariants().each { variant ->
          addTasksForVariant(variant, false)
        }
        project.android.testVariants.each { testVariant ->
          addTasksForVariant(testVariant, true)
        }
      } else {
        getSourceSets().each { sourceSet ->
          addTasksForSourceSet(sourceSet)
        }
      }
    }

    /**
     * Creates Protobuf tasks for a sourceSet in a Java project.
     */
    private addTasksForSourceSet(final SourceSet sourceSet) {
      Task generateProtoTask = addGenerateProtoTask(sourceSet.name, [sourceSet])
      generateProtoTask.sourceSet = sourceSet
      generateProtoTask.doneInitializing()
      generateProtoTask.builtins {
        java { }
      }

      Task extractProtosTask = maybeAddExtractProtosTask(sourceSet.name)
      generateProtoTask.dependsOn(extractProtosTask)

      Task extractIncludeProtosTask = maybeAddExtractIncludeProtosTask(sourceSet.name)
      generateProtoTask.dependsOn(extractIncludeProtosTask)

      // Include source proto files in the compiled archive, so that proto files from
      // dependent projects can import them.
      Task processResourcesTask =
          project.tasks.getByName(sourceSet.getTaskName('process', 'resources'))
      processResourcesTask.from(generateProtoTask.inputs.sourceFiles) {
        include '**/*.proto'
      }
    }

    /**
     * Creates Protobuf tasks for a variant in an Android project.
     */
    private addTasksForVariant(final Object variant, final boolean isTestVariant) {
      // The collection of sourceSets that will be compiled for this variant
      List sourceSetNames = []
      List sourceSets = []
      if (isTestVariant) {
        // All test variants will include the androidTest sourceSet
        sourceSetNames.add 'androidTest'
      } else {
        // All non-test variants will include the main sourceSet
        sourceSetNames.add 'main'
      }
      sourceSetNames.add variant.name
      sourceSetNames.add variant.buildType.name
      ImmutableList.Builder<String> flavorListBuilder = ImmutableList.builder()
      if (variant.hasProperty('productFlavors')) {
        variant.productFlavors.each { flavor ->
          sourceSetNames.add flavor.name
          flavorListBuilder.add flavor.name
        }
      }
      sourceSetNames.each { sourceSetName ->
        sourceSets.add project.android.sourceSets.maybeCreate(sourceSetName)
      }

      Task generateProtoTask = addGenerateProtoTask(variant.name, sourceSets)
      generateProtoTask.setVariant(variant, isTestVariant)
      generateProtoTask.flavors = flavorListBuilder.build()
      generateProtoTask.buildType = variant.buildType.name
      generateProtoTask.doneInitializing()

      sourceSetNames.each { sourceSetName ->
        Task extractProtosTask = maybeAddExtractProtosTask(sourceSetName)
        generateProtoTask.dependsOn(extractProtosTask)
      }

      if (variant.hasProperty("compileConfiguration")) {
        // For Android Gradle plugin >= 2.5
        Attribute artifactType = Attribute.of("artifactType", String)
        String name = variant.name
        FileCollection classPathConfig = variant.compileConfiguration.incoming.artifactView {
          attributes {
              it.attribute(artifactType, "jar")
          }
        }.files
        FileCollection testClassPathConfig =
            variant.hasProperty("testedVariant") ?
              variant.testedVariant.compileConfiguration.incoming.artifactView {
                attributes {
                  it.attribute(artifactType, "jar")
                }
              }.files : null
        Task extractIncludeProtosTask = maybeAddExtractIncludeProtosTask(
            name, classPathConfig, testClassPathConfig)
        generateProtoTask.dependsOn(extractIncludeProtosTask)
      } else {
        // For Android Gradle plugin < 2.5
        sourceSetNames.each { sourceSetName ->
          Task extractIncludeProtosTask =
              maybeAddExtractIncludeProtosTask(sourceSetName)
          generateProtoTask.dependsOn(extractIncludeProtosTask)
        }
      }

      // TODO(zhangkun83): Include source proto files in the compiled archive,
      // so that proto files from dependent projects can import them.
    }

    /**
     * Adds a task to run protoc and compile all proto source files for a sourceSet or variant.
     *
     * @param sourceSetOrVariantName the name of the sourceSet (Java) or
     * variant (Android) that this task will run for.
     *
     * @param sourceSets the sourceSets that contains the proto files to be
     * compiled. For Java it's the sourceSet that sourceSetOrVariantName stands
     * for; for Android it's the collection of sourceSets that the variant includes.
     */
    private Task addGenerateProtoTask(String sourceSetOrVariantName, Collection<Object> sourceSets) {
      String generateProtoTaskName = 'generate' +
          Utils.getSourceSetSubstringForTaskNames(sourceSetOrVariantName) + 'Proto'
      return project.tasks.create(generateProtoTaskName, GenerateProtoTask) {
        description = "Compiles Proto source for '${sourceSetOrVariantName}'"
        outputBaseDir = "${project.protobuf.generatedFilesBaseDir}/${sourceSetOrVariantName}"
        sourceSets.each { sourceSet ->
          // Include sources
          Utils.addFilesToTaskInputs(project, inputs, sourceSet.proto)
          ProtobufSourceDirectorySet protoSrcDirSet = sourceSet.proto
          protoSrcDirSet.srcDirs.each { srcDir ->
            include srcDir
          }

          // Include extracted sources
          ConfigurableFileTree extractedProtoSources =
              project.fileTree(getExtractedProtosDir(sourceSet.name)) {
                include "**/*.proto"
              }
          Utils.addFilesToTaskInputs(project, inputs, extractedProtoSources)
          include extractedProtoSources.dir

          // Register extracted include protos
          ConfigurableFileTree extractedIncludeProtoSources =
              project.fileTree(getExtractedIncludeProtosDir(sourceSet.name)) {
                include "**/*.proto"
              }
          // Register them as input, but not as "source".
          // Inputs are checked in incremental builds, but only "source" files are compiled.
          inputs.dir extractedIncludeProtoSources
          // Add the extracted include dir to the --proto_path include paths.
          include extractedIncludeProtoSources.dir
        }
      }
    }

    /**
     * Adds a task to extract protos from protobuf dependencies. They are
     * treated as sources and will be compiled.
     *
     * <p>This task is per-sourceSet, for both Java and Android. In Android a
     * variant may have multiple sourceSets, each of these sourceSets will have
     * its own extraction task.
     */
    private Task maybeAddExtractProtosTask(String sourceSetName) {
      String extractProtosTaskName = 'extract' +
          Utils.getSourceSetSubstringForTaskNames(sourceSetName) + 'Proto'
      Task existingTask = project.tasks.findByName(extractProtosTaskName)
      if (existingTask != null) {
        return existingTask
      }
      return project.tasks.create(extractProtosTaskName, ProtobufExtract) {
        description = "Extracts proto files/dependencies specified by 'protobuf' configuration"
        destDir = getExtractedProtosDir(sourceSetName) as File
        inputs.files project.configurations[Utils.getConfigName(sourceSetName, 'protobuf')]
      }
    }

    /**
     * Adds a task to extract protos from compile dependencies of a sourceSet,
     * if there isn't one. Those are needed for imports in proto files, but
     * they won't be compiled since they have already been compiled in their
     * own projects or artifacts.
     *
     * <p>This task is per-sourceSet for both Java and per variant for Android.
     */
    private Task maybeAddExtractIncludeProtosTask(
        String sourceSetOrVariantName,
        FileCollection compileClasspathConfiguration = null,
        FileCollection testedCompileClasspathConfiguration = null) {
      String extractIncludeProtosTaskName = 'extractInclude' +
          Utils.getSourceSetSubstringForTaskNames(sourceSetOrVariantName) + 'Proto'
      Task existingTask = project.tasks.findByName(extractIncludeProtosTaskName)
      if (existingTask != null) {
        return existingTask
      }
      return project.tasks.create(extractIncludeProtosTaskName, ProtobufExtract) {
        description = "Extracts proto files from compile dependencies for includes"
        destDir = getExtractedIncludeProtosDir(sourceSetOrVariantName) as File
        inputs.files (compileClasspathConfiguration
          ?: project.configurations[Utils.getConfigName(sourceSetOrVariantName, 'compile')])

        // TL; DR: Make protos in 'test' sourceSet able to import protos from the 'main' sourceSet.
        // Sub-configurations, e.g., 'testCompile' that extends 'compile', don't depend on the
        // their super configurations. As a result, 'testCompile' doesn't depend on 'compile' and
        // it cannot get the proto files from 'main' sourceSet through the configuration. However,
        if (Utils.isAndroidProject(project)) {
          // TODO(zhangkun83): Android sourceSet doesn't have compileClasspath. If it did, we
          // haven't figured out a way to put source protos in 'resources'. For now we use an ad-hoc
          // solution that manually includes the source protos of 'main' and its dependencies.
          if (sourceSetOrVariantName.toLowerCase().contains('androidtest')) {
            inputs.files getSourceSets()['main'].proto
            inputs.files testedCompileClasspathConfiguration ?: project.configurations['compile']
          }
        } else {
          // In Java projects, the compileClasspath of the 'test' sourceSet includes all the
          // 'resources' of the output of 'main', in which the source protos are placed.
          // This is nicer than the ad-hoc solution that Android has, because it works for any
          // extended configuration, not just 'testCompile'.
          inputs.files getSourceSets()[sourceSetOrVariantName].compileClasspath
        }
      }
    }

    private linkGenerateProtoTasksToJavaCompile() {
      if (Utils.isAndroidProject(project)) {
        (getNonTestVariants() + project.android.testVariants).each { variant ->
          project.protobuf.generateProtoTasks.ofVariant(variant.name).each { generateProtoTask ->
            // This cannot be called once task execution has started
            variant.registerJavaGeneratingTask(generateProtoTask, generateProtoTask.getAllOutputDirs())
          }
        }
      } else {
        project.sourceSets.each { SourceSet sourceSet ->
          project.protobuf.generateProtoTasks.ofSourceSet(sourceSet.name).each { GenerateProtoTask genProto ->
            getLanguages(project).each { String lang ->
              project.tasks.findByName(sourceSet.getCompileTaskName(lang)).each { Task compileTask ->
                compileTask.dependsOn(genProto)
                genProto.getAllOutputDirs().each { dir ->
                  // The Java plugin requires the dir in FileTree form, but the Kotlin plugin requires
                  // a File. It appears safe to generically supply both here. This
                  // *may* also work for additional languages such as scala, but this theory
                  // is untested. Set the project property read in getSupportedLanguages to
                  // to add other languages to the whitelist.
                  compileTask.source project.fileTree([dir:dir])
                  compileTask.source dir
                }
              }
            }
          }
        }
      }
    }

    private String getExtractedIncludeProtosDir(String sourceSetName) {
      return "${project.buildDir}/extracted-include-protos/${sourceSetName}"
    }

    private String getExtractedProtosDir(String sourceSetName) {
      return "${project.buildDir}/extracted-protos/${sourceSetName}"
    }

}
