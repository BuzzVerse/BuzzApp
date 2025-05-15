package dev.buzzverse.buzzapp.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.buzzverse.buzzapp.service.GpsServiceController
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Singleton
    @Provides
    fun provideGpsServiceController(@ApplicationContext context: Context): GpsServiceController {
        return GpsServiceController(context)
    }

}