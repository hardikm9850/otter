package com.github.apognu.otter.utils

import android.content.Context
import com.github.apognu.otter.activities.FwCredentials
import com.github.kittinunf.fuel.Fuel
import com.github.kittinunf.fuel.core.FuelError
import com.github.kittinunf.fuel.coroutines.awaitObjectResponseResult
import com.github.kittinunf.fuel.coroutines.awaitObjectResult
import com.github.kittinunf.fuel.gson.gsonDeserializerOf
import com.github.kittinunf.result.Result
import com.preference.PowerPreference
import java.io.BufferedReader
import java.io.File
import java.nio.charset.Charset
import java.security.MessageDigest

object RefreshError : Throwable()

object HTTP {
  suspend fun refresh(): Boolean {
    val body = mapOf(
      "username" to PowerPreference.getFileByName(AppContext.PREFS_CREDENTIALS).getString("username"),
      "password" to PowerPreference.getFileByName(AppContext.PREFS_CREDENTIALS).getString("password")
    ).toList()

    val result = Fuel.post(mustNormalizeUrl("/api/v1/token"), body).awaitObjectResult(gsonDeserializerOf(FwCredentials::class.java))

    return result.fold(
      { data ->
        PowerPreference.getFileByName(AppContext.PREFS_CREDENTIALS).setString("access_token", data.token)

        true
      },
      { false }
    )
  }

  suspend inline fun <reified T : Any> get(url: String): Result<T, FuelError> {
    val request = Fuel.get(mustNormalizeUrl(url)).apply {
      if (!Settings.isAnonymous()) {
        header("Authorization", "Bearer ${Settings.getAccessToken()}")
      }
    }

    val (_, response, result) = request.awaitObjectResponseResult(gsonDeserializerOf(T::class.java))

    if (response.statusCode == 401) {
      return retryGet(url)
    }

    return result
  }

  suspend inline fun <reified T : Any> retryGet(url: String): Result<T, FuelError> {
    return if (refresh()) {
      val request = Fuel.get(mustNormalizeUrl(url)).apply {
        if (!Settings.isAnonymous()) {
          header("Authorization", "Bearer ${Settings.getAccessToken()}")
        }
      }

      request.awaitObjectResult(gsonDeserializerOf(T::class.java))
    } else {
      Result.Failure(FuelError.wrap(RefreshError))
    }
  }
}

object Cache {
  private fun key(key: String): String {
    val md = MessageDigest.getInstance("SHA-1")
    val digest = md.digest(key.toByteArray(Charset.defaultCharset()))

    return digest.fold("", { acc, it -> acc + "%02x".format(it) })
  }

  fun set(context: Context?, key: String, value: ByteArray) = context?.let {
    with(File(it.cacheDir, key(key))) {
      "$key (${key(key)} : $value"
      writeBytes(value)
    }
  }

  fun get(context: Context?, key: String): BufferedReader? = context?.let {
    try {
      with(File(it.cacheDir, key(key))) {
        bufferedReader()
      }
    } catch (e: Exception) {
      return null
    }
  }

  fun delete(context: Context?, key: String) = context?.let {
    with(File(it.cacheDir, key(key))) {
      delete()
    }
  }
}