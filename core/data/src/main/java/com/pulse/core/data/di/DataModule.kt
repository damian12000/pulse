package com.pulse.core.data.di

import com.pulse.core.data.Clock
import com.pulse.core.data.DefaultDiaryRepository
import com.pulse.core.data.DefaultFoodRepository
import com.pulse.core.data.DefaultWaterRepository
import com.pulse.core.data.DiaryRepository
import com.pulse.core.data.FoodRepository
import com.pulse.core.data.WaterRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    @Singleton
    abstract fun bindFoodRepository(impl: DefaultFoodRepository): FoodRepository

    @Binds
    @Singleton
    abstract fun bindDiaryRepository(impl: DefaultDiaryRepository): DiaryRepository

    @Binds
    @Singleton
    abstract fun bindWaterRepository(impl: DefaultWaterRepository): WaterRepository

    companion object {
        /** Injectable so time-dependent behaviour can be pinned in tests. */
        @Provides
        @Singleton
        fun provideClock(): Clock = Clock.System
    }
}
