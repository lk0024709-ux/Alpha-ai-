package com.alphaai.app.di

import android.content.Context
import androidx.room.Room
import com.alphaai.app.data.local.AppDatabase
import com.alphaai.app.data.local.ArtifactDao
import com.alphaai.app.data.local.MessageDao
import com.alphaai.app.data.local.SessionDao
import com.alphaai.app.data.local.ToolCallDao
import com.alphaai.app.data.local.UsageLogDao
import com.alphaai.app.data.security.SecureAuthInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .build()

    @Provides fun provideSessionDao(db: AppDatabase): SessionDao = db.sessionDao()
    @Provides fun provideMessageDao(db: AppDatabase): MessageDao = db.messageDao()
    @Provides fun provideToolCallDao(db: AppDatabase): ToolCallDao = db.toolCallDao()
    @Provides fun provideArtifactDao(db: AppDatabase): ArtifactDao = db.artifactDao()
    @Provides fun provideUsageLogDao(db: AppDatabase): UsageLogDao = db.usageLogDao()

    @Provides @Singleton
    fun provideOkHttp(authInterceptor: SecureAuthInterceptor): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // SSE streams are bounded by AgentLoop wall clock
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
}
