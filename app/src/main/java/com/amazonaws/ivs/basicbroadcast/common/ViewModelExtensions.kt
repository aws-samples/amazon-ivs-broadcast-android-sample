package com.amazonaws.ivs.basicbroadcast.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.amazonaws.ivs.basicbroadcast.App

inline fun <reified T : ViewModel> getViewModel(noinline owner: (() -> ViewModelStoreOwner), noinline creator: (() -> T)? = null): T {
    return if (creator == null)
        ViewModelProvider(owner())[T::class.java]
    else
        ViewModelProvider(owner(), BaseViewModelFactory(creator))[T::class.java]
}

inline fun <reified T : ViewModel> lazyViewModel(noinline owner: (() -> ViewModelStoreOwner), noinline creator: (() -> T)? = null) =
    lazy { getViewModel(owner, creator) }

class BaseViewModelFactory<T>(val creator: () -> T) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return creator() as T
    }
}
