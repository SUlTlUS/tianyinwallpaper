package com.zeaze.tianyinwallpaper.base.rxbus

import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject

class RxBus private constructor() {
    private val bus: Subject<Any> = PublishSubject.create<Any>().toSerialized()

    fun post(action: Any) {
        bus.onNext(action)
    }

    fun postWithCode(code: Int, action: Any) {
        bus.onNext(Action(code, action))
    }

    fun <T> toObservable(eventType: Class<T>): Observable<T> {
        return bus.ofType(eventType)
    }

    fun <T> toObservableWithCode(code: Int, eventType: Class<T>): Observable<T> {
        return bus.ofType(Action::class.java)
            .filter { action -> action.code == code }
            .map { action -> action.data }
            .cast(eventType)
    }

    companion object {
        @Volatile
        private var defaultInstance: RxBus? = null

        fun getDefault(): RxBus {
            return defaultInstance ?: synchronized(RxBus::class.java) {
                defaultInstance ?: RxBus().also { defaultInstance = it }
            }
        }
    }
}
