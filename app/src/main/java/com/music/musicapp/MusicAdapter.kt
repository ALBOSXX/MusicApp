package com.music.musicapp

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MusicAdapter(private val music: MutableList<MusicItem>, val listenerAdapter: OnItemClickListener) :
    RecyclerView.Adapter<MusicAdapter.MusicViewHolder>() {

    class MusicViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val name: TextView = itemView.findViewById(R.id.nameLabel)
        val durationLabel : TextView = itemView.findViewById(R.id.durationLabel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MusicViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.music_item, parent, false)
        return MusicViewHolder(view)
    }

    override fun getItemCount() = music.size

    fun formatTime(millis: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    override fun onBindViewHolder(holder: MusicViewHolder, position: Int) {
        val song = music[position]
        holder.name.text = song.name
        holder.durationLabel.text = formatTime(song.duration)

        holder.itemView.setOnClickListener {
            listenerAdapter.onItemClick(song)
        }
    }

    fun updateRooms(newRooms: List<MusicItem>) {
        music.clear()
        music.addAll(newRooms)
        notifyDataSetChanged()
    }
}