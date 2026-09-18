package io.legado.app.ui.config

import android.content.Context
import android.util.AttributeSet
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import io.legado.app.R
import io.legado.app.ui.widget.image.CoverImageView

class CoverPreviewPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    init {
        layoutResource = R.layout.preference_cover_preview
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(R.id.cover_preview_short) as CoverImageView).load(
            name = "阅读引擎", author = "本地书源",
        )
        (holder.findViewById(R.id.cover_preview_long) as CoverImageView).load(
            name = "阅读引擎支持小说、漫画、听书与视频", author = "本地书源",
        )
    }

    fun refresh() = notifyChanged()
}
