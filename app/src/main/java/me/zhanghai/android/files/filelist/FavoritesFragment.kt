/*
 * Copyright (c) 2026 SkyFiles
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import me.zhanghai.android.files.R
import me.zhanghai.android.files.settings.Settings

class FavoritesFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.skyfiles_favorites_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val toolbar = view.findViewById<Toolbar>(R.id.toolbar)
        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(toolbar)
        activity.supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.title = "Favorites"
        toolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }

        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerViewFavorites)
        val emptyLayout = view.findViewById<View>(R.id.layoutEmptyFavorites)

        recyclerView.layoutManager = LinearLayoutManager(context)

        Settings.BOOKMARK_DIRECTORIES.observe(viewLifecycleOwner) { bookmarks ->
            if (bookmarks.isNullOrEmpty()) {
                recyclerView.visibility = View.GONE
                emptyLayout.visibility = View.VISIBLE
            } else {
                recyclerView.visibility = View.VISIBLE
                emptyLayout.visibility = View.GONE
                recyclerView.adapter = object : RecyclerView.Adapter<BookmarkViewHolder>() {
                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookmarkViewHolder {
                        val view = LayoutInflater.from(parent.context).inflate(R.layout.skyfiles_favorite_item, parent, false)
                        return BookmarkViewHolder(view)
                    }

                    override fun onBindViewHolder(holder: BookmarkViewHolder, position: Int) {
                        val bookmark = bookmarks[position]
                        holder.favTitle.text = bookmark.name
                        holder.favSubtitle.text = bookmark.path.toString()
                        holder.itemView.setOnClickListener {
                            val filesFragment = FilesFragment.newInstance(bookmark.path)
                            parentFragmentManager.commit {
                                replace(R.id.fragment_container, filesFragment)
                                addToBackStack(null)
                            }
                        }
                    }

                    override fun getItemCount(): Int = bookmarks.size
                }
            }
        }
    }

    private class BookmarkViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val favTitle: TextView = view.findViewById(R.id.favTitle)
        val favSubtitle: TextView = view.findViewById(R.id.favSubtitle)
    }
}
