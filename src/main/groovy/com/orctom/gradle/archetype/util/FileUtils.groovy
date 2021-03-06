package com.orctom.gradle.archetype.util

import com.orctom.gradle.archetype.ArchetypePlugin
import groovy.io.FileType
import groovy.text.GStringTemplateEngine
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

class FileUtils {

  static final Logger LOGGER = Logging.getLogger(FileUtils.class)

  static engine = new GStringTemplateEngine()

  private static File getResourceFile(File projectDir, String path) {
    if (Paths.get(path).absolute) {
      return new File(path)
    }
    new File(projectDir, path)
  }

  private static List<File> getTemplates(File templateDir) {
    LOGGER.info('Using template in: {}', templateDir.path)

    List<File> sourceFiles = []
    templateDir.eachFileRecurse(FileType.ANY) { file ->
      sourceFiles << file
    }

    sourceFiles
  }

  private static Set<String> getNonTemplates(File projectDir, File templateDir) {
    File templateSpecificNonTemplatesFile = new File(templateDir, '.nontemplates')
    if (templateSpecificNonTemplatesFile.exists()) {
      return readNonTemplates(templateDir.path, templateSpecificNonTemplatesFile)
    }

    File defaultNonTemplatesFile = new File(projectDir, 'src/main/resources/.nontemplates')
    if (defaultNonTemplatesFile.exists()) {
      return readNonTemplates(templateDir.path, defaultNonTemplatesFile)
    }

    return []
  }

  private static Set<String> readNonTemplates(String templateDirPath, File nonTemplatesFile) {
    LOGGER.info('Using non-template list in file: {}', nonTemplatesFile)
    Set<String> nonTemplates = []

    Set<String> nonTemplatesWildcards = nonTemplatesFile.readLines() as Set
    FileNameFinder finder = new FileNameFinder()
    nonTemplatesWildcards.each {
      if (null == it || 0 == it.trim().length() || it.startsWith("#")) {
        return
      }
      def files = finder.getFileNames(templateDirPath, it)
      nonTemplates.addAll(files)
    }

    nonTemplates
  }

  static void generate(File projectDir, String templatePath, Map binding, boolean failIfFileExist) {
    File templateDir = getResourceFile(projectDir, templatePath)
    List<File> templates = getTemplates(templateDir)
    File targetDir = getResourceFile(projectDir, ArchetypePlugin.DIR_TARGET)
    Set<String> nonTemplates = getNonTemplates(projectDir, templateDir)

    targetDir.mkdirs()

    List<File> generatedFiles = []
    Path sourceDirPath = templateDir.toPath()
    templates.each { source ->
      File target = getTargetFile(sourceDirPath, targetDir, source, binding)
      if (source.isDirectory()) {
        return
      }

      LOGGER.debug('Processing {} -> {}', source, target)

      failTheBuildIfFileExist(target, failIfFileExist, generatedFiles)

      target.parentFile.mkdirs()

      if (isNonTemplate(source, nonTemplates)) {
        generateFromNonTemplate(source, target)
      } else {
        generateFromTemplate(source, target, binding)
      }
      generatedFiles.add(target)
    }

    LOGGER.info('Done')
  }

  private static void failTheBuildIfFileExist(File target, boolean failIfFileExist, List<File> generatedFiles) {
    if (target.exists() && failIfFileExist) {
      LOGGER.error("File already exists '{}'.", target.absolutePath)
      LOGGER.info("Stopping the generation, deleting generated files.")
      deleteNewlyGeneratedFiles(generatedFiles)
      throw new RuntimeException("failIfFileExist=true and the target file already exists.")
    }
  }

  private static File getTargetFile(Path sourceDirPath, File targetDir, File source, Map binding) {
    Path sourcePath = sourceDirPath.relativize(source.toPath())
    String rawTargetPath = new File(targetDir, resolvePaths(sourcePath)).path
    rawTargetPath = rawTargetPath.replaceAll("\\\\", "/")
    String resolvedTargetPath = engine.createTemplate(rawTargetPath).make(binding)
    new File(resolvedTargetPath)
  }

  private static void generateFromNonTemplate(File source, File target) {
    Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
  }

  private static void generateFromTemplate(File source, File target, Map binding) {
    try {
      target.delete()
      target << resolve(source.text, binding)
    } catch (Exception e) {
      LOGGER.error("Failed to resolve variables in: '{}]", source.path)
      LOGGER.error(e.getMessage())
      Files.copy(source.toPath(), target.toPath())
    }
  }

  private static List<File> deleteNewlyGeneratedFiles(List<File> generatedFiles) {
    generatedFiles.each { file ->
      file.delete()
      LOGGER.debug("Deleted: {}", file)
    }
  }

  static boolean isNonTemplate(File source, Set<String> nonTemplates) {
    source.path in nonTemplates
  }

  static String resolve(String text, Map binding) {
    String escaped = escape(text)
    String ready = escaped.replaceAll('@([^{}/\\\\@\\n,\\s]+)@', '\\$\\{$1\\}')
    String resolved = engine.createTemplate(ready).make(binding)
    unescape(resolved)
  }

  private static String escape(String text) {
    String escaped = text.replaceAll('\\$', '__DOLLAR__')
    escaped = escaped.replaceAll('@@', '__AT__')
    escaped
  }

  private static String unescape(String resolved) {
    String unescaped = resolved.replaceAll('__DOLLAR__', '\\$')
    unescaped = unescaped.replaceAll('__AT__', '@')
    unescaped
  }

  // Applies variable substitution to provided path.
  static String resolvePaths(Path path) {
    if (!path.toString().contains('__')) {
      path.toString()
    }

    path.collect {
      resolvePath(it.toString())
    }.join(File.separator)
  }

  // replaces "__variable__" (used in directory/file names) with "${variable}"
  static String resolvePath(String path) {
    path.replaceAll('(.*)__([^{}/\\\\@\\n,]+)__(.*)', '$1\\$\\{$2\\}$3')
  }

}
