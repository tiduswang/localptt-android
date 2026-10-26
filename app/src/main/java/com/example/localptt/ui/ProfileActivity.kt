package com.example.localptt.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.localptt.AppBus
import com.example.localptt.Profile
import com.example.localptt.R
import com.example.localptt.data.Avatars
import com.example.localptt.room.RoomManager

/**
 * 我的名片：选一个卡通头像 + 起一个名字。
 * 保存后会立刻同步给房间里的其他人（主机重发名单 / 客户端重发 Hello），
 * 这样不用重新建组，对端也能看到你的新形象。
 */
class ProfileActivity : AppCompatActivity() {

    private var picked: String = AppBus.myAvatar
    private lateinit var etNick: EditText
    private lateinit var ivBig: ImageView
    private lateinit var tvBigName: TextView
    private lateinit var grid: GridView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        etNick = findViewById(R.id.etNick)
        ivBig = findViewById(R.id.ivBigAvatar)
        tvBigName = findViewById(R.id.tvBigName)
        grid = findViewById(R.id.gvAvatars)

        // 没设置过名片时给个默认名，避免空白
        val current = AppBus.myName.ifBlank { Profile.defaultName() }
        etNick.setText(current)
        etNick.setSelection(current.length)
        tvBigName.text = current

        findViewById<View>(R.id.btnProfileBack).setOnClickListener { finish() }

        etNick.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                tvBigName.text = s?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: "小朋友"
            }
        })

        grid.adapter = AvatarGridAdapter()
        grid.setOnItemClickListener { _, _, i, _ ->
            picked = Avatars.keys[i]
            refreshBig()
            (grid.adapter as BaseAdapter).notifyDataSetChanged()
        }

        findViewById<View>(R.id.btnSaveProfile).setOnClickListener { save() }

        refreshBig()
    }

    private fun refreshBig() {
        ivBig.setImageResource(Avatars.drawableOf(picked))
    }

    private fun save() {
        val name = etNick.text.toString().trim().ifEmpty { Profile.defaultName() }
        Profile.save(this, name, picked)

        // 已在房间里 → 立刻把新形象同步出去，无需重新建组。
        // 走 RoomManager 统一入口：不做-looking 的直接碰 server/client 实例，
        // 将来换 Host/Client 实现也不用改这里。
        runCatching {
            AppBus.myName = name
            AppBus.myAvatar = picked
            RoomManager.announceProfile()
        }.onFailure { e ->
            android.util.Log.w("LocalPTT", "同步名片失败：${e.message}")
        }

        Toast.makeText(this, "🎉 名片保存好啦", Toast.LENGTH_SHORT).show()
        finish()
    }

    private inner class AvatarGridAdapter : BaseAdapter() {
        override fun getCount(): Int = Avatars.keys.size
        override fun getItem(i: Int): String = Avatars.keys[i]
        override fun getItemId(i: Int): Long = i.toLong()

        override fun getView(i: Int, conv: View?, parent: ViewGroup): View {
            val v = conv ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.item_avatar, parent, false)
            val key = Avatars.keys[i]
            v.findViewById<ImageView>(R.id.ivAvatarPick).setImageResource(Avatars.drawableOf(key))
            v.findViewById<TextView>(R.id.tvAvatarName).text = Avatars.labelOf(key)
            // 选中项用黄色圆底高亮，小朋友一眼看得出选了哪个
            v.findViewById<View>(R.id.avatarRing).setBackgroundResource(
                if (key == picked) R.drawable.shape_circle_yellow else R.drawable.shape_circle_white
            )
            return v
        }
    }
}
