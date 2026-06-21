/*
 * Copyright (c) 2026 SkyFiles
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import java.util.concurrent.ConcurrentHashMap

class MediaCategoryViewModel : ViewModel() {
    // Cache permission grant states per mode/category
    val permissionsGranted = ConcurrentHashMap<String, Boolean>()
    
    // Cache the loaded media items or albums to avoid redundant queries on configuration change
    val loadedAlbums = MutableLiveData<List<MediaCategoryFragment.Album>>()
    val loadedDocuments = MutableLiveData<List<MediaCategoryFragment.DocumentCategory>>()
}
