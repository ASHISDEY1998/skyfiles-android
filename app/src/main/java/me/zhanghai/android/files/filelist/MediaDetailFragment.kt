/*
 * Copyright (c) 2026 SkyFiles
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import java8.nio.file.Paths
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.asMimeType
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.SkyFilesLogger
import me.zhanghai.android.files.util.putArgs
import java.text.Collator

class MediaDetailFragment : FileListFragment() {

    private lateinit var title: String
    private lateinit var mode: String
    private var bucketId: String? = null
    private var docCategory: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SkyFilesLogger.d("MediaDetailFragment", "onCreate")
        title = arguments?.getString(ARG_TITLE) ?: "Files"
        mode = arguments?.getString(ARG_MODE) ?: "IMAGES"
        bucketId = arguments?.getString(ARG_BUCKET_ID)
        docCategory = arguments?.getString(ARG_DOC_CATEGORY)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        SkyFilesLogger.d("MediaDetailFragment", "onViewCreated")

        // Hide breadcrumb layout since this is a flat categories view
        val breadcrumbLayout = view.findViewById<View>(R.id.breadcrumbLayout)
        breadcrumbLayout?.visibility = View.GONE

        // Customize toolbar
        val toolbar = view.findViewById<Toolbar>(R.id.toolbar)
        if (toolbar != null) {
            toolbar.title = title
            toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            toolbar.setNavigationOnClickListener {
                parentFragmentManager.popBackStack()
            }
        }

        loadItems()
    }

    private fun loadItems() {
        SkyFilesLogger.d("MediaDetailFragment", "loadItems triggered")
        viewModel.customFileListLiveData.value = Loading(null)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                SkyFilesLogger.d("MediaDetailFragment", "Querying media items for docCategory: $docCategory")
                val items = queryMediaItems(requireContext())
                SkyFilesLogger.d("MediaDetailFragment", "Query success. Items count: ${items.size}")
                viewModel.customFileListLiveData.value = Success(items)
            } catch (e: Exception) {
                SkyFilesLogger.e("MediaDetailFragment", "Query failed", e)
                viewModel.customFileListLiveData.value = Failure(null, e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        SkyFilesLogger.d("MediaDetailFragment", "onDestroy - clearing custom file list")
        viewModel.customFileListLiveData.value = null
    }

    private suspend fun queryMediaItems(context: Context): List<FileItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<FileItem>()
        val uri = when (mode) {
            "IMAGES" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            "VIDEOS" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            "AUDIO" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Files.getContentUri("external")
        }

        val projection = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE
        )

        var selection = when (mode) {
            "IMAGES" -> "${MediaStore.Images.Media.BUCKET_ID} = ?"
            "VIDEOS" -> "${MediaStore.Video.Media.BUCKET_ID} = ?"
            else -> null
        }
        var selectionArgs = when (mode) {
            "IMAGES", "VIDEOS" -> arrayOf(bucketId ?: "")
            else -> null
        }

        if (mode == "DOCUMENTS" && docCategory != null) {
            val mimeSelection = getDocumentMimeSelection(docCategory!!)
            selection = if (mimeSelection.first.isNotEmpty()) {
                "(${mimeSelection.first})"
            } else {
                null
            }
            selectionArgs = if (mimeSelection.second.isNotEmpty()) {
                mimeSelection.second
            } else {
                null
            }
        }

        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        try {
            val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
            cursor?.use { c ->
                val dataIndex = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val nameIndex = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIndex = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateIndex = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val mimeIndex = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)

                while (c.moveToNext()) {
                    val data = c.getString(dataIndex) ?: continue
                    val displayName = c.getString(nameIndex) ?: ""
                    val size = c.getLong(sizeIndex)
                    val dateModified = c.getLong(dateIndex)
                    val mime = c.getString(mimeIndex) ?: ""

                    if (mode == "DOCUMENTS" && docCategory != null) {
                        val isMatch = checkDocumentMatch(displayName, mime, docCategory!!)
                        if (!isMatch) continue
                    }

                    val path = Paths.get(data)
                    val mimeType = mime.asMimeType()
                    val fileTime = FileTime.fromMillis(dateModified * 1000L)

                    val nameCollationKey = Collator.getInstance().getCollationKeyForFileName(displayName)
                    val attributes = object : BasicFileAttributes {
                        override fun lastModifiedTime(): FileTime = fileTime
                        override fun lastAccessTime(): FileTime = fileTime
                        override fun creationTime(): FileTime = fileTime
                        override fun isRegularFile(): Boolean = true
                        override fun isDirectory(): Boolean = false
                        override fun isSymbolicLink(): Boolean = false
                        override fun isOther(): Boolean = false
                        override fun size(): Long = size
                        override fun fileKey(): Any? = null
                    }
                    val fileItem = FileItem(path, nameCollationKey, attributes, null, null, false, mimeType)
                    list.add(fileItem)
                }
            }
        } catch (e: Exception) {
            SkyFilesLogger.e("MediaDetailFragment", "Error querying MediaStore", e)
            throw e
        }
        list
    }

    private fun getDocumentMimeSelection(category: String): Pair<String, Array<String>> {
        return when (category) {
            "PDF" -> "${MediaStore.MediaColumns.MIME_TYPE} = ?" to arrayOf("application/pdf")
            "Word" -> "${MediaStore.MediaColumns.MIME_TYPE} = ? OR ${MediaStore.MediaColumns.MIME_TYPE} = ?" to arrayOf(
                "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            )
            "Excel" -> "${MediaStore.MediaColumns.MIME_TYPE} = ? OR ${MediaStore.MediaColumns.MIME_TYPE} = ?" to arrayOf(
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            )
            "PowerPoint" -> "${MediaStore.MediaColumns.MIME_TYPE} = ? OR ${MediaStore.MediaColumns.MIME_TYPE} = ?" to arrayOf(
                "application/vnd.ms-powerpoint",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            )
            "Text" -> "${MediaStore.MediaColumns.MIME_TYPE} LIKE ?" to arrayOf("text/%")
            "Archives" -> "${MediaStore.MediaColumns.MIME_TYPE} = ? OR ${MediaStore.MediaColumns.MIME_TYPE} = ? OR ${MediaStore.MediaColumns.MIME_TYPE} = ?" to arrayOf(
                "application/zip",
                "application/x-rar-compressed",
                "application/x-7z-compressed"
            )
            else -> "" to emptyArray()
        }
    }

    private fun checkDocumentMatch(fileName: String, mime: String, category: String): Boolean {
        val mimeMatch = when (category) {
            "PDF" -> mime.equals("application/pdf", ignoreCase = true)
            "Word" -> mime.equals("application/msword", ignoreCase = true) || mime.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ignoreCase = true)
            "Excel" -> mime.equals("application/vnd.ms-excel", ignoreCase = true) || mime.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ignoreCase = true)
            "PowerPoint" -> mime.equals("application/vnd.ms-powerpoint", ignoreCase = true) || mime.equals("application/vnd.openxmlformats-officedocument.presentationml.presentation", ignoreCase = true)
            "Text" -> mime.startsWith("text/", ignoreCase = true)
            "Archives" -> mime.equals("application/zip", ignoreCase = true) || mime.equals("application/x-rar-compressed", ignoreCase = true) || mime.equals("application/x-7z-compressed", ignoreCase = true)
            else -> false
        }
        if (mimeMatch) return true

        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (category) {
            "PDF" -> ext == "pdf"
            "Word" -> ext == "doc" || ext == "docx"
            "Excel" -> ext == "xls" || ext == "xlsx"
            "PowerPoint" -> ext == "ppt" || ext == "pptx"
            "Text" -> ext == "txt"
            "Archives" -> ext == "zip" || ext == "rar" || ext == "7z" || ext == "tar" || ext == "gz"
            else -> false
        }
    }

    companion object {
        private const val ARG_TITLE = "title"
        private const val ARG_MODE = "mode"
        private const val ARG_BUCKET_ID = "bucket_id"
        private const val ARG_DOC_CATEGORY = "doc_category"

        fun newInstanceForAlbum(title: String, mode: String, bucketId: String): MediaDetailFragment {
            val fragment = MediaDetailFragment()
            val args = FileListFragment.Args(FileListActivity.createViewIntent(Paths.get(Environment.getExternalStorageDirectory().path)))
            fragment.putArgs(args)
            fragment.arguments?.apply {
                putString(ARG_TITLE, title)
                putString(ARG_MODE, mode)
                putString(ARG_BUCKET_ID, bucketId)
            }
            return fragment
        }

        fun newInstanceForDocCategory(title: String, docCategory: String): MediaDetailFragment {
            val fragment = MediaDetailFragment()
            val args = FileListFragment.Args(FileListActivity.createViewIntent(Paths.get(Environment.getExternalStorageDirectory().path)))
            fragment.putArgs(args)
            fragment.arguments?.apply {
                putString(ARG_TITLE, title)
                putString(ARG_MODE, "DOCUMENTS")
                putString(ARG_DOC_CATEGORY, docCategory)
            }
            return fragment
        }
    }
}
