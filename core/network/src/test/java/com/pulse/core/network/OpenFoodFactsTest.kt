package com.pulse.core.network

import java.net.HttpURLConnection
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenFoodFactsTest {

    private lateinit var server: MockWebServer
    private lateinit var source: OpenFoodFactsDataSource

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        source = OpenFoodFactsDataSource(
            client = OkHttpClient(),
            baseUrl = server.url("/"),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    private fun enqueue(code: Int = 200, body: String = "") {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    // --- happy path ---------------------------------------------------------

    @Test
    fun `parses a complete product`() = runTest {
        enqueue(body = FULL_PRODUCT)

        val result = source.byBarcode("0013764027053")
        assertTrue("expected Found, got $result", result is RemoteResult.Found)

        val food = (result as RemoteResult.Found).food
        assertEquals("Organic Whole Grain Bread", food.name)
        assertEquals("Dave's Killer Bread", food.brand)
        assertEquals(250.0, food.kcalPer100, 1e-9)
        assertEquals(12.0, food.proteinPer100, 1e-9)
        assertEquals(43.0, food.carbsPer100, 1e-9)
        assertEquals(4.5, food.fatPer100, 1e-9)
        assertEquals(6.0, food.fiberPer100!!, 1e-9)
        assertEquals(45.0, food.servingGrams!!, 1e-9)
    }

    @Test
    fun `requests only the fields we store`() = runTest {
        enqueue(body = FULL_PRODUCT)
        source.byBarcode("0013764027053")

        val request = server.takeRequest()
        val url = request.requestUrl!!
        assertTrue("must hit the v2 product endpoint", url.encodedPath.contains("/api/v2/product/"))
        // Full OFF products are large; requesting a field list keeps the
        // response small on the user's mobile data.
        val fields = url.queryParameter("fields")
        assertTrue("expected a fields filter", !fields.isNullOrBlank())
        assertTrue(fields!!.contains("nutriments"))
    }

    /** OFF reports sodium in grams; PULSE stores milligrams. */
    @Test
    fun `converts sodium from grams to milligrams`() = runTest {
        enqueue(body = FULL_PRODUCT)
        val food = (source.byBarcode("x") as RemoteResult.Found).food
        assertEquals(400.0, food.sodiumMgPer100!!, 1e-9)
    }

    /** Many products carry only salt; sodium = salt / 2.5. */
    @Test
    fun `derives sodium from salt when sodium is absent`() = runTest {
        enqueue(body = productWith(""""salt_100g": 1.0"""))
        val food = (source.byBarcode("x") as RemoteResult.Found).food
        assertEquals(400.0, food.sodiumMgPer100!!, 1e-6)
    }

    /** Energy is sometimes published only in kilojoules. */
    @Test
    fun `converts kilojoules when kcal is absent`() = runTest {
        enqueue(body = productWith(""""energy-kj_100g": 1046.0""", includeKcal = false))
        val food = (source.byBarcode("x") as RemoteResult.Found).food
        assertEquals(250.0, food.kcalPer100, 0.1)
    }

    @Test
    fun `falls back to generic name when product name is missing`() = runTest {
        enqueue(
            body = """
            {"status":1,"product":{"generic_name":"Wholemeal loaf",
             "nutriments":{"energy-kcal_100g":250.0,"proteins_100g":12.0,
             "carbohydrates_100g":43.0,"fat_100g":4.5}}}
            """.trimIndent(),
        )
        val food = (source.byBarcode("x") as RemoteResult.Found).food
        assertEquals("Wholemeal loaf", food.name)
    }

    @Test
    fun `detects liquids from the quantity field`() = runTest {
        enqueue(
            body = """
            {"status":1,"product":{"product_name":"Sparkling Water","quantity":"500 ml",
             "nutriments":{"energy-kcal_100g":0.0,"proteins_100g":0.0,
             "carbohydrates_100g":0.0,"fat_100g":0.0}}}
            """.trimIndent(),
        )
        val food = (source.byBarcode("x") as RemoteResult.Found).food
        assertTrue("500 ml should be recognised as a liquid", food.isLiquid)
    }

    // --- misses and failures ------------------------------------------------

    @Test
    fun `status zero is a genuine miss, not a failure`() = runTest {
        enqueue(body = """{"status":0}""")
        assertEquals(RemoteResult.NotFound, source.byBarcode("0000000000000"))
    }

    @Test
    fun `404 is a miss`() = runTest {
        enqueue(code = HttpURLConnection.HTTP_NOT_FOUND, body = "")
        assertEquals(RemoteResult.NotFound, source.byBarcode("0000000000000"))
    }

    /** Without energy there is nothing to log, so it is not a usable hit. */
    @Test
    fun `a product with no energy at all is a miss`() = runTest {
        enqueue(body = """{"status":1,"product":{"product_name":"Mystery","nutriments":{}}}""")
        assertEquals(RemoteResult.NotFound, source.byBarcode("x"))
    }

    @Test
    fun `a product with no name is a miss`() = runTest {
        enqueue(body = """{"status":1,"product":{"nutriments":{"energy-kcal_100g":100.0}}}""")
        assertEquals(RemoteResult.NotFound, source.byBarcode("x"))
    }

    /** Rate limiting must be retryable — OFF allows 15 product reads/min/IP. */
    @Test
    fun `429 is a retryable failure`() = runTest {
        enqueue(code = 429)
        val result = source.byBarcode("x")
        assertTrue(result is RemoteResult.Failed)
        assertTrue("rate limiting must be retryable", (result as RemoteResult.Failed).retryable)
    }

    @Test
    fun `server errors are retryable`() = runTest {
        enqueue(code = 503)
        assertTrue((source.byBarcode("x") as RemoteResult.Failed).retryable)
    }

    @Test
    fun `client errors are not retryable`() = runTest {
        enqueue(code = 400)
        assertTrue(!(source.byBarcode("x") as RemoteResult.Failed).retryable)
    }

    /** A parse failure must degrade the chain, never crash the scanner. */
    @Test
    fun `malformed json fails without throwing`() = runTest {
        enqueue(body = "this is not json")
        val result = source.byBarcode("x")
        assertTrue("expected Failed, got $result", result is RemoteResult.Failed)
        assertTrue("retrying yields the same broken payload",
            !(result as RemoteResult.Failed).retryable)
    }

    @Test
    fun `unknown fields in the payload are ignored`() = runTest {
        enqueue(
            body = """
            {"status":1,"unexpected_top_level":42,
             "product":{"product_name":"Bread","brand_owner":"someone",
             "nutriments":{"energy-kcal_100g":250.0,"proteins_100g":12.0,
             "carbohydrates_100g":43.0,"fat_100g":4.5,"future_nutrient":1}}}
            """.trimIndent(),
        )
        assertTrue(source.byBarcode("x") is RemoteResult.Found)
    }

    @Test
    fun `a dropped connection is a retryable failure, not a crash`() = runTest {
        server.enqueue(MockResponse().apply { socketPolicy = SocketPolicy.DISCONNECT_AT_START })
        val result = source.byBarcode("x")
        assertTrue("expected Failed, got $result", result is RemoteResult.Failed)
        assertTrue((result as RemoteResult.Failed).retryable)
    }

    private fun productWith(extraNutriment: String, includeKcal: Boolean = true): String {
        val kcal = if (includeKcal) """"energy-kcal_100g": 250.0,""" else ""
        return """
        {"status":1,"product":{"product_name":"Bread",
         "nutriments":{$kcal"proteins_100g":12.0,"carbohydrates_100g":43.0,
         "fat_100g":4.5,$extraNutriment}}}
        """.trimIndent()
    }

    private companion object {
        const val FULL_PRODUCT = """
        {
          "status": 1,
          "product": {
            "code": "0013764027053",
            "product_name": "Organic Whole Grain Bread",
            "brands": "Dave's Killer Bread, Flowers Foods",
            "quantity": "765 g",
            "serving_size": "1 slice (45 g)",
            "image_url": "https://images.openfoodfacts.org/x.jpg",
            "ingredients_text": "Organic whole wheat, water, seeds",
            "nutriments": {
              "energy-kcal_100g": 250.0,
              "proteins_100g": 12.0,
              "carbohydrates_100g": 43.0,
              "fat_100g": 4.5,
              "fiber_100g": 6.0,
              "sugars_100g": 5.0,
              "saturated-fat_100g": 0.5,
              "sodium_100g": 0.4
            }
          }
        }
        """
    }
}

class ServingParsingTest {

    @Test
    fun `parses grams from common serving strings`() {
        assertEquals(45.0, parseServingGrams("1 slice (45 g)")!!, 1e-9)
        assertEquals(30.0, parseServingGrams("30g")!!, 1e-9)
        assertEquals(240.0, parseServingGrams("1 cup (240ml)")!!, 1e-9)
        assertEquals(37.5, parseServingGrams("37.5 g")!!, 1e-9)
    }

    @Test
    fun `handles comma decimal separators`() {
        assertEquals(37.5, parseServingGrams("37,5 g")!!, 1e-9)
    }

    @Test
    fun `returns null when there is no usable weight`() {
        assertNull(parseServingGrams(null))
        assertNull(parseServingGrams(""))
        assertNull(parseServingGrams("1 slice"))
        assertNull(parseServingGrams("a handful"))
        assertNull(parseServingGrams("0 g"))
    }
}

class FoodSourceChainTest {

    private fun source(
        id: String,
        result: RemoteResult,
        available: Boolean = true,
    ) = object : FoodDataSource {
        override val id = id
        override val isAvailable = available
        var called = false
        override suspend fun byBarcode(ean13: String): RemoteResult {
            called = true
            return result
        }
    }

    @Test
    fun `returns the first hit and reports which source answered`() = runTest {
        val chain = FoodSourceChain(
            listOf(
                source("A", RemoteResult.NotFound),
                source("B", RemoteResult.Found(food("from B"))),
                source("C", RemoteResult.Found(food("from C"))),
            ),
        )
        val result = chain.byBarcode("x")
        assertTrue(result is ChainResult.Found)
        assertEquals("B", (result as ChainResult.Found).sourceId)
        assertEquals("from B", result.food.name)
    }

    @Test
    fun `a failing source is skipped rather than fatal`() = runTest {
        val chain = FoodSourceChain(
            listOf(
                source("A", RemoteResult.Failed("boom", retryable = true)),
                source("B", RemoteResult.Found(food("from B"))),
            ),
        )
        assertTrue(chain.byBarcode("x") is ChainResult.Found)
    }

    /**
     * The distinction that stops the app telling you to create a food that
     * already exists: every source failing is not evidence of a genuine miss.
     */
    @Test
    fun `all sources failing is reported separately from not found`() = runTest {
        val allFail = FoodSourceChain(
            listOf(
                source("A", RemoteResult.Failed("net", retryable = true)),
                source("B", RemoteResult.Failed("net", retryable = true)),
            ),
        )
        val result = allFail.byBarcode("x")
        assertTrue("expected AllFailed, got $result", result is ChainResult.AllFailed)
        assertEquals(2, (result as ChainResult.AllFailed).reasons.size)

        val genuineMiss = FoodSourceChain(
            listOf(
                source("A", RemoteResult.NotFound),
                source("B", RemoteResult.Failed("net", retryable = true)),
            ),
        )
        assertEquals(ChainResult.NotFound, genuineMiss.byBarcode("x"))
    }

    @Test
    fun `unavailable sources are not consulted`() = runTest {
        val unconfigured = source("A", RemoteResult.Found(food("nope")), available = false)
        val chain = FoodSourceChain(listOf(unconfigured, source("B", RemoteResult.NotFound)))

        assertEquals(ChainResult.NotFound, chain.byBarcode("x"))
        assertTrue("a source without its key must not be called", !unconfigured.called)
    }

    @Test
    fun `no configured sources is its own outcome`() = runTest {
        val chain = FoodSourceChain(listOf(source("A", RemoteResult.NotFound, available = false)))
        assertEquals(ChainResult.NoSources, chain.byBarcode("x"))
        assertEquals(ChainResult.NoSources, FoodSourceChain(emptyList()).byBarcode("x"))
    }

    private fun food(name: String) = RemoteFood(
        sourceId = "1", barcode = "1", name = name, brand = null,
        kcalPer100 = 100.0, proteinPer100 = 1.0, carbsPer100 = 1.0, fatPer100 = 1.0,
    )
}
