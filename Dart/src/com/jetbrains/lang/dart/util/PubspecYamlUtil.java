package com.jetbrains.lang.dart.util;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.yaml.snakeyaml.Dumper;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Loader;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class PubspecYamlUtil {

  public static final String PUBSPEC_YAML = "pubspec.yaml";


  private static final Key<Pair<Long, Map<String, Object>>> MOD_STAMP_TO_PUBSPEC_NAME = Key.create("MOD_STAMP_TO_PUBSPEC_NAME");
  private static final String DART_CUSTOM_PACKAGE_ROOTS_OPTION = "dart.package.roots";
  public static final String DART_CUSTOM_PACKAGE_ROOTS_SEPARATOR = ";";

  @Nullable
  public static VirtualFile getPubspecYamlFile(final @NotNull Project project, final @NotNull VirtualFile contextFile) {
    final ProjectFileIndex fileIndex = ProjectRootManager.getInstance(project).getFileIndex();
    VirtualFile parent = contextFile;
    while ((parent = parent.getParent()) != null && fileIndex.isInContent(parent)) {
      final VirtualFile file = parent.findChild(PUBSPEC_YAML);
      if (file != null && !file.isDirectory()) return file;
    }

    return null;
  }

  @NotNull
  public static Pair<VirtualFile, List<VirtualFile>> getPubspecYamlFileAndDartPackageRoots(final @NotNull Project project,
                                                                                           final @NotNull VirtualFile contextFile) {
    final Module module = ModuleUtilCore.findModuleForFile(contextFile, project);
    final String customPackageRootPaths = module == null ? null : getCustomPackageRootsForModule(module);
    if (!StringUtil.isEmptyOrSpaces(customPackageRootPaths)) {
      final List<VirtualFile> dartPackageRoots = new ArrayList<VirtualFile>();
      for (String path : StringUtil.split(customPackageRootPaths, DART_CUSTOM_PACKAGE_ROOTS_SEPARATOR)) {
        final VirtualFile customPackagesFolder = LocalFileSystem.getInstance().findFileByPath(path.trim());
        if (customPackagesFolder != null && customPackagesFolder.isDirectory()) {
          dartPackageRoots.add(customPackagesFolder);
        }
      }
      return Pair.create(null, dartPackageRoots);
    }

    final VirtualFile pubspecYamlFile = getPubspecYamlFile(project, contextFile);
    final VirtualFile parentFolder = pubspecYamlFile == null ? null : pubspecYamlFile.getParent();
    final VirtualFile packagesFolder = parentFolder == null ? null : parentFolder.findChild("packages");
    return packagesFolder == null || !packagesFolder.isDirectory()
           ? Pair.create(pubspecYamlFile, Collections.<VirtualFile>emptyList())
           : Pair.create(pubspecYamlFile, Collections.singletonList(packagesFolder));
  }

  @NotNull
  public static List<VirtualFile> getDartPackageRoots(final @NotNull Project project, final @NotNull VirtualFile file) {
    return getPubspecYamlFileAndDartPackageRoots(project, file).second;
  }

  @Nullable
  public static String getPubspecName(final @NotNull VirtualFile pubspecYamlFile) {
    final Map<String, Object> pubspecYamlInfo = getPubspecYamlInfo(pubspecYamlFile);
    final Object name = pubspecYamlInfo == null ? null : pubspecYamlInfo.get("name");
    return name instanceof String ? (String)name : null;
  }

  @Nullable
  private static Map<String, Object> getPubspecYamlInfo(final @NotNull VirtualFile pubspecYamlFile) {
    // do not use Yaml plugin here - IntelliJ IDEA Community Edition doesn't contain it.
    Pair<Long, Map<String, Object>> data = pubspecYamlFile.getUserData(MOD_STAMP_TO_PUBSPEC_NAME);

    final FileDocumentManager documentManager = FileDocumentManager.getInstance();
    final Document cachedDocument = documentManager.getCachedDocument(pubspecYamlFile);
    final Long currentTimestamp = cachedDocument != null ? cachedDocument.getModificationStamp() : pubspecYamlFile.getModificationCount();
    final Long cachedTimestamp = data == null ? null : data.first;

    if (cachedTimestamp == null || !cachedTimestamp.equals(currentTimestamp)) {
      data = null;
      pubspecYamlFile.putUserData(MOD_STAMP_TO_PUBSPEC_NAME, null);
      try {
        final Map<String, Object> pubspecYamlInfo;
        if (cachedDocument != null) {
          pubspecYamlInfo = loadPubspecYamlInfo(cachedDocument.getText());
        }
        else {
          pubspecYamlInfo = loadPubspecYamlInfo(VfsUtilCore.loadText(pubspecYamlFile));
        }

        if (pubspecYamlInfo != null) {
          data = Pair.create(currentTimestamp, pubspecYamlInfo);
          pubspecYamlFile.putUserData(MOD_STAMP_TO_PUBSPEC_NAME, data);
        }
      }
      catch (IOException ignored) {/* unlucky */}
    }

    return data == null ? null : data.second;
  }

  @Nullable
  private static Map<String, Object> loadPubspecYamlInfo(final @NotNull String pubspecYamlFileContents) {
    // see com.google.dart.tools.core.utilities.yaml.PubYamlUtils#parsePubspecYamlToMap()
    // deprecated constructor used to be compatible with old snakeyaml version in testng.jar (it wins when running from sources or tests)
    //noinspection deprecation
    final Yaml yaml = new Yaml(new Loader(new Constructor()), new Dumper(new Representer(), new DumperOptions()), new Resolver() {
      @Override
      protected void addImplicitResolvers() {
        addImplicitResolver(Tag.NULL, NULL, "~nN\0");
        addImplicitResolver(Tag.NULL, EMPTY, null);
        addImplicitResolver(Tag.VALUE, VALUE, "=");
        addImplicitResolver(Tag.MERGE, MERGE, "<");
      }
    });

    try {
      //noinspection unchecked
      return (Map<String, Object>)yaml.load(pubspecYamlFileContents);
    }
    catch (Exception e) {
      return null; // malformed yaml, e.g. because of typing in it
    }
  }

  @Nullable
  public static String getCustomPackageRootsForModule(final @NotNull Module module) {
    return module.getOptionValue(DART_CUSTOM_PACKAGE_ROOTS_OPTION);
  }

  public static void setCustomPackageRootsForModule(final @NotNull Module module, final @Nullable String path) {
    if (StringUtil.isEmptyOrSpaces(path)) {
      module.clearOption(DART_CUSTOM_PACKAGE_ROOTS_OPTION);
    }
    else {
      module.setOption(DART_CUSTOM_PACKAGE_ROOTS_OPTION, path);
    }
  }
}
