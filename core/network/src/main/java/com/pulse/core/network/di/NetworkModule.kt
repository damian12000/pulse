package com.pulse.core.network.di

import com.pulse.core.network.FoodDataSource
import com.pulse.core.network.FoodSourceChain
import com.pulse.core.network.OpenFoodFactsDataSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.Interceptor
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * Open Food Facts requires a descriptive User-Agent and blocks anonymous
     * traffic. Format: AppName/Version (contact).
     *
     * TODO(Phase 4): replace the placeholder contact before any production
     * traffic — OFF asks for a real address so they can reach the operator.
     */
    private const val USER_AGENT = "PULSE/0.1.0 (github.com/pulse-app)"

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        // Short timeouts on purpose: this runs on the scanner's hot path, and a
        // slow source must be skipped rather than block the user behind a
        // spinner. The bundled database has already answered most lookups.
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(userAgentInterceptor())
        .build()

    private fun userAgentInterceptor() = Interceptor { chain ->
        chain.proceed(
            chain.request().newBuilder()
                .header("User-Agent", USER_AGENT)
                .build(),
        )
    }

    @Provides
    @Singleton
    fun provideOpenFoodFacts(client: OkHttpClient): OpenFoodFactsDataSource =
        OpenFoodFactsDataSource(client)

    /**
     * Chain order is priority order. Keyed sources (FatSecret, Nutritionix,
     * USDA) join behind OFF once their optional keys are wired; each is absent
     * from the chain when unconfigured rather than failing.
     */
    @Provides
    @Singleton
    fun provideFoodSourceChain(off: OpenFoodFactsDataSource): FoodSourceChain =
        FoodSourceChain(listOf<FoodDataSource>(off))
}
