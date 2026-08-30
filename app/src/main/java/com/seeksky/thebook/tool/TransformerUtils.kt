package com.seeksky.thebook.tool

import io.reactivex.Observable
import io.reactivex.ObservableTransformer
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers

fun <T> applySchedulers(): ObservableTransformer<T, T>? {
    return ObservableTransformer { observable: Observable<T> ->
        observable.subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }
}
