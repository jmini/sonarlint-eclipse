/*
 * SonarLint for Eclipse
 * Copyright (C) 2015-2024 SonarSource SA
 * sonarlint@sonarsource.com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonarlint.eclipse.core.internal.backend;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.annotation.Nullable;
import org.sonarlint.eclipse.core.SonarLintLogger;
import org.sonarlint.eclipse.core.analysis.SonarLintLanguage;
import org.sonarlint.eclipse.core.internal.cache.DefaultSonarLintProjectAdapterCache;
import org.sonarlint.eclipse.core.internal.cache.IProjectScopeProviderCache;
import org.sonarlint.eclipse.core.internal.extension.SonarLintExtensionTracker;
import org.sonarlint.eclipse.core.internal.jobs.TestFileClassifier;
import org.sonarlint.eclipse.core.internal.utils.SonarLintUtils;
import org.sonarlint.eclipse.core.resource.ISonarLintFile;
import org.sonarlint.eclipse.core.resource.ISonarLintProject;
import org.sonarsource.sonarlint.core.rpc.protocol.SonarLintRpcServer;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.file.DidUpdateFileSystemParams;
import org.sonarsource.sonarlint.core.rpc.protocol.common.ClientFileDto;
import org.sonarsource.sonarlint.core.rpc.protocol.common.Language;

import static java.util.stream.Collectors.toList;

public class FileSystemSynchronizer implements IResourceChangeListener {

  private static final String SONAR_SCANNER_CONFIG_FILENAME = "sonar-project.properties";
  private static final String AUTOSCAN_CONFIG_FILENAME = ".sonarcloud.properties";
  public static final String SONARLINT_FOLDER = ".sonarlint";
  public static final String SONARLINT_CONFIG_FILE = "connectedMode.json";
  public static final Pattern SONARLINT_JSON_REGEX = Pattern.compile("^\\" + SONARLINT_FOLDER + "/.*\\.json$", Pattern.CASE_INSENSITIVE);

  private final SonarLintRpcServer backend;

  FileSystemSynchronizer(SonarLintRpcServer backend) {
    this.backend = backend;
  }

  @Override
  public void resourceChanged(IResourceChangeEvent event) {
    var changedOrAddedFiles = new ArrayList<ISonarLintFile>();
    var removedFiles = new ArrayList<URI>();
    try {
      event.getDelta().accept(delta -> visitDeltaPostChange(delta, changedOrAddedFiles, removedFiles));
    } catch (CoreException e) {
      SonarLintLogger.get().error(e.getMessage(), e);
    }

    // When there was no valuable resource changed, then we don't have to do anything else!
    if (changedOrAddedFiles.isEmpty() && removedFiles.isEmpty()) {
      return;
    }

    // In order to not intervene with the DefaultSonarLintProjectAdapter we have to invalidate the cache as early as
    // possible! Otherwise "importing a project", then "analyzing whole project" wouldn't work because the initial
    // "DefaultSonarLintProjectAdapter#files()" call will always include no files after an import, they have to be
    // "added" first and that is done the first time this is called!
    final ISonarLintProject project;
    if (!changedOrAddedFiles.isEmpty()) {
      project = changedOrAddedFiles.get(0).getProject();

      // Invalidate cache if files were added, or changed as otherwise importing another related hierarchical project
      // as well as manually selecting multiple projects for analysis would be affected and could potentially lead to
      // incorrect results!
      DefaultSonarLintProjectAdapterCache.INSTANCE.removeEntry(
        ConfigScopeSynchronizer.getConfigScopeId(project));
    } else {
      // The variable has to be "final" to be usable in the thread spawned by the job, therefore we set it here
      // to null in order to not have it in a "unitialized" state as it doesn't default to "null"!
      project = null;
    }

    var job = new Job("SonarLint - Propagate FileSystem changes") {
      @Override
      protected IStatus run(IProgressMonitor monitor) {
        // For added files this won't include SonarLint configuration files in order to not suggest connections twice
        // after a project import (everything after an import is also considered "added"). In case of changes done
        // either inside or outside the IDE, the files will be included.
        var changedOrAddedDto = changedOrAddedFiles.stream()
          .map(f -> FileSystemSynchronizer.toFileDto(f, monitor))
          .collect(toList());

        // In order to add additional "changes" for informing the sub-projects we have to make the list modifiable!
        var allChangedOrAddedDtos = new ArrayList<>(changedOrAddedDto);

        var changedOrAddedSonarLintDto = allChangedOrAddedDtos.stream()
          .filter(dto -> SONARLINT_JSON_REGEX.matcher(dto.getIdeRelativePath().toString()).find())
          .collect(toList());

        // Only if there were actual changes to SonarLint configuration files we want to do the hussle and check for
        // sub-projects and inform them as well!
        if (!changedOrAddedSonarLintDto.isEmpty()) {
          // INFO: "project" cannot be null in this case!
          for (var subProject : getSubProjects(project)) {
            var changedOrAddedSubProjectDto = changedOrAddedSonarLintDto.stream()
              .map(dto -> toSubProjectFileDto(subProject, dto))
              .collect(toList());
            allChangedOrAddedDtos.addAll(changedOrAddedSubProjectDto);
          }
        }

        backend.getFileService().didUpdateFileSystem(new DidUpdateFileSystemParams(removedFiles, allChangedOrAddedDtos));
        return Status.OK_STATUS;
      }
    };

    job.setSystem(true);
    job.schedule();
  }

  private static boolean visitDeltaPostChange(IResourceDelta delta, List<ISonarLintFile> changedOrAddedFiles, List<URI> removedFiles) {
    var res = delta.getResource();
    var fullPath = res.getFullPath();

    // Immediately rule out files in the VCS and files related to Node.js "metadata" / storage. We don't care for these
    // ones no matter if removed, changed, or added!
    if (SonarLintUtils.insideVCSFolder(fullPath) || SonarLintUtils.isNodeJsRelated(fullPath)) {
      return false;
    }

    if (delta.getKind() == IResourceDelta.REMOVED) {
      // When something got removed, we don't care for exclusions and the adaption to ISonarLintFile. This happens not
      // as often as adding or changing files to we will just let SLCORE handle it and don't "care" anymore about it.
      var fileUri = res.getLocationURI();
      if (fileUri != null) {
        removedFiles.add(fileUri);
        SonarLintLogger.get().debug("File removed: " + fileUri);
      }
      return true;
    }

    var slFile = SonarLintUtils.adapt(res, ISonarLintFile.class,
      "[FileSystemSynchronizer#visitDeltaPostChange] Try get file from event '" + res + "' (added/changed)");
    if (slFile == null) {
      // Whatever happened here, try to dig deeper. If nothing is there, then okey - if there is, we can check anyway
      // and care in the next iteration of this method with the child element.
      // This must be kept as "true" as for projects imported (not when already present in the workspace and workspace
      // then opened) it would stop otherwise at "/" which is the first resource listed for a file -> the root from
      // which all resources, including the project "directory" itself, are derived!
      return true;
    }

    var project = slFile.getProject();
    var configScopeId = ConfigScopeSynchronizer.getConfigScopeId(project);
    var exclusions = IProjectScopeProviderCache.INSTANCE.getEntry(configScopeId);
    if (exclusions == null) {
      exclusions = getExclusions((IProject) project.getResource());
      IProjectScopeProviderCache.INSTANCE.putEntry(configScopeId, exclusions);
    }

    // Compared to "DefaultSonarLintProjectAdapter#files" this is only on a resource delta, therefore we won't visit
    // the folders containing the files that were added / changed. And therefore we have to iterate over the exclusions
    // instead of just checking whether the "whole" path is in there (as it would be the case for a folder).
    for (var exclusion : exclusions) {
      if (SonarLintUtils.isChild(fullPath, exclusion)) {
        return false;
      }
    }

    if (delta.getKind() == IResourceDelta.ADDED) {
      SonarLintLogger.get().debug("File added: " + slFile.getName());
      changedOrAddedFiles.add(slFile);
    } else if (delta.getKind() == IResourceDelta.CHANGED) {
      var interestingChangeForSlBackend = false;
      var flags = delta.getFlags();
      if ((flags & IResourceDelta.CONTENT) != 0) {
        interestingChangeForSlBackend = true;
        SonarLintLogger.get().debug("File content changed: " + slFile.getName());
      }
      if ((flags & IResourceDelta.REPLACED) != 0) {
        interestingChangeForSlBackend = true;
        SonarLintLogger.get().debug("File content replaced: " + slFile.getName());
      }
      if ((flags & IResourceDelta.ENCODING) != 0) {
        interestingChangeForSlBackend = true;
        SonarLintLogger.get().debug("File encoding changed: " + slFile.getName());
      }
      if (interestingChangeForSlBackend) {
        changedOrAddedFiles.add(slFile);
      }
    }

    return true;
  }

  static ClientFileDto toFileDto(ISonarLintFile slFile, IProgressMonitor monitor) {
    var configScopeId = ConfigScopeSynchronizer.getConfigScopeId(slFile.getProject());
    Path fsPath;
    File localFile;
    try {
      var fileStore = EFS.getStore(slFile.getResource().getLocationURI());
      localFile = fileStore.toLocalFile(EFS.NONE, monitor);
      if (localFile != null) {
        fsPath = localFile.toPath().toAbsolutePath();
      } else {
        fsPath = null;
      }

    } catch (Exception e) {
      SonarLintLogger.get().debug("Error while looking for file path for file " + slFile, e);
      fsPath = null;
      localFile = null;
    }

    String fileContent = null;
    if (matchesSonarLintConfigurationFiles(slFile) || localFile == null) {
      fileContent = slFile.getDocument().get();
    }

    return new ClientFileDto(slFile.uri(), Paths.get(slFile.getProjectRelativePath()), configScopeId, TestFileClassifier.get().isTest(slFile),
      slFile.getCharset().name(), fsPath, fileContent, tryDetectLanguage(slFile), true);
  }

  /**
   *  This converts a ClientFileDto from a root project to sub-project one with a correct relative path, changing only
   *  it and the configScopeId (to the sub-project)!
   *
   *  @param project the sub-project which will be used for the relative path
   *  @param dto from the root project
   *  @return DTO for the sub-project
   */
  static ClientFileDto toSubProjectFileDto(ISonarLintProject project, ClientFileDto dto) {
    var currentDtoUri = Paths.get(dto.getUri());
    var projectUri = Paths.get(project.getResource().getLocationURI());
    var relativePath = projectUri.relativize(currentDtoUri);

    return new ClientFileDto(dto.getUri(), relativePath, ConfigScopeSynchronizer.getConfigScopeId(project),
      dto.isTest(), dto.getCharset(), dto.getFsPath(), dto.getContent(), dto.getDetectedLanguage(), true);
  }

  /** This only gets the SonarLint configuration files for a specific project, instead of all of them! */
  public static Collection<ISonarLintFile> getSonarLintJsonFiles(ISonarLintProject project) {
    return project.files().stream()
      .filter(FileSystemSynchronizer::matchesSonarLintConfigurationFiles)
      .collect(toList());
  }

  private static boolean matchesSonarLintConfigurationFiles(ISonarLintFile file) {
    return file.getName().endsWith(SONAR_SCANNER_CONFIG_FILENAME)
      || file.getName().endsWith(AUTOSCAN_CONFIG_FILENAME)
      || SONARLINT_JSON_REGEX.matcher(file.getProjectRelativePath()).find();
  }

  private static HashSet<ISonarLintProject> getSubProjects(ISonarLintProject project) {
    var subProjects = new HashSet<ISonarLintProject>();
    for (var projectHistoryProvider : SonarLintExtensionTracker.getInstance().getProjectHierarchyProviders()) {
      if (projectHistoryProvider.partOfHierarchy(project)) {
        subProjects.addAll(projectHistoryProvider.getSubProjects(project));
      }
    }
    return subProjects;
  }

  @Nullable
  private static Language tryDetectLanguage(ISonarLintFile file) {
    SonarLintLanguage language = null;
    for (var languageProvider : SonarLintExtensionTracker.getInstance().getLanguageProviders()) {
      var detectedLanguage = languageProvider.language(file);
      if (detectedLanguage != null) {
        if (language == null) {
          language = detectedLanguage;
        } else if (!language.equals(detectedLanguage)) {
          SonarLintLogger.get().error("Conflicting languages detected for file " + file.getName() + ". " + language + " and " + detectedLanguage);
        }
      }
    }
    return language != null ? Language.valueOf(language.name()) : null;
  }

  private static Set<IPath> getExclusions(IProject project) {
    var exclusions = new HashSet<IPath>();
    for (var projectScopeProvider : SonarLintExtensionTracker.getInstance().getProjectScopeProviders()) {
      exclusions.addAll(projectScopeProvider.getExclusions(project));
    }
    return exclusions;
  }
}
