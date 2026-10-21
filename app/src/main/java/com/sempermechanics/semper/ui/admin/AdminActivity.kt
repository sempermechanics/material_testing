package com.sempermechanics.semper.ui.admin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.net.AdminUserDto
import com.sempermechanics.semper.databinding.ActivityAdminBinding
import com.sempermechanics.semper.databinding.ItemAdminUserBinding
import com.sempermechanics.semper.ui.common.Insets
import com.sempermechanics.semper.ui.common.dialog.Feedback
import com.sempermechanics.semper.ui.common.setBusy
import kotlinx.coroutines.launch

/**
 * Admin-only "Access requests" screen: lists PENDING users and approves/denies
 * them via the /v1/admin endpoints. Reached from the Home settings sheet, and
 * only shown to accounts whose backend role is `admin`.
 */
@MainThread
class AdminActivity : AppCompatActivity() {

    /** The list and the approvals, kept across a rotation (ADR-016). */
    private val viewModel: AdminViewModel by viewModels()

    private lateinit var binding: ActivityAdminBinding
    private val adapter = RequestAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Insets.padVertical(binding.adminRoot)

        binding.rvRequests.layoutManager = LinearLayoutManager(this)
        binding.rvRequests.adapter = adapter

        observeViewModel()
        viewModel.start()
    }

    /** The list, the spinner and each call's outcome once, on whichever screen is started. */
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.busy.collect(::setLoading) }
                launch { viewModel.users.collect { users -> users?.let(::showUsers) } }
                viewModel.outcomes.collect(::showOutcome)
            }
        }
    }

    private fun showUsers(users: List<AdminUserDto>) {
        adapter.submitList(users)
        binding.tvEmpty.isVisible = users.isEmpty()
    }

    private fun showOutcome(outcome: AdminViewModel.Outcome) {
        when (outcome) {
            is AdminViewModel.Outcome.Say -> Feedback.toast(this, outcome.text, long = outcome.long)
            AdminViewModel.Outcome.SignedOut -> {
                Feedback.toast(this, R.string.error_generic, long = true)
                finish()
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressAdmin.setBusy(loading, idleVisibility = View.GONE)
    }

    private inner class RequestAdapter : ListAdapter<AdminUserDto, RequestAdapter.Holder>(BY_UID) {

        inner class Holder(val row: ItemAdminUserBinding) : RecyclerView.ViewHolder(row.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemAdminUserBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val u = getItem(position)
            holder.row.tvEmail.text = u.email ?: u.uid
            holder.row.tvName.text = u.displayName ?: ""
            holder.row.tvName.isVisible = !u.displayName.isNullOrBlank()
            holder.row.btnApprove.setOnClickListener { viewModel.act(u, AdminViewModel.ACTION_APPROVE) }
            holder.row.btnDeny.setOnClickListener { viewModel.act(u, AdminViewModel.ACTION_REVOKE) }
        }
    }

    private companion object {
        val BY_UID = object : DiffUtil.ItemCallback<AdminUserDto>() {
            override fun areItemsTheSame(oldItem: AdminUserDto, newItem: AdminUserDto) = oldItem.uid == newItem.uid

            override fun areContentsTheSame(oldItem: AdminUserDto, newItem: AdminUserDto) = oldItem == newItem
        }
    }
}
