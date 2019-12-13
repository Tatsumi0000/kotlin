/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.codeInsight

import com.intellij.codeInsight.editorActions.TextBlockTransferableData
import java.awt.datatransfer.DataFlavor
import java.io.Serializable

data class TextBlock(val startOffset: Int, val endOffset: Int, val text: String)

class BasicKotlinReferenceTransferableData(
    val sourceFileUrl: String,
    val sourceText: String,
    val imports: List<String>,
    val textBlocks: List<TextBlock>
) : TextBlockTransferableData, Serializable {
    override fun getFlavor() = dataFlavor
    override fun getOffsetCount() = 0

    override fun getOffsets(offsets: IntArray?, index: Int) = index
    override fun setOffsets(offsets: IntArray?, index: Int) = index

    companion object {
        val dataFlavor: DataFlavor? by lazy {
            try {
                val dataClass = KotlinReferenceData::class.java
                DataFlavor(
                    DataFlavor.javaJVMLocalObjectMimeType + ";class=" + dataClass.name,
                    "BasicKotlinReferenceTransferableData",
                    dataClass.classLoader
                )
            } catch (e: NoClassDefFoundError) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}

