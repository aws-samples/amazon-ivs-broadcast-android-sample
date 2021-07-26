package com.amazonaws.ivs.basicbroadcast.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import com.amazonaws.ivs.basicbroadcast.R
import com.amazonaws.ivs.basicbroadcast.databinding.SpinnerItemBinding

class DeviceSpinnerAdapter(context: Context, items: List<String>) : ArrayAdapter<String>(context, 0, items) {

    private val layoutInflater: LayoutInflater = LayoutInflater.from(context)

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: SpinnerItemBinding.inflate(layoutInflater).root
        getItem(position)?.let { item -> setItem(view, item) }
        return view
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: SpinnerItemBinding.inflate(layoutInflater).root
        getItem(position)?.let { item -> setItem(view, item) }
        return view
    }

    private fun setItem(view: View, item: String) {
        view.findViewById<TextView>(R.id.spinner_item)?.text = item
    }

}
