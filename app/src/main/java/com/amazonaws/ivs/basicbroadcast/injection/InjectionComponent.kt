package com.amazonaws.ivs.basicbroadcast.injection

import com.amazonaws.ivs.basicbroadcast.activities.CustomSourceActivity
import com.amazonaws.ivs.basicbroadcast.activities.MainActivity
import com.amazonaws.ivs.basicbroadcast.activities.MixerActivity
import dagger.Component
import javax.inject.Singleton

@Singleton
@Component(modules = [InjectionModule::class])
interface InjectionComponent {
    fun inject(target: MainActivity)
    fun inject(target: MixerActivity)
    fun inject(target: CustomSourceActivity)
}
