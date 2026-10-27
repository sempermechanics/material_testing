package com.sempermechanics.semper.ui.examples

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sempermechanics.semper.R
import com.sempermechanics.semper.databinding.ActivityLicencesBinding
import com.sempermechanics.semper.ui.common.Insets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Credits & licences (ADR-031): who made each bundled example set and under
 * which licence, every library the app is built with, and the full texts of
 * the licences and notices it redistributes ([LicencePage]).
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
        lifecycleScope.launch {
            val blocks = withContext(Dispatchers.IO) { LicencePage.build(this@LicencesActivity, assets) }
            binding.rvLicences.adapter = BlockAdapter(blocks)
        }
    }

    /** One TextView per [LicenceBlock]; headings and paragraphs have their own layouts. */
    private class BlockAdapter(private val blocks: List<LicenceBlock>) : RecyclerView.Adapter<BlockAdapter.Holder>() {

        class Holder(val text: TextView) : RecyclerView.ViewHolder(text)

        override fun getItemCount(): Int = blocks.size

        override fun getItemViewType(position: Int): Int =
            if (blocks[position].heading) R.layout.item_licence_heading else R.layout.item_licence_paragraph

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(viewType, parent, false) as TextView)

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.text.text = blocks[position].text
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, LicencesActivity::class.java)
    }
}
