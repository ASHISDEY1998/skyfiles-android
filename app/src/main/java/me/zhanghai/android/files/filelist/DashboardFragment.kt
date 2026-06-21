/*
 * Copyright (c) 2026 SkyFiles
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.animation.ObjectAnimator
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.compat.directoryCompat
import me.zhanghai.android.files.compat.isPrimaryCompat
import me.zhanghai.android.files.compat.isRemovableCompat
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.asFileSize
import me.zhanghai.android.files.file.fileSize
import me.zhanghai.android.files.file.formatShort
import me.zhanghai.android.files.file.iconRes
import me.zhanghai.android.files.file.loadFileItem
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.storage.StorageListFragment
import me.zhanghai.android.files.storage.StorageVolumeListLiveData
import me.zhanghai.android.files.util.isMounted
import me.zhanghai.android.files.util.valueCompat
import java.io.File

class DashboardFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.skyfiles_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpLocations(view)
        
        // Navigation Buttons
        view.findViewById<View>(R.id.btnDrawer).setOnClickListener {
            (activity as? HomeActivity)?.openDrawer()
        }
        view.findViewById<View>(R.id.btnSearch).setOnClickListener {
            parentFragmentManager.commit {
                replace(R.id.fragment_container, SearchFragment())
                addToBackStack(null)
            }
        }
        view.findViewById<View>(R.id.btnSettings).setOnClickListener {
            parentFragmentManager.commit {
                replace(R.id.fragment_container, SettingsFragment())
                addToBackStack(null)
            }
        }
        view.findViewById<View>(R.id.cardFavorites).setOnClickListener {
            parentFragmentManager.commit {
                replace(R.id.fragment_container, FavoritesFragment())
                addToBackStack(null)
            }
        }
        view.findViewById<View>(R.id.cardStorageOverview).setOnClickListener {
            launchPath(Paths.get(Environment.getExternalStorageDirectory().path))
        }
        view.findViewById<View>(R.id.containerNetworkStorage).setOnClickListener {
            handleNetworkStorageClick()
        }
        
        // Trigger asynchronous data loading
        lifecycleScope.launch {
            loadStorageAsync(view)
            loadFavoritesAsync(view)
            loadRecentsAsync(view)
            loadContinueWatchingAsync(view)
        }
        
        // Trigger entry fade-in and slide-up animations
        animateEntry(view.findViewById(R.id.cardStorageOverview), 0)
        animateEntry(view.findViewById(R.id.gridLocations), 50)
        animateEntry(view.findViewById(R.id.cardFavorites), 100)
        animateEntry(view.findViewById(R.id.cardRecents), 150)
        animateEntry(view.findViewById(R.id.cardContinueWatching), 200)
    }

    private fun animateEntry(view: View?, delayMs: Long) {
        if (view == null) return
        view.alpha = 0f
        view.translationY = 40f // subtle slide-up
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(280) // within the 250ms - 300ms spec
            .setStartDelay(delayMs)
            .start()
    }

    private fun setUpLocations(view: View) {
        val rootPath = Environment.getExternalStorageDirectory()

        view.findViewById<View>(R.id.btnPhoneStorage).setOnClickListener {
            launchPath(Paths.get(rootPath.path))
        }
        view.findViewById<View>(R.id.btnDownloads).setOnClickListener {
            launchPath(Paths.get(File(rootPath, Environment.DIRECTORY_DOWNLOADS).path))
        }
        view.findViewById<View>(R.id.btnPictures).setOnClickListener {
            launchCategory("IMAGES")
        }
        view.findViewById<View>(R.id.btnVideos).setOnClickListener {
            launchCategory("VIDEOS")
        }
        view.findViewById<View>(R.id.btnDocuments).setOnClickListener {
            launchCategory("DOCUMENTS")
        }
        view.findViewById<View>(R.id.btnAudio).setOnClickListener {
            launchCategory("AUDIO")
        }
        view.findViewById<View>(R.id.btnNetworkStorage).setOnClickListener {
            handleNetworkStorageClick()
        }
    }

    private fun launchCategory(mode: String) {
        parentFragmentManager.commit {
            replace(R.id.fragment_container, MediaCategoryFragment.newInstance(mode))
            addToBackStack(null)
        }
    }

    private suspend fun loadStorageAsync(view: View) {
        val context = requireContext()
        StorageVolumeListLiveData.observe(viewLifecycleOwner) { storageVolumes ->
            viewLifecycleOwner.lifecycleScope.launch {
                // Perform space calculations on a background IO thread
                val storageData = withContext(Dispatchers.IO) {
                    val primaryVolume = storageVolumes.firstOrNull { it.isPrimaryCompat }
                    var primaryData: StorageData? = null
                    if (primaryVolume != null) {
                        primaryVolume.directoryCompat?.let { dir ->
                            val total = dir.totalSpace
                            val free = dir.usableSpace
                            val used = total - free
                            val percentUsed = if (total > 0) (used * 100 / total).toInt() else 0
                            val percentFree = 100 - percentUsed
                            primaryData = StorageData(total, free, used, percentUsed, percentFree)
                        }
                    }

                    val sdVolume = storageVolumes.firstOrNull { it.isRemovableCompat && it.isMounted }
                    var sdData: StorageData? = null
                    if (sdVolume != null) {
                        sdVolume.directoryCompat?.let { dir ->
                            val total = dir.totalSpace
                            val free = dir.usableSpace
                            val used = total - free
                            val percentUsed = if (total > 0) (used * 100 / total).toInt() else 0
                            val percentFree = 100 - percentUsed
                            sdData = StorageData(total, free, used, percentUsed, percentFree)
                        }
                    }
                    Pair(primaryData, sdData)
                }

                val primary = storageData.first
                val sd = storageData.second

                // Update UI on main thread
                if (primary != null) {
                    val freeStr = primary.free.asFileSize().formatHumanReadable(context)
                    val totalStr = primary.total.asFileSize().formatHumanReadable(context)
                    val usedStr = primary.used.asFileSize().formatHumanReadable(context)

                    view.findViewById<TextView>(R.id.txtFreeSpaceLarge).text = "$freeStr Free"
                    view.findViewById<TextView>(R.id.txtUsedSpace).text = "$usedStr Used"
                    view.findViewById<TextView>(R.id.txtTotalSpace).text = "$totalStr Total"
                    view.findViewById<TextView>(R.id.txtUsedPercent).text = "${primary.percentUsed}% Used"
                    view.findViewById<TextView>(R.id.txtFreePercent).text = "${primary.percentFree}% Free"

                    // Animate primary storage bar
                    val progressBar = view.findViewById<ProgressBar>(R.id.progressStorageCenter)
                    ObjectAnimator.ofInt(progressBar, "progress", 0, primary.percentUsed)
                        .setDuration(300)
                        .start()
                }

                val containerSdCard = view.findViewById<View>(R.id.containerSdCard)
                if (sd != null) {
                    containerSdCard.visibility = View.VISIBLE
                    val sdFreeStr = sd.free.asFileSize().formatHumanReadable(context)
                    val sdTotalStr = sd.total.asFileSize().formatHumanReadable(context)
                    val sdUsedStr = sd.used.asFileSize().formatHumanReadable(context)

                    view.findViewById<TextView>(R.id.txtSdFreeSpaceLarge).text = "$sdFreeStr Free"
                    view.findViewById<TextView>(R.id.txtSdUsedSpace).text = "$sdUsedStr Used"
                    view.findViewById<TextView>(R.id.txtSdTotalSpace).text = "$sdTotalStr Total"

                    // Animate SD storage bar
                    val sdProgressBar = view.findViewById<ProgressBar>(R.id.progressSdStorageCenter)
                    ObjectAnimator.ofInt(sdProgressBar, "progress", 0, sd.percentUsed)
                        .setDuration(300)
                        .start()
                } else {
                    containerSdCard.visibility = View.GONE
                }

                // Load Network Storage summary details
                val networkPrefs = context.getSharedPreferences("skyfiles_network", Context.MODE_PRIVATE)
                val savedCount = Settings.STORAGES.valueCompat.filter {
                    it is me.zhanghai.android.files.storage.SmbServer ||
                    it is me.zhanghai.android.files.storage.FtpServer ||
                    it is me.zhanghai.android.files.storage.SftpServer ||
                    it is me.zhanghai.android.files.storage.WebDavServer
                }.size
                view.findViewById<TextView>(R.id.txtSavedServers).text = if (savedCount == 1) "1 Saved Profile" else "$savedCount Saved Profiles"

                val activeCount = networkPrefs.getStringSet("active_connections", emptySet())?.size ?: 0
                view.findViewById<TextView>(R.id.txtConnectedServers).text = if (activeCount == 1) "1 Active Connection" else "$activeCount Active Connections"

                val lastConnected = networkPrefs.getString("last_connected", "None") ?: "None"
                view.findViewById<TextView>(R.id.txtLastConnectedServer).text = "Last connected: $lastConnected"
            }
        }
    }

    private suspend fun loadFavoritesAsync(view: View) {
        // Observe Favorites/Bookmarks
        Settings.BOOKMARK_DIRECTORIES.observe(viewLifecycleOwner) { favorites ->
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                val txtFavCount = view.findViewById<TextView>(R.id.txtFavCount)
                val txtRecentFavName = view.findViewById<TextView>(R.id.txtRecentFavName)

                if (favorites.isNullOrEmpty()) {
                    txtFavCount.text = "No favorites yet"
                    txtRecentFavName.text = "Bookmark folders to view them here."
                } else {
                    val count = favorites.size
                    txtFavCount.text = if (count == 1) "1 Favorite" else "$count Favorites"
                    
                    // Most recent favorite is the last one added
                    val mostRecent = favorites.last()
                    txtRecentFavName.text = "Last added: ${mostRecent.name}"
                }
            }
        }
    }

    private suspend fun loadRecentsAsync(view: View) {
        val context = requireContext()
        val emptyLayout = view.findViewById<View>(R.id.layoutEmptyRecents)
        val listRecents = view.findViewById<LinearLayout>(R.id.listRecents)

        // Query database on background thread
        val recentEntities = RecentOpenRepository.getRecentFiles(20)

        if (recentEntities.isEmpty()) {
            emptyLayout.visibility = View.VISIBLE
            listRecents.visibility = View.GONE
            return
        }

        // Resolve metadata for the top 20 opened items asynchronously
        val resolvedFiles = withContext(Dispatchers.IO) {
            recentEntities.mapNotNull { entity ->
                try {
                    Paths.get(entity.path).loadFileItem()
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
        }

        if (resolvedFiles.isEmpty()) {
            emptyLayout.visibility = View.VISIBLE
            listRecents.visibility = View.GONE
            return
        }

        emptyLayout.visibility = View.GONE
        listRecents.visibility = View.VISIBLE
        listRecents.removeAllViews()

        for (file in resolvedFiles) {
            val row = LayoutInflater.from(context).inflate(R.layout.skyfiles_favorite_item, listRecents, false)
            row.findViewById<TextView>(R.id.favTitle).text = file.name
            
            val subtitle = row.findViewById<TextView>(R.id.favSubtitle)
            val lastModified = file.attributes.lastModifiedTime().toInstant().formatShort(context)
            val size = file.attributes.fileSize.formatHumanReadable(context)
            subtitle.text = "$lastModified | $size"
            
            val iconView = row.findViewById<ImageView>(R.id.imgFavIcon)
            iconView.setImageResource(file.mimeType.iconRes)
            iconView.imageTintList = ContextCompat.getColorStateList(context, R.color.skyfiles_accent)
            
            row.setOnClickListener {
                val intent = OpenFileActivity.createIntent(file.path, file.mimeType)
                startActivity(intent)
            }
            listRecents.addView(row)
        }
    }

    private fun launchPath(path: Path) {
        val filesFragment = FilesFragment.newInstance(path)
        parentFragmentManager.commit {
            replace(R.id.fragment_container, filesFragment)
            addToBackStack(null)
        }
    }

    private fun handleNetworkStorageClick() {
        Log.d("SkyFiles", "Dashboard Network Storage card click triggered")
        val context = requireContext()
        val servers = Settings.STORAGES.valueCompat.filter {
            it is me.zhanghai.android.files.storage.SmbServer ||
            it is me.zhanghai.android.files.storage.FtpServer ||
            it is me.zhanghai.android.files.storage.SftpServer ||
            it is me.zhanghai.android.files.storage.WebDavServer
        }
        val firstServer = servers.firstOrNull()
        if (firstServer != null) {
            val path = firstServer.path
            Log.d("SkyFiles", "Selected server profile: ${firstServer.getName(context)}")
            Log.d("SkyFiles", "Navigation target: $path")
            if (path != null) {
                launchPath(path)
                return
            }
        }
        Log.d("SkyFiles", "No server profiles exist. Navigation target: StorageListFragment")
        parentFragmentManager.commit {
            replace(R.id.fragment_container, StorageListFragment())
            addToBackStack(null)
        }
    }

    private suspend fun loadContinueWatchingAsync(view: View) {
        val context = requireContext()
        val lblContinueWatching = view.findViewById<TextView>(R.id.lblContinueWatching)
        val cardContinueWatching = view.findViewById<View>(R.id.cardContinueWatching)
        val listContinueWatching = view.findViewById<LinearLayout>(R.id.listContinueWatching)

        // Query active resume entries on background thread
        val resumeEntries = withContext(Dispatchers.IO) {
            me.zhanghai.android.files.media.MediaResumeRepository.instance.getActiveResumeEntries()
        }

        if (resumeEntries.isEmpty()) {
            lblContinueWatching.visibility = View.GONE
            cardContinueWatching.visibility = View.GONE
            return
        }

        lblContinueWatching.visibility = View.VISIBLE
        cardContinueWatching.visibility = View.VISIBLE
        listContinueWatching.removeAllViews()

        for (entry in resumeEntries) {
            val row = LayoutInflater.from(context).inflate(R.layout.skyfiles_favorite_item, listContinueWatching, false)
            val name = Paths.get(entry.path).fileName.toString()
            row.findViewById<TextView>(R.id.favTitle).text = name
            
            val subtitle = row.findViewById<TextView>(R.id.favSubtitle)
            val pct = (entry.position * 100 / entry.duration).toInt()
            val lastMod = java.time.Instant.ofEpochMilli(entry.timestamp).formatShort(context)
            subtitle.text = "Progress: $pct% | Last opened $lastMod"
            
            val iconView = row.findViewById<ImageView>(R.id.imgFavIcon)
            iconView.setImageResource(R.drawable.video_icon_white_24dp)
            iconView.imageTintList = ContextCompat.getColorStateList(context, R.color.skyfiles_accent)
            
            row.setOnClickListener {
                me.zhanghai.android.files.video.VideoPlayerActivity.start(
                    context,
                    entry.path,
                    playlist = listOf(entry.path),
                    source = me.zhanghai.android.files.media.PlaylistSource.SOURCE_RECENTS
                )
            }
            listContinueWatching.addView(row)
        }
    }

    private data class StorageData(
        val total: Long,
        val free: Long,
        val used: Long,
        val percentUsed: Int,
        val percentFree: Int
    )
}
