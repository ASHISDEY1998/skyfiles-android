/*
 * Copyright (c) 2026 SkyFiles
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import coil.dispose
import coil.load
import java8.nio.file.Paths
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.asMimeType
import java.text.Collator
import me.zhanghai.android.files.util.SkyFilesLogger

class MediaCategoryFragment : Fragment() {

    private lateinit var mode: String
    private val viewModel: MediaCategoryViewModel by viewModels()

    private lateinit var recyclerView: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var emptyView: View
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout

    private var albumsAdapter: AlbumsAdapter? = null
    private var docAdapter: DocumentCategoriesAdapter? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.all { it }
        Log.d("SkyFiles", "MediaCategoryFragment requestPermissionLauncher results [mode: $mode] - granted: $granted, details: $permissions")
        viewModel.permissionsGranted[mode] = granted
        if (granted) {
            emptyView.visibility = View.GONE
            loadItems()
        } else {
            showEmptyState("Permission Denied", "Grant storage permission to view media files.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = arguments?.getString(ARG_MODE) ?: "IMAGES"
        Log.d("SkyFiles", "MediaCategoryFragment onCreate [mode: $mode]")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        Log.d("SkyFiles", "MediaCategoryFragment onCreateView [mode: $mode]")
        return inflater.inflate(R.layout.skyfiles_media_category_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d("SkyFiles", "MediaCategoryFragment onViewCreated [mode: $mode]")
        val toolbar = view.findViewById<Toolbar>(R.id.toolbar)
        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(toolbar)
        activity.supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.title = when (mode) {
            "IMAGES" -> "Pictures"
            "VIDEOS" -> "Videos"
            "AUDIO" -> "Audio"
            else -> "Documents"
        }
        toolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }

        recyclerView = view.findViewById(R.id.recyclerView)
        progress = view.findViewById(R.id.progress)
        emptyView = view.findViewById(R.id.emptyView)
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout)

        val isGrid = mode == "IMAGES" || mode == "VIDEOS"
        recyclerView.layoutManager = if (isGrid) GridLayoutManager(context, 2) else LinearLayoutManager(context)

        swipeRefreshLayout.setOnRefreshListener {
            loadItems()
        }

        checkPermissionsAndLoad()
    }

    override fun onStart() {
        super.onStart()
        Log.d("SkyFiles", "MediaCategoryFragment onStart [mode: $mode]")
    }

    override fun onResume() {
        super.onResume()
        Log.d("SkyFiles", "MediaCategoryFragment onResume [mode: $mode]")
    }

    override fun onPause() {
        super.onPause()
        Log.d("SkyFiles", "MediaCategoryFragment onPause [mode: $mode]")
    }

    override fun onStop() {
        super.onStop()
        Log.d("SkyFiles", "MediaCategoryFragment onStop [mode: $mode]")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d("SkyFiles", "MediaCategoryFragment onDestroyView [mode: $mode]")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("SkyFiles", "MediaCategoryFragment onDestroy [mode: $mode]")
    }

    private fun checkPermissionsAndLoad() {
        val permissions = getRequiredPermissions()
        val context = requireContext()
        val hasExternalStorageManager = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
        val allGranted = hasExternalStorageManager || permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        Log.d("SkyFiles", "MediaCategoryFragment checkPermissionsAndLoad [mode: $mode] - hasExternalStorageManager: $hasExternalStorageManager, allGranted: $allGranted, permissions: ${permissions.joinToString()}")
        
        viewModel.permissionsGranted[mode] = allGranted
        
        if (allGranted) {
            loadItems()
        } else {
            Log.d("SkyFiles", "MediaCategoryFragment launching requestPermissionLauncher for permissions: ${permissions.joinToString()}")
            requestPermissionLauncher.launch(permissions)
        }
    }

    private fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when (mode) {
                "IMAGES" -> arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES)
                "VIDEOS" -> arrayOf(android.Manifest.permission.READ_MEDIA_VIDEO)
                "AUDIO" -> arrayOf(android.Manifest.permission.READ_MEDIA_AUDIO)
                else -> arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        } else {
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun loadItems() {
        Log.d("SkyFiles", "MediaCategoryFragment loadItems() triggered [mode: $mode]")
        progress.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        
        viewLifecycleOwner.lifecycleScope.launch {
            if (mode == "IMAGES" || mode == "VIDEOS") {
                val albums = loadAlbumsAsync()
                Log.d("SkyFiles", "MediaCategoryFragment loadItems() loaded albums count: ${albums.size} [mode: $mode]")
                progress.visibility = View.GONE
                swipeRefreshLayout.isRefreshing = false
                if (albums.isEmpty()) {
                    showEmptyState("No albums found", "No items detected in this category.")
                } else {
                    albumsAdapter = AlbumsAdapter(albums) { album ->
                        openAlbumDetail(album)
                    }
                    recyclerView.adapter = albumsAdapter
                }
            } else if (mode == "DOCUMENTS") {
                val categories = loadDocumentCategoriesAsync()
                Log.d("SkyFiles", "MediaCategoryFragment loadItems() loaded document categories count: ${categories.size} [mode: $mode]")
                progress.visibility = View.GONE
                swipeRefreshLayout.isRefreshing = false
                docAdapter = DocumentCategoriesAdapter(categories) { category ->
                    openDocCategoryDetail(category)
                }
                recyclerView.adapter = docAdapter
            } else {
                // Audio or other mode fallbacks
                Log.d("SkyFiles", "MediaCategoryFragment loadItems() fallback reached [mode: $mode]")
                progress.visibility = View.GONE
                swipeRefreshLayout.isRefreshing = false
            }
        }
    }

    private fun showEmptyState(title: String, subtitle: String) {
        emptyView.visibility = View.VISIBLE
        emptyView.findViewById<TextView>(R.id.txtEmptyTitle).text = title
        emptyView.findViewById<TextView>(R.id.txtEmptySubtitle).text = subtitle
        recyclerView.adapter = null
    }

    private fun openAlbumDetail(album: Album) {
        val folderPath = album.latestFile.path.parent
        SkyFilesLogger.d("MediaCategoryFragment", "openAlbumDetail: album=${album.name}, folderPath=$folderPath")
        if (folderPath != null) {
            parentFragmentManager.commit {
                replace(R.id.fragment_container, FilesFragment.newInstance(folderPath))
                addToBackStack(null)
            }
        } else {
            SkyFilesLogger.w("MediaCategoryFragment", "openAlbumDetail: parent folderPath is null, falling back to MediaDetailFragment")
            parentFragmentManager.commit {
                replace(R.id.fragment_container, MediaDetailFragment.newInstanceForAlbum(album.name, mode, album.id))
                addToBackStack(null)
            }
        }
    }

    private fun openDocCategoryDetail(category: DocumentCategory) {
        SkyFilesLogger.d("MediaCategoryFragment", "openDocCategoryDetail: category=${category.name}")
        parentFragmentManager.commit {
            replace(R.id.fragment_container, MediaDetailFragment.newInstanceForDocCategory(category.name, category.name))
            addToBackStack(null)
        }
    }

    private suspend fun loadAlbumsAsync(): List<Album> = withContext(Dispatchers.IO) {
        Log.d("SkyFiles", "MediaStore query start for mode: $mode")
        val albumsMap = mutableMapOf<String, Album>()
        
        val uri = when (mode) {
            "IMAGES" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
            "bucket_id",
            "bucket_display_name"
        )

        // Query sorted by DATE_MODIFIED DESC, meaning the first item for each BUCKET_ID is the latest!
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        try {
            val cursor = requireContext().contentResolver.query(uri, projection, null, null, sortOrder)
            cursor?.use { c ->
                val dataIndex = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val nameIndex = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIndex = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateIndex = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val mimeIndex = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val bucketIdIndex = c.getColumnIndexOrThrow("bucket_id")
                val bucketNameIndex = c.getColumnIndexOrThrow("bucket_display_name")

                while (c.moveToNext()) {
                    val data = c.getString(dataIndex) ?: continue
                    val displayName = c.getString(nameIndex) ?: ""
                    val size = c.getLong(sizeIndex)
                    val dateModified = c.getLong(dateIndex)
                    val mime = c.getString(mimeIndex) ?: ""
                    val bucketId = c.getString(bucketIdIndex) ?: "default"
                    val bucketName = c.getString(bucketNameIndex) ?: "Unsorted"

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

                    val existingAlbum = albumsMap[bucketId]
                    if (existingAlbum == null) {
                        // First item encountered for this bucket is the latest by DATE_MODIFIED DESC
                        albumsMap[bucketId] = Album(bucketId, bucketName, 1, fileItem)
                    } else {
                        existingAlbum.count++
                    }
                }
            }
            Log.d("SkyFiles", "loadAlbumsAsync() query success. Total albums: ${albumsMap.size} [mode: $mode]")
        } catch (e: Exception) {
            Log.e("SkyFiles", "loadAlbumsAsync() query failure [mode: $mode]", e)
            e.printStackTrace()
        }
        val result = albumsMap.values.sortedBy { it.name }
        Log.d("SkyFiles", "loadAlbumsAsync() returning ${result.size} albums [mode: $mode]")
        result
    }

    private suspend fun loadDocumentCategoriesAsync(): List<DocumentCategory> = withContext(Dispatchers.IO) {
        Log.d("SkyFiles", "loadDocumentCategoriesAsync() query start")
        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE
        )
        
        var pdfCount = 0
        var wordCount = 0
        var excelCount = 0
        var pptCount = 0
        var textCount = 0
        var archiveCount = 0

        try {
            val cursor = requireContext().contentResolver.query(uri, projection, null, null, null)
            cursor?.use { c ->
                val nameIndex = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeIndex = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)

                while (c.moveToNext()) {
                    val name = c.getString(nameIndex) ?: ""
                    val mime = c.getString(mimeIndex) ?: ""

                    // Categorize by MIME type first
                    if (mime.equals("application/pdf", ignoreCase = true) || name.endsWith(".pdf", ignoreCase = true)) {
                        pdfCount++
                    } else if (mime.equals("application/msword", ignoreCase = true) ||
                        mime.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ignoreCase = true) ||
                        name.endsWith(".doc", ignoreCase = true) || name.endsWith(".docx", ignoreCase = true)) {
                        wordCount++
                    } else if (mime.equals("application/vnd.ms-excel", ignoreCase = true) ||
                        mime.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ignoreCase = true) ||
                        name.endsWith(".xls", ignoreCase = true) || name.endsWith(".xlsx", ignoreCase = true)) {
                        excelCount++
                    } else if (mime.equals("application/vnd.ms-powerpoint", ignoreCase = true) ||
                        mime.equals("application/vnd.openxmlformats-officedocument.presentationml.presentation", ignoreCase = true) ||
                        name.endsWith(".ppt", ignoreCase = true) || name.endsWith(".pptx", ignoreCase = true)) {
                        pptCount++
                    } else if (mime.startsWith("text/", ignoreCase = true) || name.endsWith(".txt", ignoreCase = true)) {
                        textCount++
                    } else if (mime.equals("application/zip", ignoreCase = true) ||
                        mime.equals("application/x-rar-compressed", ignoreCase = true) ||
                        mime.equals("application/x-7z-compressed", ignoreCase = true) ||
                        name.endsWith(".zip", ignoreCase = true) || name.endsWith(".rar", ignoreCase = true) || name.endsWith(".7z", ignoreCase = true) ||
                        name.endsWith(".tar", ignoreCase = true) || name.endsWith(".gz", ignoreCase = true)) {
                        archiveCount++
                    }
                }
            }
            Log.d("SkyFiles", "loadDocumentCategoriesAsync() query success. PDF: $pdfCount, Word: $wordCount, Excel: $excelCount, PPT: $pptCount, Text: $textCount, Archives: $archiveCount")
        } catch (e: Exception) {
            Log.e("SkyFiles", "loadDocumentCategoriesAsync() query failure", e)
            e.printStackTrace()
        }

        val result = listOf(
            DocumentCategory("PDF", pdfCount, R.drawable.file_pdf_icon),
            DocumentCategory("Word", wordCount, R.drawable.file_word_icon),
            DocumentCategory("Excel", excelCount, R.drawable.file_excel_icon),
            DocumentCategory("PowerPoint", pptCount, R.drawable.file_powerpoint_icon),
            DocumentCategory("Text", textCount, R.drawable.file_text_icon),
            DocumentCategory("Archives", archiveCount, R.drawable.file_archive_icon)
        )
        Log.d("SkyFiles", "loadDocumentCategoriesAsync() returning categories: $result")
        result
    }

    data class Album(
        val id: String,
        val name: String,
        var count: Int,
        val latestFile: FileItem
    )

    data class DocumentCategory(
        val name: String,
        val count: Int,
        val iconRes: Int
    )

    private class AlbumsAdapter(
        private val list: List<Album>,
        private val onClick: (Album) -> Unit
    ) : RecyclerView.Adapter<AlbumsAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.skyfiles_album_item, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val album = list[position]
            holder.txtTitle.text = album.name
            holder.txtCount.text = "${album.count} items"
            
            // Load latest cover using Coil
            holder.imgCover.dispose()
            holder.imgCover.setImageDrawable(null)
            holder.imgCover.load(album.latestFile.path to album.latestFile.attributes)
            
            holder.itemView.setOnClickListener { onClick(album) }
        }

        override fun getItemCount(): Int = list.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val imgCover: ImageView = view.findViewById(R.id.imgAlbumCover)
            val txtTitle: TextView = view.findViewById(R.id.txtAlbumTitle)
            val txtCount: TextView = view.findViewById(R.id.txtAlbumCount)
        }
    }

    private class DocumentCategoriesAdapter(
        private val list: List<DocumentCategory>,
        private val onClick: (DocumentCategory) -> Unit
    ) : RecyclerView.Adapter<DocumentCategoriesAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.skyfiles_document_category_item, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val cat = list[position]
            holder.txtName.text = cat.name
            holder.txtCount.text = "${cat.count} files"
            holder.imgIcon.setImageResource(cat.iconRes)
            holder.itemView.setOnClickListener { onClick(cat) }
        }

        override fun getItemCount(): Int = list.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val imgIcon: ImageView = view.findViewById(R.id.imgCategoryIcon)
            val txtName: TextView = view.findViewById(R.id.txtCategoryName)
            val txtCount: TextView = view.findViewById(R.id.txtCategoryCount)
        }
    }

    companion object {
        private const val ARG_MODE = "mode"

        fun newInstance(mode: String): MediaCategoryFragment {
            val fragment = MediaCategoryFragment()
            val args = Bundle().apply {
                putString(ARG_MODE, mode)
            }
            fragment.arguments = args
            return fragment
        }
    }
}
