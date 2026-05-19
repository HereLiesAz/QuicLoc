package com.hereliesaz.quicloc

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Legacy `RecyclerView.Adapter` from the pre-Compose UI. The current
 * settings screen renders the whitelist via Compose ([MainActivity]'s
 * `QuicLocScreen`), so this class is unused.
 *
 * Slated for deletion (along with `whitelist_item.xml`) — kept temporarily
 * so older diffs/blame still resolve.
 */
@Deprecated("Replaced by Compose rendering in QuicLocScreen. Will be removed.")
class WhitelistAdapter(
    private var numbers: List<String>,
    private val onDeleteClick: (String) -> Unit
) : RecyclerView.Adapter<WhitelistAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val phoneText: TextView = view.findViewById(R.id.phoneNumberText)
        val deleteBtn: ImageButton = view.findViewById(R.id.deleteButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.whitelist_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val number = numbers[position]
        holder.phoneText.text = number
        holder.deleteBtn.setOnClickListener { onDeleteClick(number) }
    }

    override fun getItemCount() = numbers.size

    fun updateNumbers(newNumbers: List<String>) {
        numbers = newNumbers
        notifyDataSetChanged()
    }
}
