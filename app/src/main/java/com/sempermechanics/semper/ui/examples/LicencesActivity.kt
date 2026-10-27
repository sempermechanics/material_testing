package com.sempermechanics.semper.ui.examples

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sempermechanics.semper.R
import com.sempermechanics.semper.databinding.ActivityLicencesBinding
import com.sempermechanics.semper.databinding.ItemLicenceRowBinding
import com.sempermechanics.semper.ui.common.Insets
import com.sempermechanics.semper.ui.common.auth.ExternalLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Credits & licences (ADR-031): every part of the app and its licence in one
 * list ([LicencePage]), with the full texts on the website. With [EXTRA_PAGE]
 * it shows one page the app keeps itself: an example set's credit, or a short
 * notice that has to travel with the app.
 */
@MainThread
class LicencesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLicencesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLicencesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Insets.padVertical(binding.licencesRoot)
        binding.btnLicencesBack.setOnClickListener { finish() }
        binding.rvLicences.layoutManager = LinearLayoutManager(this)
        val key = intent.getStringExtra(EXTRA_PAGE)
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { load(key) }
            if (items == null) finish() else binding.rvLicences.adapter = ItemAdapter(items, ::open)
        }
    }

    /** The list, or page [key]'s title and paragraphs; null for a page that does not exist. */
    private fun load(key: String?): List<LicenceItem>? = if (key == null) {
        LicencePage.items(this, assets)
    } else {
        LicencePage.page(this, assets, key)?.let { (title, paragraphs) ->
            listOf(LicenceItem.Heading(title)) + paragraphs.filter(String::isNotBlank).map(LicenceItem::Paragraph)
        }
    }

    private fun open(target: Target) = when (target) {
        is Target.Page -> startActivity(intent(this).putExtra(EXTRA_PAGE, target.key))
        is Target.Link -> ExternalLinks.open(this, target.url)
    }

    /** A heading, a paragraph, or a row that opens its [Target]. */
    private class ItemAdapter(
        private val items: List<LicenceItem>,
        private val open: (Target) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        class Holder(view: View) : RecyclerView.ViewHolder(view)

        override fun getItemCount(): Int = items.size

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is LicenceItem.Heading -> R.layout.item_licence_heading
            is LicenceItem.Paragraph -> R.layout.item_licence_paragraph
            is LicenceItem.Row -> R.layout.item_licence_row
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            Holder(LayoutInflater.from(parent.context).inflate(viewType, parent, false))

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is LicenceItem.Heading -> (holder.itemView as TextView).text = item.text
                is LicenceItem.Paragraph -> (holder.itemView as TextView).text = item.text
                is LicenceItem.Row -> {
                    val row = ItemLicenceRowBinding.bind(holder.itemView)
                    row.tvLicenceRowTitle.text = item.title
                    row.tvLicenceRowSubtitle.text = item.subtitle
                    row.tvLicenceRowSubtitle.visibility = if (item.subtitle.isBlank()) View.GONE else View.VISIBLE
                    val target = item.target
                    row.ivLicenceRowOpen.visibility = if (target == null) View.INVISIBLE else View.VISIBLE
                    row.root.isClickable = target != null
                    row.root.setOnClickListener(target?.let { t -> View.OnClickListener { open(t) } })
                }
            }
        }
    }

    companion object {
        /** A [LicencePage.page] key: the one page to show instead of the list. */
        const val EXTRA_PAGE = "licences_page"

        fun intent(context: Context): Intent = Intent(context, LicencesActivity::class.java)
    }
}
