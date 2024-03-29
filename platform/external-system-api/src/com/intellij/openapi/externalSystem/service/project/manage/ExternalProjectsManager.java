/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.externalSystem.service.project.manage;

import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.externalSystem.importing.ImportSpec;
import com.intellij.openapi.externalSystem.model.DataNode;
import com.intellij.openapi.externalSystem.model.ProjectSystemId;
import com.intellij.openapi.externalSystem.service.project.autoimport.ExternalSystemProjectsWatcher;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * @author Vladislav.Soroka
 */
public interface ExternalProjectsManager {

  static ExternalProjectsManager getInstance(@NotNull Project project) {
    return ServiceManager.getService(project, ExternalProjectsManager.class);
  }

  @NotNull
  Project getProject();

  ExternalSystemProjectsWatcher getExternalProjectsWatcher();

  void refreshProject(@NotNull String externalProjectPath, @NotNull ImportSpec importSpec);

  /**
   * Execute runnable after External projects manager is fully initialized.
   * <p>
   * Initialization includes loading external project data cache and can take visible time,
   * during which query to {@link com.intellij.openapi.externalSystem.util.ExternalSystemUtil#getExternalProjectInfo(Project, ProjectSystemId, String) ExternalSystemUtil#getExternalProjectInfo}
   * will return null. Use this method to postpone such queries.
   *
   * @param runnable
   */
  void runWhenInitialized(Runnable runnable);

  boolean isIgnored(@NotNull ProjectSystemId systemId, @NotNull String projectPath);

  void setIgnored(@NotNull DataNode<?> dataNode, boolean isIgnored);
}
