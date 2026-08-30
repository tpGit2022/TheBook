package com.seeksky.thebook.pomodoro

import android.content.Context
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

object PomodoroStateRepository {

    private lateinit var store: PomodoroStore
    private val mutableState = MutableLiveData<PomodoroState>()

    val state: LiveData<PomodoroState> = mutableState

    @Synchronized
    fun initialize(context: Context) {
        if (!::store.isInitialized) {
            store = PomodoroStore(context.applicationContext)
        }
        publish(store.read())
    }

    @Synchronized
    fun current(context: Context): PomodoroState {
        initialize(context)
        return store.read()
    }

    @Synchronized
    fun update(context: Context, state: PomodoroState) {
        initialize(context)
        store.write(state)
        publish(state)
    }

    fun refresh(context: Context) {
        publish(current(context))
    }

    private fun publish(state: PomodoroState) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            mutableState.value = state
        } else {
            mutableState.postValue(state)
        }
    }
}
