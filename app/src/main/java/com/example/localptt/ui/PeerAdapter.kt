package com.example.localptt.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.localptt.AppBus
import com.example.localptt.R
import com.example.localptt.data.Avatars
import com.example.localptt.data.Peer

/** 首页「房间里的小伙伴」列表：头像 + 名字 + 身份（小队长/小伙伴）。 */
class PeerAdapter : RecyclerView.Adapter<PeerAdapter.VH>() {

    private val items = mutableListOf<Peer>()

    fun submit(list: List<Peer>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: ImageView = v.findViewById(R.id.ivPeerAvatar)
        val name: TextView = v.findViewById(R.id.tvPeerName)
        val role: TextView = v.findViewById(R.id.tvPeerRole)
        val state: TextView = v.findViewById(R.id.tvPeerState)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_peer, p, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val p = items[pos]
        h.avatar.setImageResource(Avatars.drawableOf(p.avatar))
        h.name.text = if (p.id == AppBus.myId) "${p.name}（我）" else p.name
        h.role.text = if (p.role == "host") "🏠 小队长（开房间的人）" else "🧒 小伙伴"
        h.state.text = "🟢"
    }

    override fun getItemCount() = items.size
}
