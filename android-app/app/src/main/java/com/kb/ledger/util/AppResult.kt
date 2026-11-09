package com.kb.ledger.util

/**
 * A typed Result wrapper used throughout the app so callers
 * can handle success and failure explicitly — no silent swallowing.
 */
sealed class AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>()
    data class Error(val message: String, val cause: Throwable? = null) : AppResult<Nothing>()

    val isSuccess get() = this is Success
    val isError get() = this is Error

    fun getOrNull(): T? = if (this is Success) data else null

    fun getOrThrow(): T = when (this) {
        is Success -> data
        is Error -> throw cause ?: Exception(message)
    }
}

inline fun <T> AppResult<T>.onSuccess(action: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) action(data)
    return this
}

inline fun <T> AppResult<T>.onError(action: (String, Throwable?) -> Unit): AppResult<T> {
    if (this is AppResult.Error) action(message, cause)
    return this
}
