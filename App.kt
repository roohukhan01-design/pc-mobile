package com.roohu.connect

import android.app.Application

class App : Application() {
    lateinit var store: Store
    lateinit var link: Link
    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        link = Link(store)
    }
}
