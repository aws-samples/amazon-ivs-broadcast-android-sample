package com.amazonaws.ivs.basicbroadcast.injection

import com.amazonaws.ivs.basicbroadcast.activities.MainActivity
import dagger.Component
import javax.inject.Singleton

@Singleton
@Component(modules = [InjectionModule::class])
interface InjectionComponent {
    fun inject(target: MainActivity)
}
