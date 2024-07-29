package com.amazonaws.ivs.basicbroadcast.common

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

inline fun <reified T : ViewModel> getViewModel(noinline owner: (() -> AppCompatActivity), noinline creator: (() -> T)? = null): T {
    return if (creator == null)
        ViewModelProvider(owner())[T::class.java]
    else
        ViewModelProvider(owner(), BaseViewModelFactory(creator))[T::class.java]
}

inline fun <reified T : ViewModel> lazyViewModel(noinline owner: (() -> AppCompatActivity), noinline creator: (() -> T)? = null) =
    lazy { getViewModel(owner, creator) }

class BaseViewModelFactory<T>(val creator: () -> T) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return creator() as T
    }
}
