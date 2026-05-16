// app/src/main/java/com/bgi/pathfinder/ui/ChatAdapter.kt
package com.bgi.pathfinder.ui

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bgi.pathfinder.R

data class ChatMessage(
    val text: String,
    val isUser: Boolean
)

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.ViewHolder>() {

    private val messages = mutableListOf<ChatMessage>()

    fun addMessage(msg: ChatMessage) {
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
    }

    fun clear() {
        messages.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val msg = messages[position]
        holder.tvMessage.text = msg.text

        val params = holder.tvMessage.layoutParams as FrameLayout.LayoutParams
        if (msg.isUser) {
            params.gravity = Gravity.END
            holder.tvMessage.setBackgroundResource(R.drawable.chat_bubble_user)
            holder.tvMessage.setTextColor(0xFFFFFFFF.toInt())
        } else {
            params.gravity = Gravity.START
            holder.tvMessage.setBackgroundResource(R.drawable.chat_bubble_bg)
            holder.tvMessage.setTextColor(0xFFE0E0FF.toInt())
        }
        holder.tvMessage.layoutParams = params
    }

    override fun getItemCount(): Int = messages.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvMessage: TextView = view.findViewById(R.id.tvChatMessage)
    }
}
