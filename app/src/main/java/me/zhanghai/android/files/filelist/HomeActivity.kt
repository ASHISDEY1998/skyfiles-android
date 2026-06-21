/*
 * Copyright (c) 2026 SkyFiles
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.Intent
import android.os.Bundle
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.commit
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import java8.nio.file.Path
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.navigation.NavigationFragment
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.valueCompat

class HomeActivity : AppActivity(), NavigationFragment.Listener {
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationFragment: NavigationFragment
    private val currentPathLiveData = MutableLiveData<Path>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.skyfiles_home_activity)

        drawerLayout = findViewById(R.id.drawerLayout)

        if (savedInstanceState == null) {
            navigationFragment = NavigationFragment()
            supportFragmentManager.commit {
                add(R.id.navigationFragmentContainer, navigationFragment)
            }

            val path = intent.extraPath
            val fragment = if (path != null) {
                FilesFragment.newInstance(path)
            } else {
                DashboardFragment()
            }
            supportFragmentManager.commit {
                add(R.id.fragment_container, fragment)
            }
        } else {
            navigationFragment = supportFragmentManager.findFragmentById(R.id.navigationFragmentContainer) as NavigationFragment
        }
        navigationFragment.listener = this
    }

    fun openDrawer() {
        drawerLayout.openDrawer(GravityCompat.START)
    }

    fun updateCurrentPath(path: Path) {
        currentPathLiveData.value = path
    }

    override val currentPath: Path
        get() {
            val fragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
            if (fragment is FileListFragment) {
                return fragment.currentPath
            }
            return Settings.FILE_LIST_DEFAULT_DIRECTORY.valueCompat
        }

    override fun navigateTo(path: Path) {
        val fragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (fragment is FileListFragment) {
            fragment.navigateTo(path)
        } else {
            val filesFragment = FilesFragment.newInstance(path)
            supportFragmentManager.commit {
                replace(R.id.fragment_container, filesFragment)
                addToBackStack(null)
            }
        }
    }

    override fun navigateToRoot(path: Path) {
        val fragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (fragment is FileListFragment) {
            fragment.navigateToRoot(path)
        } else {
            val filesFragment = FilesFragment.newInstance(path)
            supportFragmentManager.commit {
                replace(R.id.fragment_container, filesFragment)
                addToBackStack(null)
            }
        }
    }

    override fun navigateToDefaultRoot() {
        navigateToRoot(Settings.FILE_LIST_DEFAULT_DIRECTORY.valueCompat)
    }

    override fun observeCurrentPath(owner: LifecycleOwner, observer: (Path) -> Unit) {
        currentPathLiveData.observe(owner, observer)
    }

    override fun closeNavigationDrawer() {
        drawerLayout.closeDrawer(GravityCompat.START)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val path = intent.extraPath
        if (path != null) {
            navigateToRoot(path)
        }
    }
}
