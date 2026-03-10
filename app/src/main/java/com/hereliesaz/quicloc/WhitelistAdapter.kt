package com.hereliesaz.quicloc

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

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
