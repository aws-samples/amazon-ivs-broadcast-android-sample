package com.amazonaws.ivs.basicbroadcast.viewModel

import androidx.lifecycle.Observer

class NoLossEvent<T> {

    fun observe(observer: Observer<in T>) {
        assert(mObserver == null) { "Observer is not null" }

        mObserver = observer
    }

    fun clearObserver() {
        mObserver = null
    }

    fun setValue(value: T) {
        mObserver?.onChanged(value)
    }

    private var mObserver: Observer<in T>? = null
}
