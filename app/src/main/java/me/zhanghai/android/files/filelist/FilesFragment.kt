/*
 * Copyright (c) 2026 SkyFiles
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import java8.nio.file.Path
import me.zhanghai.android.files.util.putArgs

class FilesFragment : FileListFragment() {

    companion object {
        fun newInstance(path: Path): FilesFragment {
            val args = FileListFragment.Args(FileListActivity.createViewIntent(path))
            return FilesFragment().putArgs(args)
        }
    }
}
