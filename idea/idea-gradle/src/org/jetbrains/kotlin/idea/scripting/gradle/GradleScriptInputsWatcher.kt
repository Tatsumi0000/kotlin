/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.scripting.gradle

import com.intellij.openapi.components.*
import com.intellij.openapi.externalSystem.service.project.autoimport.ConfigurationFileCrcFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.PathUtil
import org.jetbrains.annotations.TestOnly
import org.jetbrains.plugins.gradle.settings.GradleLocalSettings
import java.nio.file.Paths

@State(
    name = "KotlinBuildScriptsModificationInfo",
    storages = [Storage(StoragePathMacros.CACHE_FILE)]
)
class GradleScriptInputsWatcher(val project: Project) : PersistentStateComponent<GradleScriptInputsWatcher.Storage> {
    private var storage = Storage()

    init {
        initStorage(project)
    }

    private fun initStorage(project: Project) {
        val localSettings = GradleLocalSettings.getInstance(project)
        localSettings.externalConfigModificationStamps.forEach { (path, stamp) ->
            val file = VfsUtil.findFile(Paths.get(path), true)
            if (file != null && !file.isDirectory) {
                val calculateCrc = ConfigurationFileCrcFactory(file).create()
                if (calculateCrc != stamp) {
                    storage.fileChanged(file, file.timeStamp)
                }
            }
        }
    }

    fun lastModifiedFileTimeStamp(file: VirtualFile): Long = storage.lastModifiedTimeStampExcept(file)

    fun areRelatedFilesUpToDate(file: VirtualFile, timeStamp: Long): Boolean {
        return lastModifiedFileTimeStamp(file) < timeStamp
    }

    class Storage(
        private var lastModifiedTS: Long = Long.MIN_VALUE,
        private val lastModifiedFiles: MutableSet<String> = hashSetOf(),
        private var previousModifiedTS: Long = Long.MIN_VALUE,
        private val previousModifiedFiles: MutableSet<String> = hashSetOf()
    ) {
        fun lastModifiedTimeStampExcept(file: VirtualFile): Long {
            val fileId = getNormalizedPath(file)
            if (lastModifiedFiles.contains(fileId) && lastModifiedFiles.size == 1) {
                return previousModifiedTS
            }

            return lastModifiedTS
        }

        fun fileChanged(file: VirtualFile, ts: Long) {
            val fileId = getNormalizedPath(file)
            println(fileId)
            println(ts)
            when {
                ts > lastModifiedTS -> {
                    previousModifiedFiles.clear()
                    previousModifiedFiles.addAll(lastModifiedFiles)
                    previousModifiedTS = lastModifiedTS

                    lastModifiedTS = ts
                    lastModifiedFiles.clear()
                    lastModifiedFiles.add(fileId)
                }
                ts == lastModifiedTS -> {
                    lastModifiedFiles.add(fileId)
                }
                ts == previousModifiedTS -> {
                    previousModifiedFiles.add(fileId)
                }
            }
        }

        private fun getNormalizedPath(file: VirtualFile): String {
            val canonized = PathUtil.getCanonicalPath(file.path) ?: file.path
            return FileUtil.toSystemIndependentName(canonized)
        }
    }

    override fun getState(): Storage {
        return storage
    }

    override fun loadState(state: Storage) {
        this.storage = state
    }

    @TestOnly
    fun clearAndRefillState() {
        loadState(project.service<GradleScriptInputsWatcher>().state)
    }

    @TestOnly
    fun fileChanged(file: VirtualFile, ts: Long) {
        storage.fileChanged(file, ts)
    }
}